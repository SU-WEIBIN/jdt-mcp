package org.eclipse.jdt.mcp.app.index;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.jdt.mcp.app.decompiler.JarFingerprint;
import org.eclipse.jdt.mcp.app.json.JsonCodec;
import org.eclipse.jdt.mcp.app.maven.MavenArtifact;

/**
 * 字节码索引存储：按构件逐个保存 JAR 的符号/调用 JSON 快照，用指纹清单判断快照能否复用，
 * 并把符合条件的快照放入有内存上限的 LRU 常驻缓存，使重复查询无需反复从磁盘读取和解析
 * 同一快照。
 * Stores bytecode indexes one artifact at a time. Persisted snapshots remain
 * the recovery format, while a bounded resident cache serves repeated queries
 * without rereading and reparsing the same JSON snapshot from disk.
 */
public final class BytecodeIndexStore {
    private static final int FORMAT_VERSION = 2;
    private static final long DEFAULT_RESIDENT_LIMIT_BYTES = 1536L * 1024L * 1024L;

    private final Path root;
    private final String projectId;
    private final long residentLimitBytes;
    private final Map<String, ArtifactEntry> entries = new LinkedHashMap<>();
    private final Map<String, ResidentIndex> residentIndexes = new LinkedHashMap<>(16, 0.75f, true);
    private long residentBytes;
    private long snapshotLoadCount;
    private long snapshotLoadBytes;
    private long residentCacheHitCount;

    /**
     * Creates an artifact index store for the supplied Maven artifacts.
     *
     * @param root directory containing per-artifact index files
     * @param projectId project identifier written into snapshots
     * @param artifacts artifacts that may receive bytecode indexes
     */
    public BytecodeIndexStore(Path root, String projectId, List<MavenArtifact> artifacts) {
        this(root, projectId, artifacts, DEFAULT_RESIDENT_LIMIT_BYTES);
    }

    /**
     * Creates an artifact index store with a bounded resident query cache.
     *
     * @param root directory containing per-artifact index files
     * @param projectId project identifier written into snapshots
     * @param artifacts artifacts that may receive bytecode indexes
     * @param residentLimitBytes approximate memory budget for loaded snapshots;
     *        zero disables the resident cache
     */
    public BytecodeIndexStore(Path root, String projectId, List<MavenArtifact> artifacts,
            long residentLimitBytes) {
        this.root = root.toAbsolutePath().normalize();
        this.projectId = projectId;
        this.residentLimitBytes = Math.max(0L, residentLimitBytes);
        if (artifacts != null) {
            for (MavenArtifact artifact : artifacts) {
                if (artifact != null && artifact.file() != null) {
                    entries.put(artifact.coordinate(), new ArtifactEntry(
                            artifact.coordinate(), indexPath(artifact),
                            artifact.file().toAbsolutePath().normalize(), null, 0, 0, 0, false));
                }
            }
        }
    }

    /**
     * Persists one completed artifact index and also makes it available to the
     * resident query cache when the configured memory budget permits.
     *
     * @param artifact indexed artifact
     * @param index temporary in-memory artifact index
     * @param result indexing counts and warning
     * @throws IOException if the snapshot cannot be written
     */
    public synchronized void save(MavenArtifact artifact, ProjectIndex index, JarBytecodeIndexer.Result result)
            throws IOException {
        if (artifact == null || index == null || result == null) {
            return;
        }
        JarFingerprint fingerprint = artifact.file() != null && Files.isRegularFile(artifact.file())
                ? JarFingerprint.calculate(artifact.file()) : null;
        save(artifact, index, result, fingerprint);
    }

    /**
     * Persists one completed artifact index with a previously calculated JAR
     * fingerprint, avoiding a second read of the artifact after indexing.
     *
     * @param artifact indexed artifact
     * @param index temporary in-memory artifact index
     * @param result indexing counts and warning
     * @param fingerprint artifact fingerprint, or {@code null} when unavailable
     * @throws IOException if the snapshot cannot be written
     */
    public synchronized void save(MavenArtifact artifact, ProjectIndex index, JarBytecodeIndexer.Result result,
            JarFingerprint fingerprint) throws IOException {
        if (artifact == null || index == null || result == null) {
            return;
        }
        JarFingerprint actualFingerprint = fingerprint;
        if (actualFingerprint == null && artifact.file() != null && Files.isRegularFile(artifact.file())) {
            actualFingerprint = JarFingerprint.calculate(artifact.file());
        }
        Files.createDirectories(root);
        Path file = indexPath(artifact);
        index.save(file, projectId);
        entries.put(artifact.coordinate(), new ArtifactEntry(
                artifact.coordinate(), file,
                artifact.file() == null ? null : artifact.file().toAbsolutePath().normalize(), actualFingerprint,
                result.classCount(), result.methodCount(), result.callCount(), true));
        putResident(artifact.coordinate(), index, Files.size(file));
    }

    /**
     * Restores reusable artifact snapshots from the manifest for the current
     * Maven artifact set. Metadata is checked first; content hashing is only
     * needed when metadata changed and an existing snapshot can be verified.
     *
     * @param currentFingerprints fingerprints calculated for current artifacts
     * @return restoration counts and non-fatal cache warnings
     */
    public synchronized RestoreResult restore(Map<String, JarFingerprint> currentFingerprints) {
        clearResidentCache();
        Path manifestFile = root.resolve("manifest.json");
        List<String> warnings = new ArrayList<>();
        if (!Files.isRegularFile(manifestFile)) {
            return new RestoreResult(0, entries.size(), List.of());
        }

        Map<String, Object> manifest;
        try {
            manifest = JsonCodec.parseObject(Files.readString(manifestFile, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException exception) {
            warnings.add("[INFO/bytecode-cache] Ignoring unreadable bytecode manifest: " + message(exception));
            return new RestoreResult(0, entries.size(), List.copyOf(warnings));
        }
        if (number(manifest.get("formatVersion")) != FORMAT_VERSION) {
            warnings.add("[INFO/bytecode-cache] Bytecode manifest format changed; rebuilding artifact indexes");
            return new RestoreResult(0, entries.size(), List.copyOf(warnings));
        }
        if (!projectId.equals(text(manifest.get("projectId")))) {
            warnings.add("[INFO/bytecode-cache] Bytecode manifest belongs to another project; rebuilding artifact indexes");
            return new RestoreResult(0, entries.size(), List.copyOf(warnings));
        }

        Map<String, Map<?, ?>> previous = previousArtifacts(manifest.get("artifacts"));
        int reused = 0;
        List<ArtifactEntry> currentEntries = new ArrayList<>(entries.values());
        for (ArtifactEntry entry : currentEntries) {
            JarFingerprint fingerprint = currentFingerprints == null
                    ? null : currentFingerprints.get(entry.coordinate());
            Map<?, ?> old = previous.get(entry.coordinate());
            JarFingerprint manifestFingerprint = fingerprint;
            boolean reusable = false;
            if (fingerprint != null && old != null) {
                if (fingerprint.sha256() != null) {
                    reusable = isReusable(old, entry.indexFile(), fingerprint);
                } else if (isMetadataReusable(old, entry.indexFile(), fingerprint)) {
                    reusable = true;
                    String previousSha = text(old.get("sha256"));
                    if (previousSha != null) {
                        manifestFingerprint = new JarFingerprint(previousSha,
                                fingerprint.size(), fingerprint.lastModifiedMillis());
                    }
                } else if (hasSnapshot(old, entry.indexFile())
                        && entry.artifactFile() != null && Files.isRegularFile(entry.artifactFile())) {
                    try {
                        JarFingerprint verified = JarFingerprint.calculate(entry.artifactFile());
                        manifestFingerprint = verified;
                        replaceFingerprint(currentFingerprints, entry.coordinate(), verified);
                        reusable = isReusable(old, entry.indexFile(), verified);
                    } catch (IOException exception) {
                        warnings.add("[INFO/bytecode-cache] Could not verify " + entry.coordinate()
                                + ": " + message(exception));
                    }
                }
            }
            ArtifactEntry restored = new ArtifactEntry(
                    entry.coordinate(), entry.indexFile(), entry.artifactFile(), manifestFingerprint,
                    reusable ? number(old.get("classCount")) : 0,
                    reusable ? number(old.get("methodCount")) : 0,
                    reusable ? number(old.get("callCount")) : 0,
                    reusable);
            entries.put(entry.coordinate(), restored);
            if (reusable) {
                reused++;
            }
        }
        return new RestoreResult(reused, entries.size(), List.copyOf(warnings));
    }

    /**
     * Reports whether the current artifact already has a reusable snapshot.
     *
     * @param coordinate artifact coordinate
     * @return whether the artifact can be skipped during indexing
     */
    public synchronized boolean isIndexed(String coordinate) {
        ArtifactEntry entry = entries.get(coordinate);
        return entry != null && entry.indexed();
    }

    /**
     * Writes a compact manifest containing artifact paths and aggregate counts.
     *
     * @throws IOException if the manifest cannot be written
     */
    public synchronized void commit() throws IOException {
        List<Map<String, Object>> artifacts = new ArrayList<>();
        for (ArtifactEntry entry : entries.values()) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("coordinate", entry.coordinate());
            value.put("file", entry.artifactFile() == null ? null : entry.artifactFile().toString());
            JarFingerprint fingerprint = entry.fingerprint();
            if (fingerprint != null) {
                value.put("size", fingerprint.size());
                value.put("lastModifiedMillis", fingerprint.lastModifiedMillis());
                value.put("sha256", fingerprint.sha256());
            }
            value.put("indexFile", entry.indexFile().toString());
            value.put("classCount", entry.classCount());
            value.put("methodCount", entry.methodCount());
            value.put("callCount", entry.callCount());
            value.put("indexed", entry.indexed());
            artifacts.add(value);
        }
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("formatVersion", FORMAT_VERSION);
        manifest.put("projectId", projectId);
        manifest.put("artifacts", artifacts);
        Files.createDirectories(root);
        writeAtomically(root.resolve("manifest.json"), JsonCodec.stringify(manifest));
    }

    /**
     * Loads all reusable snapshots into the resident query cache before the
     * project is reported ready. This moves snapshot reads out of individual
     * MCP search requests. Artifacts larger than the configured resident budget
     * remain eligible for on-demand loading.
     *
     * @return preload statistics and non-fatal warnings
     */
    public synchronized PreloadResult preload() {
        int eligible = 0;
        int loaded = 0;
        List<String> warnings = new ArrayList<>();
        for (ArtifactEntry entry : entries.values()) {
            if (!entry.indexed()) {
                continue;
            }
            eligible++;
            if (residentIndexes.containsKey(entry.coordinate())) {
                continue;
            }
            ProjectIndex index = load(entry);
            if (index == null) {
                warnings.add("[INFO/bytecode-cache] Could not preload " + entry.coordinate());
            } else if (residentIndexes.containsKey(entry.coordinate())) {
                loaded++;
            }
        }
        return new PreloadResult(eligible, loaded, residentIndexes.size(), residentBytes,
                snapshotLoadCount, snapshotLoadBytes, List.copyOf(warnings));
    }

    /**
     * Returns the number of artifact snapshots currently resident in memory.
     *
     * @return resident artifact count
     */
    public synchronized int residentArtifactCount() {
        return residentIndexes.size();
    }

    /**
     * Returns the approximate resident-cache memory usage.
     *
     * @return approximate resident bytes
     */
    public synchronized long residentBytes() {
        return residentBytes;
    }

    /**
     * Returns the configured approximate resident-cache budget.
     *
     * @return resident-cache budget in bytes
     */
    public long residentLimitBytes() {
        return residentLimitBytes;
    }

    /**
     * Returns the number of snapshot files read since this store was created.
     *
     * @return snapshot load count
     */
    public synchronized long snapshotLoadCount() {
        return snapshotLoadCount;
    }

    /**
     * Returns the total serialized snapshot bytes read since this store was
     * created.
     *
     * @return snapshot load bytes
     */
    public synchronized long snapshotLoadBytes() {
        return snapshotLoadBytes;
    }

    /**
     * Returns the number of accesses served by the resident cache.
     *
     * @return resident cache hit count
     */
    public synchronized long residentCacheHitCount() {
        return residentCacheHitCount;
    }

    /**
     * Returns the fingerprint currently known by the index. The normal startup
     * path returns a metadata-only value and therefore never reads the full JAR
     * just to serve a class/method lookup.
     *
     * @param coordinate artifact coordinate
     * @return known metadata or content fingerprint
     * @throws IOException if the artifact is unavailable
     */
    public synchronized JarFingerprint artifactFingerprint(String coordinate) throws IOException {
        ArtifactEntry entry = entries.get(coordinate);
        if (entry == null) {
            throw new IOException("Artifact file is not available: " + coordinate);
        }
        if (entry.fingerprint() != null) {
            return entry.fingerprint();
        }
        if (entry.artifactFile() == null || !Files.isRegularFile(entry.artifactFile())) {
            throw new IOException("Artifact file is not available: " + coordinate);
        }
        JarFingerprint metadata = JarFingerprint.metadata(entry.artifactFile());
        entries.put(coordinate, new ArtifactEntry(
                entry.coordinate(), entry.indexFile(), entry.artifactFile(), metadata,
                entry.classCount(), entry.methodCount(), entry.callCount(), entry.indexed()));
        return metadata;
    }

    /**
     * 返回构件的内容指纹；若尚未计算完整 SHA-256 则计算并写回条目缓存。
     *
     * @param coordinate 构件坐标
     * @return 包含内容哈希的指纹
     * @throws IOException 构件不可用或文件不可读
     */
    public synchronized JarFingerprint contentFingerprint(String coordinate) throws IOException {
        ArtifactEntry entry = entries.get(coordinate);
        if (entry == null || entry.artifactFile() == null
                || !Files.isRegularFile(entry.artifactFile())) {
            throw new IOException("Artifact file is not available: " + coordinate);
        }
        JarFingerprint current = entry.fingerprint();
        if (current != null && current.sha256() != null) {
            return current;
        }
        JarFingerprint calculated = JarFingerprint.calculate(entry.artifactFile());
        entries.put(coordinate, new ArtifactEntry(
                entry.coordinate(), entry.indexFile(), entry.artifactFile(), calculated,
                entry.classCount(), entry.methodCount(), entry.callCount(), entry.indexed()));
        return calculated;
    }

    /**
     * Searches resident artifact indexes, loading a snapshot at most once
     * when it has not already been preloaded.
     *
     * @param query symbol query
     * @param kind optional symbol kind
     * @param maxResults maximum number of results
     * @return matching bytecode symbols
     */
    public synchronized List<IndexedSymbol> searchSymbols(String query, String kind, int maxResults) {
        int limit = Math.max(1, maxResults);
        List<IndexedSymbol> result = new ArrayList<>();
        for (ArtifactEntry entry : entries.values()) {
            ProjectIndex index = load(entry);
            if (index == null) {
                continue;
            }
            result.addAll(index.searchSymbols(query, kind, limit - result.size()));
            if (result.size() >= limit) {
                break;
            }
        }
        return List.copyOf(result.subList(0, Math.min(result.size(), limit)));
    }

    /**
     * Searches one persisted artifact index.
     *
     * @param coordinate artifact coordinate
     * @param query symbol query
     * @param kind optional symbol kind
     * @param maxResults maximum number of results
     * @return matching symbols, or an empty list when no snapshot exists
     */
    public synchronized List<IndexedSymbol> searchArtifactSymbols(
            String coordinate, String query, String kind, int maxResults) {
        ArtifactEntry entry = entries.get(coordinate);
        if (entry == null) {
            return List.of();
        }
        ProjectIndex index = load(entry);
        return index == null ? List.of() : index.searchSymbols(query, kind, maxResults);
    }

    /**
     * Finds a symbol in persisted artifact indexes by its stable identifier.
     *
     * @param id symbol identifier
     * @return matching symbol, or {@code null}
     */
    public synchronized IndexedSymbol symbol(String id) {
        if (id == null) {
            return null;
        }
        for (ArtifactEntry entry : entries.values()) {
            ProjectIndex index = load(entry);
            if (index == null) {
                continue;
            }
            IndexedSymbol symbol = index.symbol(id);
            if (symbol != null) {
                return symbol;
            }
        }
        return null;
    }

    /**
     * Finds callers across persisted artifact indexes.
     *
     * @param targetId optional target symbol identifier
     * @param targetQuery optional target signature query
     * @param maxResults maximum number of results
     * @return matching call edges
     */
    public synchronized List<IndexedCall> callers(String targetId, String targetQuery, int maxResults) {
        int limit = Math.max(1, maxResults);
        List<IndexedCall> result = new ArrayList<>();
        for (ArtifactEntry entry : entries.values()) {
            ProjectIndex index = load(entry);
            if (index == null) {
                continue;
            }
            result.addAll(index.callers(targetId, targetQuery, limit - result.size()));
            if (result.size() >= limit) {
                break;
            }
        }
        return List.copyOf(result.subList(0, Math.min(result.size(), limit)));
    }

    /**
     * Finds direct callees across persisted artifact indexes.
     *
     * @param callerId caller symbol identifier
     * @param maxResults maximum number of results
     * @return matching call edges
     */
    public synchronized List<IndexedCall> callees(String callerId, int maxResults) {
        int limit = Math.max(1, maxResults);
        List<IndexedCall> result = new ArrayList<>();
        for (ArtifactEntry entry : entries.values()) {
            ProjectIndex index = load(entry);
            if (index == null) {
                continue;
            }
            result.addAll(index.callees(callerId, limit - result.size()));
            if (result.size() >= limit) {
                break;
            }
        }
        return List.copyOf(result.subList(0, Math.min(result.size(), limit)));
    }

    /**
     * Finds outgoing calls for a set of callers across persisted artifacts.
     *
     * @param callerIds caller symbol identifiers
     * @param maxResults maximum number of results
     * @return matching call edges
     */
    public synchronized List<IndexedCall> callsFrom(Set<String> callerIds, int maxResults) {
        if (callerIds == null || callerIds.isEmpty()) {
            return List.of();
        }
        int limit = Math.max(1, maxResults);
        List<IndexedCall> result = new ArrayList<>();
        for (ArtifactEntry entry : entries.values()) {
            ProjectIndex index = load(entry);
            if (index == null) {
                continue;
            }
            result.addAll(index.callsFrom(callerIds, limit - result.size()));
            if (result.size() >= limit) {
                break;
            }
        }
        return List.copyOf(result.subList(0, Math.min(result.size(), limit)));
    }

    /**
     * Returns the number of artifact snapshots successfully written.
     *
     * @return indexed artifact count
     */
    public synchronized int indexedArtifactCount() {
        return (int) entries.values().stream().filter(ArtifactEntry::indexed).count();
    }

    /**
     * Returns the total number of bytecode classes in persisted snapshots.
     *
     * @return bytecode class count
     */
    public synchronized int typeCount() {
        return entries.values().stream().mapToInt(ArtifactEntry::classCount).sum();
    }

    /**
     * Returns the total number of bytecode methods in persisted snapshots.
     *
     * @return bytecode method count
     */
    public synchronized int methodCount() {
        return entries.values().stream().mapToInt(ArtifactEntry::methodCount).sum();
    }

    /**
     * Returns the total number of bytecode call edges in persisted snapshots.
     *
     * @return bytecode call-edge count
     */
    public synchronized int callCount() {
        return entries.values().stream().mapToInt(ArtifactEntry::callCount).sum();
    }

    /**
     * Returns the class count recorded for one artifact.
     *
     * @param coordinate artifact coordinate
     * @return class count, or zero when the artifact has no snapshot
     */
    public synchronized int classCount(String coordinate) {
        ArtifactEntry entry = entries.get(coordinate);
        return entry == null ? 0 : entry.classCount();
    }

    /**
     * Loads one artifact snapshot for the duration of a query operation.
     *
     * @param entry artifact snapshot metadata
     * @return loaded index, or {@code null} when the snapshot is unavailable
     */
    private ProjectIndex load(ArtifactEntry entry) {
        if (entry == null) {
            return null;
        }
        // Check the resident cache before touching the filesystem. A READY
        // project should be searchable without even per-snapshot stat calls.
        ResidentIndex resident = residentIndexes.get(entry.coordinate());
        if (resident != null) {
            residentCacheHitCount++;
            return resident.index();
        }
        if (!entry.indexed() || !Files.isRegularFile(entry.indexFile())) {
            return null;
        }
        try {
            long serializedBytes = Files.size(entry.indexFile());
            ProjectIndex loaded = ProjectIndex.load(entry.indexFile());
            snapshotLoadCount++;
            snapshotLoadBytes += serializedBytes;
            putResident(entry.coordinate(), loaded, serializedBytes);
            return loaded;
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    /**
     * Adds one loaded index to the bounded resident cache, evicting the least
     * recently used entries when necessary.
     */
    private void putResident(String coordinate, ProjectIndex index, long serializedBytes) {
        if (coordinate == null || index == null || residentLimitBytes <= 0) {
            return;
        }
        long estimate = estimateResidentBytes(serializedBytes);
        ResidentIndex previous = residentIndexes.remove(coordinate);
        if (previous != null) {
            residentBytes -= previous.estimatedBytes();
        }
        if (estimate > residentLimitBytes) {
            return;
        }
        Iterator<Map.Entry<String, ResidentIndex>> iterator = residentIndexes.entrySet().iterator();
        while (residentBytes + estimate > residentLimitBytes && iterator.hasNext()) {
            Map.Entry<String, ResidentIndex> eldest = iterator.next();
            residentBytes -= eldest.getValue().estimatedBytes();
            iterator.remove();
        }
        residentIndexes.put(coordinate, new ResidentIndex(index, estimate));
        residentBytes += estimate;
    }

    /**
     * Clears only the resident representation; persisted snapshots are kept.
     */
    private void clearResidentCache() {
        residentIndexes.clear();
        residentBytes = 0L;
    }

    /**
     * 由快照序列化字节数估算常驻内存占用：至少 64 KiB，其余按约 3 倍估算。
     *
     * @param serializedBytes 快照字节数
     * @return 估算的常驻字节数
     */
    private static long estimateResidentBytes(long serializedBytes) {
        long minimum = 64L * 1024L;
        if (serializedBytes <= 0) {
            return minimum;
        }
        if (serializedBytes > Long.MAX_VALUE / 3L) {
            return Long.MAX_VALUE;
        }
        return Math.max(minimum, serializedBytes * 3L);
    }

    /**
     * 把清单中的 artifacts 数组反序列化为坐标到元数据映射的映射。
     *
     * @param value 反序列化后的 artifacts 值
     * @return 坐标到元数据的映射
     */
    private static Map<String, Map<?, ?>> previousArtifacts(Object value) {
        Map<String, Map<?, ?>> result = new LinkedHashMap<>();
        if (!(value instanceof List<?> list)) {
            return result;
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                String coordinate = text(map.get("coordinate"));
                if (coordinate != null && !coordinate.isBlank()) {
                    result.put(coordinate, map);
                }
            }
        }
        return result;
    }

    /**
     * Checks the persisted metadata and snapshot path for one artifact.
     *
     * @param previous serialized artifact metadata
     * @param indexFile expected current snapshot path
     * @param fingerprint current JAR fingerprint
     * @return whether the snapshot can be reused
     */
    private static boolean isReusable(Map<?, ?> previous, Path indexFile, JarFingerprint fingerprint) {
        if (previous == null || !booleanValue(previous.get("indexed"))
                || fingerprint == null || fingerprint.sha256() == null
                || !Files.isRegularFile(indexFile)) {
            return false;
        }
        try {
            if (Files.size(indexFile) <= 0) {
                return false;
            }
        } catch (IOException exception) {
            return false;
        }
        // Once a full content hash is available, a timestamp-only change does
        // not invalidate an otherwise identical immutable artifact.
        return fingerprint.size() == longValue(previous.get("size"))
                && fingerprint.sha256().equals(text(previous.get("sha256")));
    }

    /**
     * 仅凭大小和修改时间判断清单中的快照能否复用，不读取 JAR 内容。
     *
     * @param previous 清单中的构件元数据
     * @param indexFile 当前快照路径
     * @param fingerprint 当前构件元数据指纹
     * @return 是否可复用
     */
    private static boolean isMetadataReusable(Map<?, ?> previous, Path indexFile, JarFingerprint fingerprint) {
        if (previous == null || !booleanValue(previous.get("indexed"))
                || fingerprint == null || !Files.isRegularFile(indexFile)) {
            return false;
        }
        try {
            if (Files.size(indexFile) <= 0) {
                return false;
            }
        } catch (IOException exception) {
            return false;
        }
        return fingerprint.size() == longValue(previous.get("size"))
                && fingerprint.lastModifiedMillis() == longValue(previous.get("lastModifiedMillis"));
    }

    /**
     * 判断清单声明构件已索引且对应快照文件存在。
     *
     * @param previous 清单中的构件元数据
     * @param indexFile 快照路径
     * @return 是否存在可用快照
     */
    private static boolean hasSnapshot(Map<?, ?> previous, Path indexFile) {
        return previous != null && booleanValue(previous.get("indexed"))
                && Files.isRegularFile(indexFile);
    }

    private static void replaceFingerprint(Map<String, JarFingerprint> fingerprints,
            String coordinate, JarFingerprint fingerprint) {
        if (fingerprints == null || coordinate == null || fingerprint == null) {
            return;
        }
        try {
            fingerprints.put(coordinate, fingerprint);
        } catch (UnsupportedOperationException ignored) {
            // The caller may pass an immutable map; the current entry still has
            // the verified fingerprint for manifest persistence.
        }
    }

    /**
     * Writes a text file through a same-directory temporary file and replaces
     * the target only after the complete content has been written.
     *
     * @param file destination file
     * @param content text content
     * @throws IOException if the temporary file or replacement cannot be written
     */
    private static void writeAtomically(Path file, String content) throws IOException {
        Path parent = file.toAbsolutePath().normalize().getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, file.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * Returns a readable message for a cache restoration exception.
     *
     * @param exception exception raised while reading the cache
     * @return exception message or type name
     */
    private static String message(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    /**
     * Converts a serialized value to a nullable string.
     *
     * @param value serialized value
     * @return string value, or {@code null}
     */
    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * Converts a serialized numeric value to an integer with a safe default.
     *
     * @param value serialized value
     * @return integer value, or zero
     */
    private static int number(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String string) {
            try {
                return Integer.parseInt(string);
            } catch (NumberFormatException exception) {
                return 0;
            }
        }
        return 0;
    }

    /**
     * Converts a serialized numeric value to a long with a safe default.
     *
     * @param value serialized value
     * @return long value, or zero
     */
    private static long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String string) {
            try {
                return Long.parseLong(string);
            } catch (NumberFormatException exception) {
                return 0L;
            }
        }
        return 0L;
    }

    /**
     * Converts a serialized value to a boolean with a safe default.
     *
     * @param value serialized value
     * @return boolean value
     */
    private static boolean booleanValue(Object value) {
        return value instanceof Boolean ? (Boolean) value : Boolean.parseBoolean(text(value));
    }

    /**
     * Computes the deterministic path used for an artifact snapshot.
     *
     * @param artifact artifact metadata
     * @return snapshot path
     */
    private Path indexPath(MavenArtifact artifact) {
        String location = artifact.file() == null ? "" : artifact.file().toAbsolutePath().normalize().toString();
        return root.resolve(hash(artifact.coordinate() + "\n" + location) + ".json");
    }

    /**
     * Computes a short SHA-256 identifier for a snapshot file name.
     *
     * @param value value to hash
     * @return first 32 hexadecimal SHA-256 characters
     */
    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(32);
            for (int i = 0; i < 16; i++) {
                result.append(String.format("%02x", digest[i]));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /**
     * 单个构件字节码索引的元数据：快照文件、对应 JAR、指纹、类/方法/调用计数以及
     * 快照是否已成功写入。
     * Metadata retained for one persisted artifact index.
     *
     * @param coordinate artifact coordinate
     * @param indexFile snapshot path
     * @param classCount class count
     * @param methodCount method count
     * @param callCount call-edge count
     * @param indexed whether the snapshot was successfully written
     */
    private record ArtifactEntry(
            String coordinate,
            Path indexFile,
            Path artifactFile,
            JarFingerprint fingerprint,
            int classCount,
            int methodCount,
            int callCount,
            boolean indexed) {
    }

    /**
     * Loaded artifact index and its conservative memory estimate.
     */
    private record ResidentIndex(ProjectIndex index, long estimatedBytes) {
    }

    /**
     * Reports the result of restoring persisted bytecode snapshots.
     *
     * @param reusedArtifactCount number of snapshots reused
     * @param currentArtifactCount number of current artifacts represented
     * @param warnings non-fatal restoration warnings
     */
    public record RestoreResult(int reusedArtifactCount, int currentArtifactCount, List<String> warnings) {
    }
    /**
     * Reports resident-cache preload work.
     */
    public record PreloadResult(
            int eligibleArtifactCount,
            int loadedArtifactCount,
            int residentArtifactCount,
            long residentBytes,
            long snapshotLoadCount,
            long snapshotLoadBytes,
            List<String> warnings) {
    }

}
