package org.eclipse.jdt.mcp.app.index;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.eclipse.jdt.mcp.app.config.McpConfig;
import org.eclipse.jdt.mcp.app.core.ProjectContext;
import org.eclipse.jdt.mcp.app.decompiler.JarFingerprint;
import org.eclipse.jdt.mcp.app.json.JsonCodec;
import org.eclipse.jdt.mcp.app.maven.MavenArtifact;
import org.eclipse.jdt.mcp.app.maven.MavenModule;
import org.eclipse.jdt.mcp.app.maven.MavenProjectModel;

/**
 * 源码索引清单存储：为源码索引计算并保存一份覆盖源码文件、类路径和依赖构件的输入指纹，
 * 据此判断已有的 source-index.json 能否复用；不能复用时返回需要重建的结果。
 * Persists and validates the project-source index independently of bytecode snapshots.
 */
public final class SourceIndexStore {
    private static final int FORMAT_VERSION = 1;
    private static final int INPUT_FORMAT_VERSION = 1;

    private final Path indexRoot;

    /**
     * Creates a source-index manifest store rooted beside the source snapshot.
     *
     * @param indexRoot project index directory
     */
    public SourceIndexStore(Path indexRoot) {
        this.indexRoot = indexRoot.toAbsolutePath().normalize();
    }

    /**
     * Attempts to restore the source snapshot when all source and classpath
     * inputs have the same fingerprint as the previous indexing run.
     *
     * @param project project whose source index is being opened
     * @param artifactFingerprints current fingerprints keyed by coordinate
     * @return restored index metadata, or a non-reused result when rebuilding is required
     */
    public RestoreResult restore(ProjectContext project, Map<String, JarFingerprint> artifactFingerprints) {
        String inputFingerprint;
        try {
            inputFingerprint = inputFingerprint(project, artifactFingerprints);
        } catch (IOException exception) {
            return new RestoreResult(null, 0, 0, List.of(
                    "[INFO/source-cache] Could not fingerprint source inputs; rebuilding source index: "
                            + message(exception)), "", false);
        }

        Path manifestFile = indexRoot.resolve("source-manifest.json");
        if (!Files.isRegularFile(manifestFile)) {
            return new RestoreResult(null, 0, 0, List.of(), inputFingerprint, false);
        }

        try {
            Map<String, Object> manifest = JsonCodec.parseObject(
                    Files.readString(manifestFile, StandardCharsets.UTF_8));
            if (number(manifest.get("formatVersion")) != FORMAT_VERSION
                    || !project.projectId().equals(text(manifest.get("projectId")))) {
                return rebuildResult(inputFingerprint, "Source manifest format or project changed");
            }
            if (!inputFingerprint.equals(text(manifest.get("inputFingerprint")))) {
                return new RestoreResult(null, 0, 0, List.of(), inputFingerprint, false);
            }
            Path indexFile = indexRoot.resolve("source-index.json");
            if (!Files.isRegularFile(indexFile) || Files.size(indexFile) <= 0) {
                return rebuildResult(inputFingerprint, "Source snapshot is missing");
            }
            ProjectIndex index = ProjectIndex.load(indexFile);
            return new RestoreResult(index,
                    number(manifest.get("sourceFileCount")),
                    number(manifest.get("sourceRootCount")),
                    strings(manifest.get("warnings")),
                    inputFingerprint,
                    true);
        } catch (IOException | RuntimeException exception) {
            return rebuildResult(inputFingerprint, "Source snapshot could not be restored: " + message(exception));
        }
    }

    /**
     * Writes the manifest associated with a source snapshot that has just been
     * generated. The snapshot itself is written by {@link JdtSourceAnalyzer}.
     *
     * @param project project whose source index was analyzed
     * @param analysis analysis result and diagnostics
     * @param inputFingerprint fingerprint captured before analysis
     * @throws IOException if the manifest cannot be written
     */
    public void save(ProjectContext project, JdtSourceAnalyzer.AnalysisResult analysis, String inputFingerprint)
            throws IOException {
        if (project == null || analysis == null || inputFingerprint == null || inputFingerprint.isBlank()) {
            return;
        }
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("formatVersion", FORMAT_VERSION);
        manifest.put("projectId", project.projectId());
        manifest.put("inputFingerprint", inputFingerprint);
        manifest.put("sourceFileCount", analysis.sourceFileCount());
        manifest.put("sourceRootCount", analysis.sourceRootCount());
        manifest.put("warnings", analysis.warnings());
        Files.createDirectories(indexRoot);
        writeAtomically(indexRoot.resolve("source-manifest.json"), JsonCodec.stringify(manifest));
    }

    /**
     * Computes the deterministic fingerprint of source, Maven and classpath
     * inputs that affect JDT binding resolution.
     *
     * @param project project being indexed
     * @param artifactFingerprints fingerprints keyed by Maven coordinate
     * @return hexadecimal input fingerprint
     * @throws IOException if an input file cannot be inspected
     */
    private static String inputFingerprint(ProjectContext project,
            Map<String, JarFingerprint> artifactFingerprints) throws IOException {
        MavenProjectModel model = project.mavenProject();
        if (model == null) {
            throw new IOException("Maven model is not loaded");
        }
        List<String> descriptors = new ArrayList<>();
        add(descriptors, "input-format", INPUT_FORMAT_VERSION);
        add(descriptors, "project-id", project.projectId());
        McpConfig config = project.config();
        add(descriptors, "maven-local-repository", config.mavenLocalRepository());
        add(descriptors, "active-profiles", config.activeProfiles().stream().sorted().toList());
        add(descriptors, "additional-jars", config.additionalJars().stream().map(Path::toString).sorted().toList());
        add(descriptors, "include-test-sources", config.includeTestSources());
        add(descriptors, "include-generated-sources", config.includeGeneratedSources());

        addFileDescriptor(descriptors, project.projectRoot().resolve("pom.xml"));
        List<MavenModule> modules = new ArrayList<>(model.modules());
        modules.sort(Comparator.comparing(MavenModule::coordinate));
        for (MavenModule module : modules) {
            add(descriptors, "module", module.coordinate());
            add(descriptors, "packaging", module.packaging());
            add(descriptors, "module-directory", module.directory());
            addFileDescriptor(descriptors, module.directory().resolve("pom.xml"));
            for (Path sourceRoot : module.mainSourceRoots().stream().sorted().toList()) {
                addDirectoryDescriptor(descriptors, sourceRoot, ".java");
            }
            addDirectoryDescriptor(descriptors, module.outputDirectory(), null);
        }

        List<MavenArtifact> artifacts = new ArrayList<>(model.artifacts());
        artifacts.sort(Comparator.comparing(MavenArtifact::coordinate));
        for (MavenArtifact artifact : artifacts) {
            add(descriptors, "artifact", artifact.coordinate());
            add(descriptors, "artifact-file", artifact.file());
            add(descriptors, "artifact-classes", artifact.classesDirectory());
            add(descriptors, "artifact-resolved", artifact.resolved());
            add(descriptors, "artifact-reactor", artifact.reactorArtifact());
            JarFingerprint fingerprint = artifactFingerprints == null
                    ? null : artifactFingerprints.get(artifact.coordinate());
            if (fingerprint == null) {
                add(descriptors, "artifact-fingerprint", artifact.coordinate(), "missing");
            } else {
                add(descriptors, "artifact-fingerprint", artifact.coordinate(), fingerprint.sha256(),
                        fingerprint.size(), fingerprint.lastModifiedMillis());
            }
            addDirectoryDescriptor(descriptors, artifact.classesDirectory(), null);
        }

        descriptors.sort(String::compareTo);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String descriptor : descriptors) {
                digest.update(descriptor.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return hex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 is unavailable", exception);
        }
    }

    /**
     * Adds a descriptor for a scalar value while preserving field boundaries.
     *
     * @param descriptors descriptor collection
     * @param name field name
     * @param values field values
     */
    private static void add(List<String> descriptors, String name, Object... values) {
        StringBuilder value = new StringBuilder(name);
        for (Object item : values) {
            value.append('\u0001').append(item == null ? "<null>" : item);
        }
        descriptors.add(value.toString());
    }

    /**
     * Adds a file path, size and modification time to the input descriptors.
     *
     * @param descriptors descriptor collection
     * @param file file to inspect
     * @throws IOException if the file metadata cannot be read
     */
    private static void addFileDescriptor(List<String> descriptors, Path file) throws IOException {
        if (file == null) {
            return;
        }
        Path normalized = file.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) {
            add(descriptors, "missing-file", normalized);
            return;
        }
        add(descriptors, "file", normalized, Files.size(normalized), Files.getLastModifiedTime(normalized).toMillis());
    }

    /**
     * Adds a directory marker and metadata for files under a source or output
     * directory.
     *
     * @param descriptors descriptor collection
     * @param directory directory to inspect
     * @param suffix optional file-name suffix filter
     * @throws IOException if directory contents cannot be read
     */
    private static void addDirectoryDescriptor(List<String> descriptors, Path directory, String suffix)
            throws IOException {
        if (directory == null) {
            return;
        }
        Path normalized = directory.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized)) {
            add(descriptors, "missing-directory", normalized);
            return;
        }
        add(descriptors, "directory", normalized);
        try (Stream<Path> files = Files.walk(normalized)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                if (suffix == null || file.getFileName().toString().endsWith(suffix)) {
                    addFileDescriptor(descriptors, file);
                }
            }
        }
    }

    /**
     * Creates a non-fatal rebuild result with one informational diagnostic.
     *
     * @param inputFingerprint current source input fingerprint
     * @param reason reason the old snapshot was not reused
     * @return non-reused restore result
     */
    private static RestoreResult rebuildResult(String inputFingerprint, String reason) {
        return new RestoreResult(null, 0, 0, List.of("[INFO/source-cache] " + reason), inputFingerprint, false);
    }

    /**
     * Writes a text file through a same-directory temporary file and replaces
     * the destination after the complete content has been written.
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
     * Converts a digest to lowercase hexadecimal text.
     *
     * @param digest digest bytes
     * @return hexadecimal digest
     */
    private static String hex(byte[] digest) {
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            result.append(String.format("%02x", value));
        }
        return result.toString();
    }

    /**
     * Converts an exception to a concise diagnostic message.
     *
     * @param exception exception to describe
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
     * Converts a serialized number to an integer with a safe default.
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
     * Converts a serialized string array to immutable text values.
     *
     * @param value serialized value
     * @return string values, or an empty list
     */
    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item != null) {
                result.add(String.valueOf(item));
            }
        }
        return List.copyOf(result);
    }

    /**
     * 源码快照恢复结果：可复用的索引（需要重建时为 null）、源文件/源码根数量、告警、
     * 当前输入指纹以及是否成功复用。
     * Reports the result of a source snapshot restoration attempt.
     *
     * @param index restored source index, or {@code null} when rebuilding
     * @param sourceFileCount number of source files from the saved analysis
     * @param sourceRootCount number of source roots from the saved analysis
     * @param warnings non-fatal restore diagnostics
     * @param inputFingerprint current input fingerprint
     * @param reused whether the persisted snapshot was reused
     */
    public record RestoreResult(
            ProjectIndex index,
            int sourceFileCount,
            int sourceRootCount,
            List<String> warnings,
            String inputFingerprint,
            boolean reused) {
    }
}
