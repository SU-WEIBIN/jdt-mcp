package org.eclipse.jdt.mcp.app.decompiler;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 反编译器 SPI：定义反编译器名称与把一个 JAR 反编译到指定输出目录的契约，
 * 使具体实现（当前为 CFR）可以替换。
 */
public interface Decompiler {
    /**
     * 返回反编译器名称，用于响应和日志。
     */
    String name();

    /**
     * 把 JAR 反编译到指定输出目录。
     */
    void decompile(Path jar, Path outputDirectory) throws IOException;
}
