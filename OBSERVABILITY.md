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

## runtime_status

The `runtime_status` MCP tool reports:

- JVM heap and non-heap used, committed, and maximum memory;
- resident bytecode-cache artifact count, estimated bytes, and configured limit;
- resident-cache hits, persisted snapshot loads, and loaded bytes;
- total, usable, and unallocated capacity for the project, cache, index, decompile, and Maven repository filesystems;
- process request totals, errors, response bytes, and per-tool average/max duration.

Example:

```json
{"jsonrpc":"2.0","id":10,"method":"tools/call","params":{"name":"runtime_status","arguments":{}}}
```

## Notes

- `maxResidentIndexBytes` controls the resident bytecode-cache budget. Set it to `0` to disable the resident cache; subsequent bytecode queries load persisted snapshots on demand.
- `heapUsedBytes` is Java heap usage, not the operating-system process RSS. The JVM may retain committed memory after garbage collection.
- Filesystem capacity comes from Java `FileStore`. The implementation deliberately does not present it as precise system-level read/write byte accounting.
