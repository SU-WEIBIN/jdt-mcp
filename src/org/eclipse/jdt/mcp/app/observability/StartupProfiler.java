package org.eclipse.jdt.mcp.app.observability;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.jdt.mcp.app.core.ProjectContext;

/**
 * 启动阶段计时器：以“上一阶段结束”为检查点，记录每个阶段的耗时、堆内存增量、常驻索引
 * 增量、进程 CPU 时间增量以及可用时的 RSS，并通过 {@link McpLogger} 输出
 * {@code startup.phase} 与 {@code startup.summary} 事件，用于定位启动慢点和内存峰值。
 */
public final class StartupProfiler {
    private final McpLogger logger;
    private final ProjectContext project;
    private final long startedNanos = System.nanoTime();
    private long checkpointNanos = startedNanos;
    private long checkpointHeapUsedBytes;
    private long checkpointResidentBytes;
    private long checkpointCpuNanos;
    private long peakHeapUsedBytes;
    private long peakResidentBytes;
    private int phaseCount;

    /**
     * Creates a profiler that writes phase events through the supplied logger.
     *
     * @param logger structured logger; must not be {@code null}
     * @param project project context used for resident-index accounting, or
     *            {@code null} before the context exists
     */
    public StartupProfiler(McpLogger logger, ProjectContext project) {
        this.logger = logger;
        this.project = project;
        this.checkpointHeapUsedBytes = heapUsedBytes();
        this.checkpointResidentBytes = residentBytes();
        this.checkpointCpuNanos = processCpuTimeNanos();
        this.peakHeapUsedBytes = this.checkpointHeapUsedBytes;
        this.peakResidentBytes = this.checkpointResidentBytes;
    }

    /**
     * Records one phase using the profiler start or the previous phase as the
     * checkpoint.
     *
     * @param name stable phase name
     * @return elapsed nanoseconds for the phase
     */
    public long phase(String name) {
        return phase(name, Map.of());
    }

    /**
     * Records one phase together with extra structured fields.
     *
     * @param name stable phase name
     * @param details additional JSON-compatible phase fields, may be {@code null}
     * @return elapsed nanoseconds for the phase
     */
    public long phase(String name, Map<String, ?> details) {
        long now = System.nanoTime();
        long durationNanos = now - checkpointNanos;
        long heapUsedBytes = heapUsedBytes();
        long residentBytes = residentBytes();
        long cpuNanos = processCpuTimeNanos();
        RuntimeMonitor.ProcessInfo process = RuntimeMonitor.processInfo();

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("phase", name == null || name.isBlank() ? "unknown" : name);
        fields.put("durationMs", durationNanos / 1_000_000.0d);
        fields.put("elapsedMs", (now - startedNanos) / 1_000_000.0d);
        fields.put("heapUsedBytes", heapUsedBytes);
        fields.put("heapUsedDeltaBytes", heapUsedBytes - checkpointHeapUsedBytes);
        fields.put("residentBytes", residentBytes);
        fields.put("residentBytesDelta", residentBytes - checkpointResidentBytes);
        fields.put("processCpuMillis", millis(cpuNanos));
        fields.put("processCpuDeltaMillis", cpuNanos < 0L || checkpointCpuNanos < 0L
                ? -1.0d : (cpuNanos - checkpointCpuNanos) / 1_000_000.0d);
        fields.put("processCpuLoad", process.processCpuLoad());
        fields.put("rssBytes", process.residentSetBytes());
        if (details != null && !details.isEmpty()) {
            fields.putAll(details);
        }
        logger.info("startup.phase", fields);

        checkpointNanos = now;
        checkpointHeapUsedBytes = heapUsedBytes;
        checkpointResidentBytes = residentBytes;
        checkpointCpuNanos = cpuNanos;
        peakHeapUsedBytes = Math.max(peakHeapUsedBytes, heapUsedBytes);
        peakResidentBytes = Math.max(peakResidentBytes, residentBytes);
        phaseCount++;
        return durationNanos;
    }

    /**
     * Writes the aggregate startup summary. Call once after the last phase.
     */
    public void finish() {
        long now = System.nanoTime();
        RuntimeMonitor.ProcessInfo process = RuntimeMonitor.processInfo();
        long heapUsedBytes = heapUsedBytes();
        long residentBytes = residentBytes();
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("totalMs", (now - startedNanos) / 1_000_000.0d);
        fields.put("phaseCount", phaseCount);
        fields.put("heapUsedBytes", heapUsedBytes);
        fields.put("peakHeapUsedBytes", Math.max(peakHeapUsedBytes, heapUsedBytes));
        fields.put("residentBytes", residentBytes);
        fields.put("peakResidentBytes", Math.max(peakResidentBytes, residentBytes));
        fields.put("rssBytes", process.residentSetBytes());
        fields.put("committedVirtualBytes", process.committedVirtualBytes());
        fields.put("processCpuMillis", millis(process.processCpuTimeNanos()));
        logger.info("startup.summary", fields);
    }

    /**
     * Reads the current JVM heap usage, normalized to a non-negative value.
     *
     * @return used heap bytes, or {@code -1} when unavailable
     */
    private static long heapUsedBytes() {
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        return heap == null ? -1L : Math.max(0L, heap.getUsed());
    }

    /**
     * Reads the resident bytecode-cache size from the project context.
     *
     * @return resident cache bytes, or {@code 0} before a project is available
     */
    private long residentBytes() {
        if (project == null || project.bytecodeIndex() == null) {
            return 0L;
        }
        return project.bytecodeIndex().residentBytes();
    }

    /**
     * Reads the cumulative process CPU time.
     *
     * @return process CPU nanoseconds, or {@code -1} when unavailable
     */
    private static long processCpuTimeNanos() {
        return RuntimeMonitor.processInfo().processCpuTimeNanos();
    }

    /**
     * Converts nanoseconds to milliseconds, preserving the unknown marker.
     *
     * @param nanos nanosecond value
     * @return milliseconds, or {@code -1} when unknown
     */
    private static double millis(long nanos) {
        return nanos < 0L ? -1.0d : nanos / 1_000_000.0d;
    }
}
