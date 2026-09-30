package org.eclipse.jdt.mcp.app.index;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.eclipse.jdt.mcp.app.json.JsonCodec;

/**
 * 内存查询索引：以 id 保存某个范围的符号（项目源码或单个 JAR）和调用边，提供符号搜索、
 * 按 id 查找、callers/callees/callsFrom 和调用链遍历等查询，并支持原子读写 JSON 快照以便
 * 检查和恢复。
 * In-memory query index with a JSON snapshot for inspection and recovery.
 */
public final class ProjectIndex {
    private final Map<String, IndexedSymbol> symbols = new LinkedHashMap<>();
    private final List<IndexedCall> calls = new ArrayList<>();

    /**
     * 添加符号；同一 id 已存在时优先保留带源码文件的版本。
     */
    public synchronized void addSymbol(IndexedSymbol symbol) {
        IndexedSymbol previous = symbols.get(symbol.id());
        if (previous == null || (previous.file() == null && symbol.file() != null)) {
            symbols.put(symbol.id(), symbol);
        }
    }

    /**
     * 追加一条调用边。
     */
    public synchronized void addCall(IndexedCall call) {
        calls.add(call);
    }

    /**
     * 返回符号总数。
     */
    public synchronized int symbolCount() {
        return symbols.size();
    }

    /**
     * 返回方法符号数量。
     */
    public synchronized int methodCount() {
        return (int) symbols.values().stream().filter(symbol -> "METHOD".equals(symbol.kind())).count();
    }

    /**
     * 返回类型符号数量。
     */
    public synchronized int typeCount() {
        return (int) symbols.values().stream().filter(symbol -> "TYPE".equals(symbol.kind())).count();
    }

    /**
     * 返回调用边数量。
     */
    public synchronized int callCount() {
        return calls.size();
    }

    /**
     * 按来源（project/bytecode 等）和种类统计符号数量。
     */
    public synchronized int symbolCount(String sourceKind, String kind) {
        return (int) symbols.values().stream()
                .filter(symbol -> sourceKind == null || sourceKind.equals(symbol.sourceKind()))
                .filter(symbol -> kind == null || kind.equals(symbol.kind()))
                .count();
    }

    /**
     * 按名称、限定名或签名做不区分大小写的模糊搜索，可限定符号种类。
     */
    public synchronized List<IndexedSymbol> searchSymbols(String query, String kind, int maxResults) {
        String needle = query == null ? "" : query.toLowerCase(Locale.ROOT);
        Predicate<IndexedSymbol> kindFilter = kind == null || kind.isBlank()
                ? symbol -> true
                : symbol -> kind.equalsIgnoreCase(symbol.kind());
        return symbols.values().stream()
                .filter(kindFilter)
                .filter(symbol -> needle.isBlank()
                        || contains(symbol.name(), needle)
                        || contains(symbol.qualifiedName(), needle)
                        || contains(symbol.signature(), needle))
                .limit(Math.max(1, maxResults))
                .toList();
    }

    /**
     * searchSymbols 的别名，兼容旧调用。
     */
    public synchronized List<IndexedSymbol> findSymbols(String query, String kind, int maxResults) {
        return searchSymbols(query, kind, maxResults);
    }

    /**
     * 按稳定 id 查找符号。
     */
    public synchronized IndexedSymbol symbol(String id) {
        return symbols.get(id);
    }

    /**
     * 返回全部符号的不可变副本。
     */
    public synchronized List<IndexedSymbol> symbols() {
        return List.copyOf(symbols.values());
    }

    /**
     * 返回全部调用边的不可变副本。
     */
    public synchronized List<IndexedCall> calls() {
        return List.copyOf(calls);
    }

    /**
     * 按目标 id 或目标签名查询调用者。
     */
    public synchronized List<IndexedCall> callers(String targetId, String targetQuery, int maxResults) {
        String needle = lower(targetQuery);
        return calls.stream()
                .filter(call -> targetId == null || targetId.equals(call.targetId()))
                .filter(call -> needle.isBlank() || contains(call.targetSignature(), needle))
                .limit(Math.max(1, maxResults))
                .toList();
    }

    /**
     * 按调用者 id 查询其直接被调方法。
     */
    public synchronized List<IndexedCall> callees(String callerId, int maxResults) {
        return calls.stream()
                .filter(call -> callerId.equals(call.callerId()))
                .limit(Math.max(1, maxResults))
                .toList();
    }

    /**
     * Returns calls whose callers are contained in the supplied set.
     *
     * @param callerIds caller symbol identifiers
     * @param maxResults maximum number of calls to return
     * @return matching calls in index order
     */
    public synchronized List<IndexedCall> callsFrom(Set<String> callerIds, int maxResults) {
        if (callerIds == null || callerIds.isEmpty()) {
            return List.of();
        }
        return calls.stream()
                .filter(call -> callerIds.contains(call.callerId()))
                .limit(Math.max(1, maxResults))
                .toList();
    }

    /**
     * 从起始符号做广度优先调用链遍历，限制深度和结果数。
     */
    public synchronized List<Map<String, Object>> trace(String startId, int maxDepth, int maxResults) {
        if (startId == null || startId.isBlank()) {
            return List.of();
        }
        Map<String, List<IndexedCall>> outgoing = calls.stream()
                .collect(Collectors.groupingBy(IndexedCall::callerId, LinkedHashMap::new, Collectors.toList()));
        List<Map<String, Object>> result = new ArrayList<>();
        Deque<TraceNode> queue = new ArrayDeque<>();
        queue.add(new TraceNode(startId, 0, List.of(startId)));
        Set<String> expanded = new java.util.HashSet<>();
        while (!queue.isEmpty() && result.size() < Math.max(1, maxResults)) {
            TraceNode current = queue.removeFirst();
            if (current.depth >= Math.max(1, maxDepth)) {
                continue;
            }
            for (IndexedCall call : outgoing.getOrDefault(current.symbolId, List.of())) {
                List<String> path = new ArrayList<>(current.path);
                path.add(call.targetId());
                Map<String, Object> edge = new LinkedHashMap<>();
                edge.put("depth", current.depth + 1);
                edge.put("path", path);
                edge.put("call", call.toInfo());
                result.add(edge);
                if (expanded.add(call.targetId())) {
                    queue.addLast(new TraceNode(call.targetId(), current.depth + 1, path));
                }
                if (result.size() >= Math.max(1, maxResults)) {
                    break;
                }
            }
        }
        return result;
    }

    /**
     * Writes this index as an atomically replaced JSON snapshot, including
     * source offsets needed to reconstruct source-backed symbol responses.
     *
     * @param file snapshot file
     * @param projectId project identifier written into the snapshot
     * @throws IOException if the snapshot cannot be written
     */
    public synchronized void save(Path file, String projectId) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("formatVersion", 1);
        root.put("projectId", projectId);
        root.put("symbolCount", symbols.size());
        root.put("methodCount", methodCount());
        root.put("callCount", calls.size());
        root.put("symbols", symbols.values().stream().map(IndexedSymbol::toSnapshot).toList());
        root.put("calls", calls.stream().map(IndexedCall::toInfo).toList());
        Path parent = file.toAbsolutePath().normalize().getParent();
        Files.createDirectories(parent);
        writeAtomically(file, JsonCodec.stringify(root));
    }

    /**
     * Loads an index snapshot written by {@link #save(Path, String)}.
     *
     * @param file snapshot file
     * @return reconstructed in-memory index
     * @throws IOException if the snapshot cannot be read
     */
    public static ProjectIndex load(Path file) throws IOException {
        Map<String, Object> root = JsonCodec.parseObject(Files.readString(file, StandardCharsets.UTF_8));
        ProjectIndex result = new ProjectIndex();
        Object symbolValue = root.get("symbols");
        if (symbolValue instanceof List<?>) {
            for (Object value : (List<?>) symbolValue) {
                if (value instanceof Map<?, ?>) {
                    result.addSymbol(readSymbol((Map<?, ?>) value));
                }
            }
        }
        Object callValue = root.get("calls");
        if (callValue instanceof List<?>) {
            for (Object value : (List<?>) callValue) {
                if (value instanceof Map<?, ?>) {
                    result.addCall(readCall((Map<?, ?>) value));
                }
            }
        }
        return result;
    }

    /**
     * Reconstructs one symbol from its serialized representation.
     *
     * @param value serialized symbol map
     * @return reconstructed symbol
     */
    private static IndexedSymbol readSymbol(Map<?, ?> value) {
        return new IndexedSymbol(
                text(value.get("id")),
                text(value.get("kind")),
                text(value.get("name")),
                text(value.get("qualifiedName")),
                text(value.get("signature")),
                text(value.get("source")),
                text(value.get("module")),
                path(value.get("file")),
                number(value.get("startLine")),
                number(value.get("endLine")),
                number(value.get("startOffset")),
                number(value.get("length")),
                text(value.get("declaringTypeId")));
    }

    /**
     * Reconstructs one call edge from its serialized representation.
     *
     * @param value serialized call map
     * @return reconstructed call edge
     */
    private static IndexedCall readCall(Map<?, ?> value) {
        return new IndexedCall(
                text(value.get("callerId")),
                text(value.get("targetId")),
                text(value.get("targetSignature")),
                text(value.get("resolution")),
                path(value.get("file")),
                number(value.get("line")),
                number(value.get("column")),
                text(value.get("expression")));
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
     * @return integer value, or zero when absent or malformed
     */
    private static int number(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException exception) {
                return 0;
            }
        }
        return 0;
    }

    /**
     * Converts a serialized path value to a nullable normalized path.
     *
     * @param value serialized path value
     * @return path value, or {@code null}
     */
    private static java.nio.file.Path path(Object value) {
        return value == null ? null : java.nio.file.Path.of(String.valueOf(value));
    }

    /**
     * Replaces a snapshot only after its complete JSON content is written.
     *
     * @param file destination snapshot
     * @param content serialized index content
     * @throws IOException if the temporary file or replacement cannot be written
     */
    private static void writeAtomically(Path file, String content) throws IOException {
        Path parent = file.toAbsolutePath().normalize().getParent();
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
     * 不区分大小写的子串包含判断。
     */
    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    /**
     * null 安全的字符串转小写。
     */
    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    /**
     * 调用链遍历的待扩展节点：当前符号、深度以及从起点到这里的路径。
     */
    private record TraceNode(String symbolId, int depth, List<String> path) {
    }
}
