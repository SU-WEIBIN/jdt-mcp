package org.eclipse.jdt.mcp.app.maven;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一条 Maven 模型或依赖解析诊断：包含严重级别、类别、消息、相关坐标和文件路径，
 * 并支持格式化为告警文本或序列化为元数据。
 * Describes a Maven model or artifact-resolution issue without losing its cause.
 */
public record MavenDiagnostic(
        Severity severity,
        Kind kind,
        String message,
        String coordinate,
        Path path) {

    /**
     * Normalizes diagnostic fields for stable serialization.
     */
    public MavenDiagnostic {
        severity = severity == null ? Severity.WARNING : severity;
        kind = kind == null ? Kind.OTHER : kind;
        message = message == null ? "" : message;
    }

    /**
     * Formats this diagnostic for the legacy warning list.
     *
     * @return a stable human-readable diagnostic string
     */
    public String format() {
        StringBuilder result = new StringBuilder()
                .append('[').append(severity).append('/').append(kind).append("] ")
                .append(message);
        if (coordinate != null && !coordinate.isBlank()) {
            result.append(" (coordinate=").append(coordinate).append(')');
        }
        if (path != null) {
            result.append(" (path=").append(path).append(')');
        }
        return result.toString();
    }

    /**
     * Converts this diagnostic to MCP metadata.
     *
     * @return serializable diagnostic map
     */
    public Map<String, Object> toInfo() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("severity", severity.name());
        result.put("kind", kind.name());
        result.put("message", message);
        result.put("coordinate", coordinate);
        result.put("path", path == null ? null : path.toString());
        return result;
    }

    /**
     * 诊断严重级别，用于推导 Maven 解析状态（INFO/WARNING/ERROR）。
     * Diagnostic severity used for resolution-state grading.
     */
    public enum Severity {
        INFO,
        WARNING,
        ERROR
    }

    /**
     * 诊断类别，用于区分模型错误与本地文件缺失等具体原因。
     * Categories that distinguish model errors from missing local files.
     */
    public enum Kind {
        PARENT_NOT_FOUND,
        PARENT_MISMATCH,
        BOM_NOT_FOUND,
        POM_CYCLE,
        INVALID_POM,
        INVALID_DEPENDENCY,
        UNRESOLVED_VERSION,
        UNRESOLVED_PROPERTY,
        MISSING_POM,
        MISSING_JAR,
        MISSING_SYSTEM_PATH,
        PROFILE_NOT_FOUND,
        SKIPPED_MODULE,
        VERSION_CONFLICT,
        OTHER
    }
}
