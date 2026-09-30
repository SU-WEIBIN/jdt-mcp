package org.eclipse.jdt.mcp.app.decompiler;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * JAR 指纹：以 SHA-256、文件大小和修改时间标识一个构件。{@code metadata()} 只读取文件
 * 元数据用于快速缓存校验，{@code calculate()} 才计算内容哈希；{@code storageKey()} 与
 * {@code pathIdentity()} 分别生成稳定的缓存键和外部 JAR 标识。
 */
public record JarFingerprint(String sha256, long size, long lastModifiedMillis) {

    /**
     * Reads only stable file metadata. This is the fast path used for cache
     * validation; content hashing is deferred until metadata changes.
     *
     * @param file JAR file
     * @return metadata-only fingerprint
     * @throws IOException if the file metadata cannot be read
     */
    public static JarFingerprint metadata(Path file) throws IOException {
        return new JarFingerprint(null, Files.size(file), Files.getLastModifiedTime(file).toMillis());
    }

    /**
     * Returns the key used by content/decompilation caches. Metadata-only
     * fingerprints deliberately avoid reading the whole JAR.
     *
     * @return content hash when known, otherwise a metadata key
     */
    public String storageKey() {
        return sha256 != null ? sha256 : "stamp-" + size + "-" + lastModifiedMillis;
    }

    /**
     * Returns a stable identity for an external JAR path without reading its
     * contents. The path identifies the artifact; its metadata/content stamp
     * determines whether the index must be rebuilt.
     *
     * @param file external JAR path
     * @return short path identity
     */
    public static String pathIdentity(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(file.toAbsolutePath().normalize().toString()
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                result.append(String.format("%02x", bytes[i]));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /**
     * 计算 JAR 的完整 SHA-256 内容指纹，同时记录文件大小和修改时间，用于缓存失效判断。
     *
     * @param file JAR 文件
     * @return 包含内容哈希的指纹
     * @throws IOException 当文件无法读取或 SHA-256 不可用时
     */
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
