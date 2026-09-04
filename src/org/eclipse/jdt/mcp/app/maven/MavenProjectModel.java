package org.eclipse.jdt.mcp.app.maven;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record MavenProjectModel(
        Path root,
        List<MavenModule> modules,
        List<MavenArtifact> artifacts,
        List<String> warnings,
        List<MavenDiagnostic> diagnostics) {

    /**
     * Creates a project model without structured diagnostics.
     *
     * @param root project root
     * @param modules Maven modules
     * @param artifacts resolved and unresolved artifacts
     * @param warnings legacy warning strings
     */
    public MavenProjectModel(
            Path root,
            List<MavenModule> modules,
            List<MavenArtifact> artifacts,
            List<String> warnings) {
        this(root, modules, artifacts, warnings, List.of());
    }

    /**
     * Normalizes model collections for immutable use.
     */
    public MavenProjectModel {
        modules = modules == null ? List.of() : List.copyOf(modules);
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    /**
     * Converts the Maven model to project metadata for MCP responses.
     *
     * @return serializable project metadata
     */
    public Map<String, Object> toInfo() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("root", root.toString());
        result.put("moduleCount", modules.size());
        result.put("artifactCount", artifacts.size());
        result.put("warnings", warnings);
        result.put("diagnostics", diagnostics.stream().map(MavenDiagnostic::toInfo).toList());
        result.put("resolutionState", resolutionState());
        result.put("diagnosticSummary", diagnosticSummary());
        result.put("modules", modules.stream().map(module -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("coordinate", module.coordinate());
            value.put("directory", module.directory().toString());
            value.put("packaging", module.packaging());
            value.put("mainSourceRoots", module.mainSourceRoots().stream().map(Path::toString).toList());
            value.put("outputDirectory", module.outputDirectory().toString());
            value.put("dependencyCount", module.dependencies().size());
            return value;
        }).toList());
        result.put("artifacts", artifacts.stream().map(MavenArtifact::toInfo).toList());
        return result;
    }

    /**
     * Grades Maven resolution independently from source indexing.
     *
     * @return READY, DEGRADED or FAILED
     */
    public String resolutionState() {
        if (diagnostics.stream().anyMatch(diagnostic -> diagnostic.severity() == MavenDiagnostic.Severity.ERROR)) {
            return "FAILED";
        }
        return diagnostics.stream().anyMatch(diagnostic -> diagnostic.severity() == MavenDiagnostic.Severity.WARNING)
                ? "DEGRADED"
                : "READY";
    }

    /**
     * Counts diagnostics by severity and category.
     *
     * @return diagnostic summary map
     */
    public Map<String, Object> diagnosticSummary() {
        Map<String, Integer> bySeverity = new LinkedHashMap<>();
        Map<String, Integer> byKind = new LinkedHashMap<>();
        for (MavenDiagnostic diagnostic : diagnostics) {
            bySeverity.merge(diagnostic.severity().name(), 1, Integer::sum);
            byKind.merge(diagnostic.kind().name(), 1, Integer::sum);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", diagnostics.size());
        result.put("bySeverity", bySeverity);
        result.put("byKind", byKind);
        return result;
    }
}
