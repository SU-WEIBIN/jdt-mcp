package org.eclipse.jdt.mcp.app.index;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** A stable, protocol-facing description of a Java type or method. */
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
