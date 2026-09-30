package org.eclipse.jdt.mcp.app.maven;

import java.nio.file.Path;
import java.util.List;

/**
 * 一个 Maven 模块：坐标、打包方式、目录、main 源码根、输出目录及其依赖列表。
 */
public record MavenModule(
        String groupId,
        String artifactId,
        String version,
        String packaging,
        Path directory,
        List<Path> mainSourceRoots,
        Path outputDirectory,
        List<MavenArtifact> dependencies) {

    /**
     * 返回 groupId:artifactId:version 形式的模块坐标。
     */
    public String coordinate() {
        return groupId + ":" + artifactId + ":" + version;
    }
}
