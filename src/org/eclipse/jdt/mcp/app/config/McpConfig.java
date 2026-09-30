package org.eclipse.jdt.mcp.app.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 运行期不可变配置：保存项目根目录、缓存/反编译目录、Maven 本地仓库、额外 JAR、
 * 激活的 profile，以及网络访问、测试/生成源码开关和调用深度、结果数、响应大小、
 * 常驻索引内存上限等限制，并支持从 JSON 值映射构建与校验。
 */
public record McpConfig(
        Path projectRoot,
        Path cacheRoot,
        Path decompileRoot,
        Path mavenLocalRepository,
        List<Path> additionalJars,
        List<String> activeProfiles,
        boolean allowNetwork,
        boolean includeTestSources,
        boolean includeGeneratedSources,
        int maxCallDepth,
        int maxResults,
        int maxResponseBytes,
        long maxResidentIndexBytes) {

    /**
     * Creates the default MCP configuration.
     *
     * @param projectRoot project root, possibly {@code null} before loading
     * @return default configuration
     */
    public static McpConfig defaults(Path projectRoot) {
        Path userHome = Path.of(System.getProperty("user.home"));
        Path base = userHome.resolve(".jdt-mcp");
        return new McpConfig(
                projectRoot,
                base.resolve("cache"),
                base.resolve("decompiled"),
                userHome.resolve(".m2").resolve("repository"),
                List.of(),
                List.of(),
                false,
                false,
                false,
                8,
                100,
                1024 * 1024,
                1536L * 1024L * 1024L);
    }

    /**
     * Returns this configuration with a normalized project root override.
     *
     * @param root project root
     * @return copied configuration
     */
    public McpConfig withProjectRoot(Path root) {
        return new McpConfig(root, cacheRoot, decompileRoot, mavenLocalRepository,
                additionalJars, activeProfiles,
                allowNetwork, includeTestSources, includeGeneratedSources,
                maxCallDepth, maxResults, maxResponseBytes, maxResidentIndexBytes);
    }

    /**
     * Validates required paths and query limits.
     */
    public void validate() {
        Objects.requireNonNull(projectRoot, "projectRoot is required");
        Objects.requireNonNull(cacheRoot, "cacheRoot is required");
        Objects.requireNonNull(decompileRoot, "decompileRoot is required");
        Objects.requireNonNull(mavenLocalRepository, "mavenLocalRepository is required");
        if (maxCallDepth < 1 || maxResults < 1 || maxResponseBytes < 1024) {
            throw new IllegalArgumentException("Call depth, result count and response size must be positive");
        }
        if (maxResidentIndexBytes < 0) {
            throw new IllegalArgumentException("maxResidentIndexBytes must not be negative");
        }
    }

    /**
     * Builds configuration from a JSON-like value map.
     *
     * @param values parsed configuration values
     * @param baseDirectory base directory for relative paths
     * @param projectOverride command-line project override
     * @return parsed configuration
     */
    public static McpConfig fromMap(Map<String, Object> values, Path baseDirectory, Path projectOverride) {
        McpConfig defaults = defaults(null);
        Path projectRoot = projectOverride != null
                ? projectOverride
                : path(values, "projectRoot", defaults.projectRoot(), baseDirectory);
        return new McpConfig(
                projectRoot,
                path(values, "cacheRoot", defaults.cacheRoot(), baseDirectory),
                path(values, "decompileRoot", defaults.decompileRoot(), baseDirectory),
                path(values, "mavenLocalRepository", defaults.mavenLocalRepository(), baseDirectory),
                paths(values, "additionalJars", baseDirectory),
                strings(values, "activeProfiles"),
                bool(values, "allowNetwork", defaults.allowNetwork()),
                bool(values, "includeTestSources", defaults.includeTestSources()),
                bool(values, "includeGeneratedSources", defaults.includeGeneratedSources()),
                integer(values, "maxCallDepth", defaults.maxCallDepth()),
                integer(values, "maxResults", defaults.maxResults()),
                integer(values, "maxResponseBytes", defaults.maxResponseBytes()),
                longValue(values, "maxResidentIndexBytes", defaults.maxResidentIndexBytes()));
    }

    /**
     * Parses one path-valued configuration property.
     *
     * @param values configuration values
     * @param name property name
     * @param fallback default path
     * @param baseDirectory relative path base
     * @return normalized path
     */
    private static Path path(Map<String, Object> values, String name, Path fallback, Path baseDirectory) {
        Object value = values.get(name);
        if (value == null) {
            return fallback.toAbsolutePath().normalize();
        }
        if (!(value instanceof String)) {
            throw new IllegalArgumentException(name + " must be a string path");
        }
        Path path = Path.of((String) value);
        return (path.isAbsolute() ? path : baseDirectory.resolve(path)).toAbsolutePath().normalize();
    }

    /**
     * Parses one boolean-valued configuration property.
     *
     * @param values configuration values
     * @param name property name
     * @param fallback default value
     * @return parsed boolean
     */
    private static boolean bool(Map<String, Object> values, String name, boolean fallback) {
        Object value = values.get(name);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Boolean)) {
            throw new IllegalArgumentException(name + " must be a boolean");
        }
        return (Boolean) value;
    }

    /**
     * Parses an array of path-valued configuration properties.
     *
     * @param values configuration values
     * @param name property name
     * @param baseDirectory relative path base
     * @return normalized paths
     */
    private static List<Path> paths(Map<String, Object> values, String name, Path baseDirectory) {
        Object value = values.get(name);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?>)) {
            throw new IllegalArgumentException(name + " must be an array of paths");
        }
        java.util.ArrayList<Path> result = new java.util.ArrayList<>();
        for (Object item : (List<?>) value) {
            if (!(item instanceof String)) {
                throw new IllegalArgumentException(name + " must contain only string paths");
            }
            Path path = Path.of((String) item);
            result.add((path.isAbsolute() ? path : baseDirectory.resolve(path)).toAbsolutePath().normalize());
        }
        return List.copyOf(result);
    }

    /**
     * Parses one integer-valued configuration property.
     *
     * @param values configuration values
     * @param name property name
     * @param fallback default value
     * @return parsed integer
     */
    private static int integer(Map<String, Object> values, String name, int fallback) {
        Object value = values.get(name);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Number)) {
            throw new IllegalArgumentException(name + " must be a number");
        }
        return ((Number) value).intValue();
    }

    /**
     * Parses one long-valued configuration property.
     *
     * @param values configuration values
     * @param name property name
     * @param fallback default value
     * @return parsed long value
     */
    private static long longValue(Map<String, Object> values, String name, long fallback) {
        Object value = values.get(name);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Number)) {
            throw new IllegalArgumentException(name + " must be a number");
        }
        return ((Number) value).longValue();
    }

    /**
     * Parses an array of non-blank profile identifiers.
     *
     * @param values configuration values
     * @param name property name
     * @return immutable profile identifiers
     */
    private static List<String> strings(Map<String, Object> values, String name) {
        Object value = values.get(name);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?>)) {
            throw new IllegalArgumentException(name + " must be an array of strings");
        }
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        for (Object item : (List<?>) value) {
            if (!(item instanceof String) || ((String) item).isBlank()) {
                throw new IllegalArgumentException(name + " must contain only non-blank strings");
            }
            result.add(((String) item).trim());
        }
        return List.copyOf(result);
    }
}
