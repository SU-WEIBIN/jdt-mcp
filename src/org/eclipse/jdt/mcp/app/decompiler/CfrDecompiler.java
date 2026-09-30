package org.eclipse.jdt.mcp.app.decompiler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.benf.cfr.reader.api.CfrDriver;

/**
 * {@link Decompiler} 的 CFR 实现：使用 CFR 驱动把一个 JAR 反编译为 Java 源码目录，
 * 采用静默输出并关闭注释、字符串 switch 解码等可选项。
 * CFR adapter kept behind the decompiler SPI so the implementation can change later.
 */
public final class CfrDecompiler implements Decompiler {

    /**
     * 返回反编译器标识（CFR 版本）。
     */
    @Override
    public String name() {
        return "CFR 0.152";
    }

    /**
     * 调用 CFR 驱动把 JAR 反编译到输出目录，失败时包装为 IOException。
     */
    @Override
    public void decompile(Path jar, Path outputDirectory) throws IOException {
        Files.createDirectories(outputDirectory);
        Map<String, String> options = new LinkedHashMap<>();
        options.put("outputdir", outputDirectory.toString());
        options.put("silent", "true");
        options.put("comments", "false");
        options.put("decodestringswitch", "false");
        try {
            new CfrDriver.Builder().withOptions(options).build().analyse(List.of(jar.toString()));
        } catch (RuntimeException exception) {
            throw new IOException("CFR failed for " + jar + ": " + exception.getMessage(), exception);
        }
    }
}
