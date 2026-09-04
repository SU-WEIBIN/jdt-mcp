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

/** Persistent, never-automatically-deleted storage for decompiled artifacts. */
public final class DecompiledArtifactStore {
    private final Path root;
    private final Decompiler decompiler;

    public DecompiledArtifactStore(Path root) {
        this(root, new CfrDecompiler());
    }

    public DecompiledArtifactStore(Path root, Decompiler decompiler) {
        this.root = root.toAbsolutePath().normalize();
        this.decompiler = decompiler;
    }

    public Result ensure(MavenArtifact artifact) throws IOException {
        if (artifact.file() == null || !Files.isRegularFile(artifact.file())) {
            throw new IOException("Artifact file is not available: " + artifact.coordinate());
        }
        JarFingerprint fingerprint = JarFingerprint.calculate(artifact.file());
        Path artifactRoot = root.resolve(artifact.groupId().replace('.', '/'))
                .resolve(artifact.artifactId())
                .resolve(artifact.version())
                .resolve(fingerprint.sha256());
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

    private static boolean containsJavaFile(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return false;
        }
        try (var files = Files.walk(directory)) {
            return files.anyMatch(path -> Files.isRegularFile(path)
                    && path.getFileName().toString().endsWith(".java"));
        }
    }

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
