package org.eclipse.jdt.mcp.app.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.eclipse.jdt.mcp.app.json.JsonCodec;

/**
 * 把 {@link ProjectContext#projectInfo()} 序列化为 {@code metadata/project.json}，
 * 作为项目元数据快照落盘。
 */
public final class ProjectMetadataStore {

    /**
     * 把项目信息写入 metadata/project.json。
     */
    public void save(ProjectContext project) throws IOException {
        Path metadataFile = project.metadataRoot().resolve("project.json");
        Files.writeString(
                metadataFile,
                JsonCodec.stringify(project.projectInfo()),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
    }
}
