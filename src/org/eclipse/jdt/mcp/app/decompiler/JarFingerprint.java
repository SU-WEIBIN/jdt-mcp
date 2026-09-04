package org.eclipse.jdt.mcp.app.decompiler;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public record JarFingerprint(String sha256, long size, long lastModifiedMillis) {

    public static JarFingerprint calculate(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            StringBuilder hash = new StringBuilder();
            for (byte value : digest.digest()) {
                hash.append(String.format("%02x", value));
            }
            return new JarFingerprint(hash.toString(), Files.size(file), Files.getLastModifiedTime(file).toMillis());
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 is unavailable", exception);
        }
    }
}
