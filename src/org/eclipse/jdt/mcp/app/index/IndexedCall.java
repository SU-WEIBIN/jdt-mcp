package org.eclipse.jdt.mcp.app.index;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** One statically resolved or unresolved method call. */
public record IndexedCall(
        String callerId,
        String targetId,
        String targetSignature,
        String resolution,
        Path file,
        int line,
        int column,
        String expression) {

    public Map<String, Object> toInfo() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("callerId", callerId);
        result.put("targetId", targetId);
        result.put("targetSignature", targetSignature);
        result.put("resolution", resolution);
        result.put("file", file == null ? null : file.toString());
        result.put("line", line);
        result.put("column", column);
        result.put("expression", expression);
        return result;
    }
}
