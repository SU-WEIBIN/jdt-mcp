package org.eclipse.jdt.mcp.app.index;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一条方法调用边：记录调用者、目标方法 id 和签名、解析方式（直接调用、接口分派、
 * invokedynamic 等）以及调用点所在文件和位置，并提供序列化方法。
 * One statically resolved or unresolved method call.
 */
public record IndexedCall(
        String callerId,
        String targetId,
        String targetSignature,
        String resolution,
        Path file,
        int line,
        int column,
        String expression) {

    /**
     * 转换为可序列化的 MCP 响应映射。
     */
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
