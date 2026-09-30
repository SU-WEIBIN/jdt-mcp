package org.eclipse.jdt.mcp.app.config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import junit.framework.TestCase;

/** Regression tests for configuration loading. */
public final class ConfigLoaderTest extends TestCase {

    /** 验证带 UTF-8 BOM 的配置文件仍能正常加载。 */
    public void testLoadAcceptsUtf8Bom() throws Exception {
        Path directory = Files.createTempDirectory("jdt-mcp-config-test");
        try {
            Path projectRoot = Files.createDirectory(directory.resolve("project"));
            Files.writeString(projectRoot.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
            Path configFile = directory.resolve("jdt-mcp.json");
            String json = "{\"projectRoot\":\"" + projectRoot.toString().replace('\\', '/') + "\"}";
            Files.write(configFile, ("\uFEFF" + json).getBytes(StandardCharsets.UTF_8));

            McpConfig config = ConfigLoader.load(configFile, null);

            assertEquals(projectRoot.toAbsolutePath().normalize(), config.projectRoot());
        } finally {
            deleteRecursively(directory);
        }
    }

    /**
     * Deletes a temporary directory tree, ignoring individual deletion failures.
     *
     * @param root directory to remove
     * @throws Exception when the directory tree cannot be walked
     */
    private static void deleteRecursively(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
