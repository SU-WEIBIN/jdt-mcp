package org.eclipse.jdt.mcp.app.maven;

import java.nio.file.Path;
import java.util.List;

public record MavenModule(
        String groupId,
        String artifactId,
        String version,
        String packaging,
        Path directory,
        List<Path> mainSourceRoots,
        Path outputDirectory,
        List<MavenArtifact> dependencies) {

    public String coordinate() {
        return groupId + ":" + artifactId + ":" + version;
    }
}
