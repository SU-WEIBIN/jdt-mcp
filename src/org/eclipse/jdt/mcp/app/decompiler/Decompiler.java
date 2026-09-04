package org.eclipse.jdt.mcp.app.decompiler;

import java.io.IOException;
import java.nio.file.Path;

public interface Decompiler {
    String name();

    void decompile(Path jar, Path outputDirectory) throws IOException;
}
