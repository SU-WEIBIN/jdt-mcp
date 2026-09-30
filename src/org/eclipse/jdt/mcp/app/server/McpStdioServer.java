package org.eclipse.jdt.mcp.app.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.jdt.mcp.app.core.ProjectContext;
import org.eclipse.jdt.mcp.app.decompiler.DecompiledArtifactStore;
import org.eclipse.jdt.mcp.app.decompiler.JarFingerprint;
import org.eclipse.jdt.mcp.app.index.IndexedCall;
import org.eclipse.jdt.mcp.app.index.IndexedSymbol;
import org.eclipse.jdt.mcp.app.json.JsonCodec;
import org.eclipse.jdt.mcp.app.observability.McpLogger;
import org.eclipse.jdt.mcp.app.observability.RequestMetrics;
import org.eclipse.jdt.mcp.app.observability.RuntimeMonitor;

/**
 * 基于 stdio 的 MCP JSON-RPC 服务器：逐行读取请求，处理 initialize、ping、tools/list、
 * tools/call，实现 project_info、index_status、runtime_status、search_symbols/search_text、
 * get_class/get_method、inspect_jar、调用者/被调者查询和调用链追踪等只读工具，并记录
 * 请求指标和资源使用。
 */
public final class McpStdioServer {
    private static final String PROTOCOL_VERSION = "2024-11-05";
    private final ProjectContext project;
    private final BufferedReader input;
    private final PrintWriter output;
    private final McpLogger logger;
    private final RequestMetrics metrics = new RequestMetrics();

    /**
     * 用输出流包装日志器，委托给主构造器。
     */
    public McpStdioServer(ProjectContext project, InputStream input, OutputStream output, OutputStream log) {
        this(project, input, output, new McpLogger(log));
    }

    /**
     * Creates a server using a shared structured logger.
     *
     * @param project loaded project context
     * @param input MCP request stream
     * @param output MCP response stream
     * @param logger structured diagnostic logger
     */
    public McpStdioServer(ProjectContext project, InputStream input, OutputStream output, McpLogger logger) {
        this.project = project;
        this.input = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        this.output = new PrintWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8), true);
        this.logger = logger;
    }

    /**
     * 主循环：逐行读取请求并处理，记录每次请求的指标和资源使用。
     */
    public void run() throws IOException {
        String line;
        while ((line = input.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            long started = System.nanoTime();
            Map<String, Object> request = safeParse(line);
            String method = textValue(request.get("method"));
            String tool = toolName(request);
            RuntimeMonitor.Snapshot before = RuntimeMonitor.capture(project);
            HandleResult result = null;
            try {
                result = handle(line);
            } catch (RuntimeException exception) {
                int responseBytes = 0;
                if (request.containsKey("id")) {
                    responseBytes = write(error(request.get("id"), -32603, exception.getMessage()));
                }
                result = new HandleResult(responseBytes, false);
                Map<String, Object> errorFields = new LinkedHashMap<>();
                errorFields.put("method", method == null ? "<invalid>" : method);
                errorFields.put("tool", tool == null ? "" : tool);
                errorFields.put("message", message(exception));
                logger.error("request.exception", errorFields);
            } finally {
                RuntimeMonitor.Snapshot after = RuntimeMonitor.capture(project);
                long durationNanos = Math.max(0L, System.nanoTime() - started);
                boolean success = result != null && result.success();
                int responseBytes = result == null ? 0 : result.responseBytes();
                String operation = tool == null || tool.isBlank()
                        ? (method == null || method.isBlank() ? "parse_error" : method)
                        : "tools/call/" + tool;
                metrics.record(operation, durationNanos, success, responseBytes);
                Map<String, Object> fields = new LinkedHashMap<>();
                fields.put("requestId", request.get("id"));
                fields.put("method", method == null ? "<invalid>" : method);
                if (tool != null) {
                    fields.put("tool", tool);
                }
                fields.put("status", success ? "ok" : "error");
                fields.put("durationMs", durationNanos / 1_000_000.0);
                fields.put("responseBytes", responseBytes);
                fields.put("resources", RuntimeMonitor.requestResources(before, after, tool));
                if (success) {
                    logger.info("request.completed", fields);
                } else {
                    logger.error("request.completed", fields);
                }
            }
        }
        logger.info("server.stopped", Map.of("metrics", metrics.snapshot()));
    }

    /**
     * Handles one JSON-RPC request. Bytecode query data is served from the
     * project's resident artifact cache after indexing/preload completes.
     *
     * @param line incoming JSON-RPC request
     */
    private HandleResult handle(String line) {
        Map<String, Object> request = JsonCodec.parseObject(line);
        Object methodValue = request.get("method");
        if (!(methodValue instanceof String)) {
            if (request.containsKey("id")) {
                return new HandleResult(write(error(request.get("id"), -32600,
                        "Request method is required")), false);
            }
            return new HandleResult(0, false);
        }
        String method = (String) methodValue;
        if ("notifications/initialized".equals(method)) {
            return new HandleResult(0, true);
        }
        Object id = request.get("id");
        if ("initialize".equals(method)) {
            return new HandleResult(write(success(id, initializeResult())), true);
        } else if ("ping".equals(method)) {
            return new HandleResult(write(success(id, new LinkedHashMap<>())), true);
        } else if ("tools/list".equals(method)) {
            return new HandleResult(write(success(id, toolsListResult())), true);
        } else if ("tools/call".equals(method)) {
            return new HandleResult(write(success(id, callTool(request))), true);
        } else if (request.containsKey("id")) {
            return new HandleResult(write(error(id, -32601, "Unsupported method: " + method)), false);
        }
        return new HandleResult(0, true);
    }

    /**
     * 构造 initialize 响应，声明协议版本、能力和服务器信息。
     */
    private Map<String, Object> initializeResult() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", PROTOCOL_VERSION);
        result.put("capabilities", Map.of("tools", Map.of()));
        result.put("serverInfo", Map.of("name", "jdt-mcp", "version", "0.1.0"));
        result.put("instructions", "Read-only Java project navigation. Source indexing is performed during startup.");
        return result;
    }

    /**
     * 构造 tools/list 响应，返回全部工具及其输入 schema。
     */
    private Map<String, Object> toolsListResult() {
        List<Map<String, Object>> tools = new ArrayList<>();
        tools.add(tool("project_info", "Return the loaded Maven project and cache locations."));
        tools.add(tool("index_status", "Return the current project indexing status."));
        tools.add(tool("runtime_status", "Return request metrics, JVM memory, cache residency and disk capacity."));
        tools.add(tool("search_symbols", "Search project source types and methods by name or signature.",
                Map.of("query", Map.of("type", "string"), "kind", Map.of("type", "string"),
                        "maxResults", Map.of("type", "integer"))));
        tools.add(tool("search_text", "Search text in project main Java source files.",
                Map.of("query", Map.of("type", "string"), "path", Map.of("type", "string"),
                        "caseSensitive", Map.of("type", "boolean"), "maxResults", Map.of("type", "integer"))));
        tools.add(tool("get_class", "Return one indexed class and its source when available.",
                Map.of("id", Map.of("type", "string"), "query", Map.of("type", "string"))));
        tools.add(tool("get_method", "Return one indexed method and its source when available.",
                Map.of("id", Map.of("type", "string"), "query", Map.of("type", "string"))));
        tools.add(tool("inspect_jar", "Inspect one resolved Maven JAR and list indexed classes.",
                Map.of("coordinate", Map.of("type", "string"), "query", Map.of("type", "string"),
                        "maxResults", Map.of("type", "integer"))));
        tools.add(tool("find_project_usages", "Find project call sites that use a method.",
                Map.of("symbolId", Map.of("type", "string"), "query", Map.of("type", "string"),
                        "maxResults", Map.of("type", "integer"))));
        tools.add(tool("find_callers", "Find callers of a method.",
                Map.of("symbolId", Map.of("type", "string"), "query", Map.of("type", "string"),
                        "maxResults", Map.of("type", "integer"))));
        tools.add(tool("find_callees", "Find methods called by a project method.",
                Map.of("symbolId", Map.of("type", "string"), "query", Map.of("type", "string"),
                        "maxResults", Map.of("type", "integer"))));
        tools.add(tool("trace_call_chain", "Trace outgoing project calls from a method.",
                Map.of("symbolId", Map.of("type", "string"), "query", Map.of("type", "string"),
                        "maxDepth", Map.of("type", "integer"), "maxResults", Map.of("type", "integer"))));
        return Map.of("tools", tools);
    }

    /**
     * 定义无参数工具。
     */
    private Map<String, Object> tool(String name, String description) {
        return tool(name, description, Map.of());
    }

    /**
     * 定义带参数 schema 的工具。
     */
    private Map<String, Object> tool(String name, String description, Map<String, Object> properties) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("additionalProperties", false);
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", name);
        tool.put("description", description);
        tool.put("inputSchema", schema);
        return tool;
    }

    /**
     * 按工具名把 tools/call 分派到具体实现。
     */
    private Map<String, Object> callTool(Map<String, Object> request) {
        Object paramsValue = request.get("params");
        if (!(paramsValue instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("tools/call params are required");
        }
        Object nameValue = ((Map<?, ?>) paramsValue).get("name");
        if (!(nameValue instanceof String)) {
            throw new IllegalArgumentException("tools/call params.name is required");
        }
        String name = (String) nameValue;
        Map<String, Object> arguments = arguments((Map<?, ?>) paramsValue);
        Map<String, Object> result = switch (name) {
        case "project_info" -> toolTextResult(project.projectInfo());
        case "index_status" -> toolTextResult(project.indexStatus());
        case "runtime_status" -> toolTextResult(RuntimeMonitor.currentInfo(project, metrics));
        case "search_symbols" -> toolTextResult(searchSymbols(arguments));
        case "search_text" -> toolTextResult(searchText(arguments));
        case "get_class" -> toolTextResult(getSymbol(arguments, "TYPE"));
        case "get_method" -> toolTextResult(getSymbol(arguments, "METHOD"));
        case "inspect_jar" -> toolTextResult(inspectJar(arguments));
        case "find_project_usages", "find_callers" -> toolTextResult(findCallers(arguments));
        case "find_callees" -> toolTextResult(findCallees(arguments));
        case "trace_call_chain" -> toolTextResult(trace(arguments));
        default -> throw new IllegalArgumentException("Unknown tool: " + name);
        };
        return result;
    }

    /**
     * Searches project symbols and lazily scanned bytecode symbols.
     *
     * @param arguments MCP tool arguments
     * @return serialized search response
     */
    private Map<String, Object> searchSymbols(Map<String, Object> arguments) {
        String query = string(arguments, "query", "");
        String kind = string(arguments, "kind", null);
        int maxResults = integer(arguments, "maxResults", project.config().maxResults());
        List<Map<String, Object>> results = project.searchSymbols(query, kind, maxResults).stream()
                .map(IndexedSymbol::toInfo)
                .toList();
        return Map.of("query", query, "count", results.size(), "results", results);
    }

    /**
     * 在模块 main 源码中做文本搜索，支持路径过滤和大小写选项。
     */
    private Map<String, Object> searchText(Map<String, Object> arguments) {
        String query = string(arguments, "query", null);
        if (query == null || query.isEmpty()) {
            throw new IllegalArgumentException("query is required");
        }
        String pathFilter = string(arguments, "path", null);
        boolean caseSensitive = bool(arguments, "caseSensitive", false);
        int maxResults = integer(arguments, "maxResults", project.config().maxResults());
        String expected = caseSensitive ? query : query.toLowerCase(java.util.Locale.ROOT);
        List<Map<String, Object>> results = new ArrayList<>();
        if (project.mavenProject() != null) {
            outer: for (org.eclipse.jdt.mcp.app.maven.MavenModule module : project.mavenProject().modules()) {
                for (Path root : module.mainSourceRoots()) {
                    if (!Files.isDirectory(root)) {
                        continue;
                    }
                    try (var files = Files.walk(root)) {
                        for (Path file : files.filter(path -> Files.isRegularFile(path)
                                && path.getFileName().toString().endsWith(".java")).toList()) {
                            if (pathFilter != null && !file.toString().contains(pathFilter)) {
                                continue;
                            }
                            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                            for (int i = 0; i < lines.size(); i++) {
                                String line = lines.get(i);
                                String candidate = caseSensitive ? line : line.toLowerCase(java.util.Locale.ROOT);
                                if (candidate.contains(expected)) {
                                    Map<String, Object> match = new LinkedHashMap<>();
                                    match.put("file", file.toString());
                                    match.put("module", module.coordinate());
                                    match.put("line", i + 1);
                                    match.put("text", line);
                                    results.add(match);
                                    if (results.size() >= maxResults) {
                                        break outer;
                                    }
                                }
                            }
                        }
                    } catch (IOException exception) {
                        // Continue searching other source roots.
                    }
                }
            }
        }
        return Map.of("query", query, "count", results.size(), "truncated", results.size() >= maxResults,
                "results", results);
    }

    /**
     * 按 id 或查询返回单个类/方法，附带源码或反编译源码。
     */
    private Map<String, Object> getSymbol(Map<String, Object> arguments, String kind) {
        IndexedSymbol symbol = symbolArgument(arguments, kind);
        if (symbol == null) {
            return Map.of("found", false, "results", List.of());
        }
        Map<String, Object> result = new LinkedHashMap<>(symbol.toInfo());
        result.put("found", true);
        String source = source(symbol);
        if (source != null) {
            result.put("sourceText", source);
        } else {
            addDecompiledSource(result, symbol);
        }
        return result;
    }

    /**
     * 对字节码/依赖符号按需反编译并补充源码文本。
     */
    private void addDecompiledSource(Map<String, Object> result, IndexedSymbol symbol) {
        if (!"bytecode".equals(symbol.sourceKind()) && !"dependency".equals(symbol.sourceKind())) {
            return;
        }
        org.eclipse.jdt.mcp.app.maven.MavenArtifact artifact = project.artifact(symbol.module());
        if (artifact == null && symbol.file() != null) {
            artifact = project.mavenProject().artifacts().stream()
                    .filter(candidate -> symbol.file().equals(candidate.file()))
                    .findFirst().orElse(null);
        }
        if (artifact == null) {
            return;
        }
        try {
            JarFingerprint fingerprint = project.bytecodeIndex().artifactFingerprint(artifact.coordinate());
            DecompiledArtifactStore.Result decompiled = project.decompiledArtifacts().ensure(artifact, fingerprint);
            String qualifiedName = symbol.qualifiedName();
            int separator = qualifiedName == null ? -1 : qualifiedName.indexOf('#');
            if (separator >= 0) {
                qualifiedName = qualifiedName.substring(0, separator);
            }
            Path sourceFile = project.decompiledArtifacts().findClassSource(decompiled, qualifiedName);
            if (sourceFile != null) {
                String sourceText = Files.readString(sourceFile, StandardCharsets.UTF_8);
                int max = Math.max(1024, project.config().maxResponseBytes() / 2);
                result.put("decompiledFile", sourceFile.toString());
                result.put("decompiler", decompiled.decompiler());
                result.put("sourceText", sourceText.substring(0, Math.min(max, sourceText.length())));
            }
        } catch (IOException exception) {
            result.put("decompileError", exception.getMessage());
        }
    }

    /**
     * Finds callers in the source index and persisted bytecode indexes.
     *
     * @param arguments MCP tool arguments
     * @return serialized caller response
     */
    private Map<String, Object> findCallers(Map<String, Object> arguments) {
        String symbolId = string(arguments, "symbolId", null);
        String query = string(arguments, "query", null);
        if (symbolId == null && query != null) {
            IndexedSymbol symbol = symbolArgument(arguments, "METHOD");
            symbolId = symbol == null ? null : symbol.id();
        }
        int maxResults = integer(arguments, "maxResults", project.config().maxResults());
        List<Map<String, Object>> results = project.callers(symbolId, query, maxResults).stream()
                .map(this::callInfo)
                .toList();
        return Map.of("symbolId", symbolId, "query", query, "count", results.size(), "results", results);
    }

    /**
     * Inspects one persisted bytecode artifact index.
     *
     * @param arguments MCP tool arguments
     * @return serialized artifact inspection response
     */
    private Map<String, Object> inspectJar(Map<String, Object> arguments) {
        String coordinate = string(arguments, "coordinate", null);
        if (coordinate == null) {
            String query = string(arguments, "query", null);
            if (query != null) {
                coordinate = project.mavenProject().artifacts().stream()
                        .filter(artifact -> artifact.coordinate().contains(query))
                        .map(org.eclipse.jdt.mcp.app.maven.MavenArtifact::coordinate)
                        .findFirst().orElse(null);
            }
        }
        if (coordinate == null) {
            throw new IllegalArgumentException("coordinate or query is required");
        }
        String selectedCoordinate = coordinate;
        org.eclipse.jdt.mcp.app.maven.MavenArtifact artifact = project.artifact(selectedCoordinate);
        if (artifact == null) {
            return Map.of("found", false, "coordinate", selectedCoordinate);
        }
        int maxResults = integer(arguments, "maxResults", project.config().maxResults());
        String classQuery = string(arguments, "query", "");
        List<Map<String, Object>> classes = project.bytecodeIndex()
                .searchArtifactSymbols(selectedCoordinate, classQuery, "TYPE", maxResults).stream()
                .map(IndexedSymbol::toInfo)
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("found", true);
        result.put("artifact", artifact.toInfo());
        result.put("classCount", project.bytecodeIndex().classCount(selectedCoordinate));
        result.put("classes", classes);
        result.put("truncated", classes.size() >= maxResults);
        return result;
    }

    /**
     * Finds direct callees in the source index and persisted bytecode indexes.
     *
     * @param arguments MCP tool arguments
     * @return serialized callee response
     */
    private Map<String, Object> findCallees(Map<String, Object> arguments) {
        IndexedSymbol caller = symbolArgument(arguments, "METHOD");
        if (caller == null) {
            return Map.of("found", false, "results", List.of());
        }
        int maxResults = integer(arguments, "maxResults", project.config().maxResults());
        List<Map<String, Object>> results = project.callees(caller.id(), maxResults).stream()
                .map(this::callInfo)
                .toList();
        return Map.of("found", true, "caller", caller.toInfo(), "count", results.size(), "results", results);
    }

    /**
     * Traces calls while loading persisted artifact snapshots one scan pass at
     * a time.
     *
     * @param arguments MCP tool arguments
     * @return serialized call-chain response
     */
    private Map<String, Object> trace(Map<String, Object> arguments) {
        IndexedSymbol start = symbolArgument(arguments, "METHOD");
        if (start == null) {
            return Map.of("found", false, "results", List.of());
        }
        int maxDepth = integer(arguments, "maxDepth", project.config().maxCallDepth());
        int maxResults = integer(arguments, "maxResults", project.config().maxResults());
        return Map.of("found", true, "start", start.toInfo(), "results",
                traceCalls(start.id(), maxDepth, maxResults));
    }

    /**
     * Performs breadth-first tracing while retaining only bounded result and
     * frontier state.
     *
     * @param startId starting method identifier
     * @param maxDepth maximum traversal depth
     * @param maxResults maximum number of returned edges
     * @return serialized trace edges
     */
    private List<Map<String, Object>> traceCalls(String startId, int maxDepth, int maxResults) {
        int depthLimit = Math.max(1, maxDepth);
        int resultLimit = Math.max(1, maxResults);
        List<Map<String, Object>> result = new ArrayList<>();
        List<TraceNode> current = List.of(new TraceNode(startId, List.of(startId)));
        Set<String> expanded = new HashSet<>();
        expanded.add(startId);
        int depth = 0;
        while (!current.isEmpty() && depth < depthLimit && result.size() < resultLimit) {
            Set<String> callerIds = new LinkedHashSet<>();
            for (TraceNode node : current) {
                callerIds.add(node.symbolId());
            }
            List<IndexedCall> calls = project.callsFrom(callerIds, resultLimit - result.size());
            Map<String, List<IndexedCall>> outgoing = new LinkedHashMap<>();
            for (IndexedCall call : calls) {
                outgoing.computeIfAbsent(call.callerId(), ignored -> new ArrayList<>()).add(call);
            }
            List<TraceNode> next = new ArrayList<>();
            for (TraceNode node : current) {
                for (IndexedCall call : outgoing.getOrDefault(node.symbolId(), List.of())) {
                    List<String> path = new ArrayList<>(node.path());
                    path.add(call.targetId());
                    Map<String, Object> edge = new LinkedHashMap<>();
                    edge.put("depth", depth + 1);
                    edge.put("path", path);
                    edge.put("call", call.toInfo());
                    result.add(edge);
                    if (expanded.add(call.targetId())) {
                        next.add(new TraceNode(call.targetId(), List.copyOf(path)));
                    }
                    if (result.size() >= resultLimit) {
                        break;
                    }
                }
                if (result.size() >= resultLimit) {
                    break;
                }
            }
            current = next;
            depth++;
        }
        return List.copyOf(result);
    }

    /**
     * Adds resolved caller and target symbols to one call response.
     *
     * @param call call edge
     * @return serialized call information
     */
    private Map<String, Object> callInfo(IndexedCall call) {
        Map<String, Object> result = new LinkedHashMap<>(call.toInfo());
        IndexedSymbol caller = project.symbol(call.callerId());
        IndexedSymbol target = project.symbol(call.targetId());
        result.put("caller", caller == null ? null : caller.toInfo());
        result.put("target", target == null ? null : target.toInfo());
        return result;
    }

    /**
     * Resolves a symbol argument against both source and bytecode indexes.
     *
     * @param arguments MCP tool arguments
     * @param kind required symbol kind
     * @return matching symbol, or {@code null}
     */
    private IndexedSymbol symbolArgument(Map<String, Object> arguments, String kind) {
        String id = string(arguments, "id", null);
        if (id == null) {
            id = string(arguments, "symbolId", null);
        }
        if (id != null) {
            IndexedSymbol symbol = project.symbol(id);
            if (symbol != null && kind.equalsIgnoreCase(symbol.kind())) {
                return symbol;
            }
        }
        String query = string(arguments, "query", null);
        if (query == null) {
            return null;
        }
        return project.searchSymbols(query, kind, 1).stream().findFirst().orElse(null);
    }

    /**
     * 按符号记录的 offset/length 截取源码文本，受响应大小限制。
     */
    private String source(IndexedSymbol symbol) {
        if (symbol.file() == null || symbol.startOffset() < 0 || symbol.length() <= 0) {
            return null;
        }
        try {
            String text = Files.readString(symbol.file(), StandardCharsets.UTF_8);
            int start = Math.min(symbol.startOffset(), text.length());
            int end = Math.min(text.length(), start + symbol.length());
            int max = Math.max(1024, project.config().maxResponseBytes() / 2);
            return text.substring(start, Math.min(end, start + max));
        } catch (IOException exception) {
            return null;
        }
    }

    /**
     * 提取 tools/call 的 arguments 对象，缺省时返回空映射。
     */
    private static Map<String, Object> arguments(Map<?, ?> params) {
        Object value = params.get("arguments");
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("tools/call params.arguments must be an object");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            if (entry.getKey() instanceof String) {
                result.put((String) entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    /**
     * 读取字符串参数，缺省时返回回退值。
     */
    private static String string(Map<String, Object> values, String name, String fallback) {
        Object value = values.get(name);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof String)) {
            throw new IllegalArgumentException(name + " must be a string");
        }
        return (String) value;
    }

    /**
     * 读取整数参数并保证为正数，缺省时返回回退值。
     */
    private static int integer(Map<String, Object> values, String name, int fallback) {
        Object value = values.get(name);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Number)) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        return Math.max(1, ((Number) value).intValue());
    }

    /**
     * 读取布尔参数，缺省时返回回退值。
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
     * 把结果包装为 MCP text content 响应。
     */
    private Map<String, Object> toolTextResult(Map<String, Object> value) {
        Map<String, Object> contentItem = new LinkedHashMap<>();
        contentItem.put("type", "text");
        contentItem.put("text", JsonCodec.stringify(value));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", List.of(contentItem));
        result.put("isError", false);
        return result;
    }

    /**
     * 写出一行 JSON-RPC 响应并返回写入字节数（含换行）。
     */
    private int write(Map<String, Object> response) {
        String serialized = JsonCodec.stringify(response);
        output.println(serialized);
        return serialized.getBytes(StandardCharsets.UTF_8).length
                + System.lineSeparator().getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * 构造成功的 JSON-RPC 响应。
     */
    private static Map<String, Object> success(Object id, Object result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", result);
        return response;
    }

    /**
     * 构造错误的 JSON-RPC 响应。
     */
    private static Map<String, Object> error(Object id, int code, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message == null ? "Unknown error" : message);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("error", error);
        return response;
    }

    /**
     * 从 tools/call 请求的 params 中提取工具名，缺失或类型不符时返回 null。
     *
     * @param request JSON-RPC 请求
     * @return 工具名，或 null
     */
    private static String toolName(Map<String, Object> request) {
        Object params = request.get("params");
        if (!(params instanceof Map<?, ?>)) {
            return null;
        }
        Object name = ((Map<?, ?>) params).get("name");
        return name instanceof String ? (String) name : null;
    }

    /**
     * 取字符串值，类型不符时返回 null。
     *
     * @param value JSON 值
     * @return 字符串值或 null
     */
    private static String textValue(Object value) {
        return value instanceof String ? (String) value : null;
    }

    /**
     * 提取异常消息；消息为空或空白时退化为异常类简单名。
     *
     * @param exception 异常
     * @return 可用于响应的消息
     */
    private static String message(Throwable exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    /**
     * 尝试把一行文本解析为 JSON 对象，失败时返回空映射，用于日志字段提取。
     *
     * @param line 请求文本
     * @return 解析后的对象，解析失败时为空映射
     */
    private static Map<String, Object> safeParse(String line) {
        try {
            return JsonCodec.parseObject(line);
        } catch (RuntimeException ignored) {
            return Map.of();
        }
    }

    /**
     * 单次请求处理的返回：写入的响应字节数以及是否成功。
     */
    private record HandleResult(int responseBytes, boolean success) {
    }

    /**
     * 调用链追踪的待扩展节点：当前符号和从起点到这里的路径。
     *
     * @param symbolId current symbol identifier
     * @param path path from the trace start
     */
    private record TraceNode(String symbolId, List<String> path) {
    }
}
