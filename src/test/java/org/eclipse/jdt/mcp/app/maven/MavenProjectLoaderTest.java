package org.eclipse.jdt.mcp.app.maven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import junit.framework.TestCase;

/**
 * {@link MavenProjectLoader} 的回归测试：覆盖父 POM 继承、BOM 导入、systemPath 插值、
 * profile、排除项和传递依赖版本仲裁等有效 POM 特性。
 * Regression tests for the local effective-POM Maven loader.
 */
public final class MavenProjectLoaderTest extends TestCase {

    /**
     * Verifies parent inheritance, BOM import, systemPath interpolation,
     * profiles, exclusions and transitive version mediation together.
     *
     * @throws Exception if the temporary fixture cannot be created
     */
    public void testLoadsEffectivePomFeatures() throws Exception {
        Path workspace = Files.createTempDirectory("jdt-mcp-maven-loader-");
        try {
            Path project = workspace.resolve("project");
            Path repository = workspace.resolve("repository");
            Files.createDirectories(project.resolve("lib"));
            Files.write(project.resolve("lib/local.jar"), new byte[0]);
            write(project.resolve("pom.xml"), """
                    <project>
                      <modelVersion>4.0.0</modelVersion>
                      <parent>
                        <groupId>test.parent</groupId>
                        <artifactId>parent</artifactId>
                        <version>1.0</version>
                        <relativePath/>
                      </parent>
                      <groupId>test.project</groupId>
                      <artifactId>application</artifactId>
                      <version>1.0</version>
                      <dependencyManagement>
                        <dependencies>
                          <dependency>
                            <groupId>test.bom</groupId>
                            <artifactId>platform</artifactId>
                            <version>2.0</version>
                            <type>pom</type>
                            <scope>import</scope>
                          </dependency>
                        </dependencies>
                      </dependencyManagement>
                      <dependencies>
                        <dependency>
                          <groupId>test.lib</groupId>
                          <artifactId>parent-managed</artifactId>
                        </dependency>
                        <dependency>
                          <groupId>test.lib</groupId>
                          <artifactId>bom-managed</artifactId>
                        </dependency>
                        <dependency>
                          <groupId>test.lib</groupId>
                          <artifactId>excluded-root</artifactId>
                          <version>1.0</version>
                          <exclusions>
                            <exclusion>
                              <groupId>test.lib</groupId>
                              <artifactId>excluded-child</artifactId>
                            </exclusion>
                          </exclusions>
                        </dependency>
                        <dependency>
                          <groupId>test.system</groupId>
                          <artifactId>sdk</artifactId>
                          <version>1.0</version>
                          <scope>system</scope>
                          <systemPath>${pom.basedir}/lib/local.jar</systemPath>
                        </dependency>
                        <dependency>
                          <groupId>test.a</groupId>
                          <artifactId>first</artifactId>
                          <version>1.0</version>
                        </dependency>
                        <dependency>
                          <groupId>test.b</groupId>
                          <artifactId>second</artifactId>
                          <version>1.0</version>
                        </dependency>
                      </dependencies>
                      <profiles>
                        <profile>
                          <id>default-profile</id>
                          <activation><activeByDefault>true</activeByDefault></activation>
                          <dependencies>
                            <dependency>
                              <groupId>test.lib</groupId>
                              <artifactId>profile-default</artifactId>
                              <version>1.0</version>
                            </dependency>
                          </dependencies>
                        </profile>
                        <profile>
                          <id>explicit-profile</id>
                          <dependencies>
                            <dependency>
                              <groupId>test.lib</groupId>
                              <artifactId>profile-explicit</artifactId>
                              <version>1.0</version>
                            </dependency>
                          </dependencies>
                        </profile>
                      </profiles>
                    </project>
                    """);

            installPom(repository, "test.parent", "parent", "1.0", """
                    <project>
                      <modelVersion>4.0.0</modelVersion>
                      <groupId>test.parent</groupId>
                      <artifactId>parent</artifactId>
                      <version>1.0</version>
                      <dependencyManagement>
                        <dependencies>
                          <dependency>
                            <groupId>test.lib</groupId>
                            <artifactId>parent-managed</artifactId>
                            <version>3.0</version>
                          </dependency>
                        </dependencies>
                      </dependencyManagement>
                    </project>
                    """);
            installPom(repository, "test.bom", "platform", "2.0", """
                    <project>
                      <modelVersion>4.0.0</modelVersion>
                      <groupId>test.bom</groupId>
                      <artifactId>platform</artifactId>
                      <version>2.0</version>
                      <packaging>pom</packaging>
                      <dependencyManagement>
                        <dependencies>
                          <dependency>
                            <groupId>test.lib</groupId>
                            <artifactId>bom-managed</artifactId>
                            <version>4.0</version>
                          </dependency>
                        </dependencies>
                      </dependencyManagement>
                    </project>
                    """);

            installPom(repository, "test.lib", "excluded-root", "1.0", dependencyPom(
                    "test.lib", "excluded-root", "1.0", """
                            <dependencies>
                              <dependency><groupId>test.lib</groupId><artifactId>excluded-child</artifactId><version>1.0</version></dependency>
                              <dependency><groupId>test.lib</groupId><artifactId>kept-child</artifactId><version>1.0</version></dependency>
                            </dependencies>
                            """));
            installPom(repository, "test.a", "first", "1.0", dependencyPom(
                    "test.a", "first", "1.0", dependency("test.lib", "mediated", "1.0")));
            installPom(repository, "test.b", "second", "1.0", dependencyPom(
                    "test.b", "second", "1.0", dependency("test.lib", "mediated", "2.0")));
            installArtifacts(repository, "test.lib", "parent-managed", "3.0");
            installArtifacts(repository, "test.lib", "bom-managed", "4.0");
            installArtifacts(repository, "test.lib", "excluded-child", "1.0");
            installArtifacts(repository, "test.lib", "kept-child", "1.0");
            installArtifacts(repository, "test.lib", "mediated", "1.0");
            installArtifacts(repository, "test.lib", "mediated", "2.0");
            installArtifacts(repository, "test.lib", "profile-default", "1.0");
            installArtifacts(repository, "test.lib", "profile-explicit", "1.0");

            MavenProjectModel model = new MavenProjectLoader().load(project, repository);
            MavenModule module = model.modules().get(0);
            MavenArtifact systemArtifact = module.dependencies().stream()
                    .filter(artifact -> "test.system:sdk:1.0".equals(artifact.coordinate()))
                    .findFirst()
                    .orElse(null);
            assertNotNull(systemArtifact);
            assertEquals(project.resolve("lib/local.jar").toAbsolutePath().normalize(), systemArtifact.file());
            assertTrue(systemArtifact.resolved());
            assertTrue(hasArtifact(model, "test.lib:parent-managed:3.0"));
            assertTrue(hasArtifact(model, "test.lib:bom-managed:4.0"));
            assertTrue(hasArtifact(model, "test.lib:kept-child:1.0"));
            assertFalse(hasArtifact(model, "test.lib:excluded-child:1.0"));
            assertTrue(hasArtifact(model, "test.lib:profile-default:1.0"));
            assertFalse(hasArtifact(model, "test.lib:profile-explicit:1.0"));
            assertTrue(hasArtifact(model, "test.lib:mediated:1.0"));
            assertFalse(hasArtifact(model, "test.lib:mediated:2.0"));
            assertFalse(hasDiagnostic(model, MavenDiagnostic.Kind.PARENT_NOT_FOUND));
            assertFalse(hasDiagnostic(model, MavenDiagnostic.Kind.BOM_NOT_FOUND));

            MavenProjectModel explicitModel = new MavenProjectLoader().load(
                    project,
                    repository,
                    Set.of("explicit-profile"));
            assertTrue(hasArtifact(explicitModel, "test.lib:profile-explicit:1.0"));
            assertFalse(hasArtifact(explicitModel, "test.lib:profile-default:1.0"));
        } finally {
            deleteRecursively(workspace);
        }
    }

    /**
     * Verifies that unresolved versions and missing JARs receive different
     * diagnostic categories and do not create an unknown filesystem path.
     *
     * @throws Exception if the temporary fixture cannot be created
     */
    public void testDistinguishesUnresolvedVersionFromMissingJar() throws Exception {
        Path workspace = Files.createTempDirectory("jdt-mcp-maven-diagnostics-");
        try {
            Path project = workspace.resolve("project");
            Path repository = workspace.resolve("repository");
            write(project.resolve("pom.xml"), """
                    <project>
                      <modelVersion>4.0.0</modelVersion>
                      <groupId>test.project</groupId>
                      <artifactId>diagnostics</artifactId>
                      <version>1.0</version>
                      <dependencies>
                        <dependency><groupId>test.lib</groupId><artifactId>unknown</artifactId></dependency>
                        <dependency><groupId>test.lib</groupId><artifactId>missing</artifactId><version>1.0</version></dependency>
                      </dependencies>
                    </project>
                    """);
            MavenProjectModel model = new MavenProjectLoader().load(project, repository);
            MavenArtifact unknown = model.artifacts().stream()
                    .filter(artifact -> "test.lib:unknown:unknown".equals(artifact.coordinate()))
                    .findFirst()
                    .orElse(null);
            assertNotNull(unknown);
            assertEquals(null, unknown.file());
            assertTrue(hasDiagnostic(model, MavenDiagnostic.Kind.UNRESOLVED_VERSION));
            assertTrue(hasDiagnostic(model, MavenDiagnostic.Kind.MISSING_JAR));
            assertTrue(model.resolutionState().equals("FAILED"));
        } finally {
            deleteRecursively(workspace);
        }
    }

    /**
     * Installs a small artifact POM and empty placeholder JAR in a fixture
     * repository.
     *
     * @param repository fixture repository
     * @param groupId group identifier
     * @param artifactId artifact identifier
     * @param version version
     * @throws IOException if fixture files cannot be written
     */
    private static void installArtifacts(Path repository, String groupId, String artifactId, String version)
            throws IOException {
        installPom(repository, groupId, artifactId, version,
                dependencyPom(groupId, artifactId, version, ""));
        Path directory = artifactDirectory(repository, groupId, artifactId, version);
        Files.write(directory.resolve(artifactId + "-" + version + ".jar"), new byte[0]);
    }

    /**
     * Installs a POM into a Maven-style fixture repository.
     *
     * @param repository fixture repository
     * @param groupId group identifier
     * @param artifactId artifact identifier
     * @param version version
     * @param pom POM content
     * @throws IOException if fixture files cannot be written
     */
    private static void installPom(
            Path repository,
            String groupId,
            String artifactId,
            String version,
            String pom) throws IOException {
        Path directory = artifactDirectory(repository, groupId, artifactId, version);
        write(directory.resolve(artifactId + "-" + version + ".pom"), pom);
    }

    /**
     * Returns a Maven-style artifact directory and creates it when necessary.
     *
     * @param repository fixture repository
     * @param groupId group identifier
     * @param artifactId artifact identifier
     * @param version version
     * @return artifact directory
     * @throws IOException if the directory cannot be created
     */
    private static Path artifactDirectory(Path repository, String groupId, String artifactId, String version)
            throws IOException {
        Path directory = repository.resolve(groupId.replace('.', '/')).resolve(artifactId).resolve(version);
        Files.createDirectories(directory);
        return directory;
    }

    /**
     * Wraps dependency XML in a minimal POM.
     *
     * @param groupId group identifier
     * @param artifactId artifact identifier
     * @param version version
     * @param dependencies dependency XML
     * @return POM content
     */
    private static String dependencyPom(String groupId, String artifactId, String version, String dependencies) {
        return """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                  %s
                </project>
                """.formatted(groupId, artifactId, version, dependencies);
    }

    /**
     * Creates one dependency element.
     *
     * @param groupId group identifier
     * @param artifactId artifact identifier
     * @param version version
     * @return dependency XML
     */
    private static String dependency(String groupId, String artifactId, String version) {
        return "<dependencies><dependency><groupId>" + groupId + "</groupId><artifactId>"
                + artifactId + "</artifactId><version>" + version + "</version></dependency></dependencies>";
    }

    /**
     * Writes UTF-8 fixture content and creates parent directories.
     *
     * @param file destination file
     * @param content file content
     * @throws IOException if the file cannot be written
     */
    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    /**
     * Checks whether an artifact coordinate is present.
     *
     * @param model Maven model
     * @param coordinate exact coordinate
     * @return whether the artifact is present
     */
    private static boolean hasArtifact(MavenProjectModel model, String coordinate) {
        return model.artifacts().stream().anyMatch(artifact -> coordinate.equals(artifact.coordinate()));
    }

    /**
     * Checks whether a diagnostic category is present.
     *
     * @param model Maven model
     * @param kind diagnostic category
     * @return whether the category is present
     */
    private static boolean hasDiagnostic(MavenProjectModel model, MavenDiagnostic.Kind kind) {
        return model.diagnostics().stream().anyMatch(diagnostic -> diagnostic.kind() == kind);
    }

    /**
     * Deletes a temporary fixture tree from leaves to root.
     *
     * @param root fixture root
     * @throws IOException if a fixture entry cannot be deleted
     */
    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
