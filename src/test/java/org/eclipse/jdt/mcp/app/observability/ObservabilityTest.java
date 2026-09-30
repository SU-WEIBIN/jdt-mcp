package org.eclipse.jdt.mcp.app.observability;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import junit.framework.TestCase;

/** Regression tests for structured logging and runtime accounting. */
public final class ObservabilityTest extends TestCase {

    /** 验证日志器每条记录输出为一行 JSON，且包含事件和字段。 */
    public void testLoggerWritesOneJsonLine() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (McpLogger logger = new McpLogger(output)) {
            logger.info("test.event", Map.of("durationMs", 12.5, "status", "ok"));
        }

        String line = output.toString(StandardCharsets.UTF_8);
        assertTrue(line.endsWith(System.lineSeparator()));
        assertTrue(line.contains("\"event\":\"test.event\""));
        assertTrue(line.contains("\"durationMs\":12.5"));
    }

    /** 验证请求指标按操作名聚合计数、错误数和响应字节。 */
    public void testRequestMetricsAggregatesPerOperation() {
        RequestMetrics metrics = new RequestMetrics();
        metrics.record("tools/call/search_symbols", 10_000_000L, true, 100);
        metrics.record("tools/call/search_symbols", 30_000_000L, false, 50);

        Map<String, Object> snapshot = metrics.snapshot();
        assertEquals(2L, snapshot.get("totalRequests"));
        assertEquals(1L, snapshot.get("successfulRequests"));
        assertEquals(1L, snapshot.get("failedRequests"));
        Map<?, ?> operations = (Map<?, ?>) snapshot.get("operations");
        Map<?, ?> search = (Map<?, ?>) operations.get("tools/call/search_symbols");
        assertEquals(2L, search.get("count"));
        assertEquals(1L, search.get("errorCount"));
        assertEquals(150L, search.get("responseBytes"));
    }

    /** 验证请求资源分类根据缓存命中和工具语义判断内存/磁盘来源。 */
    public void testStorageClassificationUsesCacheCountersAndToolSemantics() {
        RuntimeMonitor.Snapshot before = snapshot(10, 100, 0, 0, 0);
        RuntimeMonitor.Snapshot afterMemory = snapshot(10, 100, 2, 0, 0);
        RuntimeMonitor.Snapshot afterDisk = snapshot(10, 100, 2, 1, 4096);

        assertEquals("memory", RuntimeMonitor.requestResources(before, afterMemory, "search_symbols")
                .get("storageSource"));
        assertEquals("memory+disk", RuntimeMonitor.requestResources(before, afterDisk, "search_symbols")
                .get("storageSource"));
        assertEquals("disk", RuntimeMonitor.requestResources(before, before, "search_text")
                .get("storageSource"));
    }

    /** 验证无项目时运行时快照仍生成 JSON 兼容的内存、缓存和磁盘信息。 */
    public void testRuntimeSnapshotIsJsonCompatibleWithoutProject() {
        RuntimeMonitor.Snapshot snapshot = RuntimeMonitor.capture(null);
        Map<String, Object> info = snapshot.toInfo();
        assertNotNull(info.get("memory"));
        assertNotNull(info.get("cache"));
        assertNotNull(info.get("disk"));
    }

    /** 验证进程级 CPU/内存指标可转换为 JSON 兼容映射。 */
    public void testProcessInfoIsJsonCompatible() {
        Map<String, Object> info = RuntimeMonitor.processInfo().toInfo();
        assertTrue(info.containsKey("processCpuTimeMillis"));
        assertTrue(info.containsKey("processCpuLoad"));
        assertTrue(info.containsKey("committedVirtualBytes"));
        assertTrue(info.containsKey("residentSetBytes"));
        assertTrue(info.containsKey("residentSetAvailable"));
    }

    /** 验证启动阶段计时器输出 startup.phase 和 startup.summary 事件。 */
    public void testStartupProfilerEmitsPhaseAndSummary() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (McpLogger logger = new McpLogger(output)) {
            StartupProfiler profiler = new StartupProfiler(logger, null);
            profiler.phase("test.phase", Map.of("items", 3));
            profiler.finish();
        }

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("\"event\":\"startup.phase\""));
        assertTrue(text.contains("\"phase\":\"test.phase\""));
        assertTrue(text.contains("\"items\":3"));
        assertTrue(text.contains("\"event\":\"startup.summary\""));
        assertTrue(text.contains("\"phaseCount\":1"));
    }

    /**
     * 构造用于资源分类断言的快照。
     *
     * @param residentBytes 常驻字节数
     * @param heapUsed 已用堆内存
     * @param cacheHits 常驻缓存命中数
     * @param snapshotLoads 快照加载次数
     * @param snapshotLoadBytes 快照加载字节数
     * @return 测试快照
     */
    private static RuntimeMonitor.Snapshot snapshot(long residentBytes, long heapUsed, long cacheHits,
            long snapshotLoads, long snapshotLoadBytes) {
        return new RuntimeMonitor.Snapshot(
                1L, 2L, heapUsed, heapUsed, heapUsed, 0L, 0L, residentBytes, 1000L, 1,
                snapshotLoads, snapshotLoadBytes, cacheHits, Map.of());
    }
}
