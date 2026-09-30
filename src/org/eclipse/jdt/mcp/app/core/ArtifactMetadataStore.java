package org.eclipse.jdt.mcp.app.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.mcp.app.decompiler.JarFingerprint;
import org.eclipse.jdt.mcp.app.json.JsonCodec;
import org.eclipse.jdt.mcp.app.maven.MavenArtifact;

/**
 * 把项目依赖清单写入 {@code metadata/artifacts.json}：为每个 Maven 构件记录坐标、
 * 文件位置以及 SHA-256、大小、修改时间指纹。若调用方已在校验缓存时算过指纹则直接
 * 复用，避免重复读取 JAR。
 */
public final class ArtifactMetadataStore {

    /**
     * Saves artifact metadata, calculating fingerprints when no shared scan is
     * available.
     *
     * @param project project whose artifacts are being saved
     * @throws IOException if metadata or an artifact fingerprint cannot be written
     */
    public void save(ProjectContext project) throws IOException {
        save(project, null);
    }

    /**
     * Saves artifact metadata using fingerprints already calculated during
     * cache validation to avoid rereading every JAR.
     *
     * @param project project whose artifacts are being saved
     * @param fingerprints shared fingerprints keyed by Maven coordinate
     * @throws IOException if metadata or an artifact fingerprint cannot be written
     */
    public void save(ProjectContext project, Map<String, JarFingerprint> fingerprints) throws IOException {
        List<Map<String, Object>> artifacts = new ArrayList<>();
        if (project.mavenProject() != null) {
            for (MavenArtifact artifact : project.mavenProject().artifacts()) {
                Map<String, Object> value = new LinkedHashMap<>(artifact.toInfo());
                if (artifact.file() != null && Files.isRegularFile(artifact.file())) {
                    JarFingerprint fingerprint = fingerprints == null ? null : fingerprints.get(artifact.coordinate());
                    if (fingerprint == null) {
                        fingerprint = JarFingerprint.calculate(artifact.file());
                    }
                    value.put("sha256", fingerprint.sha256());
                    value.put("size", fingerprint.size());
                    value.put("lastModifiedMillis", fingerprint.lastModifiedMillis());
                }
                artifacts.add(value);
            }
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("formatVersion", 1);
        root.put("projectId", project.projectId());
        root.put("artifacts", artifacts);
        Files.writeString(project.metadataRoot().resolve("artifacts.json"), JsonCodec.stringify(root),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
    }
}
