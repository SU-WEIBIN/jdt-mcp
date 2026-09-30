package org.eclipse.jdt.mcp.app.index;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.eclipse.jdt.mcp.app.decompiler.JarFingerprint;
import org.eclipse.jdt.mcp.app.maven.MavenArtifact;

import junit.framework.TestCase;

/** Regression tests for resident bytecode snapshot queries. */
public final class BytecodeIndexStoreTest extends TestCase {

    /**
     * 验证预热后的常驻缓存能在多次查询中复用同一快照，不会重复读取。
     *
     * @throws Exception 临时夹具创建失败
     */
    public void testPreloadedSnapshotIsNotReadAgainForQueries() throws Exception {
        Path fixture = Files.createTempDirectory("jdt-mcp-bytecode-cache-");
        try {
            Path jar = fixture.resolve("sample.jar");
            Files.write(jar, new byte[] { 1, 2, 3, 4, 5 });
            MavenArtifact artifact = new MavenArtifact(
                    "example", "sample", "1.0", "compile", null, jar, null, true, false);
            Path indexRoot = fixture.resolve("index");
            ProjectIndex source = new ProjectIndex();
            source.addSymbol(new IndexedSymbol(
                    "type:example/Sample", "TYPE", "Sample", "example.Sample", "example.Sample",
                    "bytecode", artifact.coordinate(), jar, 0, 0, -1, 0, null));

            BytecodeIndexStore writer = new BytecodeIndexStore(
                    indexRoot, "project", List.of(artifact), 16L * 1024L * 1024L);
            writer.save(artifact, source,
                    new JarBytecodeIndexer.Result(artifact.coordinate(), 1, 0, 0, null),
                    JarFingerprint.metadata(jar));
            writer.commit();

            BytecodeIndexStore reader = new BytecodeIndexStore(
                    indexRoot, "project", List.of(artifact), 16L * 1024L * 1024L);
            Map<String, JarFingerprint> fingerprints = Map.of(
                    artifact.coordinate(), JarFingerprint.metadata(jar));
            assertEquals(1, reader.restore(fingerprints).reusedArtifactCount());
            JarFingerprint known = reader.artifactFingerprint(artifact.coordinate());
            assertNull(known.sha256());
            assertTrue(known.storageKey().startsWith("stamp-"));

            BytecodeIndexStore.PreloadResult preload = reader.preload();
            assertEquals(1, preload.residentArtifactCount());
            assertEquals(1L, reader.snapshotLoadCount());

            assertEquals(1, reader.searchSymbols("Sample", "TYPE", 10).size());
            assertNotNull(reader.symbol("type:example/Sample"));
            assertEquals(1L, reader.snapshotLoadCount());
            assertTrue(reader.residentCacheHitCount() >= 2L);
        } finally {
            deleteRecursively(fixture);
        }
    }

    /**
     * 验证 JAR 仅修改时间变化、内容不变时仍可复用已有快照。
     *
     * @throws Exception 临时夹具创建失败
     */
    public void testMetadataChangeCanReuseContentIdenticalSnapshot() throws Exception {
        Path fixture = Files.createTempDirectory("jdt-mcp-bytecode-verify-");
        try {
            Path jar = fixture.resolve("sample.jar");
            Files.write(jar, new byte[] { 1, 3, 3, 7 });
            MavenArtifact artifact = new MavenArtifact(
                    "example", "sample", "1.0", "compile", null, jar, null, true, false);
            Path indexRoot = fixture.resolve("index");
            ProjectIndex source = new ProjectIndex();
            source.addSymbol(new IndexedSymbol(
                    "type:example/Sample", "TYPE", "Sample", "example.Sample", "example.Sample",
                    "bytecode", artifact.coordinate(), jar, 0, 0, -1, 0, null));

            BytecodeIndexStore writer = new BytecodeIndexStore(
                    indexRoot, "project", List.of(artifact), 16L * 1024L * 1024L);
            writer.save(artifact, source,
                    new JarBytecodeIndexer.Result(artifact.coordinate(), 1, 0, 0, null),
                    JarFingerprint.calculate(jar));
            writer.commit();

            Files.setLastModifiedTime(jar,
                    java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 2000L));
            BytecodeIndexStore reader = new BytecodeIndexStore(
                    indexRoot, "project", List.of(artifact), 16L * 1024L * 1024L);
            assertEquals(1, reader.restore(Map.of(artifact.coordinate(), JarFingerprint.metadata(jar)))
                    .reusedArtifactCount());
        } finally {
            deleteRecursively(fixture);
        }
    }

    /**
     * 验证常驻预算为 0 时禁用缓存，但查询仍按需读取快照。
     *
     * @throws Exception 临时夹具创建失败
     */
    public void testZeroResidentBudgetRetainsOnDemandFallback() throws Exception {
        Path fixture = Files.createTempDirectory("jdt-mcp-bytecode-no-cache-");
        try {
            Path jar = fixture.resolve("sample.jar");
            Files.write(jar, new byte[] { 9, 8, 7 });
            MavenArtifact artifact = new MavenArtifact(
                    "example", "sample", "1.0", "compile", null, jar, null, true, false);
            Path indexRoot = fixture.resolve("index");
            ProjectIndex source = new ProjectIndex();
            source.addSymbol(new IndexedSymbol(
                    "type:example/Sample", "TYPE", "Sample", "example.Sample", "example.Sample",
                    "bytecode", artifact.coordinate(), jar, 0, 0, -1, 0, null));
            BytecodeIndexStore writer = new BytecodeIndexStore(indexRoot, "project", List.of(artifact), 0);
            writer.save(artifact, source,
                    new JarBytecodeIndexer.Result(artifact.coordinate(), 1, 0, 0, null),
                    JarFingerprint.metadata(jar));
            writer.commit();

            BytecodeIndexStore reader = new BytecodeIndexStore(indexRoot, "project", List.of(artifact), 0);
            assertEquals(1, reader.restore(Map.of(artifact.coordinate(), JarFingerprint.metadata(jar)))
                    .reusedArtifactCount());
            assertEquals(1, reader.searchSymbols("Sample", "TYPE", 10).size());
            assertEquals(1, reader.snapshotLoadCount());
            assertEquals(0, reader.residentArtifactCount());
        } finally {
            deleteRecursively(fixture);
        }
    }

    /**
     * 递归删除临时夹具目录。
     *
     * @param root 夹具根目录
     * @throws IOException 删除失败
     */
    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted((left, right) -> right.compareTo(left)).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
