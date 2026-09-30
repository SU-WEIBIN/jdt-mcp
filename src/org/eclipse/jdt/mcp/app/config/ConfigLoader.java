package org.eclipse.jdt.mcp.app.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.jdt.mcp.app.json.JsonCodec;

/**
 * 配置加载工具：读取可选的 JSON 配置文件，把其中的键值交给
 * {@link McpConfig#fromMap} 解析并应用项目路径覆盖，最后调用
 * {@link McpConfig#validate()} 校验。相对路径以配置文件所在目录为基准。
 */
public final class ConfigLoader {

    /**
     * 工具类，禁止实例化。
     */
    private ConfigLoader() {
    }

    /**
     * 加载配置：读取 JSON 文件（可为 null），以文件所在目录解析相对路径，再应用项目路径覆盖并校验。
     */
    public static McpConfig load(Path configFile, Path projectOverride) throws IOException {
        Map<String, Object> values = new LinkedHashMap<>();
        Path baseDirectory = Path.of(".").toAbsolutePath().normalize();
        if (configFile != null) {
            Path absoluteConfig = configFile.toAbsolutePath().normalize();
            if (!Files.isRegularFile(absoluteConfig)) {
                throw new IOException("Configuration file does not exist: " + absoluteConfig);
            }
            baseDirectory = absoluteConfig.getParent();
            String content = Files.readString(absoluteConfig, StandardCharsets.UTF_8);
            if (!content.isEmpty() && content.charAt(0) == '\uFEFF') {
                // Windows editors frequently save JSON as UTF-8 with a BOM.
                content = content.substring(1);
            }
            Object parsed = JsonCodec.parse(content);
            if (!(parsed instanceof Map<?, ?>)) {
                throw new IOException("Configuration root must be a JSON object: " + absoluteConfig);
            }
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) parsed).entrySet()) {
                if (!(entry.getKey() instanceof String)) {
                    throw new IOException("Configuration property names must be strings");
                }
                values.put((String) entry.getKey(), entry.getValue());
            }
        }
        McpConfig config = McpConfig.fromMap(values, baseDirectory, projectOverride);
        config.validate();
        return config;
    }
}
