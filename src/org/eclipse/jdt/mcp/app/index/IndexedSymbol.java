package org.eclipse.jdt.mcp.app.index;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一个 Java 类型或方法符号的稳定描述：包含对协议暴露的 id、种类、名称、签名、来源
 * （源码/字节码）、所属模块、文件位置和行号范围等，可序列化为 MCP 响应或持久化快照。
 * A stable, protocol-facing description of a Java type or method.
 */
public record IndexedSymbol(
        String id,
        String kind,
        String name,
        String qualifiedName,
        String signature,
        String sourceKind,
        String module,
        Path file,
        int startLine,
        int endLine,
        int startOffset,
        int length,
        String declaringTypeId) {

    /**
     * Converts this symbol to a JSON-compatible response and snapshot map.
     *
     * @return serialized symbol information
     */
    public Map<String, Object> toInfo() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("kind", kind);
        result.put("name", name);
        result.put("qualifiedName", qualifiedName);
        result.put("signature", signature);
        result.put("source", sourceKind);
        result.put("module", module);
        result.put("file", file == null ? null : file.toString());
        result.put("startLine", startLine);
        result.put("endLine", endLine);
        result.put("declaringTypeId", declaringTypeId);
        return result;
    }

    /**
     * Converts this symbol to the extended representation used by persistent
     * index snapshots, including source offsets omitted from MCP responses.
     *
     * @return serialized snapshot information
     */
    public Map<String, Object> toSnapshot() {
        Map<String, Object> result = new LinkedHashMap<>(toInfo());
        result.put("startOffset", startOffset);
        result.put("length", length);
        return result;
    }
}
