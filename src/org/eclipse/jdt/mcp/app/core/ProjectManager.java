package org.eclipse.jdt.mcp.app.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.mcp.app.config.McpConfig;
import org.eclipse.jdt.mcp.app.decompiler.JarFingerprint;
import org.eclipse.jdt.mcp.app.index.BytecodeIndexStore;
import org.eclipse.jdt.mcp.app.index.JarBytecodeIndexer;
import org.eclipse.jdt.mcp.app.index.JdtSourceAnalyzer;
import org.eclipse.jdt.mcp.app.index.ProjectIndex;
import org.eclipse.jdt.mcp.app.index.SourceIndexStore;
import org.eclipse.jdt.mcp.app.maven.MavenProjectLoader;
import org.eclipse.jdt.mcp.app.maven.MavenProjectModel;

public final class ProjectManager {

    /**
     * Opens a Maven project, loads its effective model and starts indexing.
     *
     * @param config normalized MCP configuration
     * @return project context whose indexing may still be running
     * @throws IOException if the project cannot be opened
     */
    public ProjectContext open(McpConfig config) throws IOException {
        Path projectRoot = config.projectRoot().toAbsolutePath().normalize();
        if (!Files.isDirectory(projectRoot)) {
            throw new IOException("Project directory does not exist: " + projectRoot);
        }
        Path pom = projectRoot.resolve("pom.xml");
        if (!Files.isRegularFile(pom)) {
            throw new IOException("Maven project root must contain pom.xml: " + projectRoot);
        }

        String projectId = projectId(projectRoot);
        Path projectCacheRoot = config.cacheRoot().resolve(projectId).toAbsolutePath().normalize();
        Files.createDirectories(projectCacheRoot.resolve("workspace"));
        Files.createDirectories(projectCacheRoot.resolve("index"));
        Files.createDirectories(projectCacheRoot.resolve("metadata"));
        Files.createDirectories(projectCacheRoot.resolve("logs"));
        Files.createDirectories(config.decompileRoot());

        McpConfig normalizedConfig = config.withProjectRoot(projectRoot);
        MavenProjectModel loadedMavenProject = new MavenProjectLoader().load(
                projectRoot,
                normalizedConfig.mavenLocalRepository(),
                new java.util.LinkedHashSet<>(normalizedConfig.activeProfiles()));
        MavenProjectModel mavenProject = addAdditionalJars(loadedMavenProject, normalizedConfig.additionalJars());
        ProjectContext context = new ProjectContext(normalizedConfig, projectId, projectCacheRoot);
        context.mavenProject(mavenProject);
        context.state(ProjectState.INDEXING);
        new ProjectMetadataStore().save(context);
        Thread indexer = new Thread(() -> indexInBackground(context), "jdt-mcp-source-indexer");
        indexer.setDaemon(true);
        indexer.start();
        return context;
    }

    /**
     * Restores reusable snapshots, rebuilds only changed inputs and persists
     * newly generated indexes in the background. Only the project source
     * index remains attached to the context after this method finishes.
     *
     * @param context project context being indexed
     */
    private static void indexInBackground(ProjectContext context) {
        try {
            List<String> warnings = new ArrayList<>(context.mavenProject().warnings());
            Map<String, JarFingerprint> fingerprints = fingerprintArtifacts(context.mavenProject(), warnings);
            BytecodeIndexStore bytecodeStore = context.bytecodeIndex();
            BytecodeIndexStore.RestoreResult bytecodeRestore = bytecodeStore.restore(fingerprints);
            warnings.addAll(bytecodeRestore.warnings());
            if (bytecodeRestore.reusedArtifactCount() > 0) {
                warnings.add("[INFO/bytecode-cache] Reused " + bytecodeRestore.reusedArtifactCount()
                        + " of " + bytecodeRestore.currentArtifactCount() + " artifact indexes");
            }

            SourceIndexStore sourceStore = new SourceIndexStore(context.indexRoot());
            SourceIndexStore.RestoreResult sourceRestore = sourceStore.restore(context, fingerprints);
            JdtSourceAnalyzer.AnalysisResult analysis;
            if (sourceRestore.index() != null) {
                analysis = new JdtSourceAnalyzer.AnalysisResult(
                        sourceRestore.index(), sourceRestore.sourceFileCount(), sourceRestore.warnings(),
                        sourceRestore.sourceRootCount());
            } else {
                warnings.addAll(sourceRestore.warnings());
                analysis = new JdtSourceAnalyzer().analyze(context);
                try {
                    sourceStore.save(context, analysis, sourceRestore.inputFingerprint());
                } catch (IOException exception) {
                    warnings.add("Could not save source index manifest: " + exception.getMessage());
                }
            }
            ProjectIndex sourceIndex = analysis.index();
            if (sourceRestore.reused()) {
                warnings.add("[INFO/source-cache] Reused persisted source index");
            }
            warnings.addAll(analysis.warnings());
            JarBytecodeIndexer bytecodeIndexer = new JarBytecodeIndexer();
            for (org.eclipse.jdt.mcp.app.maven.MavenArtifact artifact : context.mavenProject().artifacts()) {
                if (artifact.file() == null || !Files.isRegularFile(artifact.file())
                        || bytecodeStore.isIndexed(artifact.coordinate())) {
                    continue;
                }
                try {
                    ProjectIndex artifactIndex = new ProjectIndex();
                    JarBytecodeIndexer.Result result = bytecodeIndexer.index(artifact, artifactIndex);
                    bytecodeStore.save(artifact, artifactIndex, result, fingerprints.get(artifact.coordinate()));
                    if (result.warning() != null) {
                        warnings.add(result.coordinate() + ": " + result.warning());
                    }
                } catch (Exception exception) {
                    warnings.add("Could not index artifact " + artifact.coordinate() + ": " + exception.getMessage());
                }
            }
            try {
                bytecodeStore.commit();
            } catch (IOException exception) {
                warnings.add("Could not save bytecode index manifest: " + exception.getMessage());
            }
            context.index(sourceIndex, warnings);
            try {
                new ArtifactMetadataStore().save(context, fingerprints);
            } catch (IOException exception) {
                warnings.add("Could not save artifact metadata: " + exception.getMessage());
                context.index(sourceIndex, warnings);
            }
        } catch (Exception | LinkageError exception) {
            context.index(new org.eclipse.jdt.mcp.app.index.ProjectIndex(),
                    java.util.List.of("JDT source indexing failed: " + exception.getMessage()));
        }
        try {
            new ProjectMetadataStore().save(context);
        } catch (IOException exception) {
            System.err.println("Could not save project metadata: " + exception.getMessage());
        }
        requestPostIndexCollection();
    }

    /**
     * Calculates one fingerprint per current JAR so source and bytecode cache
     * validation can share the same disk scan.
     *
     * @param project loaded Maven model
     * @param warnings warning collection for unavailable artifacts
     * @return fingerprints keyed by Maven coordinate
     */
    private static Map<String, JarFingerprint> fingerprintArtifacts(
            MavenProjectModel project, List<String> warnings) {
        Map<String, JarFingerprint> result = new LinkedHashMap<>();
        if (project == null) {
            return result;
        }
        for (org.eclipse.jdt.mcp.app.maven.MavenArtifact artifact : project.artifacts()) {
            if (artifact == null || artifact.file() == null || !Files.isRegularFile(artifact.file())) {
                continue;
            }
            try {
                result.put(artifact.coordinate(), JarFingerprint.calculate(artifact.file()));
            } catch (IOException exception) {
                warnings.add("Could not fingerprint artifact " + artifact.coordinate() + ": "
                        + exception.getMessage());
            }
        }
        return result;
    }

    /**
     * Requests a best-effort collection after the one-time indexing peak so
     * temporary AST, ASM and serialization objects are not retained by the
     * long-running MCP process.
     */
    private static void requestPostIndexCollection() {
        System.gc();
    }

    /**
     * Appends user-supplied JARs while preserving Maven diagnostics.
     *
     * @param project loaded Maven model
     * @param jars additional local JARs
     * @return model with additional JAR artifacts
     */
    private static MavenProjectModel addAdditionalJars(MavenProjectModel project, java.util.List<Path> jars) {
        if (jars == null || jars.isEmpty()) {
            return project;
        }
        java.util.List<org.eclipse.jdt.mcp.app.maven.MavenArtifact> artifacts =
                new java.util.ArrayList<>(project.artifacts());
        java.util.List<String> warnings = new java.util.ArrayList<>(project.warnings());
        for (Path jar : jars) {
            if (!java.nio.file.Files.isRegularFile(jar)) {
                warnings.add("Additional JAR is not available: " + jar);
                continue;
            }
            try {
                String hash = JarFingerprint.calculate(jar).sha256();
                String artifactId = jar.getFileName().toString().replaceFirst("\\.[^.]+$", "");
                artifacts.add(new org.eclipse.jdt.mcp.app.maven.MavenArtifact(
                        "external",
                        artifactId,
                        hash.substring(0, 16),
                        "compile",
                        null,
                        jar,
                        null,
                        true,
                        false));
            } catch (java.io.IOException exception) {
                warnings.add("Could not fingerprint additional JAR " + jar + ": " + exception.getMessage());
            }
        }
        return new MavenProjectModel(project.root(), project.modules(), java.util.List.copyOf(artifacts),
                java.util.List.copyOf(warnings), project.diagnostics());
    }

    /**
     * Computes a stable project cache identifier from its normalized path.
     *
     * @param projectRoot normalized project root
     * @return short hexadecimal project identifier
     */
    private static String projectId(Path projectRoot) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(projectRoot.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                result.append(String.format("%02x", bytes[i]));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
