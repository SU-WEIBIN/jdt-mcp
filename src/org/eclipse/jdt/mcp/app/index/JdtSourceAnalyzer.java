package org.eclipse.jdt.mcp.app.index;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AnnotationTypeDeclaration;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ConstructorInvocation;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.PackageDeclaration;
import org.eclipse.jdt.core.dom.RecordDeclaration;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SuperConstructorInvocation;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.mcp.app.core.ProjectContext;
import org.eclipse.jdt.mcp.app.maven.MavenArtifact;
import org.eclipse.jdt.mcp.app.maven.MavenModule;
import org.eclipse.jdt.mcp.app.maven.MavenProjectModel;

/** Builds the first useful source-side index using JDT's headless AST API. */
public final class JdtSourceAnalyzer {

    public AnalysisResult analyze(ProjectContext project) throws IOException {
        MavenProjectModel model = project.mavenProject();
        if (model == null) {
            throw new IOException("Maven model is not loaded");
        }
        ProjectIndex index = new ProjectIndex();
        List<String> warnings = new ArrayList<>();
        List<String> classpath = classpath(model);
        List<String> sourcepath = sourcepath(model);
        Map<Path, String> moduleBySourceRoot = sourceRootModules(model);
        int fileCount = 0;
        for (MavenModule module : model.modules()) {
            for (Path sourceRoot : module.mainSourceRoots()) {
                if (!Files.isDirectory(sourceRoot)) {
                    continue;
                }
                try (Stream<Path> files = Files.walk(sourceRoot)) {
                    for (Path file : files.filter(JdtSourceAnalyzer::isJavaFile).toList()) {
                        fileCount++;
                        try {
                            analyzeFile(file, module.coordinate(), classpath, sourcepath, index);
                        } catch (RuntimeException | IOException exception) {
                            warnings.add("Could not analyze " + file + ": " + message(exception));
                        }
                    }
                }
            }
        }
        if (fileCount == 0) {
            warnings.add("No main Java source files were found");
        }
        Path indexFile = project.indexRoot().resolve("source-index.json");
        Files.createDirectories(project.indexRoot());
        index.save(indexFile, project.projectId());
        return new AnalysisResult(index, fileCount, List.copyOf(warnings), moduleBySourceRoot.size());
    }

    private static void analyzeFile(
            Path file,
            String module,
            List<String> classpath,
            List<String> sourcepath,
            ProjectIndex index) throws IOException {
        Path absoluteFile = file.toAbsolutePath().normalize();
        String source = Files.readString(absoluteFile, StandardCharsets.UTF_8);
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setSource(source.toCharArray());
        parser.setUnitName(absoluteFile.toString());
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        parser.setStatementsRecovery(true);
        parser.setIgnoreMethodBodies(false);
        parser.setEnvironment(
                classpath.toArray(String[]::new),
                sourcepath.toArray(String[]::new),
                null,
                true);
        @SuppressWarnings("unchecked")
        Map<String, String> compilerOptions = JavaCore.getOptions();
        compilerOptions.put(JavaCore.COMPILER_SOURCE, JavaCore.VERSION_17);
        compilerOptions.put(JavaCore.COMPILER_COMPLIANCE, JavaCore.VERSION_17);
        compilerOptions.put(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, JavaCore.VERSION_17);
        parser.setCompilerOptions(compilerOptions);
        CompilationUnit unit = (CompilationUnit) parser.createAST(null);
        String packageName = packageName(unit);
        unit.accept(new FileVisitor(unit, absoluteFile, module, packageName, index));
    }

    private static List<String> classpath(MavenProjectModel model) {
        Set<String> entries = new HashSet<>();
        for (MavenModule module : model.modules()) {
            addDirectory(entries, module.outputDirectory());
            for (MavenArtifact dependency : module.dependencies()) {
                if (dependency.classesDirectory() != null) {
                    addDirectory(entries, dependency.classesDirectory());
                }
                if (dependency.file() != null && Files.isRegularFile(dependency.file())) {
                    entries.add(dependency.file().toAbsolutePath().normalize().toString());
                }
            }
        }
        for (MavenArtifact artifact : model.artifacts()) {
            if (artifact.file() != null && Files.isRegularFile(artifact.file())) {
                entries.add(artifact.file().toAbsolutePath().normalize().toString());
            }
        }
        return List.copyOf(entries);
    }

    private static List<String> sourcepath(MavenProjectModel model) {
        Set<String> entries = new HashSet<>();
        for (MavenModule module : model.modules()) {
            for (Path sourceRoot : module.mainSourceRoots()) {
                addDirectory(entries, sourceRoot);
            }
        }
        return List.copyOf(entries);
    }

    private static Map<Path, String> sourceRootModules(MavenProjectModel model) {
        Map<Path, String> result = new HashMap<>();
        for (MavenModule module : model.modules()) {
            for (Path root : module.mainSourceRoots()) {
                result.put(root.toAbsolutePath().normalize(), module.coordinate());
            }
        }
        return result;
    }

    private static void addDirectory(Set<String> entries, Path path) {
        if (path != null && Files.isDirectory(path)) {
            entries.add(path.toAbsolutePath().normalize().toString());
        }
    }

    private static boolean isJavaFile(Path path) {
        return Files.isRegularFile(path) && path.getFileName().toString().endsWith(".java");
    }

    private static String packageName(CompilationUnit unit) {
        PackageDeclaration declaration = unit.getPackage();
        return declaration == null ? "" : declaration.getName().getFullyQualifiedName();
    }

    private static String message(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    public record AnalysisResult(ProjectIndex index, int sourceFileCount, List<String> warnings, int sourceRootCount) {
    }

    private static final class FileVisitor extends ASTVisitor {
        private final CompilationUnit unit;
        private final Path file;
        private final String module;
        private final String packageName;
        private final ProjectIndex index;
        private final Deque<String> typeIds = new ArrayDeque<>();
        private final Deque<String> typeNames = new ArrayDeque<>();
        private final Deque<String> methodIds = new ArrayDeque<>();
        private final Deque<String> methodNames = new ArrayDeque<>();

        private FileVisitor(
                CompilationUnit unit,
                Path file,
                String module,
                String packageName,
                ProjectIndex index) {
            this.unit = unit;
            this.file = file;
            this.module = module;
            this.packageName = packageName;
            this.index = index;
        }

        private void visitType(SimpleName name, ITypeBinding binding, ASTNode node) {
            String fallbackName = qualifiedTypeName(name.getIdentifier());
            String qualifiedName = binding == null || binding.getQualifiedName() == null
                    || binding.getQualifiedName().isBlank() ? fallbackName : binding.getQualifiedName();
            String typeIdentity = internalTypeName(binding);
            String id = typeIdentity == null
                    ? "type:source:" + file + ":" + node.getStartPosition()
                    : "type:" + typeIdentity;
            IndexedSymbol symbol = new IndexedSymbol(
                    id,
                    "TYPE",
                    name.getIdentifier(),
                    qualifiedName,
                    qualifiedName,
                    "project",
                    module,
                    file,
                    startLine(node),
                    endLine(node),
                    node.getStartPosition(),
                    node.getLength(),
                    typeIds.peek());
            index.addSymbol(symbol);
            typeIds.push(id);
            typeNames.push(name.getIdentifier());
        }

        private void endType() {
            typeNames.pop();
            typeIds.pop();
        }

        @Override
        public boolean visit(TypeDeclaration node) {
            visitType(node.getName(), node.resolveBinding(), node);
            return true;
        }

        @Override
        public void endVisit(TypeDeclaration node) {
            endType();
        }

        @Override
        public boolean visit(EnumDeclaration node) {
            visitType(node.getName(), node.resolveBinding(), node);
            return true;
        }

        @Override
        public void endVisit(EnumDeclaration node) {
            endType();
        }

        @Override
        public boolean visit(AnnotationTypeDeclaration node) {
            visitType(node.getName(), node.resolveBinding(), node);
            return true;
        }

        @Override
        public void endVisit(AnnotationTypeDeclaration node) {
            endType();
        }

        @Override
        public boolean visit(RecordDeclaration node) {
            visitType(node.getName(), node.resolveBinding(), node);
            return true;
        }

        @Override
        public void endVisit(RecordDeclaration node) {
            endType();
        }

        @Override
        public boolean visit(MethodDeclaration node) {
            IMethodBinding binding = node.resolveBinding();
            String owner = typeIds.peek();
            String fallbackOwner = typeNames.isEmpty() ? packageName : qualifiedTypeName(typeNames.peek());
            String signature = methodSignature(binding, fallbackOwner, node);
            String identity = methodDescriptor(binding);
            String id = identity == null
                    ? "method:source:" + file + ":" + node.getStartPosition()
                    : "method:" + identity;
            String name = node.isConstructor() ? "<init>" : node.getName().getIdentifier();
            index.addSymbol(new IndexedSymbol(
                    id,
                    "METHOD",
                    name,
                    signature,
                    signature,
                    "project",
                    module,
                    file,
                    startLine(node),
                    endLine(node),
                    node.getStartPosition(),
                    node.getLength(),
                    owner));
            methodIds.push(id);
            methodNames.push(name);
            return true;
        }

        @Override
        public void endVisit(MethodDeclaration node) {
            methodNames.pop();
            methodIds.pop();
        }

        @Override
        public boolean visit(MethodInvocation node) {
            addCall(node, node.resolveMethodBinding(), node.getName().getIdentifier());
            return true;
        }

        @Override
        public boolean visit(SuperMethodInvocation node) {
            addCall(node, node.resolveMethodBinding(), node.getName().getIdentifier());
            return true;
        }

        @Override
        public boolean visit(ClassInstanceCreation node) {
            addCall(node, node.resolveConstructorBinding(), "new " + node.getType());
            return true;
        }

        @Override
        public boolean visit(ConstructorInvocation node) {
            addCall(node, node.resolveConstructorBinding(), "this(...)");
            return true;
        }

        @Override
        public boolean visit(SuperConstructorInvocation node) {
            addCall(node, node.resolveConstructorBinding(), "super(...)");
            return true;
        }

        private void addCall(ASTNode node, IMethodBinding binding, String expression) {
            String callerId = methodIds.peek();
            if (callerId == null) {
                return;
            }
            String targetSignature = methodSignature(binding, null, null);
            String targetId;
            String identity = methodDescriptor(binding);
            if (identity != null) {
                targetId = "method:" + identity;
                index.addSymbol(new IndexedSymbol(
                        targetId,
                        "METHOD",
                        binding.getName(),
                        targetSignature,
                        targetSignature,
                        "dependency",
                        binding.getDeclaringClass() == null ? null : binding.getDeclaringClass().getQualifiedName(),
                        null,
                        0,
                        0,
                        -1,
                        0,
                        null));
            } else {
                targetSignature = targetSignature == null ? expression : targetSignature;
                targetId = "unresolved:" + targetSignature;
            }
            index.addCall(new IndexedCall(
                    callerId,
                    targetId,
                    targetSignature,
                    binding == null ? "unresolved" : "binding",
                    file,
                    startLine(node),
                    unit.getColumnNumber(node.getStartPosition()),
                    expression));
        }

        private String qualifiedTypeName(String simpleName) {
            List<String> names = new ArrayList<>(typeNames);
            java.util.Collections.reverse(names);
            names.add(simpleName);
            String suffix = String.join(".", names);
            return packageName.isBlank() ? suffix : packageName + "." + suffix;
        }

        private int startLine(ASTNode node) {
            return unit.getLineNumber(Math.max(0, node.getStartPosition()));
        }

        private int endLine(ASTNode node) {
            int end = Math.max(0, node.getStartPosition() + Math.max(0, node.getLength() - 1));
            return unit.getLineNumber(end);
        }

        private static String methodSignature(IMethodBinding binding, String fallbackOwner, MethodDeclaration declaration) {
            if (binding != null) {
                ITypeBinding owner = binding.getDeclaringClass();
                String ownerName = owner == null ? fallbackOwner : owner.getQualifiedName();
                StringBuilder result = new StringBuilder(ownerName == null ? "" : ownerName)
                        .append('#').append(binding.getName()).append('(');
                ITypeBinding[] parameters = binding.getParameterTypes();
                for (int i = 0; i < parameters.length; i++) {
                    if (i > 0) {
                        result.append(',');
                    }
                    result.append(typeName(parameters[i]));
                }
                result.append(')');
                if (!binding.isConstructor()) {
                    result.append(':').append(typeName(binding.getReturnType()));
                }
                return result.toString();
            }
            if (declaration == null) {
                return fallbackOwner == null ? null : fallbackOwner + "#?";
            }
            String owner = fallbackOwner == null ? "" : fallbackOwner;
            StringBuilder result = new StringBuilder(owner).append('#')
                    .append(declaration.isConstructor() ? "<init>" : declaration.getName().getIdentifier()).append('(');
            for (int i = 0; i < declaration.parameters().size(); i++) {
                if (i > 0) {
                    result.append(',');
                }
                result.append(declaration.parameters().get(i));
            }
            return result.append(')').toString();
        }

        private static String typeName(ITypeBinding binding) {
            if (binding == null) {
                return "?";
            }
            String qualified = binding.getQualifiedName();
            return qualified == null || qualified.isBlank() ? binding.getName() : qualified;
        }

        private static String internalTypeName(ITypeBinding binding) {
            if (binding == null) {
                return null;
            }
            ITypeBinding erasure = binding.getErasure();
            String binaryName = erasure.getBinaryName();
            if (binaryName == null || binaryName.isBlank()) {
                binaryName = erasure.getQualifiedName();
            }
            return binaryName == null || binaryName.isBlank()
                    ? null
                    : binaryName.replace('.', '/');
        }

        private static String methodDescriptor(IMethodBinding binding) {
            if (binding == null || binding.getDeclaringClass() == null) {
                return null;
            }
            String owner = internalTypeName(binding.getDeclaringClass());
            if (owner == null) {
                return null;
            }
            StringBuilder descriptor = new StringBuilder(owner).append('#')
                    .append(binding.isConstructor() ? "<init>" : binding.getName()).append('(');
            for (ITypeBinding parameter : binding.getParameterTypes()) {
                descriptor.append(typeDescriptor(parameter));
            }
            descriptor.append(')').append(binding.isConstructor() ? 'V' : typeDescriptor(binding.getReturnType()));
            return descriptor.toString();
        }

        private static String typeDescriptor(ITypeBinding binding) {
            if (binding == null) {
                return "Ljava/lang/Object;";
            }
            ITypeBinding erasure = binding.getErasure();
            if (erasure.isPrimitive()) {
                return switch (erasure.getName()) {
                case "boolean" -> "Z";
                case "byte" -> "B";
                case "char" -> "C";
                case "short" -> "S";
                case "int" -> "I";
                case "long" -> "J";
                case "float" -> "F";
                case "double" -> "D";
                case "void" -> "V";
                default -> "Ljava/lang/Object;";
                };
            }
            if (erasure.isArray()) {
                return "[" + typeDescriptor(erasure.getComponentType());
            }
            String name = internalTypeName(erasure);
            return name == null ? "Ljava/lang/Object;" : "L" + name + ";";
        }
    }
}
