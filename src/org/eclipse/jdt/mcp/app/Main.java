package org.eclipse.jdt.mcp.app;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jdt.mcp.app.config.ConfigLoader;
import org.eclipse.jdt.mcp.app.config.McpConfig;
import org.eclipse.jdt.mcp.app.core.ProjectContext;
import org.eclipse.jdt.mcp.app.core.ProjectManager;
import org.eclipse.jdt.mcp.app.server.McpStdioServer;

/**
 * Command-line entry point for the JDT MCP stdio server.
 */
public final class Main {

    /**
     * Prevents instantiation of the command-line entry point.
     */
    private Main() {
        // Utility entry point; instances are not meaningful.
    }

    /**
     * Loads the selected project and serves MCP requests over standard I/O.
     *
     * @param args command-line options
     */
    public static void main(String[] args) {
        try {
            Map<String, String> options = parseArguments(args);
            if (options.containsKey("help")) {
                printUsage();
                return;
            }
            if (options.containsKey("version")) {
                printVersion();
                return;
            }
            Path projectOverride = pathOption(options, "project");
            Path configFile = pathOption(options, "config");
            McpConfig config = ConfigLoader.load(configFile, projectOverride);
            ProjectContext project = new ProjectManager().open(config);
            System.err.println("jdt-mcp bootstrap ready for " + project.projectRoot());
            new McpStdioServer(project, System.in, System.out, System.err).run();
        } catch (Exception exception) {
            System.err.println("jdt-mcp failed to start: " + exception.getMessage());
            System.exit(2);
        }
    }

    /**
     * Parses supported command-line options without interpreting project paths.
     *
     * @param args raw command-line arguments
     * @return option names and values
     * @throws IllegalArgumentException if an option is unknown or missing a value
     */
    private static Map<String, String> parseArguments(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String argument = args[i];
            switch (argument) {
            case "--help":
            case "-h":
                options.put("help", "true");
                break;
            case "--version":
            case "-V":
                options.put("version", "true");
                break;
            case "--project":
            case "--config":
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException(argument + " requires a value");
                }
                options.put(argument.substring(2), args[++i]);
                break;
            default:
                throw new IllegalArgumentException("Unknown option: " + argument);
            }
        }
        return options;
    }

    /**
     * Converts an optional string option into a normalized path value.
     *
     * @param options parsed command-line options
     * @param name option name without the leading dashes
     * @return the option path, or {@code null} when it was not supplied
     */
    private static Path pathOption(Map<String, String> options, String name) {
        String value = options.get(name);
        return value == null ? null : Path.of(value);
    }

    /**
     * Prints the command-line usage text for both development and release launchers.
     */
    private static void printUsage() {
        System.out.println("Usage: jdt-mcp --project <maven-project> [--config <jdt-mcp.json>]");
        System.out.println("       jdt-mcp --config <jdt-mcp.json>");
        System.out.println("       jdt-mcp --version");
    }

    /**
     * Prints the implementation version stored in the release JAR manifest.
     */
    private static void printVersion() {
        String version = Main.class.getPackage().getImplementationVersion();
        if (version == null || version.isBlank()) {
            version = "development";
        }
        System.out.println("jdt-mcp " + version);
    }
}
