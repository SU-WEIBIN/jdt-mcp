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

/**
 * 源码分析器：用 JDT 无界面 AST 解析器和绑定解析（开启绑定恢复）遍历 Maven 模块的 main
 * Java 源码，把类型、方法符号及解析出的方法调用写入 {@link ProjectIndex}，并保存源码索引快照。
 * Builds the first useful source-side index using JDT's headless AST API.
 */
public final class JdtSourceAnalyzer {

    /**
     * 遍历所有 Maven 模块的 main 源码，建立源码索引并写入快照，返回分析统计。
     */
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

    /**
     * 用 JDT 解析单个 Java 文件，并把 AST 交给 FileVisitor 处理。
     */
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

    /**
     * 汇总模块输出目录和依赖 JAR，作为 JDT 绑定的类路径。
     */
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

    /**
     * 汇总模块 main 源码根，作为 JDT 的源路径。
     */
    private static List<String> sourcepath(MavenProjectModel model) {
        Set<String> entries = new HashSet<>();
        for (MavenModule module : model.modules()) {
            for (Path sourceRoot : module.mainSourceRoots()) {
                addDirectory(entries, sourceRoot);
            }
        }
        return List.copyOf(entries);
    }

    /**
     * 建立源码根到所属模块坐标的映射，供符号标注使用。
     */
    private static Map<Path, String> sourceRootModules(MavenProjectModel model) {
        Map<Path, String> result = new HashMap<>();
        for (MavenModule module : model.modules()) {
            for (Path root : module.mainSourceRoots()) {
                result.put(root.toAbsolutePath().normalize(), module.coordinate());
            }
        }
        return result;
    }

    /**
     * 目录存在时把其绝对路径加入集合。
     */
    private static void addDirectory(Set<String> entries, Path path) {
        if (path != null && Files.isDirectory(path)) {
            entries.add(path.toAbsolutePath().normalize().toString());
        }
    }

    /**
     * 判断路径是否为 .java 源文件。
     */
    private static boolean isJavaFile(Path path) {
        return Files.isRegularFile(path) && path.getFileName().toString().endsWith(".java");
    }

    /**
     * 返回编译单元的包名，缺省时返回空串。
     */
    private static String packageName(CompilationUnit unit) {
        PackageDeclaration declaration = unit.getPackage();
        return declaration == null ? "" : declaration.getName().getFullyQualifiedName();
    }

    /**
     * 提取异常消息，为空时退化为异常类名。
     */
    private static String message(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    /**
     * 源码分析结果：生成的索引、分析的源文件数、源码根数量和告警列表。
     */
    public record AnalysisResult(ProjectIndex index, int sourceFileCount, List<String> warnings, int sourceRootCount) {
    }

    /**
     * AST 访问器：在遍历一个编译单元时登记类型和方法符号，并记录方法与构造调用的解析结果。
     */
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

        /**
         * 创建 AST 访问器，绑定当前文件和模块上下文。
         */
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

        /**
         * 登记一个类型符号，并压入类型作用域栈。
         */
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

        /**
         * 弹出当前类型作用域。
         */
        private void endType() {
            typeNames.pop();
            typeIds.pop();
        }

        /**
         * 处理类声明：登记类型符号后继续遍历成员。
         */
        @Override
        public boolean visit(TypeDeclaration node) {
            visitType(node.getName(), node.resolveBinding(), node);
            return true;
        }

        /**
         * 结束类声明：退出类型作用域。
         */
        @Override
        public void endVisit(TypeDeclaration node) {
            endType();
        }

        /**
         * 处理枚举声明：登记类型符号后继续遍历成员。
         */
        @Override
        public boolean visit(EnumDeclaration node) {
            visitType(node.getName(), node.resolveBinding(), node);
            return true;
        }

        /**
         * 结束枚举声明：退出类型作用域。
         */
        @Override
        public void endVisit(EnumDeclaration node) {
            endType();
        }

        /**
         * 处理注解类型声明：登记类型符号后继续遍历成员。
         */
        @Override
        public boolean visit(AnnotationTypeDeclaration node) {
            visitType(node.getName(), node.resolveBinding(), node);
            return true;
        }

        /**
         * 结束注解类型声明：退出类型作用域。
         */
        @Override
        public void endVisit(AnnotationTypeDeclaration node) {
            endType();
        }

        /**
         * 处理 record 声明：登记类型符号后继续遍历成员。
         */
        @Override
        public boolean visit(RecordDeclaration node) {
            visitType(node.getName(), node.resolveBinding(), node);
            return true;
        }

        /**
         * 结束 record 声明：退出类型作用域。
         */
        @Override
        public void endVisit(RecordDeclaration node) {
            endType();
        }

        /**
         * 处理方法/构造器声明：登记符号并进入方法作用域。
         */
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

        /**
         * 结束方法声明：退出方法作用域。
         */
        @Override
        public void endVisit(MethodDeclaration node) {
            methodNames.pop();
            methodIds.pop();
        }

        /**
         * 记录普通方法调用。
         */
        @Override
        public boolean visit(MethodInvocation node) {
            addCall(node, node.resolveMethodBinding(), node.getName().getIdentifier());
            return true;
        }

        /**
         * 记录 super 方法调用。
         */
        @Override
        public boolean visit(SuperMethodInvocation node) {
            addCall(node, node.resolveMethodBinding(), node.getName().getIdentifier());
            return true;
        }

        /**
         * 记录 new 表达式触发的构造器调用。
         */
        @Override
        public boolean visit(ClassInstanceCreation node) {
            addCall(node, node.resolveConstructorBinding(), "new " + node.getType());
            return true;
        }

        /**
         * 记录 this(...) 构造器调用。
         */
        @Override
        public boolean visit(ConstructorInvocation node) {
            addCall(node, node.resolveConstructorBinding(), "this(...)");
            return true;
        }

        /**
         * 记录 super(...) 构造器调用。
         */
        @Override
        public boolean visit(SuperConstructorInvocation node) {
            addCall(node, node.resolveConstructorBinding(), "super(...)");
            return true;
        }

        /**
         * 记录一条调用边；目标可解析时同时登记依赖侧方法符号。
         */
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

        /**
         * 根据嵌套类型栈和包名计算限定类型名。
         */
        private String qualifiedTypeName(String simpleName) {
            List<String> names = new ArrayList<>(typeNames);
            java.util.Collections.reverse(names);
            names.add(simpleName);
            String suffix = String.join(".", names);
            return packageName.isBlank() ? suffix : packageName + "." + suffix;
        }

        /**
         * 返回 AST 节点的起始行号。
         */
        private int startLine(ASTNode node) {
            return unit.getLineNumber(Math.max(0, node.getStartPosition()));
        }

        /**
         * 返回 AST 节点的结束行号。
         */
        private int endLine(ASTNode node) {
            int end = Math.max(0, node.getStartPosition() + Math.max(0, node.getLength() - 1));
            return unit.getLineNumber(end);
        }

        /**
         * 由绑定或声明生成方法签名，无法解析时给出占位签名。
         */
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

        /**
         * 取类型绑定的限定名，缺省时用简单名。
         */
        private static String typeName(ITypeBinding binding) {
            if (binding == null) {
                return "?";
            }
            String qualified = binding.getQualifiedName();
            return qualified == null || qualified.isBlank() ? binding.getName() : qualified;
        }

        /**
         * 取类型绑定的内部二进制名（斜杠分隔）。
         */
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

        /**
         * 由方法绑定生成 JVM 风格方法描述符，含 owner、参数和返回类型。
         */
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

        /**
         * 把类型绑定转换为 JVM 描述符（含数组和基本类型）。
         */
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
