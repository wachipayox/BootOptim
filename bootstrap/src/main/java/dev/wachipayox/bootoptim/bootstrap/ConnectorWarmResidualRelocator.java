package dev.wachipayox.bootoptim.bootstrap;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/** Relocates the diagnostic hook into Connector's own package space to avoid a JPMS split package. */
public final class ConnectorWarmResidualRelocator {
    private static final String OLD_HOOK = "dev/wachipayox/bootoptim/bootstrap/ConnectorWarmResidualHooks";
    private static final String NEW_HOOK = "org/sinytra/connector/bootoptim/ConnectorWarmResidualHooks";
    private static final String OLD_ENTRY = OLD_HOOK + ".class";
    private static final String NEW_ENTRY = NEW_HOOK + ".class";
    private static final List<String> PATCHED_CLASSES = List.of(
            "org/sinytra/connector/locator/ConnectorLocator.class",
            "org/sinytra/connector/locator/FabricModsDiscoverer.class",
            "org/sinytra/connector/transformer/jar/FabricJarReader.class",
            "org/sinytra/connector/locator/DependencyResolver.class",
            "org/sinytra/connector/locator/filter/SplitPackageMerger.class",
            "org/sinytra/connector/locator/filter/ForgeModPackageFilter.class",
            "org/sinytra/connector/transformer/jar/JarTransformer.class",
            "org/sinytra/connector/transformer/transform/TransformerUtil.class",
            "org/sinytra/connector/util/ConnectorUtil.class",
            "org/sinytra/connector/ConnectorEarlyLoader.class");

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: ConnectorWarmResidualRelocator <mods-dir>");
        Path connector = findConnector(Path.of(args[0]).toAbsolutePath().normalize());
        URI uri = URI.create("jar:" + connector.toUri());
        try (FileSystem fs = FileSystems.newFileSystem(uri, Map.of())) {
            Path oldHook = fs.getPath("/" + OLD_ENTRY);
            if (!Files.isRegularFile(oldHook)) {
                throw new IllegalStateException("Patched Connector is missing diagnostic hook " + OLD_ENTRY);
            }
            byte[] relocatedHook = relocateClass(Files.readAllBytes(oldHook), true);
            Path newHook = fs.getPath("/" + NEW_ENTRY);
            Files.createDirectories(newHook.getParent());
            Files.write(newHook, relocatedHook);

            int totalReferences = 0;
            for (String entry : PATCHED_CLASSES) {
                Path classPath = fs.getPath("/" + entry);
                if (!Files.isRegularFile(classPath)) throw new IllegalStateException("Patched Connector class missing: " + entry);
                ClassNode node = readNode(Files.readAllBytes(classPath));
                int changed = remapHookReferences(node);
                if (changed == 0) throw new IllegalStateException("No diagnostic hook reference found in patched class " + entry);
                totalReferences += changed;
                Files.write(classPath, writeNode(node));
            }
            Files.delete(oldHook);
            if (Files.exists(oldHook) || !Files.isRegularFile(newHook)) {
                throw new IllegalStateException("Connector diagnostic hook relocation did not complete atomically enough for launch");
            }
            System.out.println("BOOTOPTIM_CONNECTOR_HOOK_RELOCATED package=org.sinytra.connector.bootoptim references=" + totalReferences);
        }
    }

    private static byte[] relocateClass(byte[] input, boolean requireSelfName) {
        ClassNode node = readNode(input);
        if (requireSelfName && !OLD_HOOK.equals(node.name)) {
            throw new IllegalStateException("Unexpected diagnostic hook internal name: " + node.name);
        }
        node.name = NEW_HOOK;
        remapHookReferences(node);
        if (node.outerClass != null && node.outerClass.equals(OLD_HOOK)) node.outerClass = NEW_HOOK;
        return writeNode(node);
    }

    private static int remapHookReferences(ClassNode node) {
        int changed = 0;
        for (MethodNode method : node.methods) {
            if (method.desc.contains(OLD_HOOK)) method.desc = method.desc.replace(OLD_HOOK, NEW_HOOK);
            if (method.signature != null && method.signature.contains(OLD_HOOK)) method.signature = method.signature.replace(OLD_HOOK, NEW_HOOK);
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.owner.equals(OLD_HOOK)) {
                    call.owner = NEW_HOOK;
                    changed++;
                } else if (insn instanceof FieldInsnNode field && field.owner.equals(OLD_HOOK)) {
                    field.owner = NEW_HOOK;
                    changed++;
                } else if (insn instanceof TypeInsnNode typeInsn && typeInsn.desc.equals(OLD_HOOK)) {
                    typeInsn.desc = NEW_HOOK;
                    changed++;
                } else if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Type type && type.getSort() == Type.OBJECT
                        && type.getInternalName().equals(OLD_HOOK)) {
                    ldc.cst = Type.getObjectType(NEW_HOOK);
                    changed++;
                } else if (insn instanceof InvokeDynamicInsnNode indy) {
                    for (int i = 0; i < indy.bsmArgs.length; i++) {
                        Object arg = indy.bsmArgs[i];
                        if (arg instanceof Handle handle && handle.getOwner().equals(OLD_HOOK)) {
                            indy.bsmArgs[i] = new Handle(handle.getTag(), NEW_HOOK, handle.getName(),
                                    handle.getDesc().replace(OLD_HOOK, NEW_HOOK), handle.isInterface());
                            changed++;
                        } else if (arg instanceof Type type && type.getDescriptor().contains(OLD_HOOK)) {
                            indy.bsmArgs[i] = Type.getType(type.getDescriptor().replace(OLD_HOOK, NEW_HOOK));
                            changed++;
                        }
                    }
                }
            }
        }
        return changed;
    }

    private static ClassNode readNode(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static byte[] writeNode(ClassNode node) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static Path findConnector(Path mods) throws IOException {
        if (!Files.isDirectory(mods)) throw new IllegalArgumentException("Missing mods directory: " + mods);
        List<Path> matches = new ArrayList<>();
        try (var paths = Files.list(mods)) {
            for (Path path : paths.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")).toList()) {
                try (JarFile jar = new JarFile(path.toFile())) {
                    if (jar.getJarEntry("org/sinytra/connector/locator/ConnectorLocator.class") != null) matches.add(path);
                }
            }
        }
        if (matches.size() != 1) throw new IllegalStateException("Expected exactly one Connector jar, found " + matches);
        return matches.getFirst();
    }

    private ConnectorWarmResidualRelocator() {}
}
