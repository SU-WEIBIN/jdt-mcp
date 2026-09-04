package org.eclipse.jdt.mcp.app.decompiler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.benf.cfr.reader.api.CfrDriver;

/** CFR adapter kept behind the decompiler SPI so the implementation can change later. */
public final class CfrDecompiler implements Decompiler {

    @Override
    public String name() {
        return "CFR 0.152";
    }

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
