package org.eclipse.jdt.mcp.app.maven;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * 不依赖 Maven 插件、仅使用本地仓库的 Maven 项目加载器：在解析依赖前先构建有效 POM
 * （父 POM 继承、BOM 导入、profile 激活、属性插值、依赖管理与排除），发现 reactor
 * 模块并做确定性版本仲裁，最终产出项目模型与诊断信息。
 *
 * <p>The loader deliberately remains plugin-free and local-repository based,
 * but it now builds an effective POM view before resolving artifacts. This
 * includes parent inheritance, imported BOMs, active profiles, exclusions and
 * deterministic dependency mediation.</p>
 */
public final class MavenProjectLoader {
    private static final Pattern PROPERTY = Pattern.compile("\\$\\{([^}]+)}");
    private static final int MAX_PROPERTY_PASSES = 20;

    private Path localRepository;
    private Set<String> activeProfiles = Set.of();
    private final Map<Path, EffectivePom> pomCache = new HashMap<>();
    private final Set<Path> loadingPoms = new HashSet<>();
    private final List<MavenDiagnostic> diagnostics = new ArrayList<>();

    /**
     * Loads a Maven project using automatic profile activation only.
     *
     * @param projectRoot project directory containing the root {@code pom.xml}
     * @param localRepository local Maven repository used for parent, BOM and
     *        dependency POM/JAR lookup
     * @return the effective project model
     * @throws IOException if the root POM cannot be read or parsed
     */
    public MavenProjectModel load(Path projectRoot, Path localRepository) throws IOException {
        return load(projectRoot, localRepository, Set.of());
    }

    /**
     * Loads a Maven project with explicitly activated profile identifiers.
     *
     * @param projectRoot project directory containing the root {@code pom.xml}
     * @param localRepository local Maven repository used for parent, BOM and
     *        dependency POM/JAR lookup
     * @param requestedProfiles profile identifiers requested by the caller
     * @return the effective project model
     * @throws IOException if the root POM cannot be read or parsed
     */
    public MavenProjectModel load(
            Path projectRoot,
            Path localRepository,
            Set<String> requestedProfiles) throws IOException {
        Path root = projectRoot.toAbsolutePath().normalize();
        Path rootPom = root.resolve("pom.xml");
        if (!Files.isRegularFile(rootPom)) {
            throw new IOException("Maven root POM does not exist: " + rootPom);
        }

        this.localRepository = localRepository.toAbsolutePath().normalize();
        this.activeProfiles = normalizeProfileIds(requestedProfiles);
        this.pomCache.clear();
        this.loadingPoms.clear();
        this.diagnostics.clear();

        EffectivePom rootModel = readPom(rootPom);
        List<EffectivePom> effectivePoms = new ArrayList<>();
        List<String> legacyWarnings = new ArrayList<>();
        visitModules(rootModel, effectivePoms, new HashSet<>(), legacyWarnings);

        Map<String, EffectivePom> reactorByGa = new LinkedHashMap<>();
        for (EffectivePom pom : effectivePoms) {
            reactorByGa.put(pom.groupId + ":" + pom.artifactId, pom);
        }

        Map<String, ArtifactSelection> selectedArtifacts = new LinkedHashMap<>();
        ArrayDeque<TraversalNode> traversal = new ArrayDeque<>();
        List<MavenModule> modules = new ArrayList<>();
        int declarationOrder = 0;
        for (EffectivePom pom : effectivePoms) {
            if (pom.isTestModule()) {
                addDiagnostic(
                        MavenDiagnostic.Severity.INFO,
                        MavenDiagnostic.Kind.SKIPPED_MODULE,
                        "Skipped test module: " + pom.directory,
                        pom.coordinate(),
                        pom.pom);
                continue;
            }
            List<MavenArtifact> dependencies = new ArrayList<>();
            for (DependencySpec dependency : pom.dependencies) {
                if (isNonClasspathDependency(dependency)) {
                    continue;
                }
                MavenArtifact artifact = resolve(dependency, pom, reactorByGa);
                dependencies.add(artifact);
                if (offerArtifact(selectedArtifacts, artifact, 0, declarationOrder++)) {
                    traversal.add(new TraversalNode(
                            artifact,
                            0,
                            new LinkedHashSet<>(artifact.exclusions())));
                }
            }
            modules.add(new MavenModule(
                    pom.groupId,
                    pom.artifactId,
                    pom.version,
                    pom.packaging,
                    pom.directory,
                    pom.sourceDirectories,
                    pom.outputDirectory,
                    List.copyOf(dependencies)));
        }

        expandTransitive(traversal, selectedArtifacts, reactorByGa);
        List<MavenArtifact> artifacts = selectedArtifacts.values().stream()
                .map(ArtifactSelection::artifact)
                .toList();
        legacyWarnings.addAll(diagnostics.stream().map(MavenDiagnostic::format).toList());
        return new MavenProjectModel(
                root,
                List.copyOf(modules),
                artifacts,
                List.copyOf(legacyWarnings),
                List.copyOf(diagnostics));
    }

    /**
     * Walks the reactor modules declared by an effective POM.
     *
     * @param pom current effective POM
     * @param result collected effective POMs
     * @param visited normalized POM paths already visited
     * @param warnings legacy non-structured warnings
     * @throws IOException if a declared module POM cannot be parsed
     */
    private void visitModules(
            EffectivePom pom,
            List<EffectivePom> result,
            Set<Path> visited,
            List<String> warnings) throws IOException {
        if (pom == null) {
            return;
        }
        Path normalizedPom = pom.pom.toAbsolutePath().normalize();
        if (!visited.add(normalizedPom)) {
            return;
        }
        result.add(pom);
        for (Path moduleDirectory : pom.moduleDirectories) {
            Path modulePom = moduleDirectory.resolve("pom.xml");
            if (!Files.isRegularFile(modulePom)) {
                warnings.add("Skipped module without pom.xml: " + moduleDirectory);
                continue;
            }
            visitModules(readPom(modulePom), result, visited, warnings);
        }
    }

    /**
     * Expands dependency POMs breadth-first so nearer dependencies win during
     * version mediation.
     *
     * @param traversal pending dependency paths
     * @param selectedArtifacts mediated artifacts keyed by dependency identity
     * @param reactorByGa reactor modules keyed by group and artifact
     */
    private void expandTransitive(
            ArrayDeque<TraversalNode> traversal,
            Map<String, ArtifactSelection> selectedArtifacts,
            Map<String, EffectivePom> reactorByGa) {
        Set<String> expandedPaths = new HashSet<>();
        int declarationOrder = selectedArtifacts.size();
        while (!traversal.isEmpty()) {
            TraversalNode node = traversal.removeFirst();
            MavenArtifact parentArtifact = node.artifact();
            String pathKey = parentArtifact.coordinate() + "|" + sortedKey(node.excludedArtifacts());
            if (!expandedPaths.add(pathKey) || isUnknownVersion(parentArtifact.version())) {
                continue;
            }

            EffectivePom dependencyPom = reactorByGa.get(parentArtifact.groupId() + ":" + parentArtifact.artifactId());
            if (dependencyPom == null) {
                Path pomPath = artifactPom(parentArtifact);
                if (pomPath == null || !Files.isRegularFile(pomPath)) {
                    addDiagnostic(
                            MavenDiagnostic.Severity.WARNING,
                            MavenDiagnostic.Kind.MISSING_POM,
                            "Dependency POM is not available for " + parentArtifact.coordinate(),
                            parentArtifact.coordinate(),
                            pomPath);
                    continue;
                }
                try {
                    dependencyPom = readPom(pomPath);
                } catch (IOException exception) {
                    addDiagnostic(
                            MavenDiagnostic.Severity.WARNING,
                            MavenDiagnostic.Kind.INVALID_POM,
                            "Could not read dependency POM " + pomPath + ": " + message(exception),
                            parentArtifact.coordinate(),
                            pomPath);
                    continue;
                }
            }

            for (DependencySpec dependency : dependencyPom.dependencies) {
                if (isTransitiveNonClasspathDependency(dependency)
                        || node.excludedArtifacts().contains(dependency.ga())) {
                    continue;
                }
                MavenArtifact child = resolve(dependency, dependencyPom, reactorByGa);
                if (!offerArtifact(selectedArtifacts, child, node.depth() + 1, declarationOrder++)) {
                    continue;
                }
                Set<String> excluded = new LinkedHashSet<>(node.excludedArtifacts());
                excluded.addAll(child.exclusions());
                traversal.addLast(new TraversalNode(child, node.depth() + 1, excluded));
            }
        }
    }

    /**
     * Resolves one dependency to a local JAR, reactor output directory or a
     * structured unresolved artifact.
     *
     * @param dependency dependency declaration
     * @param owner effective POM that declares the dependency
     * @param reactorByGa reactor modules keyed by group and artifact
     * @return resolved or diagnostically unresolved artifact
     */
    private MavenArtifact resolve(
            DependencySpec dependency,
            EffectivePom owner,
            Map<String, EffectivePom> reactorByGa) {
        String version = resolveValue(dependency.version, owner.properties);
        if (version == null || version.isBlank()) {
            version = owner.dependencyManagement.get(dependency.ga());
        }
        version = resolveValue(version, owner.properties);
        if (version == null || version.isBlank() || hasProperty(version)) {
            addDiagnostic(
                    MavenDiagnostic.Severity.ERROR,
                    hasProperty(version)
                            ? MavenDiagnostic.Kind.UNRESOLVED_PROPERTY
                            : MavenDiagnostic.Kind.UNRESOLVED_VERSION,
                    "Could not resolve dependency version for " + dependency.ga(),
                    dependency.ga(),
                    owner.pom);
            version = "unknown";
        }

        EffectivePom reactor = reactorByGa.get(dependency.ga());
        if (reactor != null && ("unknown".equals(version) || version.equals(reactor.version))) {
            Path jar = reactor.directory.resolve("target")
                    .resolve(reactor.artifactId + "-" + reactor.version + ".jar");
            boolean classesExist = Files.isDirectory(reactor.outputDirectory);
            boolean jarExists = Files.isRegularFile(jar);
            if (!classesExist && !jarExists) {
                addDiagnostic(
                        MavenDiagnostic.Severity.WARNING,
                        MavenDiagnostic.Kind.MISSING_JAR,
                        "Reactor output is not available for " + reactor.coordinate(),
                        reactor.coordinate(),
                        jar);
            }
            return new MavenArtifact(
                    dependency.groupId,
                    dependency.artifactId,
                    reactor.version,
                    dependency.scope,
                    dependency.classifier,
                    jar,
                    reactor.outputDirectory,
                    classesExist || jarExists,
                    true,
                    dependency.exclusionKeys());
        }

        Path file;
        if ("system".equals(dependency.scope) && dependency.systemPath != null) {
            String systemPath = resolveValue(dependency.systemPath, owner.properties);
            if (systemPath == null || systemPath.isBlank() || hasProperty(systemPath)) {
                addDiagnostic(
                        MavenDiagnostic.Severity.ERROR,
                        MavenDiagnostic.Kind.UNRESOLVED_PROPERTY,
                        "Could not resolve systemPath for " + dependency.ga(),
                        dependency.ga(),
                        owner.pom);
                file = null;
            } else {
                file = resolvePath(systemPath, owner.directory);
                if (!Files.isRegularFile(file)) {
                    addDiagnostic(
                            MavenDiagnostic.Severity.WARNING,
                            MavenDiagnostic.Kind.MISSING_SYSTEM_PATH,
                            "systemPath file is not available for " + dependency.ga() + ": " + file,
                            dependency.ga(),
                            file);
                }
            }
        } else if (isUnknownVersion(version)) {
            file = null;
        } else {
            String groupPath = dependency.groupId.replace('.', java.io.File.separatorChar);
            String baseName = dependency.artifactId + "-" + version;
            if (dependency.classifier != null && !dependency.classifier.isBlank()) {
                baseName += "-" + dependency.classifier;
            }
            file = localRepository.resolve(groupPath)
                    .resolve(dependency.artifactId)
                    .resolve(version)
                    .resolve(baseName + ".jar");
            if (!Files.isRegularFile(file)) {
                addDiagnostic(
                        MavenDiagnostic.Severity.WARNING,
                        MavenDiagnostic.Kind.MISSING_JAR,
                        "Artifact JAR is not available for " + dependency.groupId + ":"
                                + dependency.artifactId + ":" + version + ": " + file,
                        dependency.groupId + ":" + dependency.artifactId + ":" + version,
                        file);
            }
        }
        return new MavenArtifact(
                dependency.groupId,
                dependency.artifactId,
                version,
                dependency.scope,
                dependency.classifier,
                file,
                null,
                file != null && Files.isRegularFile(file),
                false,
                dependency.exclusionKeys());
    }

    /**
     * Selects the nearest and then earliest declaration for one dependency
     * identity.
     *
     * @param selectedArtifacts mediated artifact map
     * @param candidate candidate artifact
     * @param depth dependency depth, with direct dependencies at zero
     * @param declarationOrder stable encounter order
     * @return whether the candidate should be traversed
     */
    private boolean offerArtifact(
            Map<String, ArtifactSelection> selectedArtifacts,
            MavenArtifact candidate,
            int depth,
            int declarationOrder) {
        String key = artifactKey(candidate);
        ArtifactSelection current = selectedArtifacts.get(key);
        if (current == null) {
            selectedArtifacts.put(key, new ArtifactSelection(candidate, depth, declarationOrder));
            return true;
        }
        if (current.artifact().coordinate().equals(candidate.coordinate())) {
            return true;
        }
        if (depth < current.depth()) {
            addDiagnostic(
                    MavenDiagnostic.Severity.INFO,
                    MavenDiagnostic.Kind.VERSION_CONFLICT,
                    "Selected nearer dependency " + candidate.coordinate() + " over "
                            + current.artifact().coordinate(),
                    candidate.coordinate(),
                    null);
            selectedArtifacts.put(key, new ArtifactSelection(candidate, depth, declarationOrder));
            return true;
        }
        if (depth == current.depth()) {
            addDiagnostic(
                    MavenDiagnostic.Severity.INFO,
                    MavenDiagnostic.Kind.VERSION_CONFLICT,
                    "Selected earlier dependency " + current.artifact().coordinate() + " over "
                            + candidate.coordinate(),
                    current.artifact().coordinate(),
                    null);
        }
        return false;
    }

    /**
     * Reads and expands one POM, including its external parent and active
     * profiles.
     *
     * @param pom POM path
     * @return effective POM
     * @throws IOException if the POM is malformed or lacks coordinates
     */
    private EffectivePom readPom(Path pom) throws IOException {
        Path normalizedPom = pom.toAbsolutePath().normalize();
        EffectivePom cached = pomCache.get(normalizedPom);
        if (cached != null) {
            return cached;
        }
        if (!loadingPoms.add(normalizedPom)) {
            addDiagnostic(
                    MavenDiagnostic.Severity.ERROR,
                    MavenDiagnostic.Kind.POM_CYCLE,
                    "Cyclic POM inheritance detected at " + normalizedPom,
                    null,
                    normalizedPom);
            throw new IOException("Cyclic Maven POM inheritance: " + normalizedPom);
        }

        try (InputStream input = Files.newInputStream(normalizedPom)) {
            Document document;
            try {
                document = parseDocument(input, normalizedPom);
            } catch (SAXException | ParserConfigurationException exception) {
                addDiagnostic(
                        MavenDiagnostic.Severity.ERROR,
                        MavenDiagnostic.Kind.INVALID_POM,
                        "Cannot parse Maven POM " + normalizedPom + ": " + message(exception),
                        null,
                        normalizedPom);
                throw new IOException("Cannot parse Maven POM: " + normalizedPom, exception);
            }

            Element project = document.getDocumentElement();
            Path directory = normalizedPom.getParent();
            Element parentElement = child(project, "parent");
            EffectivePom parent = loadParent(normalizedPom, parentElement);
            Map<String, String> properties = inheritedProperties(parent, directory);
            mergeProperties(properties, child(project, "properties"));

            String artifactId = resolveValue(text(child(project, "artifactId")), properties);
            String groupId = resolveValue(
                    firstNonBlank(text(child(project, "groupId")), parent == null ? null : parent.groupId),
                    properties);
            String version = resolveValue(
                    firstNonBlank(text(child(project, "version")), parent == null ? null : parent.version),
                    properties);
            if (groupId == null || groupId.isBlank() || artifactId == null || artifactId.isBlank()) {
                throw new IOException("Maven POM is missing groupId or artifactId: " + normalizedPom);
            }
            version = firstNonBlank(version, "unknown");
            setProjectProperties(properties, groupId, artifactId, version, directory);
            resolveAllProperties(properties);

            List<Element> activeProfileElements = activeProfiles(project, properties, normalizedPom);
            for (Element profile : activeProfileElements) {
                mergeProperties(properties, child(profile, "properties"));
            }
            resolveAllProperties(properties);

            groupId = resolveValue(groupId, properties);
            artifactId = resolveValue(artifactId, properties);
            version = firstNonBlank(resolveValue(version, properties), "unknown");
            setProjectProperties(properties, groupId, artifactId, version, directory);
            resolveAllProperties(properties);
            reportUnresolvedCoordinates(groupId, artifactId, version, normalizedPom);

            Map<String, String> dependencyManagement = dependencyManagement(
                    project,
                    activeProfileElements,
                    parent,
                    properties,
                    normalizedPom);
            List<DependencySpec> dependencies = dependencies(
                    project,
                    activeProfileElements,
                    parent,
                    properties,
                    normalizedPom);
            BuildPaths buildPaths = buildPaths(project, activeProfileElements, parent, properties, directory);
            List<Path> moduleDirectories = moduleDirectories(
                    project,
                    activeProfileElements,
                    properties,
                    directory);

            EffectivePom effective = new EffectivePom(
                    normalizedPom,
                    directory,
                    groupId,
                    artifactId,
                    version,
                    firstNonBlank(resolveValue(text(child(project, "packaging")), properties), "jar"),
                    buildPaths.sourceDirectories(),
                    buildPaths.outputDirectory(),
                    properties,
                    dependencyManagement,
                    dependencies,
                    moduleDirectories);
            pomCache.put(normalizedPom, effective);
            return effective;
        } finally {
            loadingPoms.remove(normalizedPom);
        }
    }

    /**
     * Loads a POM's declared parent from relativePath or the local repository.
     *
     * @param childPom child POM path
     * @param parentElement parent declaration, or {@code null}
     * @return effective parent or {@code null} when no parent is declared or
     *         the parent is unavailable
     * @throws IOException if a candidate parent POM is malformed
     */
    private EffectivePom loadParent(Path childPom, Element parentElement) throws IOException {
        if (parentElement == null) {
            return null;
        }
        String groupId = text(child(parentElement, "groupId"));
        String artifactId = text(child(parentElement, "artifactId"));
        String version = text(child(parentElement, "version"));
        Path childDirectory = childPom.getParent();
        String relativePath = text(child(parentElement, "relativePath"));
        if (relativePath == null) {
            relativePath = "../pom.xml";
        }
        if (!relativePath.isBlank()) {
            Path candidate = childDirectory.resolve(relativePath).normalize();
            if (Files.isRegularFile(candidate)) {
                EffectivePom relativeParent = readPom(candidate);
                if (matchesCoordinates(relativeParent, groupId, artifactId, version)) {
                    return relativeParent;
                }
                addDiagnostic(
                        MavenDiagnostic.Severity.WARNING,
                        MavenDiagnostic.Kind.PARENT_MISMATCH,
                        "relativePath parent does not match " + groupId + ":" + artifactId + ":" + version,
                        groupId + ":" + artifactId + ":" + version,
                        candidate);
            }
        }

        Path repositoryPom = pomPath(groupId, artifactId, version);
        if (repositoryPom != null && Files.isRegularFile(repositoryPom)) {
            return readPom(repositoryPom);
        }
        addDiagnostic(
                MavenDiagnostic.Severity.ERROR,
                MavenDiagnostic.Kind.PARENT_NOT_FOUND,
                "Parent POM is not available: " + groupId + ":" + artifactId + ":" + version,
                groupId + ":" + artifactId + ":" + version,
                repositoryPom);
        return null;
    }

    /**
     * Determines which profiles are active for one POM.
     *
     * @param project POM project element
     * @param properties currently known project properties
     * @param pom POM path used for diagnostics
     * @return active profile elements in declaration order
     */
    private List<Element> activeProfiles(Element project, Map<String, String> properties, Path pom) {
        Element profilesElement = child(project, "profiles");
        List<Element> profiles = children(profilesElement, "profile");
        List<Element> active = new ArrayList<>();
        boolean automaticProfileActive = false;
        for (Element profile : profiles) {
            if (explicitlyActive(profile)) {
                active.add(profile);
                automaticProfileActive = true;
            } else if (activationMatches(profile, properties)) {
                active.add(profile);
                automaticProfileActive = true;
            }
        }
        if (!automaticProfileActive) {
            for (Element profile : profiles) {
                Element activation = child(profile, "activation");
                if (booleanValue(text(child(activation, "activeByDefault")))) {
                    active.add(profile);
                    break;
                }
            }
        }
        Set<String> knownIds = new HashSet<>();
        for (Element profile : profiles) {
            String id = text(child(profile, "id"));
            if (id != null) {
                knownIds.add(id);
            }
        }
        for (String requested : activeProfiles) {
            if (!knownIds.contains(requested)) {
                addDiagnostic(
                        MavenDiagnostic.Severity.INFO,
                        MavenDiagnostic.Kind.PROFILE_NOT_FOUND,
                        "Requested profile is not declared in " + pom + ": " + requested,
                        requested,
                        pom);
            }
        }
        return List.copyOf(active);
    }

    /**
     * Checks whether a profile is explicitly selected by configuration.
     *
     * @param profile profile element
     * @return whether the profile identifier was requested
     */
    private boolean explicitlyActive(Element profile) {
        String id = text(child(profile, "id"));
        return id != null && activeProfiles.contains(id);
    }

    /**
     * Evaluates non-default Maven profile activation conditions.
     *
     * @param profile profile element
     * @param properties project properties available to activation
     * @return whether the profile is automatically active
     */
    private boolean activationMatches(Element profile, Map<String, String> properties) {
        Element activation = child(profile, "activation");
        if (activation == null || booleanValue(text(child(activation, "activeByDefault")))) {
            return false;
        }
        String jdk = text(child(activation, "jdk"));
        if (jdk != null && !jdkMatches(jdk)) {
            return false;
        }
        Element os = child(activation, "os");
        if (os != null && !osMatches(os)) {
            return false;
        }
        Element property = child(activation, "property");
        if (property != null && !propertyMatches(property, properties)) {
            return false;
        }
        return jdk != null || os != null || property != null;
    }

    /**
     * Matches the running JDK against the common Maven activation forms.
     *
     * @param expression JDK activation expression
     * @return whether the current JDK matches
     */
    private boolean jdkMatches(String expression) {
        String current = System.getProperty("java.version", "");
        String value = expression.trim();
        if (value.startsWith("!")) {
            return !jdkMatches(value.substring(1));
        }
        if (value.startsWith("[") || value.startsWith("(") || value.endsWith("]") || value.endsWith(")")) {
            return versionRangeMatches(current, value);
        }
        return current.equals(value) || current.startsWith(value);
    }

    /**
     * Matches Maven's simple JDK version range syntax.
     *
     * @param current current JDK version
     * @param expression inclusive/exclusive range expression
     * @return whether the current version is within the range
     */
    private boolean versionRangeMatches(String current, String expression) {
        String value = expression.trim();
        if (value.length() < 2) {
            return false;
        }
        boolean minimumInclusive = value.charAt(0) == '[';
        boolean maximumInclusive = value.charAt(value.length() - 1) == ']';
        String[] bounds = value.substring(1, value.length() - 1).split(",", -1);
        String minimum = bounds.length > 0 ? bounds[0].trim() : "";
        String maximum = bounds.length > 1 ? bounds[1].trim() : minimum;
        if (!minimum.isEmpty()) {
            int comparison = compareVersions(current, minimum);
            if (comparison < 0 || (!minimumInclusive && comparison == 0)) {
                return false;
            }
        }
        if (!maximum.isEmpty()) {
            int comparison = compareVersions(current, maximum);
            if (comparison > 0 || (!maximumInclusive && comparison == 0)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Compares dot-separated numeric version components.
     *
     * @param left first version
     * @param right second version
     * @return negative, zero or positive according to version order
     */
    private int compareVersions(String left, String right) {
        String[] leftParts = left.split("[.-]");
        String[] rightParts = right.split("[.-]");
        int length = Math.max(leftParts.length, rightParts.length);
        for (int i = 0; i < length; i++) {
            int leftValue = i < leftParts.length ? numericPrefix(leftParts[i]) : 0;
            int rightValue = i < rightParts.length ? numericPrefix(rightParts[i]) : 0;
            if (leftValue != rightValue) {
                return Integer.compare(leftValue, rightValue);
            }
        }
        return 0;
    }

    /**
     * Extracts the numeric prefix used by version comparison.
     *
     * @param value version component
     * @return numeric prefix, or zero when absent
     */
    private int numericPrefix(String value) {
        Matcher matcher = Pattern.compile("^\\d+").matcher(value);
        return matcher.find() ? Integer.parseInt(matcher.group()) : 0;
    }

    /**
     * Evaluates operating-system profile activation.
     *
     * @param os operating-system activation element
     * @return whether all specified OS fields match
     */
    private boolean osMatches(Element os) {
        return matchesOptional(text(child(os, "name")), System.getProperty("os.name", ""))
                && matchesOptional(text(child(os, "family")), osFamily())
                && matchesOptional(text(child(os, "arch")), System.getProperty("os.arch", ""))
                && matchesOptional(text(child(os, "version")), System.getProperty("os.version", ""));
    }

    /**
     * Maps the current operating system to Maven's common family names.
     *
     * @return current OS family
     */
    private String osFamily() {
        String name = System.getProperty("os.name", "").toLowerCase();
        if (name.contains("win")) {
            return "windows";
        }
        if (name.contains("mac") || name.contains("darwin")) {
            return "mac";
        }
        if (name.contains("linux")) {
            return "unix";
        }
        return name;
    }

    /**
     * Matches an optional activation value against a system value.
     *
     * @param expected expected value, possibly negated
     * @param actual actual system value
     * @return whether the value matches
     */
    private boolean matchesOptional(String expected, String actual) {
        if (expected == null || expected.isBlank()) {
            return true;
        }
        if (expected.startsWith("!")) {
            return !actual.equalsIgnoreCase(expected.substring(1));
        }
        return actual.equalsIgnoreCase(expected);
    }

    /**
     * Evaluates property-based profile activation.
     *
     * @param property activation property element
     * @param properties project properties
     * @return whether the property condition matches
     */
    private boolean propertyMatches(Element property, Map<String, String> properties) {
        String name = text(child(property, "name"));
        String expected = text(child(property, "value"));
        if (name == null || name.isBlank()) {
            return false;
        }
        boolean negateName = name.startsWith("!");
        String actualName = negateName ? name.substring(1) : name;
        boolean present = properties.containsKey(actualName)
                || System.getProperty(actualName) != null
                || System.getenv(actualName) != null;
        if (negateName) {
            return !present;
        }
        if (!present || expected == null || expected.isBlank()) {
            return present;
        }
        if (expected.startsWith("!")) {
            return !expected.substring(1).equals(propertyValue(actualName, properties));
        }
        return expected.equals(propertyValue(actualName, properties));
    }

    /**
     * Gets a property value from project, system or environment properties.
     *
     * @param name property name
     * @param properties project properties
     * @return property value, or {@code null}
     */
    private String propertyValue(String name, Map<String, String> properties) {
        String value = properties.get(name);
        if (value != null) {
            return value;
        }
        value = System.getProperty(name);
        return value == null ? System.getenv(name) : value;
    }

    /**
     * Builds inherited and imported dependency management.
     *
     * @param project POM project element
     * @param profiles active profiles
     * @param parent effective parent
     * @param properties resolved project properties
     * @param pom current POM path
     * @return effective GA-to-version management map
     * @throws IOException if an imported BOM is malformed
     */
    private Map<String, String> dependencyManagement(
            Element project,
            List<Element> profiles,
            EffectivePom parent,
            Map<String, String> properties,
            Path pom) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        if (parent != null) {
            result.putAll(parent.dependencyManagement);
        }
        List<ManagedDependencySpec> managed = new ArrayList<>();
        for (Element profile : profiles) {
            managed.addAll(managedDependencies(child(profile, "dependencyManagement"), properties));
        }
        managed.addAll(managedDependencies(child(project, "dependencyManagement"), properties));

        for (ManagedDependencySpec dependency : managed) {
            if (!dependency.isBomImport()) {
                continue;
            }
            String version = managedVersion(dependency, result, properties, pom);
            if (version == null) {
                continue;
            }
            Path bomPom = pomPath(dependency.groupId, dependency.artifactId, version);
            if (bomPom == null || !Files.isRegularFile(bomPom)) {
                addDiagnostic(
                        MavenDiagnostic.Severity.ERROR,
                        MavenDiagnostic.Kind.BOM_NOT_FOUND,
                        "Imported BOM is not available: " + dependency.coordinate(version),
                        dependency.coordinate(version),
                        bomPom);
                continue;
            }
            EffectivePom bom = readPom(bomPom);
            for (Map.Entry<String, String> entry : bom.dependencyManagement.entrySet()) {
                result.putIfAbsent(entry.getKey(), entry.getValue());
            }
        }
        for (ManagedDependencySpec dependency : managed) {
            if (dependency.isBomImport()) {
                continue;
            }
            String version = managedVersion(dependency, result, properties, pom);
            if (version != null) {
                result.put(dependency.ga(), version);
            }
        }
        return Map.copyOf(result);
    }

    /**
     * Reads managed dependency declarations from one dependencyManagement
     * element.
     *
     * @param management dependencyManagement element
     * @param properties project properties
     * @return managed dependency declarations
     */
    private List<ManagedDependencySpec> managedDependencies(
            Element management,
            Map<String, String> properties) {
        Element dependencies = management == null ? null : child(management, "dependencies");
        List<ManagedDependencySpec> result = new ArrayList<>();
        for (Element dependency : children(dependencies, "dependency")) {
            String groupId = resolveValue(text(child(dependency, "groupId")), properties);
            String artifactId = resolveValue(text(child(dependency, "artifactId")), properties);
            if (groupId == null || artifactId == null) {
                continue;
            }
            result.add(new ManagedDependencySpec(
                    groupId,
                    artifactId,
                    resolveValue(text(child(dependency, "version")), properties),
                    firstNonBlank(resolveValue(text(child(dependency, "scope")), properties), "compile"),
                    firstNonBlank(resolveValue(text(child(dependency, "type")), properties), "jar")));
        }
        return result;
    }

    /**
     * Resolves a managed version either explicitly or from earlier management.
     *
     * @param dependency managed dependency
     * @param managed existing management map
     * @param properties project properties
     * @param pom source POM
     * @return resolved version, or {@code null}
     */
    private String managedVersion(
            ManagedDependencySpec dependency,
            Map<String, String> managed,
            Map<String, String> properties,
            Path pom) {
        String version = resolveValue(dependency.version, properties);
        if (version == null || version.isBlank()) {
            version = managed.get(dependency.ga());
        }
        if (version == null || version.isBlank() || hasProperty(version)) {
            addDiagnostic(
                    MavenDiagnostic.Severity.ERROR,
                    hasProperty(version)
                            ? MavenDiagnostic.Kind.UNRESOLVED_PROPERTY
                            : MavenDiagnostic.Kind.UNRESOLVED_VERSION,
                    "Could not resolve managed dependency version for " + dependency.ga(),
                    dependency.ga(),
                    pom);
            return null;
        }
        return version;
    }

    /**
     * Reads inherited, profile and direct dependency declarations.
     *
     * @param project POM project element
     * @param profiles active profiles
     * @param parent effective parent
     * @param properties resolved project properties
     * @param pom source POM
     * @return effective dependency declarations
     */
    private List<DependencySpec> dependencies(
            Element project,
            List<Element> profiles,
            EffectivePom parent,
            Map<String, String> properties,
            Path pom) {
        Map<String, DependencySpec> result = new LinkedHashMap<>();
        if (parent != null) {
            for (DependencySpec dependency : parent.dependencies) {
                result.put(dependency.identity(), dependency);
            }
        }
        for (Element profile : profiles) {
            mergeDependencies(result, child(profile, "dependencies"), properties, pom);
        }
        mergeDependencies(result, child(project, "dependencies"), properties, pom);
        return List.copyOf(result.values());
    }

    /**
     * Merges dependency declarations, allowing later declarations to override
     * inherited declarations with the same identity.
     *
     * @param result dependency map
     * @param dependencies dependency container
     * @param properties project properties
     * @param pom source POM
     */
    private void mergeDependencies(
            Map<String, DependencySpec> result,
            Element dependencies,
            Map<String, String> properties,
            Path pom) {
        for (Element dependency : children(dependencies, "dependency")) {
            String groupId = resolveValue(text(child(dependency, "groupId")), properties);
            String artifactId = resolveValue(text(child(dependency, "artifactId")), properties);
            if (groupId == null || artifactId == null) {
                addDiagnostic(
                        MavenDiagnostic.Severity.WARNING,
                        MavenDiagnostic.Kind.INVALID_DEPENDENCY,
                        "Dependency is missing groupId or artifactId in " + pom,
                        null,
                        pom);
                continue;
            }
            DependencySpec value = new DependencySpec(
                    groupId,
                    artifactId,
                    resolveValue(text(child(dependency, "version")), properties),
                    firstNonBlank(resolveValue(text(child(dependency, "scope")), properties), "compile"),
                    firstNonBlank(resolveValue(text(child(dependency, "type")), properties), "jar"),
                    resolveValue(text(child(dependency, "classifier")), properties),
                    resolveValue(text(child(dependency, "systemPath")), properties),
                    exclusions(child(dependency, "exclusions"), properties));
            result.put(value.identity(), value);
        }
    }

    /**
     * Reads dependency exclusions.
     *
     * @param exclusions exclusions container
     * @param properties project properties
     * @return excluded GA identities
     */
    private List<String> exclusions(Element exclusions, Map<String, String> properties) {
        List<String> result = new ArrayList<>();
        for (Element exclusion : children(exclusions, "exclusion")) {
            String groupId = resolveValue(text(child(exclusion, "groupId")), properties);
            String artifactId = resolveValue(text(child(exclusion, "artifactId")), properties);
            if (groupId != null && artifactId != null) {
                result.add(groupId + ":" + artifactId);
            }
        }
        return List.copyOf(result);
    }

    /**
     * Resolves source and output directories, including active profile values.
     *
     * @param project POM project element
     * @param profiles active profiles
     * @param parent effective parent
     * @param properties resolved project properties
     * @param directory POM directory
     * @return effective build paths
     * @throws IOException if build.properties cannot be read
     */
    private BuildPaths buildPaths(
            Element project,
            List<Element> profiles,
            EffectivePom parent,
            Map<String, String> properties,
            Path directory) throws IOException {
        Element build = child(project, "build");
        String sourceDirectoryText = build == null ? null : text(child(build, "sourceDirectory"));
        String outputDirectoryText = build == null ? null : text(child(build, "outputDirectory"));
        if (sourceDirectoryText == null || sourceDirectoryText.isBlank()) {
            for (Element profile : profiles) {
                Element profileBuild = child(profile, "build");
                String candidate = profileBuild == null ? null : text(child(profileBuild, "sourceDirectory"));
                if (candidate != null && !candidate.isBlank()) {
                    sourceDirectoryText = candidate;
                }
            }
        }
        if (outputDirectoryText == null || outputDirectoryText.isBlank()) {
            for (Element profile : profiles) {
                Element profileBuild = child(profile, "build");
                String candidate = profileBuild == null ? null : text(child(profileBuild, "outputDirectory"));
                if (candidate != null && !candidate.isBlank()) {
                    outputDirectoryText = candidate;
                }
            }
        }
        boolean explicitSourceDirectory = sourceDirectoryText != null && !sourceDirectoryText.isBlank();
        Path sourceDirectory = resolvePath(
                resolveValue(firstNonBlank(sourceDirectoryText, "src/main/java"), properties),
                directory);
        List<Path> sourceDirectories = new ArrayList<>();
        if (explicitSourceDirectory || Files.isDirectory(sourceDirectory)) {
            sourceDirectories.add(sourceDirectory);
        }
        if (sourceDirectories.isEmpty() && !explicitSourceDirectory && Files.isDirectory(directory.resolve("src"))) {
            sourceDirectories.add(directory.resolve("src").normalize());
        }
        if (sourceDirectories.isEmpty()) {
            sourceDirectories.addAll(readBuildPropertiesSources(directory));
        }
        if (sourceDirectories.isEmpty()) {
            sourceDirectories.add(sourceDirectory);
        }
        Path outputDirectory = resolvePath(
                resolveValue(firstNonBlank(outputDirectoryText, "target/classes"), properties),
                directory);
        return new BuildPaths(List.copyOf(sourceDirectories), outputDirectory);
    }

    /**
     * Reads module directories from the base POM and active profiles.
     *
     * @param project POM project element
     * @param profiles active profiles
     * @param properties resolved project properties
     * @param directory POM directory
     * @return normalized module directories
     */
    private List<Path> moduleDirectories(
            Element project,
            List<Element> profiles,
            Map<String, String> properties,
            Path directory) {
        List<Path> result = new ArrayList<>();
        addModuleDirectories(result, child(project, "modules"), properties, directory);
        for (Element profile : profiles) {
            addModuleDirectories(result, child(profile, "modules"), properties, directory);
        }
        return List.copyOf(new LinkedHashSet<>(result));
    }

    /**
     * Adds module paths from one modules element.
     *
     * @param result module path accumulator
     * @param modules modules element
     * @param properties resolved project properties
     * @param directory POM directory
     */
    private void addModuleDirectories(
            List<Path> result,
            Element modules,
            Map<String, String> properties,
            Path directory) {
        for (Element module : children(modules, "module")) {
            String modulePath = resolveValue(text(module), properties);
            if (modulePath == null || modulePath.isBlank()) {
                continue;
            }
            Path moduleDirectory = directory.resolve(modulePath).normalize();
            if (modulePath.endsWith(".xml")) {
                moduleDirectory = moduleDirectory.getParent();
            }
            result.add(moduleDirectory);
        }
    }

    /**
     * Creates the property map inherited by one POM.
     *
     * @param parent effective parent
     * @param directory current POM directory
     * @return mutable property map
     */
    private Map<String, String> inheritedProperties(EffectivePom parent, Path directory) {
        Map<String, String> properties = new LinkedHashMap<>();
        if (parent != null) {
            properties.putAll(parent.properties);
        }
        for (String name : System.getProperties().stringPropertyNames()) {
            properties.putIfAbsent(name, System.getProperty(name));
        }
        for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
            properties.putIfAbsent("env." + entry.getKey(), entry.getValue());
        }
        properties.put("basedir", directory.toString());
        properties.put("project.basedir", directory.toString());
        properties.put("pom.basedir", directory.toString());
        return properties;
    }

    /**
     * Merges XML property children into a property map.
     *
     * @param properties mutable property map
     * @param propertiesElement properties element
     */
    private void mergeProperties(Map<String, String> properties, Element propertiesElement) {
        for (Element property : children(propertiesElement, null)) {
            properties.put(property.getNodeName(), resolveValue(text(property), properties));
        }
        resolveAllProperties(properties);
    }

    /**
     * Sets the standard Maven project and POM properties for the current POM.
     *
     * @param properties mutable property map
     * @param groupId project groupId
     * @param artifactId project artifactId
     * @param version project version
     * @param directory POM directory
     */
    private void setProjectProperties(
            Map<String, String> properties,
            String groupId,
            String artifactId,
            String version,
            Path directory) {
        properties.put("project.groupId", groupId);
        properties.put("project.artifactId", artifactId);
        properties.put("project.version", version);
        properties.put("pom.groupId", groupId);
        properties.put("pom.artifactId", artifactId);
        properties.put("pom.version", version);
        properties.put("basedir", directory.toString());
        properties.put("project.basedir", directory.toString());
        properties.put("pom.basedir", directory.toString());
    }

    /**
     * Repeatedly expands property references until the map reaches a fixed
     * point or the safety limit is reached.
     *
     * @param properties mutable property map
     */
    private void resolveAllProperties(Map<String, String> properties) {
        for (int pass = 0; pass < MAX_PROPERTY_PASSES; pass++) {
            boolean changed = false;
            for (Map.Entry<String, String> entry : new ArrayList<>(properties.entrySet())) {
                String resolved = resolveValue(entry.getValue(), properties);
                if (!java.util.Objects.equals(resolved, entry.getValue())) {
                    properties.put(entry.getKey(), resolved);
                    changed = true;
                }
            }
            if (!changed) {
                return;
            }
        }
    }

    /**
     * Reports unresolved coordinate expressions after property expansion.
     *
     * @param groupId project groupId
     * @param artifactId project artifactId
     * @param version project version
     * @param pom source POM
     */
    private void reportUnresolvedCoordinates(String groupId, String artifactId, String version, Path pom) {
        if (hasProperty(groupId) || hasProperty(artifactId) || hasProperty(version)) {
            addDiagnostic(
                    MavenDiagnostic.Severity.ERROR,
                    MavenDiagnostic.Kind.UNRESOLVED_PROPERTY,
                    "Unresolved project coordinate in " + pom,
                    groupId + ":" + artifactId + ":" + version,
                    pom);
        }
    }

    /**
     * Expands property references without changing unresolved expressions.
     *
     * @param value raw value
     * @param properties property map
     * @return expanded value, or {@code null}
     */
    private static String resolveValue(String value, Map<String, String> properties) {
        if (value == null) {
            return null;
        }
        String result = value.trim();
        for (int pass = 0; pass < MAX_PROPERTY_PASSES; pass++) {
            Matcher matcher = PROPERTY.matcher(result);
            StringBuffer expanded = new StringBuffer();
            boolean changed = false;
            while (matcher.find()) {
                String replacement = properties.get(matcher.group(1));
                if (replacement != null) {
                    matcher.appendReplacement(expanded, Matcher.quoteReplacement(replacement));
                    changed = true;
                }
            }
            if (!changed) {
                return result;
            }
            matcher.appendTail(expanded);
            result = expanded.toString();
        }
        return result;
    }

    /**
     * Checks whether a value still contains an unresolved property expression.
     *
     * @param value value to inspect
     * @return whether an unresolved expression is present
     */
    private static boolean hasProperty(String value) {
        return value != null && PROPERTY.matcher(value).find();
    }

    /**
     * Resolves a relative or absolute filesystem path.
     *
     * @param value path text
     * @param directory base directory for relative paths
     * @return normalized path
     */
    private static Path resolvePath(String value, Path directory) {
        Path path = Path.of(value);
        return (path.isAbsolute() ? path : directory.resolve(path)).normalize();
    }

    /**
     * Returns the local repository path for a Maven POM coordinate.
     *
     * @param groupId group identifier
     * @param artifactId artifact identifier
     * @param version artifact version
     * @return POM path, or {@code null} when a coordinate is incomplete
     */
    private Path pomPath(String groupId, String artifactId, String version) {
        if (groupId == null || artifactId == null || version == null
                || groupId.isBlank() || artifactId.isBlank() || version.isBlank() || hasProperty(version)) {
            return null;
        }
        return localRepository.resolve(groupId.replace('.', java.io.File.separatorChar))
                .resolve(artifactId)
                .resolve(version)
                .resolve(artifactId + "-" + version + ".pom");
    }

    /**
     * Returns the POM path for a resolved artifact.
     *
     * @param artifact artifact coordinate
     * @return local repository POM path, or {@code null} for unknown versions
     */
    private Path artifactPom(MavenArtifact artifact) {
        return pomPath(artifact.groupId(), artifact.artifactId(), artifact.version());
    }

    /**
     * Reads Eclipse-style build.properties source roots.
     *
     * @param directory project directory
     * @return existing source roots declared by build.properties
     * @throws IOException if build.properties cannot be read
     */
    private static List<Path> readBuildPropertiesSources(Path directory) throws IOException {
        Path buildProperties = directory.resolve("build.properties");
        if (!Files.isRegularFile(buildProperties)) {
            return List.of();
        }
        List<String> logicalLines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : Files.readAllLines(buildProperties)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            if (current.length() > 0) {
                current.append(' ');
            }
            if (trimmed.endsWith("\\")) {
                current.append(trimmed, 0, trimmed.length() - 1);
            } else {
                current.append(trimmed);
                logicalLines.add(current.toString());
                current.setLength(0);
            }
        }
        if (current.length() > 0) {
            logicalLines.add(current.toString());
        }
        for (String line : logicalLines) {
            int equals = line.indexOf('=');
            if (equals < 0 || !"source..".equals(line.substring(0, equals).trim())) {
                continue;
            }
            List<Path> result = new ArrayList<>();
            for (String value : line.substring(equals + 1).split(",")) {
                String source = value.trim();
                if (source.isEmpty()) {
                    continue;
                }
                if (source.endsWith("/")) {
                    source = source.substring(0, source.length() - 1);
                }
                Path path = directory.resolve(source).normalize();
                if (Files.isDirectory(path)) {
                    result.add(path);
                }
            }
            return result;
        }
        return List.of();
    }

    /**
     * Parses a secure, namespace-tolerant XML document.
     *
     * @param input POM input stream
     * @param pom POM path for diagnostics
     * @return parsed document
     * @throws IOException if the document is empty
     * @throws SAXException if XML parsing fails
     * @throws ParserConfigurationException if secure parser setup fails
     */
    private static Document parseDocument(InputStream input, Path pom)
            throws IOException, SAXException, ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        Document document = factory.newDocumentBuilder().parse(input);
        if (document.getDocumentElement() == null) {
            throw new IOException("Empty Maven POM: " + pom);
        }
        return document;
    }

    /**
     * Gets one direct child element by local or qualified name.
     *
     * @param parent parent element
     * @param name requested child name
     * @return matching child, or {@code null}
     */
    private static Element child(Element parent, String name) {
        if (parent == null) {
            return null;
        }
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element element && matches(element, name)) {
                return element;
            }
        }
        return null;
    }

    /**
     * Gets all direct child elements matching an optional name.
     *
     * @param parent parent element
     * @param name requested child name, or {@code null} for every element
     * @return matching children
     */
    private static List<Element> children(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        if (parent == null) {
            return result;
        }
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element element && (name == null || matches(element, name))) {
                result.add(element);
            }
        }
        return result;
    }

    /**
     * Tests an element against a local or qualified XML name.
     *
     * @param element element to inspect
     * @param name expected name
     * @return whether the names match
     */
    private static boolean matches(Element element, String name) {
        return name.equals(element.getNodeName()) || name.equals(element.getLocalName());
    }

    /**
     * Returns trimmed element text.
     *
     * @param element element, possibly {@code null}
     * @return trimmed text, or {@code null}
     */
    private static String text(Element element) {
        return element == null ? null : element.getTextContent().trim();
    }

    /**
     * Returns the first non-blank string.
     *
     * @param value preferred value
     * @param fallback fallback value
     * @return preferred value when non-blank, otherwise fallback
     */
    private static String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * Converts a Maven boolean text value.
     *
     * @param value text value
     * @return parsed boolean
     */
    private static boolean booleanValue(String value) {
        return value != null && Boolean.parseBoolean(value.trim());
    }

    /**
     * Normalizes requested profile identifiers.
     *
     * @param requestedProfiles requested identifiers
     * @return immutable non-blank profile set
     */
    private static Set<String> normalizeProfileIds(Set<String> requestedProfiles) {
        Set<String> result = new LinkedHashSet<>();
        if (requestedProfiles != null) {
            for (String profile : requestedProfiles) {
                if (profile != null && !profile.isBlank()) {
                    result.add(profile.trim());
                }
            }
        }
        return Set.copyOf(result);
    }

    /**
     * Tests whether a dependency should be omitted from the analysis
     * classpath.
     *
     * @param dependency dependency declaration
     * @return whether the dependency is test, POM or otherwise non-classpath
     */
    private static boolean isNonClasspathDependency(DependencySpec dependency) {
        return "test".equals(dependency.scope)
                || "pom".equalsIgnoreCase(dependency.type)
                || "import".equals(dependency.scope);
    }

    /**
     * Tests whether a dependency must be omitted when traversing a dependency
     * POM. System dependencies belong to the declaring project only.
     *
     * @param dependency dependency declaration
     * @return whether the dependency is not propagated transitively
     */
    private static boolean isTransitiveNonClasspathDependency(DependencySpec dependency) {
        return isNonClasspathDependency(dependency) || "system".equals(dependency.scope);
    }

    /**
     * Tests whether a version is an unresolved placeholder.
     *
     * @param version version string
     * @return whether the version is unknown or unresolved
     */
    private static boolean isUnknownVersion(String version) {
        return version == null || version.isBlank() || "unknown".equals(version) || hasProperty(version);
    }

    /**
     * Builds the map key used for dependency mediation.
     *
     * @param artifact artifact
     * @return dependency identity key
     */
    private static String artifactKey(MavenArtifact artifact) {
        return artifact.groupId() + ":" + artifact.artifactId() + ":"
                + (artifact.classifier() == null ? "" : artifact.classifier());
    }

    /**
     * Sorts exclusions into a stable traversal key.
     *
     * @param exclusions exclusion identities
     * @return stable key
     */
    private static String sortedKey(Set<String> exclusions) {
        return String.join(",", new TreeSet<>(exclusions));
    }

    /**
     * Verifies that a relativePath POM matches its declared parent identity.
     *
     * @param pom candidate parent
     * @param groupId expected groupId
     * @param artifactId expected artifactId
     * @param version expected version
     * @return whether all supplied coordinates match
     */
    private static boolean matchesCoordinates(
            EffectivePom pom,
            String groupId,
            String artifactId,
            String version) {
        return pom != null
                && (groupId == null || groupId.isBlank() || groupId.equals(pom.groupId))
                && (artifactId == null || artifactId.isBlank() || artifactId.equals(pom.artifactId))
                && (version == null || version.isBlank() || version.equals(pom.version));
    }

    /**
     * Adds a structured diagnostic to the current load operation.
     *
     * @param severity diagnostic severity
     * @param kind diagnostic category
     * @param message human-readable detail
     * @param coordinate related coordinate, possibly {@code null}
     * @param path related filesystem path, possibly {@code null}
     */
    private void addDiagnostic(
            MavenDiagnostic.Severity severity,
            MavenDiagnostic.Kind kind,
            String message,
            String coordinate,
            Path path) {
        diagnostics.add(new MavenDiagnostic(severity, kind, message, coordinate, path));
    }

    /**
     * Returns an exception message suitable for diagnostics.
     *
     * @param exception exception
     * @return message or exception type
     */
    private static String message(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    /**
     * 不可变的依赖声明：坐标、作用域、类型、classifier、systemPath 和排除项。
     * Immutable dependency declaration.
     *
     * @param groupId group identifier
     * @param artifactId artifact identifier
     * @param version declared version, possibly absent
     * @param scope dependency scope
     * @param type dependency type
     * @param classifier dependency classifier
     * @param systemPath system dependency path
     * @param exclusions excluded transitive GA identities
     */
    private record DependencySpec(
            String groupId,
            String artifactId,
            String version,
            String scope,
            String type,
            String classifier,
            String systemPath,
            List<String> exclusions) {

        /**
         * Returns the group-and-artifact identity.
         *
         * @return GA identity
         */
        private String ga() {
            return groupId + ":" + artifactId;
        }

        /**
         * Returns the declaration identity used for overrides.
         *
         * @return dependency identity
         */
        private String identity() {
            return ga() + ":" + type + ":" + (classifier == null ? "" : classifier);
        }

        /**
         * Returns an immutable exclusion set for artifact traversal.
         *
         * @return excluded GA identities
         */
        private List<String> exclusionKeys() {
            return exclusions == null ? List.of() : exclusions;
        }
    }

    /**
     * 不可变的依赖管理声明：坐标、版本、作用域和类型，可表示 BOM 导入。
     * Immutable dependency-management declaration.
     *
     * @param groupId group identifier
     * @param artifactId artifact identifier
     * @param version declared or inherited version
     * @param scope management scope
     * @param type management type
     */
    private record ManagedDependencySpec(
            String groupId,
            String artifactId,
            String version,
            String scope,
            String type) {

        /**
         * Returns the group-and-artifact identity.
         *
         * @return GA identity
         */
        private String ga() {
            return groupId + ":" + artifactId;
        }

        /**
         * Tests whether this declaration imports a BOM.
         *
         * @return whether type is pom and scope is import
         */
        private boolean isBomImport() {
            return "import".equals(scope) && "pom".equalsIgnoreCase(type);
        }

        /**
         * Formats this managed dependency with a resolved version.
         *
         * @param resolvedVersion resolved version
         * @return Maven coordinate
         */
        private String coordinate(String resolvedVersion) {
            return ga() + ":" + resolvedVersion;
        }
    }

    /**
     * 有效 POM 状态：合并父 POM、profile 和属性后的坐标、打包方式、源码/输出目录、
     * 依赖管理、依赖和子模块目录，供模块与依赖处理使用。
     * Effective POM state used by module and dependency processing.
     */
    private static final class EffectivePom {
        private final Path pom;
        private final Path directory;
        private final String groupId;
        private final String artifactId;
        private final String version;
        private final String packaging;
        private final List<Path> sourceDirectories;
        private final Path outputDirectory;
        private final Map<String, String> properties;
        private final Map<String, String> dependencyManagement;
        private final List<DependencySpec> dependencies;
        private final List<Path> moduleDirectories;

        /**
         * Creates an immutable effective POM.
         *
         * @param pom POM path
         * @param directory POM directory
         * @param groupId group identifier
         * @param artifactId artifact identifier
         * @param version project version
         * @param packaging packaging type
         * @param sourceDirectories source roots
         * @param outputDirectory compiled output directory
         * @param properties effective properties
         * @param dependencyManagement effective dependency management
         * @param dependencies effective dependencies
         * @param moduleDirectories module directories
         */
        private EffectivePom(
                Path pom,
                Path directory,
                String groupId,
                String artifactId,
                String version,
                String packaging,
                List<Path> sourceDirectories,
                Path outputDirectory,
                Map<String, String> properties,
                Map<String, String> dependencyManagement,
                List<DependencySpec> dependencies,
                List<Path> moduleDirectories) {
            this.pom = pom;
            this.directory = directory;
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version;
            this.packaging = packaging;
            this.sourceDirectories = List.copyOf(sourceDirectories);
            this.outputDirectory = outputDirectory;
            this.properties = Map.copyOf(properties);
            this.dependencyManagement = Map.copyOf(dependencyManagement);
            this.dependencies = List.copyOf(dependencies);
            this.moduleDirectories = List.copyOf(moduleDirectories);
        }

        /**
         * Returns the project coordinate.
         *
         * @return groupId:artifactId:version
         */
        private String coordinate() {
            return groupId + ":" + artifactId + ":" + version;
        }

        /**
         * Identifies Eclipse and test-only module packaging.
         *
         * @return whether the module is excluded from main indexing
         */
        private boolean isTestModule() {
            return "eclipse-test-plugin".equals(packaging)
                    || "eclipse-test-feature".equals(packaging)
                    || "test-jar".equals(packaging);
        }
    }

    /**
     * 有效源码根与输出目录的组合。
     * Effective source/output directory pair.
     *
     * @param sourceDirectories source roots
     * @param outputDirectory output directory
     */
    private record BuildPaths(List<Path> sourceDirectories, Path outputDirectory) {
    }

    /**
     * 依赖遍历队列中的一个待展开节点：构件、深度和沿该路径继承的排除集合。
     * A dependency path waiting for transitive expansion.
     *
     * @param artifact dependency artifact
     * @param depth graph depth
     * @param excludedArtifacts exclusions inherited along this path
     */
    private record TraversalNode(MavenArtifact artifact, int depth, Set<String> excludedArtifacts) {
    }

    /**
     * 版本仲裁选中的构件及其选择依据：依赖深度和稳定的声明顺序。
     * Mediated artifact and the path information that selected it.
     *
     * @param artifact selected artifact
     * @param depth dependency depth
     * @param declarationOrder stable encounter order
     */
    private record ArtifactSelection(MavenArtifact artifact, int depth, int declarationOrder) {
    }
}
