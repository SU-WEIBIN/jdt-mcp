package org.eclipse.jdt.mcp.app.maven;

import java.nio.file.Path;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

public record MavenArtifact(
        String groupId,
        String artifactId,
        String version,
        String scope,
        String classifier,
        Path file,
        Path classesDirectory,
        boolean resolved,
        boolean reactorArtifact,
        List<String> exclusions) {

    /**
     * Normalizes optional dependency exclusions for immutable artifact use.
     */
    public MavenArtifact {
        exclusions = exclusions == null ? List.of() : List.copyOf(exclusions);
    }

    /**
     * Creates an artifact without transitive exclusions.
     *
     * @param groupId group identifier
     * @param artifactId artifact identifier
     * @param version artifact version
     * @param scope dependency scope
     * @param classifier artifact classifier
     * @param file artifact JAR
     * @param classesDirectory reactor classes directory
     * @param resolved whether the artifact file or classes are available
     * @param reactorArtifact whether this artifact belongs to the reactor
     */
    public MavenArtifact(
            String groupId,
            String artifactId,
            String version,
            String scope,
            String classifier,
            Path file,
            Path classesDirectory,
            boolean resolved,
            boolean reactorArtifact) {
        this(groupId, artifactId, version, scope, classifier, file, classesDirectory,
                resolved, reactorArtifact, List.of());
    }

    /**
     * Returns the Maven coordinate, including a classifier when present.
     *
     * @return Maven coordinate
     */
    public String coordinate() {
        StringBuilder result = new StringBuilder(groupId).append(':').append(artifactId).append(':').append(version);
        if (classifier != null && !classifier.isBlank()) {
            result.append(':').append(classifier);
        }
        return result.toString();
    }

    /**
     * Converts artifact metadata to an MCP response map.
     *
     * @return serializable artifact metadata
     */
    public Map<String, Object> toInfo() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("coordinate", coordinate());
        result.put("groupId", groupId);
        result.put("artifactId", artifactId);
        result.put("version", version);
        result.put("scope", scope);
        result.put("classifier", classifier);
        result.put("file", file == null ? null : file.toString());
        result.put("classesDirectory", classesDirectory == null ? null : classesDirectory.toString());
        result.put("resolved", resolved);
        result.put("reactorArtifact", reactorArtifact);
        result.put("exclusions", exclusions);
        return result;
    }
}
