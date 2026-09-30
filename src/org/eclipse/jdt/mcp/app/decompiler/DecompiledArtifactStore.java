package org.eclipse.jdt.mcp.app.decompiler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.jdt.mcp.app.json.JsonCodec;
import org.eclipse.jdt.mcp.app.maven.MavenArtifact;

/**
 * 反编译产物的持久化存储：按构件坐标和 JAR 指纹分目录缓存反编译源码，已存在时直接复用，
 * 不会自动删除。同时维护 metadata.json，并提供按类名定位源码文件的能力。
 * Persistent, never-automatically-deleted storage for decompiled artifacts.
 */
public final class DecompiledArtifactStore {
    private final Path root;
    private final Decompiler decompiler;

    /**
     * 使用默认 CFR 反编译器创建存储。
     */
    public DecompiledArtifactStore(Path root) {
        this(root, new CfrDecompiler());
    }

    /**
     * 使用指定反编译器创建存储，根目录会被规范化为绝对路径。
     */
    public DecompiledArtifactStore(Path root, Decompiler decompiler) {
        this.root = root.toAbsolutePath().normalize();
        this.decompiler = decompiler;
    }

    /**
     * 确保构件已反编译；未提供指纹时自行计算。
     */
    public Result ensure(MavenArtifact artifact) throws IOException {
        return ensure(artifact, null);
    }

    /**
     * Ensures decompiled sources using a fingerprint already obtained by the
     * project index. This avoids hashing the same JAR again for every lookup.
     *
     * @param artifact artifact to decompile
     * @param knownFingerprint optional full fingerprint
     * @return decompiled artifact metadata
     * @throws IOException if the artifact cannot be read or decompiled
     */
    public Result ensure(MavenArtifact artifact, JarFingerprint knownFingerprint) throws IOException {
        if (artifact.file() == null || !Files.isRegularFile(artifact.file())) {
            throw new IOException("Artifact file is not available: " + artifact.coordinate());
        }
        JarFingerprint fingerprint = knownFingerprint == null
                ? JarFingerprint.calculate(artifact.file()) : knownFingerprint;
        Path artifactRoot = root.resolve(artifact.groupId().replace('.', '/'))
                .resolve(artifact.artifactId())
                .resolve(artifact.version())
                .resolve(fingerprint.storageKey());
        Path sourceRoot = artifactRoot.resolve("source");
        Path metadata = artifactRoot.resolve("metadata.json");
        Files.createDirectories(artifactRoot);
        if (!containsJavaFile(sourceRoot)) {
            decompiler.decompile(artifact.file(), sourceRoot);
            writeMetadata(metadata, artifact, fingerprint, sourceRoot);
        } else if (!Files.isRegularFile(metadata)) {
            writeMetadata(metadata, artifact, fingerprint, sourceRoot);
        }
        return new Result(artifact.coordinate(), artifact.file(), artifactRoot, sourceRoot, metadata,
                fingerprint.sha256(), decompiler.name());
    }

    /**
     * 在反编译结果中按限定类名查找 .java 文件，找不到时退化为按简单名匹配。
     */
    public Path findClassSource(Result result, String qualifiedName) {
        String relative = qualifiedName.replace('.', '/') + ".java";
        Path exact = result.sourceRoot().resolve(relative);
        if (Files.isRegularFile(exact)) {
            return exact;
        }
        String simpleName = qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
        try (var files = Files.walk(result.sourceRoot())) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals(simpleName + ".java"))
                    .findFirst().orElse(null);
        } catch (IOException exception) {
            return null;
        }
    }

    /**
     * 判断目录下是否已存在至少一个 .java 文件。
     */
    private static boolean containsJavaFile(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return false;
        }
        try (var files = Files.walk(directory)) {
            return files.anyMatch(path -> Files.isRegularFile(path)
                    && path.getFileName().toString().endsWith(".java"));
        }
    }

    /**
     * 写入反编译产物的 metadata.json，记录坐标、指纹、源码根和存储策略。
     */
    private static void writeMetadata(Path file, MavenArtifact artifact, JarFingerprint fingerprint, Path sourceRoot)
            throws IOException {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("coordinate", artifact.coordinate());
        value.put("jar", artifact.file().toString());
        value.put("sha256", fingerprint.sha256());
        value.put("size", fingerprint.size());
        value.put("lastModifiedMillis", fingerprint.lastModifiedMillis());
        value.put("sourceRoot", sourceRoot.toString());
        value.put("storagePolicy", "manual-delete-only");
        Files.writeString(file, JsonCodec.stringify(value), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    /**
     * 一次反编译的结果：构件坐标、JAR、存储根目录、源码根、元数据文件、
     * 内容指纹以及使用的反编译器名称。
     */
    public record Result(
            String coordinate,
            Path jar,
            Path artifactRoot,
            Path sourceRoot,
            Path metadata,
            String sha256,
            String decompiler) {
    }
}
