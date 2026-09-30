package org.eclipse.jdt.mcp.app.observability;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.eclipse.jdt.mcp.app.core.ProjectContext;

/**
 * Captures JVM memory, application cache and filesystem information without
 * adding an operating-system-specific monitoring dependency.
 */
public final class RuntimeMonitor {
    private static final MemoryMXBean MEMORY = ManagementFactory.getMemoryMXBean();
    private static final Path LINUX_STATM = Path.of("/proc/self/statm");
    private static final long LINUX_PAGE_SIZE_BYTES = 4096L;
    private static final Set<String> BYTECODE_TOOLS = Set.of(
            "search_symbols", "get_class", "get_method", "inspect_jar",
            "find_project_usages", "find_callers", "find_callees", "trace_call_chain");

    /** 工具类，禁止实例化。 */
    private RuntimeMonitor() {
    }

    /**
     * Captures a point-in-time snapshot.
     *
     * @param project project context
     * @return immutable resource snapshot
     */
    public static Snapshot capture(ProjectContext project) {
        MemoryUsage heap = MEMORY.getHeapMemoryUsage();
        MemoryUsage nonHeap = MEMORY.getNonHeapMemoryUsage();
        long heapUsed = value(heap == null ? -1L : heap.getUsed());
        long heapCommitted = value(heap == null ? -1L : heap.getCommitted());
        long heapMax = value(heap == null ? -1L : heap.getMax());
        long nonHeapUsed = value(nonHeap == null ? -1L : nonHeap.getUsed());
        long nonHeapCommitted = value(nonHeap == null ? -1L : nonHeap.getCommitted());

        long residentBytes = 0L;
        long residentLimitBytes = 0L;
        int residentArtifacts = 0;
        long snapshotLoadCount = 0L;
        long snapshotLoadBytes = 0L;
        long residentCacheHitCount = 0L;
        if (project != null && project.bytecodeIndex() != null) {
            residentBytes = project.bytecodeIndex().residentBytes();
            residentLimitBytes = project.bytecodeIndex().residentLimitBytes();
            residentArtifacts = project.bytecodeIndex().residentArtifactCount();
            snapshotLoadCount = project.bytecodeIndex().snapshotLoadCount();
            snapshotLoadBytes = project.bytecodeIndex().snapshotLoadBytes();
            residentCacheHitCount = project.bytecodeIndex().residentCacheHitCount();
        }

        Map<String, DiskUsage> disks = new LinkedHashMap<>();
        if (project != null) {
            addDisk(disks, "project", project.projectRoot());
            addDisk(disks, "cache", project.projectCacheRoot());
            addDisk(disks, "index", project.indexRoot());
            addDisk(disks, "decompile", project.config().decompileRoot());
            addDisk(disks, "mavenLocalRepository", project.config().mavenLocalRepository());
        }
        return new Snapshot(
                System.currentTimeMillis(),
                Math.max(0L, ManagementFactory.getRuntimeMXBean().getUptime()),
                heapUsed,
                heapCommitted,
                heapMax,
                nonHeapUsed,
                nonHeapCommitted,
                residentBytes,
                residentLimitBytes,
                residentArtifacts,
                snapshotLoadCount,
                snapshotLoadBytes,
                residentCacheHitCount,
                Map.copyOf(disks));
    }

    /**
     * Captures operating-system process metrics that {@link MemoryMXBean} does
     * not expose: cumulative process CPU time, recent process CPU load,
     * committed virtual memory and, when the platform provides it, the
     * resident set size. Unsupported values are reported as {@code -1}.
     *
     * @return process metrics snapshot
     */
    public static ProcessInfo processInfo() {
        long cpuTimeNanos = -1L;
        double cpuLoad = -1.0d;
        long committedVirtualBytes = -1L;
        if (ManagementFactory.getOperatingSystemMXBean()
                instanceof com.sun.management.OperatingSystemMXBean extended) {
            try {
                cpuTimeNanos = extended.getProcessCpuTime();
            } catch (RuntimeException ignored) {
                cpuTimeNanos = -1L;
            }
            try {
                cpuLoad = extended.getProcessCpuLoad();
            } catch (RuntimeException ignored) {
                cpuLoad = -1.0d;
            }
            try {
                committedVirtualBytes = extended.getCommittedVirtualMemorySize();
            } catch (RuntimeException ignored) {
                committedVirtualBytes = -1L;
            }
        }
        return new ProcessInfo(
                cpuTimeNanos < 0L ? -1L : cpuTimeNanos,
                cpuLoad,
                committedVirtualBytes < 0L ? -1L : committedVirtualBytes,
                residentSetBytes());
    }

    /**
     * Reads the resident set size from {@code /proc/self/statm} on platforms
     * that expose it. The value assumes 4 KiB pages, which matches mainstream
     * Linux configurations.
     *
     * @return resident set size in bytes, or {@code -1} when unavailable
     */
    private static long residentSetBytes() {
        if (!Files.isReadable(LINUX_STATM)) {
            return -1L;
        }
        try {
            String[] fields = Files.readString(LINUX_STATM, StandardCharsets.US_ASCII).trim().split("\\s+");
            if (fields.length < 2) {
                return -1L;
            }
            return Math.multiplyExact(Long.parseLong(fields[1]), LINUX_PAGE_SIZE_BYTES);
        } catch (IOException | NumberFormatException | ArithmeticException exception) {
            return -1L;
        }
    }

    /**
     * Builds the response for the runtime_status tool.
     *
     * @param project project context
     * @param metrics request metrics
     * @return serializable runtime status
     */
    public static Map<String, Object> currentInfo(ProjectContext project, RequestMetrics metrics) {
        Snapshot snapshot = capture(project);
        Map<String, Object> result = snapshot.toInfo();
        Map<String, Object> paths = new LinkedHashMap<>();
        if (project != null) {
            paths.put("projectRoot", project.projectRoot().toString());
            paths.put("cacheRoot", project.projectCacheRoot().toString());
            paths.put("indexRoot", project.indexRoot().toString());
            paths.put("decompileRoot", project.config().decompileRoot().toString());
            paths.put("mavenLocalRepository", project.config().mavenLocalRepository().toString());
        }
        result.put("paths", paths);
        result.put("process", processInfo().toInfo());
        result.put("metrics", metrics == null ? Map.of() : metrics.snapshot());
        result.put("storageSemantics", Map.of(
                "memory", "resident bytecode snapshots and the project source index",
                "disk", "persisted snapshots, source files, Maven artifacts and decompiled source",
                "note", "storageSource is application-level access classification, not OS disk I/O counters"));
        return result;
    }

    /**
     * Computes per-request resource deltas and classifies the logical storage
     * source used by the operation.
     *
     * @param before snapshot before handling
     * @param after snapshot after handling
     * @param tool MCP tool name, or {@code null}
     * @return serializable request resource details
     */
    public static Map<String, Object> requestResources(Snapshot before, Snapshot after, String tool) {
        Snapshot start = before == null ? after : before;
        Snapshot end = after == null ? before : after;
        Map<String, Object> result = new LinkedHashMap<>();
        if (start == null || end == null) {
            result.put("storageSource", "unknown");
            return result;
        }

        long residentHits = Math.max(0L, end.residentCacheHitCount() - start.residentCacheHitCount());
        long snapshotLoads = Math.max(0L, end.snapshotLoadCount() - start.snapshotLoadCount());
        long snapshotBytes = Math.max(0L, end.snapshotLoadBytes() - start.snapshotLoadBytes());
        boolean sourceFileRead = "search_text".equals(tool)
                || "get_class".equals(tool)
                || "get_method".equals(tool);
        boolean bytecodeOperation = tool != null && BYTECODE_TOOLS.contains(tool);
        boolean memory = residentHits > 0 || (bytecodeOperation && snapshotLoads == 0 && end.residentArtifacts() > 0);
        boolean disk = snapshotLoads > 0 || sourceFileRead;
        String storageSource;
        if (memory && disk) {
            storageSource = "memory+disk";
        } else if (memory) {
            storageSource = "memory";
        } else if (disk) {
            storageSource = "disk";
        } else if ("project_info".equals(tool) || "index_status".equals(tool)
                || "runtime_status".equals(tool)) {
            storageSource = "memory";
        } else {
            storageSource = "none";
        }
        result.put("storageSource", storageSource);
        result.put("bytecodeResidentCacheHits", residentHits);
        result.put("bytecodeSnapshotLoads", snapshotLoads);
        result.put("bytecodeSnapshotLoadBytes", snapshotBytes);
        result.put("heapUsedBeforeBytes", start.heapUsedBytes());
        result.put("heapUsedAfterBytes", end.heapUsedBytes());
        result.put("heapUsedDeltaBytes", end.heapUsedBytes() - start.heapUsedBytes());
        result.put("residentBytesBefore", start.residentBytes());
        result.put("residentBytesAfter", end.residentBytes());
        result.put("residentBytesDelta", end.residentBytes() - start.residentBytes());
        result.put("cacheResidentArtifacts", end.residentArtifacts());
        return result;
    }

    /**
     * 记录一个应用路径所在文件系统的容量信息，查询失败时记录错误而不中断。
     *
     * @param disks 结果映射
     * @param label 逻辑标签
     * @param path 应用路径
     */
    private static void addDisk(Map<String, DiskUsage> disks, String label, Path path) {
        if (path == null) {
            return;
        }
        Path existing = existingPath(path);
        try {
            FileStore store = Files.getFileStore(existing);
            disks.put(label, new DiskUsage(path.toAbsolutePath().normalize().toString(), store.name(),
                    store.type(), store.getTotalSpace(), store.getUsableSpace(), store.getUnallocatedSpace(), null));
        } catch (IOException | RuntimeException exception) {
            disks.put(label, new DiskUsage(path.toAbsolutePath().normalize().toString(), null, null,
                    -1L, -1L, -1L, message(exception)));
        }
    }

    /**
     * 返回目标路径存在的最深祖先，用于查询文件存储。
     *
     * @param path 目标路径
     * @return 存在的路径，找不到时返回当前目录
     */
    private static Path existingPath(Path path) {
        Path current = path.toAbsolutePath().normalize();
        while (current != null && !Files.exists(current)) {
            current = current.getParent();
        }
        return current == null ? Path.of(".").toAbsolutePath().normalize() : current;
    }

    /**
     * 把未知的内存值（负数）归一化为 -1，否则原样返回。
     *
     * @param value 原始值
     * @return 归一化后的值
     */
    private static long value(long value) {
        return value < 0L ? -1L : value;
    }

    /**
     * 提取异常消息；消息为空或空白时退化为异常类简单名。
     *
     * @param exception 异常
     * @return 可用于输出的消息
     */
    private static String message(Throwable exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    /** Immutable resource snapshot. */
    public record Snapshot(
            long capturedAtEpochMillis,
            long uptimeMillis,
            long heapUsedBytes,
            long heapCommittedBytes,
            long heapMaxBytes,
            long nonHeapUsedBytes,
            long nonHeapCommittedBytes,
            long residentBytes,
            long residentLimitBytes,
            int residentArtifacts,
            long snapshotLoadCount,
            long snapshotLoadBytes,
            long residentCacheHitCount,
            Map<String, DiskUsage> disks) {

        /**
         * Converts this snapshot to a JSON-compatible map.
         *
         * @return resource information
         */
        public Map<String, Object> toInfo() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("capturedAt", Instant.ofEpochMilli(capturedAtEpochMillis).toString());
            result.put("capturedAtEpochMillis", capturedAtEpochMillis);
            result.put("uptimeMillis", uptimeMillis);

            Map<String, Object> memory = new LinkedHashMap<>();
            memory.put("heapUsedBytes", heapUsedBytes);
            memory.put("heapCommittedBytes", heapCommittedBytes);
            memory.put("heapMaxBytes", heapMaxBytes);
            memory.put("nonHeapUsedBytes", nonHeapUsedBytes);
            memory.put("nonHeapCommittedBytes", nonHeapCommittedBytes);
            result.put("memory", memory);

            Map<String, Object> cache = new LinkedHashMap<>();
            cache.put("residentArtifacts", residentArtifacts);
            cache.put("residentBytes", residentBytes);
            cache.put("residentLimitBytes", residentLimitBytes);
            cache.put("snapshotLoadCount", snapshotLoadCount);
            cache.put("snapshotLoadBytes", snapshotLoadBytes);
            cache.put("residentCacheHitCount", residentCacheHitCount);
            result.put("cache", cache);

            Map<String, Object> diskInfo = new LinkedHashMap<>();
            for (Map.Entry<String, DiskUsage> entry : disks.entrySet()) {
                diskInfo.put(entry.getKey(), entry.getValue().toInfo());
            }
            result.put("disk", diskInfo);
            return result;
        }
    }

    /** Operating-system process metrics that need no native dependency. */
    public record ProcessInfo(
            long processCpuTimeNanos,
            double processCpuLoad,
            long committedVirtualBytes,
            long residentSetBytes) {

        /**
         * Converts the process metrics to a JSON-compatible map.
         *
         * @return process metric map
         */
        public Map<String, Object> toInfo() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("processCpuTimeMillis", processCpuTimeNanos < 0L
                    ? -1.0d : processCpuTimeNanos / 1_000_000.0d);
            result.put("processCpuLoad", processCpuLoad);
            result.put("committedVirtualBytes", committedVirtualBytes);
            result.put("residentSetBytes", residentSetBytes);
            result.put("residentSetAvailable", residentSetBytes >= 0L);
            return result;
        }
    }

    /** Filesystem capacity information for one logical application path. */
    public record DiskUsage(
            String path,
            String fileStore,
            String fileStoreType,
            long totalBytes,
            long usableBytes,
            long unallocatedBytes,
            String error) {

        /**
     * 把文件系统容量信息转换为 JSON 兼容映射，仅在出错时附带 error 字段。
     *
     * @return 容量信息映射
     */
    private Map<String, Object> toInfo() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("path", path);
            result.put("fileStore", fileStore);
            result.put("fileStoreType", fileStoreType);
            result.put("totalBytes", totalBytes);
            result.put("usableBytes", usableBytes);
            result.put("unallocatedBytes", unallocatedBytes);
            if (error != null) {
                result.put("error", error);
            }
            return result;
        }
    }
}
