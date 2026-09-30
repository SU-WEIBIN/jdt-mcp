package org.eclipse.jdt.mcp.app.observability;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.jdt.mcp.app.json.JsonCodec;

/**
 * Small structured logger for the stdio server.
 *
 * <p>Every record is JSON Lines. The console stream is normally stderr because
 * stdout is reserved for MCP JSON-RPC responses. When a log file is supplied,
 * records are written to both streams.</p>
 */
public final class McpLogger implements AutoCloseable {
    private final PrintWriter console;
    private final PrintWriter file;
    private final Object lock = new Object();

    /**
     * Creates a logger that writes only to the supplied stream.
     *
     * @param output console/log output stream
     */
    public McpLogger(OutputStream output) {
        this(output, null);
    }

    /**
     * 用控制台输出和可选日志文件创建日志器。
     *
     * @param output 控制台/诊断输出流
     * @param fileOutput 追加写入的日志文件流，可为 null
     */
    private McpLogger(OutputStream output, OutputStream fileOutput) {
        this.console = new PrintWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8), true);
        this.file = fileOutput == null
                ? null
                : new PrintWriter(new OutputStreamWriter(fileOutput, StandardCharsets.UTF_8), true);
    }

    /**
     * Creates a logger that mirrors records to a UTF-8 append-only log file.
     * If the file cannot be opened, logging continues on the console and a
     * warning is emitted there.
     *
     * @param consoleOutput stderr or another diagnostic output stream
     * @param logFile append-only JSONL file
     * @return logger
     */
    public static McpLogger open(OutputStream consoleOutput, Path logFile) {
        try {
            Path absolute = logFile.toAbsolutePath().normalize();
            Path parent = absolute.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            OutputStream fileOutput = Files.newOutputStream(absolute,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
            return new McpLogger(consoleOutput, fileOutput);
        } catch (IOException | RuntimeException exception) {
            McpLogger fallback = new McpLogger(consoleOutput);
            fallback.warn("logging.file_unavailable", Map.of(
                    "file", String.valueOf(logFile),
                    "message", message(exception)));
            return fallback;
        }
    }

    /**
     * Writes an informational event.
     *
     * @param event stable event name
     * @param fields structured event fields
     */
    public void info(String event, Map<String, ?> fields) {
        write("INFO", event, fields);
    }

    /**
     * Writes a warning event.
     *
     * @param event stable event name
     * @param fields structured event fields
     */
    public void warn(String event, Map<String, ?> fields) {
        write("WARN", event, fields);
    }

    /**
     * Writes an error event.
     *
     * @param event stable event name
     * @param fields structured event fields
     */
    public void error(String event, Map<String, ?> fields) {
        write("ERROR", event, fields);
    }

    /**
     * 组装并写出单条 JSONL 记录，包含时间戳、级别、事件、线程和附加字段。
     *
     * @param level 日志级别
     * @param event 事件名
     * @param fields 结构化字段
     */
    private void write(String level, String event, Map<String, ?> fields) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("timestamp", Instant.now().toString());
        record.put("level", level);
        record.put("event", event == null ? "unknown" : event);
        record.put("thread", Thread.currentThread().getName());
        if (fields != null) {
            fields.forEach(record::put);
        }
        String line = JsonCodec.stringify(record);
        synchronized (lock) {
            console.println(line);
            if (file != null) {
                file.println(line);
            }
        }
    }

    /** 刷新控制台输出并关闭可选日志文件。 */
    @Override
    public void close() {
        synchronized (lock) {
            console.flush();
            if (file != null) {
                file.close();
            }
        }
    }

    /**
     * 提取异常消息；消息为空或空白时退化为异常类简单名。
     *
     * @param exception 异常
     * @return 可用于日志的消息
     */
    private static String message(Throwable exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }
}
