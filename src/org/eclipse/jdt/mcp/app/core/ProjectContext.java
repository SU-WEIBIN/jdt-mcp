package org.eclipse.jdt.mcp.app.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.jdt.mcp.app.config.McpConfig;
import org.eclipse.jdt.mcp.app.decompiler.DecompiledArtifactStore;
import org.eclipse.jdt.mcp.app.index.BytecodeIndexStore;
import org.eclipse.jdt.mcp.app.index.IndexedCall;
import org.eclipse.jdt.mcp.app.index.IndexedSymbol;
import org.eclipse.jdt.mcp.app.index.ProjectIndex;
import org.eclipse.jdt.mcp.app.maven.MavenArtifact;
import org.eclipse.jdt.mcp.app.maven.MavenProjectModel;

/**
 * 单个 Maven 项目的运行期状态中枢：持有配置、各类缓存路径、生命周期状态、Maven 模型、
 * 内存中的源码索引、落盘的字节码索引存储、索引告警和反编译存储。对外提供跨源码/字节码
 * 的统一符号与调用查询，并生成 project_info、index_status 等 MCP 响应数据。
 */
public final class ProjectContext {
    private final McpConfig config;
    private final Path projectRoot;
    private final String projectId;
    private final Path projectCacheRoot;
    private final Path workspaceRoot;
    private final Path indexRoot;
    private final Path metadataRoot;
    private volatile ProjectState state = ProjectState.CONFIGURED;
    private volatile MavenProjectModel mavenProject;
    private volatile ProjectIndex index = new ProjectIndex();
    private volatile BytecodeIndexStore bytecodeIndex;
    private volatile java.util.List<String> indexWarnings = java.util.List.of();
    private final DecompiledArtifactStore decompiledArtifacts;

    /**
     * Creates the mutable state container for one indexed project.
     *
     * @param config normalized MCP configuration
     * @param projectId stable project identifier
     * @param projectCacheRoot project cache directory
     */
    ProjectContext(McpConfig config, String projectId, Path projectCacheRoot) {
        this.config = config;
        this.projectRoot = config.projectRoot();
        this.projectId = projectId;
        this.projectCacheRoot = projectCacheRoot;
        this.workspaceRoot = projectCacheRoot.resolve("workspace");
        this.indexRoot = projectCacheRoot.resolve("index");
        this.metadataRoot = projectCacheRoot.resolve("metadata");
        this.bytecodeIndex = new BytecodeIndexStore(indexRoot.resolve("bytecode"), projectId, List.of(),
                config.maxResidentIndexBytes());
        this.decompiledArtifacts = new DecompiledArtifactStore(config.decompileRoot());
    }

    /**
     * Returns the project configuration.
     *
     * @return configuration
     */
    public McpConfig config() {
        return config;
    }

    /**
     * Returns the normalized project root.
     *
     * @return project root
     */
    public Path projectRoot() {
        return projectRoot;
    }

    /**
     * Returns the stable project identifier.
     *
     * @return project identifier
     */
    public String projectId() {
        return projectId;
    }

    /**
     * Returns the project cache root.
     *
     * @return cache root
     */
    public Path projectCacheRoot() {
        return projectCacheRoot;
    }

    /**
     * Returns the project workspace root.
     *
     * @return workspace root
     */
    public Path workspaceRoot() {
        return workspaceRoot;
    }

    /**
     * Returns the persistent index root.
     *
     * @return index root
     */
    public Path indexRoot() {
        return indexRoot;
    }

    /**
     * Returns the metadata root.
     *
     * @return metadata root
     */
    public Path metadataRoot() {
        return metadataRoot;
    }

    /**
     * Returns the current project lifecycle state.
     *
     * @return current state
     */
    public ProjectState state() {
        return state;
    }

    /**
     * Updates the current project lifecycle state.
     *
     * @param state new state
     */
    public void state(ProjectState state) {
        this.state = state;
    }

    /**
     * Returns the loaded Maven model.
     *
     * @return Maven model, or {@code null} before loading
     */
    public MavenProjectModel mavenProject() {
        return mavenProject;
    }

    /**
     * Installs the loaded Maven model, prepares the disk-backed bytecode index
     * catalog and advances lifecycle state.
     *
     * @param mavenProject Maven model
     */
    public void mavenProject(MavenProjectModel mavenProject) {
        this.mavenProject = mavenProject;
        this.bytecodeIndex = new BytecodeIndexStore(indexRoot.resolve("bytecode"), projectId,
                mavenProject == null ? List.of() : mavenProject.artifacts(), config.maxResidentIndexBytes());
        this.state = ProjectState.MAVEN_LOADED;
    }

    /**
     * Returns the current in-memory project source index. Bytecode indexes are
     * persisted on disk and served from the resident cache in {@link #bytecodeIndex()}.
     *
     * @return current index
     */
    public ProjectIndex index() {
        return index;
    }

    /**
     * Returns the persisted bytecode index catalog with a resident query cache.
     *
     * @return bytecode index store
     */
    public BytecodeIndexStore bytecodeIndex() {
        return bytecodeIndex;
    }

    /**
     * Searches the source index and then scans persisted bytecode indexes one
     * artifact at a time.
     *
     * @param query symbol query
     * @param kind optional symbol kind
     * @param maxResults maximum number of results
     * @return matching symbols
     */
    public List<IndexedSymbol> searchSymbols(String query, String kind, int maxResults) {
        int limit = Math.max(1, maxResults);
        List<IndexedSymbol> result = new ArrayList<>(index.searchSymbols(query, kind, limit));
        if (result.size() < limit) {
            result.addAll(bytecodeIndex.searchSymbols(query, kind, limit - result.size()));
        }
        return List.copyOf(result.subList(0, Math.min(result.size(), limit)));
    }

    /**
     * Finds a symbol in the source index or in a persisted bytecode snapshot.
     *
     * @param id stable symbol identifier
     * @return matching symbol, or {@code null}
     */
    public IndexedSymbol symbol(String id) {
        IndexedSymbol result = id == null ? null : index.symbol(id);
        return result == null ? bytecodeIndex.symbol(id) : result;
    }

    /**
     * Finds callers in both the source index and persisted bytecode indexes.
     *
     * @param targetId optional target identifier
     * @param targetQuery optional target signature query
     * @param maxResults maximum number of results
     * @return matching call edges
     */
    public List<IndexedCall> callers(String targetId, String targetQuery, int maxResults) {
        int limit = Math.max(1, maxResults);
        List<IndexedCall> result = new ArrayList<>(index.callers(targetId, targetQuery, limit));
        if (result.size() < limit) {
            result.addAll(bytecodeIndex.callers(targetId, targetQuery, limit - result.size()));
        }
        return List.copyOf(result.subList(0, Math.min(result.size(), limit)));
    }

    /**
     * Finds direct callees in both the source index and persisted bytecode
     * indexes.
     *
     * @param callerId caller identifier
     * @param maxResults maximum number of results
     * @return matching call edges
     */
    public List<IndexedCall> callees(String callerId, int maxResults) {
        int limit = Math.max(1, maxResults);
        List<IndexedCall> result = new ArrayList<>(index.callees(callerId, limit));
        if (result.size() < limit) {
            result.addAll(bytecodeIndex.callees(callerId, limit - result.size()));
        }
        return List.copyOf(result.subList(0, Math.min(result.size(), limit)));
    }

    /**
     * Finds outgoing calls for a group of trace nodes across both index types.
     *
     * @param callerIds caller identifiers
     * @param maxResults maximum number of results
     * @return matching call edges
     */
    public List<IndexedCall> callsFrom(Set<String> callerIds, int maxResults) {
        int limit = Math.max(1, maxResults);
        List<IndexedCall> result = new ArrayList<>(index.callsFrom(callerIds, limit));
        if (result.size() < limit) {
            result.addAll(bytecodeIndex.callsFrom(callerIds, limit - result.size()));
        }
        return List.copyOf(result.subList(0, Math.min(result.size(), limit)));
    }

    /**
     * Requests a best-effort collection after a query has released a temporary
     * artifact index. The request is skipped while startup indexing is active.
     */
    public void requestMemoryCleanup() {
        // Resident query indexes are intentionally retained. Let the JVM manage
        // temporary allocations instead of forcing a full collection per query.
    }

    /**
     * Installs the completed project source index and records indexing
     * diagnostics. Bytecode snapshots remain in the disk-backed store.
     *
     * @param index completed index
     * @param warnings source and bytecode indexing warnings
     */
    public void index(ProjectIndex index, java.util.List<String> warnings) {
        this.index = index == null ? new ProjectIndex() : index;
        this.indexWarnings = warnings == null ? java.util.List.of() : java.util.List.copyOf(warnings);
        boolean hasNonInformationalWarning = this.indexWarnings.stream()
                .anyMatch(warning -> !isInformationalDiagnostic(warning));
        boolean mavenDegraded = mavenProject != null && !"READY".equals(mavenProject.resolutionState());
        this.state = hasNonInformationalWarning || mavenDegraded ? ProjectState.DEGRADED : ProjectState.READY;
    }

    /**
     * Returns warnings produced after Maven model loading.
     *
     * @return immutable warning list
     */
    public java.util.List<String> indexWarnings() {
        return indexWarnings;
    }

    /**
     * Returns the persistent decompiled-artifact store.
     *
     * @return decompiled artifact store
     */
    public DecompiledArtifactStore decompiledArtifacts() {
        return decompiledArtifacts;
    }

    /**
     * Finds an indexed artifact by its exact coordinate.
     *
     * @param coordinate Maven coordinate
     * @return matching artifact, or {@code null}
     */
    public MavenArtifact artifact(String coordinate) {
        if (mavenProject == null || coordinate == null) {
            return null;
        }
        return mavenProject.artifacts().stream()
                .filter(artifact -> coordinate.equals(artifact.coordinate()))
                .findFirst().orElse(null);
    }

    /**
     * Builds project metadata for the MCP project_info tool.
     *
     * @return serializable project metadata
     */
    public Map<String, Object> projectInfo() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("projectId", projectId);
        result.put("projectRoot", projectRoot.toString());
        result.put("projectCacheRoot", projectCacheRoot.toString());
        result.put("workspaceRoot", workspaceRoot.toString());
        result.put("indexRoot", indexRoot.toString());
        result.put("decompileRoot", config.decompileRoot().toString());
        result.put("maxResidentIndexBytes", config.maxResidentIndexBytes());
        result.put("residentArtifacts", bytecodeIndex.residentArtifactCount());
        result.put("residentBytes", bytecodeIndex.residentBytes());
        result.put("mavenLocalRepository", config.mavenLocalRepository().toString());
        result.put("additionalJars", config.additionalJars().stream().map(Path::toString).toList());
        result.put("activeProfiles", config.activeProfiles());
        result.put("hasPom", projectRoot.resolve("pom.xml").toFile().isFile());
        result.put("state", state.name());
        result.put("indexWarnings", indexWarnings);
        if (mavenProject != null) {
            result.put("maven", mavenProject.toInfo());
            result.put("mavenResolutionState", mavenProject.resolutionState());
            result.put("mavenDiagnosticSummary", mavenProject.diagnosticSummary());
        }
        return result;
    }

    /**
     * Builds current source, persisted bytecode and Maven resolution status
     * for MCP clients.
     *
     * @return serializable index status
     */
    public Map<String, Object> indexStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("state", state.name());
        result.put("projectId", projectId);
        result.put("indexedArtifacts", bytecodeIndex.indexedArtifactCount());
        result.put("declaredArtifacts", mavenProject == null ? 0 : mavenProject.artifacts().size());
        result.put("indexedClasses", index.typeCount() + bytecodeIndex.typeCount());
        result.put("indexedMethods", index.methodCount() + bytecodeIndex.methodCount());
        result.put("callEdges", index.callCount() + bytecodeIndex.callCount());
        result.put("residentArtifacts", bytecodeIndex.residentArtifactCount());
        result.put("residentBytes", bytecodeIndex.residentBytes());
        result.put("residentLimitBytes", bytecodeIndex.residentLimitBytes());
        result.put("snapshotLoadCount", bytecodeIndex.snapshotLoadCount());
        result.put("snapshotLoadBytes", bytecodeIndex.snapshotLoadBytes());
        result.put("residentCacheHitCount", bytecodeIndex.residentCacheHitCount());
        result.put("projectClasses", index.symbolCount("project", "TYPE"));
        result.put("projectMethods", index.symbolCount("project", "METHOD"));
        result.put("bytecodeClasses", bytecodeIndex.typeCount());
        result.put("bytecodeMethods", bytecodeIndex.methodCount());
        result.put("moduleCount", mavenProject == null ? 0 : mavenProject.modules().size());
        result.put("warnings", indexWarnings);
        result.put("mavenResolutionState", mavenProject == null ? "UNKNOWN" : mavenProject.resolutionState());
        result.put("mavenDiagnosticSummary", mavenProject == null
                ? Map.of()
                : mavenProject.diagnosticSummary());
        result.put("message", mavenProject == null
                ? "Maven project has not been loaded"
                : state == ProjectState.INDEXING ? "Project source index is being built"
                : indexWarnings.isEmpty() ? "Project source index is ready" : "Project source index is ready with warnings");
        return result;
    }

    /**
     * Identifies structured informational diagnostics that do not degrade the
     * project index state.
     *
     * @param warning warning text
     * @return whether the warning is informational only
     */
    private static boolean isInformationalDiagnostic(String warning) {
        return warning != null && warning.startsWith("[INFO/");
    }
}
