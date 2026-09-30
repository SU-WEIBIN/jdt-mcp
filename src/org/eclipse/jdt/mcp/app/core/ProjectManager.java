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
import org.eclipse.jdt.mcp.app.observability.McpLogger;
import org.eclipse.jdt.mcp.app.observability.StartupProfiler;

/**
 * 项目打开与索引编排：校验项目目录和 pom.xml，准备缓存目录，调用
 * {@link MavenProjectLoader} 加载 Maven 模型并补充额外 JAR，构造
 * {@link ProjectContext}，然后在守护线程中恢复/重建源码索引与字节码索引，并持久化
 * 项目元数据和构件指纹。
 */
public final class ProjectManager {

    /**
     * Opens a Maven project, loads its effective model and starts indexing.
     *
     * @param config normalized MCP configuration
     * @param logger structured logger shared with the background indexer
     * @return project context whose indexing may still be running
     * @throws IOException if the project cannot be opened
     */
    public ProjectContext open(McpConfig config, McpLogger logger) throws IOException {
        Path projectRoot = config.projectRoot().toAbsolutePath().normalize();
        if (!Files.isDirectory(projectRoot)) {
            throw new IOException("Project directory does not exist: " + projectRoot);
        }
        Path pom = projectRoot.resolve("pom.xml");
        if (!Files.isRegularFile(pom)) {
            throw new IOException("Maven project root must contain pom.xml: " + projectRoot);
        }

        String projectId = projectId(projectRoot);
        Path projectCacheRoot = projectCacheRoot(projectRoot, config.cacheRoot());
        Files.createDirectories(projectCacheRoot.resolve("workspace"));
        Files.createDirectories(projectCacheRoot.resolve("index"));
        Files.createDirectories(projectCacheRoot.resolve("metadata"));
        Files.createDirectories(projectCacheRoot.resolve("logs"));
        Files.createDirectories(config.decompileRoot());

        McpConfig normalizedConfig = config.withProjectRoot(projectRoot);
        ProjectContext context = new ProjectContext(normalizedConfig, projectId, projectCacheRoot);
        StartupProfiler profiler = new StartupProfiler(logger, context);
        MavenProjectModel loadedMavenProject = new MavenProjectLoader().load(
                projectRoot,
                normalizedConfig.mavenLocalRepository(),
                new java.util.LinkedHashSet<>(normalizedConfig.activeProfiles()));
        MavenProjectModel mavenProject = addAdditionalJars(loadedMavenProject, normalizedConfig.additionalJars());
        context.mavenProject(mavenProject);
        profiler.phase("maven.load", Map.of(
                "moduleCount", mavenProject.modules().size(),
                "artifactCount", mavenProject.artifacts().size(),
                "warningCount", mavenProject.warnings().size(),
                "diagnosticCount", mavenProject.diagnostics().size(),
                "resolutionState", mavenProject.resolutionState()));
        if (!mavenProject.warnings().isEmpty()) {
            logger.warn("maven.warnings", Map.of(
                    "count", mavenProject.warnings().size(),
                    "warnings", mavenProject.warnings()));
        }
        context.state(ProjectState.INDEXING);
        logger.info("project.state", Map.of("state", ProjectState.INDEXING.name(), "projectId", projectId));
        new ProjectMetadataStore().save(context);
        Thread indexer = new Thread(() -> indexInBackground(context, logger, profiler), "jdt-mcp-source-indexer");
        indexer.setDaemon(true);
        indexer.start();
        return context;
    }

    /**
     * Computes the per-project cache directory without loading the project.
     *
     * @param config normalized MCP configuration
     * @return normalized project cache root
     */
    public static Path projectCacheRoot(McpConfig config) {
        return projectCacheRoot(config.projectRoot().toAbsolutePath().normalize(), config.cacheRoot());
    }

    /**
     * Resolves the cache directory for one project root.
     *
     * @param projectRoot normalized project root
     * @param cacheRoot configured cache root
     * @return normalized project cache root
     */
    private static Path projectCacheRoot(Path projectRoot, Path cacheRoot) {
        return cacheRoot.resolve(projectId(projectRoot)).toAbsolutePath().normalize();
    }

    /**
     * Restores reusable snapshots, rebuilds only changed inputs and persists
     * newly generated indexes in the background. Only the project source
     * index remains attached to the context after this method finishes.
     *
     * @param context project context being indexed
     * @param logger structured logger shared with the server loop
     * @param profiler startup phase profiler
     */
    private static void indexInBackground(ProjectContext context, McpLogger logger, StartupProfiler profiler) {
        try {
            List<String> warnings = new ArrayList<>(context.mavenProject().warnings());
            int mavenWarningCount = warnings.size();
            Map<String, JarFingerprint> fingerprints = fingerprintArtifacts(context.mavenProject(), warnings);
            profiler.phase("artifact.fingerprint", Map.of(
                    "artifactCount", fingerprints.size(),
                    "warningCount", warnings.size() - mavenWarningCount));

            BytecodeIndexStore bytecodeStore = context.bytecodeIndex();
            BytecodeIndexStore.RestoreResult bytecodeRestore = bytecodeStore.restore(fingerprints);
            warnings.addAll(bytecodeRestore.warnings());
            profiler.phase("bytecode.restore", Map.of(
                    "reusedArtifacts", bytecodeRestore.reusedArtifactCount(),
                    "currentArtifacts", bytecodeRestore.currentArtifactCount(),
                    "warningCount", bytecodeRestore.warnings().size()));
            // Warm reusable snapshots before source analysis so an early MCP
            // query does not fall back to disk-backed artifact loading.
            BytecodeIndexStore.PreloadResult restoredPreload = bytecodeStore.preload();
            warnings.addAll(restoredPreload.warnings());
            profiler.phase("bytecode.preload", Map.of(
                    "eligibleArtifacts", restoredPreload.eligibleArtifactCount(),
                    "residentArtifacts", restoredPreload.residentArtifactCount(),
                    "residentBytes", restoredPreload.residentBytes(),
                    "snapshotLoads", restoredPreload.snapshotLoadCount()));
            if (bytecodeRestore.reusedArtifactCount() > 0) {
                warnings.add("[INFO/bytecode-cache] Reused " + bytecodeRestore.reusedArtifactCount()
                        + " of " + bytecodeRestore.currentArtifactCount() + " artifact indexes");
            }

            SourceIndexStore sourceStore = new SourceIndexStore(context.indexRoot());
            SourceIndexStore.RestoreResult sourceRestore = sourceStore.restore(context, fingerprints);
            profiler.phase("source.restore", Map.of(
                    "reused", sourceRestore.reused(),
                    "sourceFileCount", sourceRestore.sourceFileCount(),
                    "sourceRootCount", sourceRestore.sourceRootCount(),
                    "warningCount", sourceRestore.warnings().size()));
            JdtSourceAnalyzer.AnalysisResult analysis;
            if (sourceRestore.index() != null) {
                analysis = new JdtSourceAnalyzer.AnalysisResult(
                        sourceRestore.index(), sourceRestore.sourceFileCount(), sourceRestore.warnings(),
                        sourceRestore.sourceRootCount());
            } else {
                warnings.addAll(sourceRestore.warnings());
                analysis = new JdtSourceAnalyzer().analyze(context);
                profiler.phase("source.analyze", Map.of(
                        "sourceFileCount", analysis.sourceFileCount(),
                        "sourceRootCount", analysis.sourceRootCount(),
                        "warningCount", analysis.warnings().size()));
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
            int indexedArtifacts = 0;
            int skippedArtifacts = 0;
            for (org.eclipse.jdt.mcp.app.maven.MavenArtifact artifact : context.mavenProject().artifacts()) {
                if (artifact.file() == null || !Files.isRegularFile(artifact.file())
                        || bytecodeStore.isIndexed(artifact.coordinate())) {
                    skippedArtifacts++;
                    continue;
                }
                long artifactStartedNanos = System.nanoTime();
                try {
                    ProjectIndex artifactIndex = new ProjectIndex();
                    JarBytecodeIndexer.Result result = bytecodeIndexer.index(artifact, artifactIndex);
                    bytecodeStore.save(artifact, artifactIndex, result, fingerprints.get(artifact.coordinate()));
                    try {
                        // Persist a checkpoint after each completed artifact so a
                        // process interruption cannot discard all prior progress.
                        bytecodeStore.commit();
                    } catch (IOException exception) {
                        warnings.add("Could not checkpoint bytecode index manifest: " + exception.getMessage());
                    }
                    if (result.warning() != null) {
                        warnings.add(result.coordinate() + ": " + result.warning());
                    }
                    indexedArtifacts++;
                    logger.info("bytecode.index.artifact", Map.of(
                            "coordinate", result.coordinate(),
                            "durationMs", (System.nanoTime() - artifactStartedNanos) / 1_000_000.0d,
                            "classCount", result.classCount(),
                            "methodCount", result.methodCount(),
                            "callCount", result.callCount(),
                            "warning", result.warning() == null ? "" : result.warning()));
                } catch (Exception exception) {
                    warnings.add("Could not index artifact " + artifact.coordinate() + ": " + exception.getMessage());
                    logger.warn("bytecode.index.artifact_failed", Map.of(
                            "coordinate", artifact.coordinate(),
                            "message", message(exception)));
                }
            }
            profiler.phase("bytecode.index", Map.of(
                    "indexedArtifacts", indexedArtifacts,
                    "skippedArtifacts", skippedArtifacts));
            try {
                bytecodeStore.commit();
            } catch (IOException exception) {
                warnings.add("Could not save bytecode index manifest: " + exception.getMessage());
            }
            BytecodeIndexStore.PreloadResult preload = bytecodeStore.preload();
            warnings.addAll(preload.warnings());
            profiler.phase("bytecode.preload.final", Map.of(
                    "eligibleArtifacts", preload.eligibleArtifactCount(),
                    "residentArtifacts", preload.residentArtifactCount(),
                    "residentBytes", preload.residentBytes()));
            if (preload.eligibleArtifactCount() > 0) {
                warnings.add("[INFO/bytecode-cache] Resident " + preload.residentArtifactCount()
                        + " of " + preload.eligibleArtifactCount() + " artifact indexes");
            }
            context.index(sourceIndex, warnings);
            profiler.phase("index.install", Map.of(
                    "state", context.state().name(),
                    "typeCount", sourceIndex.typeCount(),
                    "methodCount", sourceIndex.methodCount(),
                    "callCount", sourceIndex.callCount(),
                    "warningCount", warnings.size()));
            try {
                new ArtifactMetadataStore().save(context, fingerprints);
            } catch (IOException exception) {
                warnings.add("Could not save artifact metadata: " + exception.getMessage());
                context.index(sourceIndex, warnings);
            }
            profiler.phase("metadata.save", Map.of(
                    "artifactCount", fingerprints.size(),
                    "warningCount", warnings.size()));
            logIndexWarnings(logger, warnings);
        } catch (Exception | LinkageError exception) {
            context.index(new org.eclipse.jdt.mcp.app.index.ProjectIndex(),
                    java.util.List.of("JDT source indexing failed: " + exception.getMessage()));
            logger.error("index.failed", Map.of(
                    "message", message(exception),
                    "exceptionClass", exception.getClass().getName(),
                    "state", context.state().name()));
        }
        try {
            new ProjectMetadataStore().save(context);
        } catch (IOException exception) {
            logger.warn("metadata.save_failed", Map.of("message", message(exception)));
        }
        logger.info("project.state", Map.of("state", context.state().name(), "projectId", context.projectId()));
        profiler.finish();
        requestPostIndexCollection();
    }

    /**
     * Emits collected indexing warnings so the structured log reflects what
     * index_status later reports.
     *
     * @param logger structured logger
     * @param warnings accumulated indexing warnings
     */
    private static void logIndexWarnings(McpLogger logger, List<String> warnings) {
        if (warnings == null || warnings.isEmpty()) {
            logger.info("index.warnings", Map.of("count", 0));
            return;
        }
        for (String warning : warnings) {
            Map<String, Object> fields = Map.of("message", warning == null ? "" : warning);
            if (warning != null && warning.startsWith("[INFO/")) {
                logger.info("index.warning", fields);
            } else {
                logger.warn("index.warning", fields);
            }
        }
        logger.info("index.warnings", Map.of("count", warnings.size()));
    }

    /**
     * Extracts an exception message, falling back to the simple class name.
     *
     * @param exception thrown exception
     * @return usable message
     */
    private static String message(Throwable exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
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
                result.put(artifact.coordinate(), JarFingerprint.metadata(artifact.file()));
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
        // Resident indexes are intentionally retained; avoid forcing a full GC
        // immediately after indexing and let the JVM collect temporary objects.
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
                String pathIdentity = JarFingerprint.pathIdentity(jar);
                String artifactId = jar.getFileName().toString().replaceFirst("\\.[^.]+$", "");
                artifacts.add(new org.eclipse.jdt.mcp.app.maven.MavenArtifact(
                        "external",
                        artifactId,
                        pathIdentity,
                        "compile",
                        null,
                        jar,
                        null,
                        true,
                        false));
            } catch (RuntimeException exception) {
                warnings.add("Could not identify additional JAR " + jar + ": " + exception.getMessage());
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
