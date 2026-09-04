package org.eclipse.jdt.mcp.app.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.eclipse.jdt.mcp.app.json.JsonCodec;

public final class ProjectMetadataStore {

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
