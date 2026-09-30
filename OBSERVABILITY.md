# Runtime observability

The MCP stdio protocol reserves `stdout` for JSON-RPC responses. Diagnostic logs are therefore written to `stderr` and are also persisted per project.

## Log location and format

After startup, structured JSON Lines are written to:

- `stderr`, so an MCP host can collect them directly;
- `<cacheRoot>/<project-id>/logs/jdt-mcp.jsonl`, as an append-only per-project log.

Each request log includes:

- `event`, normally `request.completed`;
- `requestId`, `method`, and `tool`;
- `status`, `durationMs`, and `responseBytes`;
- `resources`, including heap and resident-cache deltas;
- `resources.storageSource`: `memory`, `disk`, `memory+disk`, or `none`.

`storageSource` is an application-level access classification, not an operating-system disk-I/O counter. `memory` means the project source index or resident bytecode cache was used. `disk` means persisted indexes, source files, Maven JARs, or decompiled source were accessed.

## Startup and indexing events

Because indexing runs on a background thread after the Maven model is loaded, the process writes additional events so startup cost can be attributed without an external profiler:

- `startup.phase`: one record per phase with `phase`, `durationMs`, `elapsedMs`, `heapUsedBytes`, `heapUsedDeltaBytes`, `residentBytes`, `residentBytesDelta`, `processCpuMillis`, `processCpuDeltaMillis`, `processCpuLoad`, and `rssBytes`. Phase names are `maven.load`, `artifact.fingerprint`, `bytecode.restore`, `bytecode.preload`, `source.restore`, `source.analyze` (cache miss only), `bytecode.index`, `bytecode.preload.final`, `index.install` and `metadata.save`.
- `startup.summary`: aggregate `totalMs`, `phaseCount`, `peakHeapUsedBytes`, `peakResidentBytes`, `rssBytes`, `committedVirtualBytes` and `processCpuMillis`.
- `bytecode.index.artifact`: per-artifact `coordinate`, `durationMs`, `classCount`, `methodCount` and `callCount`, written as each JAR is indexed.
- `maven.warnings`, `index.warning`, `index.warnings`: Maven and indexing diagnostics, including the same warnings later returned by `index_status`.
- `project.state`: lifecycle transitions such as `INDEXING`, `READY` and `DEGRADED`.

`processCpuLoad` and `committedVirtualBytes` come from the JDK's extended operating-system MXBean. `rssBytes` is read from `/proc/self/statm` when that file is available and is `-1` elsewhere. `processCpuDeltaMillis` is the CPU time consumed by the process during the phase, which may exceed the wall-clock `durationMs` on multi-core machines.

## runtime_status

The `runtime_status` MCP tool reports:

- JVM heap and non-heap used, committed, and maximum memory;
- resident bytecode-cache artifact count, estimated bytes, and configured limit;
- resident-cache hits, persisted snapshot loads, and loaded bytes;
- total, usable, and unallocated capacity for the project, cache, index, decompile, and Maven repository filesystems;
- process CPU time, recent process CPU load, committed virtual memory and, when the platform provides it, resident set size;
- process request totals, errors, response bytes, and per-tool average/max duration.

Example:

```json
{"jsonrpc":"2.0","id":10,"method":"tools/call","params":{"name":"runtime_status","arguments":{}}}
```

## Enabling JVM diagnostics

The launchers (`bin/jdt-mcp.ps1`, `bin/jdt-mcp.sh`) and `build.ps1 -Run` pass the value of `JDT_MCP_JVM_OPTIONS` to the JVM before `-jar`. This keeps JVM flags out of the MCP host configuration when the host only controls the command and arguments.

```powershell
$env:JDT_MCP_JVM_OPTIONS = '-Xmx2g -XX:NativeMemoryTracking=summary -XX:StartFlightRecording=filename=C:/tmp/jdt-mcp.jfr,settings=profile,dumponexit=true'
```

The standard `JAVA_TOOL_OPTIONS` variable is also honored by the JVM directly, which is useful when the MCP host spawns the process with its own environment. `JAVA_TOOL_OPTIONS` only writes a single `Picked up ...` line to `stderr`, so it does not corrupt the JSON-RPC stream on `stdout`.

## Notes

- `maxResidentIndexBytes` controls the resident bytecode-cache budget. Set it to `0` to disable the resident cache; subsequent bytecode queries load persisted snapshots on demand.
- `heapUsedBytes` is Java heap usage, not the operating-system process RSS. The JVM may retain committed memory after garbage collection.
- Filesystem capacity comes from Java `FileStore`. The implementation deliberately does not present it as precise system-level read/write byte accounting.
