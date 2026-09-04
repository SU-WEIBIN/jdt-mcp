# JDT MCP Quick Start

## Use a release package

A release package contains a self-contained JAR, a Java runtime, and launchers. Maven is not required on the target machine.

1. Extract `jdt-mcp-<version>-<platform>.zip`.
2. Configure an MCP client to use the launcher.
3. Put the Maven project directory after `--project`.

### Windows

```json
{
  "mcpServers": {
    "jdt-mcp": {
      "command": "C:\\tools\\jdt-mcp\\bin\\jdt-mcp.cmd",
      "args": ["--project", "C:\\work\\my-maven-project"]
    }
  }
}
```

### Linux/macOS

```json
{
  "mcpServers": {
    "jdt-mcp": {
      "command": "/opt/jdt-mcp/bin/jdt-mcp.sh",
      "args": ["--project", "/work/my-maven-project"]
    }
  }
}
```

Use `--config /absolute/path/jdt-mcp.json` instead when project settings are stored in a configuration file. Configure one MCP server entry per Maven project because one process currently owns one project.

## Build locally

```powershell
$env:JDT_MCP_JAVA_HOME = 'C:\Program Files\Java\latest\jdk-21'
.\build.ps1
.\build.ps1 -Run --project 'C:\work\my-maven-project'
.\smoke-test.ps1 -JarPath 'target\jdt-mcp.jar' -ProjectRoot 'C:\work\my-maven-project'
```

Create a distributable package with:

```powershell
.\release.ps1 -RuntimeHome 'C:\Program Files\Java\latest\jdk-21'
```

The build scripts validate the Java release declared by `maven.compiler.release` and verify Maven's Java runtime before compiling. All MCP diagnostics go to stderr; stdout is reserved for JSON-RPC.
