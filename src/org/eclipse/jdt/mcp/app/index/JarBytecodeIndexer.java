package org.eclipse.jdt.mcp.app.index;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.eclipse.jdt.mcp.app.maven.MavenArtifact;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/** Indexes class/method declarations and bytecode method calls from one JAR. */
public final class JarBytecodeIndexer {

    public Result index(MavenArtifact artifact, ProjectIndex index) throws IOException {
        Path jar = artifact.file();
        if (jar == null || !Files.isRegularFile(jar)) {
            return new Result(artifact.coordinate(), 0, 0, 0, "artifact file is not available");
        }
        int[] counts = new int[3];
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            jarFile.stream()
                    .filter(entry -> !entry.isDirectory())
                    .filter(entry -> entry.getName().endsWith(".class"))
                    .filter(entry -> !entry.getName().equals("module-info.class"))
                    .forEach(entry -> {
                        try (InputStream input = jarFile.getInputStream(entry)) {
                            ClassReader reader = new ClassReader(input);
                            reader.accept(new Visitor(artifact, index, counts), ClassReader.SKIP_FRAMES);
                            counts[0]++;
                        } catch (IOException | RuntimeException exception) {
                            // One malformed class must not make the rest of a JAR unavailable.
                        }
                    });
        }
        return new Result(artifact.coordinate(), counts[0], counts[1], counts[2], null);
    }

    public record Result(String coordinate, int classCount, int methodCount, int callCount, String warning) {
    }

    private static final class Visitor extends ClassVisitor {
        private final MavenArtifact artifact;
        private final ProjectIndex index;
        private final int[] counts;
        private String owner;

        private Visitor(MavenArtifact artifact, ProjectIndex index, int[] counts) {
            super(Opcodes.ASM9);
            this.artifact = artifact;
            this.index = index;
            this.counts = counts;
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            owner = name;
            String qualifiedName = qualified(name);
            index.addSymbol(new IndexedSymbol(
                    "type:" + name,
                    "TYPE",
                    simpleName(name),
                    qualifiedName,
                    qualifiedName,
                    "bytecode",
                    artifact.coordinate(),
                    artifact.file(),
                    0,
                    0,
                    -1,
                    0,
                    null));
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            String methodId = methodId(owner, name, descriptor);
            index.addSymbol(new IndexedSymbol(
                    methodId,
                    "METHOD",
                    name,
                    methodSignature(owner, name, descriptor),
                    methodSignature(owner, name, descriptor),
                    "bytecode",
                    artifact.coordinate(),
                    artifact.file(),
                    0,
                    0,
                    -1,
                    0,
                    "type:" + owner));
            counts[1]++;
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public void visitMethodInsn(int opcode, String targetOwner, String targetName,
                        String targetDescriptor, boolean isInterface) {
                    String targetId = methodId(targetOwner, targetName, targetDescriptor);
                    index.addSymbol(new IndexedSymbol(
                            targetId,
                            "METHOD",
                            targetName,
                            methodSignature(targetOwner, targetName, targetDescriptor),
                            methodSignature(targetOwner, targetName, targetDescriptor),
                            "bytecode",
                            artifact.coordinate(),
                            artifact.file(),
                            0,
                            0,
                            -1,
                            0,
                            "type:" + targetOwner));
                    index.addCall(new IndexedCall(
                            methodId,
                            targetId,
                            methodSignature(targetOwner, targetName, targetDescriptor),
                            resolution(opcode, isInterface),
                            artifact.file(),
                            0,
                            0,
                            targetOwner.replace('/', '.') + "." + targetName));
                    counts[2]++;
                }

                @Override
                public void visitInvokeDynamicInsn(String name, String descriptor, org.objectweb.asm.Handle bootstrapMethodHandle,
                        Object... bootstrapMethodArguments) {
                    String targetId = "invokedynamic:" + name + descriptor;
                    index.addCall(new IndexedCall(
                            methodId,
                            targetId,
                            name + descriptor,
                            "invokedynamic",
                            artifact.file(),
                            0,
                            0,
                            name));
                    counts[2]++;
                }
            };
        }
    }

    private static String resolution(int opcode, boolean isInterface) {
        if (isInterface || opcode == Opcodes.INVOKEINTERFACE) {
            return "interface-dispatch";
        }
        return "direct-bytecode-call";
    }

    public static String methodId(String owner, String name, String descriptor) {
        return "method:" + owner + "#" + name + descriptor;
    }

    public static String methodSignature(String owner, String name, String descriptor) {
        Type[] arguments = Type.getArgumentTypes(descriptor);
        StringBuilder result = new StringBuilder(qualified(owner)).append('#').append(name).append('(');
        for (int i = 0; i < arguments.length; i++) {
            if (i > 0) {
                result.append(',');
            }
            result.append(typeName(arguments[i]));
        }
        result.append(')');
        if (!"<init>".equals(name)) {
            result.append(':').append(typeName(Type.getReturnType(descriptor)));
        }
        return result.toString();
    }

    private static String qualified(String internalName) {
        return internalName.replace('/', '.');
    }

    private static String simpleName(String internalName) {
        int slash = internalName.lastIndexOf('/');
        return internalName.substring(slash + 1);
    }

    private static String typeName(Type type) {
        return type.getClassName();
    }
}
