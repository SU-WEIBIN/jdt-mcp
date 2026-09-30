package org.eclipse.jdt.mcp.app.observability;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Thread-safe process-local request counters exposed by runtime_status.
 */
public final class RequestMetrics {
    private final long startedAtMillis = System.currentTimeMillis();
    private final Map<String, MutableOperation> operations = new LinkedHashMap<>();
    private long totalRequests;
    private long successfulRequests;
    private long failedRequests;
    private long totalResponseBytes;
    private long totalDurationNanos;

    /**
     * Records one completed request.
     *
     * @param operation operation name
     * @param durationNanos elapsed duration
     * @param success whether protocol/business handling completed successfully
     * @param responseBytes response bytes written to stdout
     */
    public synchronized void record(String operation, long durationNanos, boolean success, int responseBytes) {
        String key = operation == null || operation.isBlank() ? "unknown" : operation;
        long duration = Math.max(0L, durationNanos);
        MutableOperation stats = operations.computeIfAbsent(key, ignored -> new MutableOperation());
        stats.count++;
        if (!success) {
            stats.errorCount++;
            failedRequests++;
        } else {
            successfulRequests++;
        }
        stats.totalDurationNanos += duration;
        stats.maxDurationNanos = Math.max(stats.maxDurationNanos, duration);
        stats.lastDurationNanos = duration;
        stats.responseBytes += Math.max(0, responseBytes);
        totalRequests++;
        totalResponseBytes += Math.max(0, responseBytes);
        totalDurationNanos += duration;
    }

    /**
     * Returns a serializable point-in-time metrics snapshot.
     *
     * @return metrics snapshot
     */
    public synchronized Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("startedAtEpochMillis", startedAtMillis);
        result.put("uptimeMillis", Math.max(0L, System.currentTimeMillis() - startedAtMillis));
        result.put("totalRequests", totalRequests);
        result.put("successfulRequests", successfulRequests);
        result.put("failedRequests", failedRequests);
        result.put("totalResponseBytes", totalResponseBytes);
        result.put("totalDurationMs", milliseconds(totalDurationNanos));
        result.put("averageDurationMs", totalRequests == 0 ? 0.0 : milliseconds(totalDurationNanos) / totalRequests);

        Map<String, Object> operationInfo = new LinkedHashMap<>();
        for (Map.Entry<String, MutableOperation> entry : operations.entrySet()) {
            MutableOperation stats = entry.getValue();
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("count", stats.count);
            value.put("errorCount", stats.errorCount);
            value.put("totalDurationMs", milliseconds(stats.totalDurationNanos));
            value.put("averageDurationMs", stats.count == 0 ? 0.0
                    : milliseconds(stats.totalDurationNanos) / stats.count);
            value.put("maxDurationMs", milliseconds(stats.maxDurationNanos));
            value.put("lastDurationMs", milliseconds(stats.lastDurationNanos));
            value.put("responseBytes", stats.responseBytes);
            operationInfo.put(entry.getKey(), value);
        }
        result.put("operations", operationInfo);
        return result;
    }

    /**
     * 把纳秒转换为毫秒。
     *
     * @param nanos 纳秒值
     * @return 毫秒值
     */
    private static double milliseconds(long nanos) {
        return nanos / 1_000_000.0;
    }

    private static final class MutableOperation {
        private long count;
        private long errorCount;
        private long totalDurationNanos;
        private long maxDurationNanos;
        private long lastDurationNanos;
        private long responseBytes;
    }
}
