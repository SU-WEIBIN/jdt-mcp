package org.eclipse.jdt.mcp.app.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.jdt.mcp.app.json.JsonCodec;

public final class ConfigLoader {

    private ConfigLoader() {
    }

    public static McpConfig load(Path configFile, Path projectOverride) throws IOException {
        Map<String, Object> values = new LinkedHashMap<>();
        Path baseDirectory = Path.of(".").toAbsolutePath().normalize();
        if (configFile != null) {
            Path absoluteConfig = configFile.toAbsolutePath().normalize();
            if (!Files.isRegularFile(absoluteConfig)) {
                throw new IOException("Configuration file does not exist: " + absoluteConfig);
            }
            baseDirectory = absoluteConfig.getParent();
            Object parsed = JsonCodec.parse(Files.readString(absoluteConfig, StandardCharsets.UTF_8));
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
