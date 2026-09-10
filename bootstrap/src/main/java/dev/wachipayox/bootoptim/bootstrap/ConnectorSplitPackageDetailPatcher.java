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
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Second-stage, exact-version Connector diagnostic.
 *
 * <p>This runs after {@link ConnectorWarmResidualPatcher} and before hook relocation. It only adds
 * observations inside the two residuals selected by PR #243: split-package merging and the exclusive
 * body of locateFabricMods. No branch, callback, collection, module, filesystem or failure behavior is
 * replaced. Any bytecode-shape drift fails the offline patch task before Minecraft starts.</p>
 */
public final class ConnectorSplitPackageDetailPatcher {
    private static final String VERSION = ConnectorWarmResidualHooks.CONNECTOR_VERSION;
    private static final String HOOK = "dev/wachipayox/bootoptim/bootstrap/ConnectorWarmResidualHooks";
    private static final String SPLIT = "org/sinytra/connector/locator/filter/SplitPackageMerger.class";
    private static final String LOCATOR = "org/sinytra/connector/locator/ConnectorLocator.class";

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: ConnectorSplitPackageDetailPatcher <mods-dir>");
        Path connector = findConnector(Path.of(args[0]).toAbsolutePath().normalize());
        URI uri = URI.create("jar:" + connector.toUri());
        try (FileSystem fs = FileSystems.newFileSystem(uri, Map.of())) {
            Path split = fs.getPath("/" + SPLIT);
            Path locator = fs.getPath("/" + LOCATOR);
            if (!Files.isRegularFile(split) || !Files.isRegularFile(locator)) {
                throw new IllegalStateException("Exact Connector classes missing for split-package detail patch");
            }
            Files.write(split, patchSplit(Files.readAllBytes(split)));
            Files.write(locator, patchLocator(Files.readAllBytes(locator)));
        }
        System.out.println("BOOTOPTIM_CONNECTOR_SPLIT_DETAIL_PATCH version=" + VERSION + " jar=" + connector.getFileName()
                + " upstream=" + ConnectorWarmResidualHooks.CONNECTOR_COMMIT);
    }

    private static byte[] patchSplit(byte[] input) {
        ClassNode node = readNode(input);
        MethodNode merge = uniqueMethod(node, "mergeSplitPackages");

        instrumentChain(merge,
                "cpw/mods/jarhandling/SecureJar", "from",
                "java/lang/module/ModuleDescriptor", "packages",
                "connector_split_fabric_jar_packages");

        instrumentChain(merge,
                "net/neoforged/neoforgespi/locating/IModFile", "getSecureJar",
                "java/lang/module/ModuleDescriptor", "packages",
                "connector_split_existing_mod_packages");

        int moduleCalls = 0;
        for (MethodNode method : node.methods) {
            for (MethodInsnNode call : matchingCalls(method, "java/lang/Module", "getPackages")) {
                instrumentSingleCallWithReceiver(method, call, "connector_split_loaded_module_packages");
                moduleCalls++;
            }
        }
        if (moduleCalls != 1) {
            throw new IllegalStateException("Connector " + VERSION + " split-package shape drift: Module.getPackages=" + moduleCalls);
        }

        instrumentMethod(uniqueMethod(node, "analyzeJar"), "connector_split_analyze_package", 3);
        return writeNode(node);
    }

    private static byte[] patchLocator(byte[] input) {
        ClassNode node = readNode(input);
        instrumentMethod(uniqueMethod(node, "getPreviouslyDiscoveredMods"), "connector_locate_previous_mod_projection", 0);
        instrumentMethod(uniqueMethod(node, "shouldIgnoreMod"), "connector_locate_should_ignore_mod", 0);
        instrumentMethod(uniqueMethod(node, "handleDuplicateMods"), "connector_locate_duplicate_handling", 0);
        instrumentMethod(uniqueMethod(node, "discoverNestedJarsRecursive"), "connector_locate_nested_discovery", 2);
        instrumentMethod(uniqueMethod(node, "prepareNestedJar"), "connector_locate_nested_prepare", 3);
        return writeNode(node);
    }

    private static InsnList beginWithTopResource(String phase) {
        InsnList list = new InsnList();
        list.add(new InsnNode(Opcodes.DUP));
        list.add(new LdcInsnNode(phase));
        list.add(new InsnNode(Opcodes.SWAP));
        list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "beginScope", "(Ljava/lang/String;Ljava/lang/Object;)V", false));
        return list;
    }

    private static void instrumentChain(MethodNode method, String startOwner, String startName,
            String endOwner, String endName, String phase) {
        List<MethodInsnNode> starts = matchingCalls(method, startOwner, startName);
        if (starts.size() != 1) {
            throw new IllegalStateException("Connector " + VERSION + " expected one " + startOwner + "." + startName
                    + " in " + method.name + ", got " + starts.size());
        }
        MethodInsnNode start = starts.getFirst();
        MethodInsnNode end = null;
        boolean after = false;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn == start) after = true;
            if (after && insn instanceof MethodInsnNode call && call.owner.equals(endOwner) && call.name.equals(endName)) {
                end = call;
                break;
            }
        }
        if (end == null) {
            throw new IllegalStateException("Connector " + VERSION + " could not pair " + startOwner + "." + startName
                    + " with " + endOwner + "." + endName + " in " + method.name);
        }
        method.instructions.insertBefore(start, beginWithTopResource(phase));
        InsnList finish = new InsnList();
        finish.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "endScope", "()V", false));
        method.instructions.insert(end, finish);
    }

    private static void instrumentSingleCallWithReceiver(MethodNode method, MethodInsnNode call, String phase) {
        method.instructions.insertBefore(call, beginWithTopResource(phase));
        InsnList finish = new InsnList();
        finish.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "endScope", "()V", false));
        method.instructions.insert(call, finish);
    }

    private static void instrumentMethod(MethodNode method, String phase, int resourceLocal) {
        InsnList begin = new InsnList();
        begin.add(new LdcInsnNode(phase));
        begin.add(resourceLocal >= 0 ? new VarInsnNode(Opcodes.ALOAD, resourceLocal) : new InsnNode(Opcodes.ACONST_NULL));
        begin.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "beginScope", "(Ljava/lang/String;Ljava/lang/Object;)V", false));
        method.instructions.insert(begin);
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            int opcode = instruction.getOpcode();
            if ((opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) || opcode == Opcodes.ATHROW) {
                InsnList end = new InsnList();
                end.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "endScope", "()V", false));
                method.instructions.insertBefore(instruction, end);
            }
        }
    }

    private static MethodNode uniqueMethod(ClassNode node, String name) {
        List<MethodNode> matches = node.methods.stream().filter(m -> m.name.equals(name)).toList();
        if (matches.size() != 1) throw new IllegalStateException("Expected one " + node.name + "." + name + ", got " + matches.size());
        return matches.getFirst();
    }

    private static List<MethodInsnNode> matchingCalls(MethodNode method, String owner, String name) {
        List<MethodInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode invoke && invoke.owner.equals(owner) && invoke.name.equals(name)) result.add(invoke);
        }
        return result;
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
                    if (jar.getJarEntry(LOCATOR) != null && jar.getJarEntry(SPLIT) != null) matches.add(path);
                }
            }
        }
        if (matches.size() != 1) throw new IllegalStateException("Expected exactly one Connector jar, found " + matches);
        return matches.getFirst();
    }

    private ConnectorSplitPackageDetailPatcher() {}
}
