#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PACKAGE_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
REQUIRED_JAVA_VERSION=17

write_diagnostic() {
    printf '%s\n' "[jdt-mcp] $*" >&2
}

find_java() {
    if [ -n "${JDT_MCP_JAVA_HOME:-}" ]; then
        if [ -x "$JDT_MCP_JAVA_HOME/bin/java" ]; then
            printf '%s\n' "$JDT_MCP_JAVA_HOME/bin/java"
            return 0
        fi
        if [ -x "$JDT_MCP_JAVA_HOME/Contents/Home/bin/java" ]; then
            printf '%s\n' "$JDT_MCP_JAVA_HOME/Contents/Home/bin/java"
            return 0
        fi
        write_diagnostic "JDT_MCP_JAVA_HOME is not a valid Java $REQUIRED_JAVA_VERSION+ installation: $JDT_MCP_JAVA_HOME"
        return 1
    fi
    if [ -x "$PACKAGE_ROOT/runtime/bin/java" ]; then
        printf '%s\n' "$PACKAGE_ROOT/runtime/bin/java"
        return 0
    fi
    if [ -x "$PACKAGE_ROOT/runtime/Contents/Home/bin/java" ]; then
        printf '%s\n' "$PACKAGE_ROOT/runtime/Contents/Home/bin/java"
        return 0
    fi
    command -v java 2>/dev/null || true
}

JAR_PATH=''
if [ -f "$PACKAGE_ROOT/lib/jdt-mcp.jar" ]; then
    JAR_PATH="$PACKAGE_ROOT/lib/jdt-mcp.jar"
elif [ -f "$PACKAGE_ROOT/target/jdt-mcp.jar" ]; then
    JAR_PATH="$PACKAGE_ROOT/target/jdt-mcp.jar"
fi
if [ -z "$JAR_PATH" ]; then
    write_diagnostic 'JDT MCP JAR was not found. Expected lib/jdt-mcp.jar in a release package or target/jdt-mcp.jar in a source checkout.'
    exit 1
fi

JAVA_PATH=$(find_java)
if [ -z "$JAVA_PATH" ] || [ ! -x "$JAVA_PATH" ]; then
    write_diagnostic "No compatible Java $REQUIRED_JAVA_VERSION+ runtime found. Set JDT_MCP_JAVA_HOME or use a release package containing runtime."
    exit 1
fi

JAVA_VERSION=$($JAVA_PATH -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -n 1)
case "$JAVA_VERSION" in
    ''|*[!0-9]*)
        write_diagnostic "Unable to determine Java version from $JAVA_PATH"
        exit 1
        ;;
    *)
        if [ "$JAVA_VERSION" -lt "$REQUIRED_JAVA_VERSION" ]; then
            write_diagnostic "Java $REQUIRED_JAVA_VERSION+ is required, but $JAVA_PATH reports Java $JAVA_VERSION"
            exit 1
        fi
        ;;
esac

write_diagnostic "using Java $JAVA_VERSION at $JAVA_PATH"
exec "$JAVA_PATH" -jar "$JAR_PATH" "$@"
