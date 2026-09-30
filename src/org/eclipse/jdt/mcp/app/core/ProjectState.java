package org.eclipse.jdt.mcp.app.core;

/**
 * 项目初始化生命周期状态：从配置完成、Maven 加载、JDT 就绪、索引中到就绪，
 * 以及索引降级和启动失败等状态，供 index_status 等工具反映当前进度。
 */
public enum ProjectState {
    NEW,
    CONFIGURED,
    MAVEN_LOADED,
    JDT_READY,
    INDEXING,
    READY,
    DEGRADED,
    FAILED
}
