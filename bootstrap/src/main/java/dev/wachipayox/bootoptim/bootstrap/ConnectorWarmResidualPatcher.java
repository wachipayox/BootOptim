package dev.wachipayox.bootoptim.bootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.jar.Attributes;
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
 * Offline, exact-version patcher used only by the hosted Connector warm-residual diagnostic.
 *
 * <p>It mutates the copied exact-pack Connector jar before ModLauncher starts. The patch is fail-closed on the
 * Connector version plus exact method/call-site shapes and only inserts timing/cache-state observations. It never
 * changes a Connector branch, return value, exception, callback, cache decision, filesystem operation or thread.</p>
 */
public final class ConnectorWarmResidualPatcher {
    private static final String VERSION = ConnectorWarmResidualHooks.CONNECTOR_VERSION;
    private static final String HOOK = "dev/wachipayox/bootoptim/bootstrap/ConnectorWarmResidualHooks";
    private static final String HOOK_ENTRY = HOOK + ".class";

    private static final String LOCATOR = "org/sinytra/connector/locator/ConnectorLocator.class";
    private static final String DISCOVERER = "org/sinytra/connector/locator/FabricModsDiscoverer.class";
    private static final String METADATA = "org/sinytra/connector/transformer/jar/FabricJarReader.class";
    private static final String DEPENDENCY = "org/sinytra/connector/locator/DependencyResolver.class";
    private static final String SPLIT = "org/sinytra/connector/locator/filter/SplitPackageMerger.class";
    private static final String PACKAGE_FILTER = "org/sinytra/connector/locator/filter/ForgeModPackageFilter.class";
    private static final String JAR_TRANSFORMER = "org/sinytra/connector/transformer/jar/JarTransformer.class";
    private static final String TRANSFORMER_UTIL = "org/sinytra/connector/transformer/transform/TransformerUtil.class";
    private static final String CONNECTOR_UTIL = "org/sinytra/connector/util/ConnectorUtil.class";
    private static final String EARLY_LOADER = "org/sinytra/connector/ConnectorEarlyLoader.class";

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: ConnectorWarmResidualPatcher <mods-dir>");
        Path mods = Path.of(args[0]).toAbsolutePath().normalize();
        Path connector = findConnector(mods);
        verifyVersion(connector);
        verifyUnsigned(connector);

        Map<String, byte[]> patched = new HashMap<>();
        try (JarFile jar = new JarFile(connector.toFile())) {
            patched.put(LOCATOR, patchLocator(read(jar, LOCATOR)));
            patched.put(DISCOVERER, patchSingleScope(read(jar, DISCOVERER), "scanFabricMods",
                    "connector_fabric_candidate_scan", -1));
            patched.put(METADATA, patchSingleScope(read(jar, METADATA), "readModMetadata",
                    "connector_fabric_metadata", 0));
            patched.put(DEPENDENCY, patchSingleScope(read(jar, DEPENDENCY), "resolveDependencies",
                    "connector_dependency_resolution", -1));
            patched.put(SPLIT, patchSingleScope(read(jar, SPLIT), "mergeSplitPackages",
                    "connector_split_package_merge", -1));
            patched.put(PACKAGE_FILTER, patchSingleScope(read(jar, PACKAGE_FILTER), "filterPackages",
                    "connector_forge_package_filter", -1));
            patched.put(JAR_TRANSFORMER, patchJarTransformer(read(jar, JAR_TRANSFORMER)));
            patched.put(TRANSFORMER_UTIL, patchCacheUtil(read(jar, TRANSFORMER_UTIL),
                    "org/sinytra/connector/transformer/transform/TransformerUtil$CacheFile", "transform"));
            patched.put(CONNECTOR_UTIL, patchCacheUtil(read(jar, CONNECTOR_UTIL),
                    "org/sinytra/connector/util/ConnectorUtil$CacheFile", "nested_extract"));
            patched.put(EARLY_LOADER, patchSingleScope(read(jar, EARLY_LOADER), "addConnectorModPath",
                    "connector_original_path_callback", 0));
        }

        byte[] hooks;
        try (InputStream input = ConnectorWarmResidualHooks.class.getResourceAsStream("ConnectorWarmResidualHooks.class")) {
            if (input == null) throw new IllegalStateException("Missing compiled ConnectorWarmResidualHooks.class");
            hooks = input.readAllBytes();
        }

        URI uri = URI.create("jar:" + connector.toUri());
        try (FileSystem fs = FileSystems.newFileSystem(uri, Map.of())) {
            for (Map.Entry<String, byte[]> entry : patched.entrySet()) {
                Files.write(fs.getPath("/" + entry.getKey()), entry.getValue());
            }
            Path hookPath = fs.getPath("/" + HOOK_ENTRY);
            Files.createDirectories(hookPath.getParent());
            Files.write(hookPath, hooks);
        }
        System.out.println("BOOTOPTIM_CONNECTOR_PATCH version=" + VERSION + " jar=" + connector.getFileName()
                + " classes=" + patched.size() + " upstream=" + ConnectorWarmResidualHooks.CONNECTOR_COMMIT);
    }

    private static byte[] patchLocator(byte[] input) {
        ClassNode node = readNode(input);
        requireCallCount(node, "scanMods", "org/sinytra/connector/locator/ConnectorLocator", "locateFabricMods", 1);
        // javac duplicates this exact finally body across the normal/early-return/exception exits.
        requireCallCount(node, "scanMods", "org/sinytra/connector/locator/filter/ForgeModPackageFilter", "filterPackages", 4);
        requireCallCount(node, "scanMods", "org/sinytra/connector/locator/ConnectorLocator", "loadEmbeddedJars", 4);
        requireCallCount(node, "locateFabricMods", "org/sinytra/connector/locator/DependencyResolver", "resolveDependencies", 1);
        requireCallCount(node, "locateFabricMods", "org/sinytra/connector/transformer/jar/JarTransformer", "transform", 1);
        requireCallCount(node, "locateFabricMods", "org/sinytra/connector/locator/filter/SplitPackageMerger", "mergeSplitPackages", 1);
        instrumentUniqueMethod(node, "scanMods", "connector_dependency_locator_callback", -1);
        instrumentUniqueMethod(node, "locateFabricMods", "connector_locate_fabric_mods", -1);
        instrumentUniqueMethod(node, "createConnectorModFile", "connector_fresh_modfile_create", -1);
        instrumentUniqueMethod(node, "loadEmbeddedJars", "connector_embedded_jarjar_callback", -1);
        return writeNode(node);
    }

    private static byte[] patchJarTransformer(byte[] input) {
        ClassNode node = readNode(input);
        requireCallCount(node, "cacheTransformableJar", "org/sinytra/connector/transformer/jar/FabricJarReader", "readModMetadata", 1);
        requireCallCount(node, "cacheTransformableJar", "org/sinytra/connector/transformer/transform/TransformerUtil", "getCached", 1);
        requireCallCount(node, "cacheTransformableJar", "org/sinytra/connector/transformer/jar/JarTransformer", "getModuleName", 1);
        instrumentUniqueMethod(node, "cacheTransformableJar", "connector_cache_transformable_jar", 1);
        instrumentUniqueMethod(node, "getModuleName", "connector_module_descriptor", 0);
        instrumentUniqueMethod(node, "transform", "connector_transform_dispatch", -1);
        return writeNode(node);
    }

    private static byte[] patchCacheUtil(byte[] input, String cacheFileOwner, String kind) {
        ClassNode node = readNode(input);
        MethodNode method = uniqueMethod(node, "getCached");
        int reads = countCalls(method, "java/nio/file/Files", "readAllBytes");
        int shaFactories = countCalls(method, "com/google/common/hash/Hashing", "sha256");
        int hashes = countCalls(method, "com/google/common/hash/HashFunction", "hashBytes");
        int exists = countCalls(method, "java/nio/file/Files", "exists");
        int sidecarReads = countCalls(method, "java/nio/file/Files", "readString");
        if (reads != 1 || shaFactories != 1 || hashes != 1 || exists != 2 || sidecarReads != 1) {
            throw new IllegalStateException("Connector " + VERSION + " cache shape drift in " + node.name
                    + ": readAllBytes=" + reads + " sha256=" + shaFactories + " hashBytes=" + hashes
                    + " exists=" + exists + " readString=" + sidecarReads);
        }
        instrumentMethod(method, kind.equals("transform") ? "connector_transform_cache_validation"
                : "connector_nested_cache_validation", 0);
        instrumentCall(method, "java/nio/file/Files", "readAllBytes",
                kind.equals("transform") ? "connector_transform_cache_read_all_bytes" : "connector_nested_cache_read_all_bytes", 0);
        instrumentSpan(method, "com/google/common/hash/Hashing", "sha256",
                "com/google/common/hash/HashFunction", "hashBytes",
                kind.equals("transform") ? "connector_transform_cache_sha256" : "connector_nested_cache_sha256", 0);

        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction.getOpcode() != Opcodes.ARETURN) continue;
            InsnList probe = new InsnList();
            probe.add(new InsnNode(Opcodes.DUP));
            probe.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, cacheFileOwner, "isUpToDate", "()Z", false));
            probe.add(new VarInsnNode(Opcodes.ALOAD, 0));
            probe.add(new LdcInsnNode(kind));
            probe.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "cacheResult",
                    "(ZLjava/lang/Object;Ljava/lang/String;)V", false));
            method.instructions.insertBefore(instruction, probe);
        }
        return writeNode(node);
    }

    private static byte[] patchSingleScope(byte[] input, String method, String phase, int resourceLocal) {
        ClassNode node = readNode(input);
        instrumentUniqueMethod(node, method, phase, resourceLocal);
        return writeNode(node);
    }

    private static void instrumentUniqueMethod(ClassNode node, String name, String phase, int resourceLocal) {
        instrumentMethod(uniqueMethod(node, name), phase, resourceLocal);
    }

    private static void instrumentMethod(MethodNode method, String phase, int resourceLocal) {
        InsnList begin = scopeBegin(phase, resourceLocal);
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

    private static void instrumentCall(MethodNode method, String owner, String name, String phase, int resourceLocal) {
        List<MethodInsnNode> calls = matchingCalls(method, owner, name);
        if (calls.size() != 1) throw new IllegalStateException("Expected one " + owner + "." + name + " in " + method.name + ", got " + calls.size());
        MethodInsnNode call = calls.getFirst();
        method.instructions.insertBefore(call, scopeBegin(phase, resourceLocal));
        InsnList end = new InsnList();
        end.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "endScope", "()V", false));
        method.instructions.insert(call, end);
    }

    private static void instrumentSpan(MethodNode method, String startOwner, String startName,
            String endOwner, String endName, String phase, int resourceLocal) {
        List<MethodInsnNode> starts = matchingCalls(method, startOwner, startName);
        List<MethodInsnNode> ends = matchingCalls(method, endOwner, endName);
        if (starts.size() != 1 || ends.size() != 1) {
            throw new IllegalStateException("Expected one hash span in " + method.name + ", got " + starts.size() + "/" + ends.size());
        }
        method.instructions.insertBefore(starts.getFirst(), scopeBegin(phase, resourceLocal));
        InsnList end = new InsnList();
        end.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "endScope", "()V", false));
        method.instructions.insert(ends.getFirst(), end);
    }

    private static InsnList scopeBegin(String phase, int resourceLocal) {
        InsnList list = new InsnList();
        list.add(new LdcInsnNode(phase));
        list.add(resourceLocal >= 0 ? new VarInsnNode(Opcodes.ALOAD, resourceLocal) : new InsnNode(Opcodes.ACONST_NULL));
        list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "beginScope",
                "(Ljava/lang/String;Ljava/lang/Object;)V", false));
        return list;
    }

    private static MethodNode uniqueMethod(ClassNode node, String name) {
        List<MethodNode> matches = node.methods.stream().filter(m -> m.name.equals(name)).toList();
        if (matches.size() != 1) throw new IllegalStateException("Expected one " + node.name + "." + name + ", got " + matches.size());
        return matches.getFirst();
    }

    private static void requireCallCount(ClassNode node, String methodName, String owner, String callName, int expected) {
        MethodNode method = uniqueMethod(node, methodName);
        int actual = countCalls(method, owner, callName);
        if (actual != expected) throw new IllegalStateException("Connector " + VERSION + " shape drift: " + node.name + "." + methodName
                + " expected " + expected + " call(s) to " + owner + "." + callName + " but found " + actual);
    }

    private static int countCalls(MethodNode method, String owner, String name) {
        return matchingCalls(method, owner, name).size();
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

    private static byte[] read(JarFile jar, String entry) throws IOException {
        var zipEntry = jar.getJarEntry(entry);
        if (zipEntry == null) throw new IllegalStateException("Exact Connector class missing: " + entry);
        try (InputStream input = jar.getInputStream(zipEntry)) {
            return input.readAllBytes();
        }
    }

    private static Path findConnector(Path mods) throws IOException {
        if (!Files.isDirectory(mods)) throw new IllegalArgumentException("Missing mods directory: " + mods);
        List<Path> matches = new ArrayList<>();
        try (var paths = Files.list(mods)) {
            for (Path path : paths.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")).toList()) {
                try (JarFile jar = new JarFile(path.toFile())) {
                    if (jar.getJarEntry(LOCATOR) != null) matches.add(path);
                }
            }
        }
        if (matches.size() != 1) throw new IllegalStateException("Expected exactly one Connector jar, found " + matches);
        return matches.getFirst();
    }

    private static void verifyVersion(Path connector) throws IOException {
        String filename = connector.getFileName().toString();
        String implementation = null;
        try (JarFile jar = new JarFile(connector.toFile())) {
            if (jar.getManifest() != null) implementation = jar.getManifest().getMainAttributes().getValue(Attributes.Name.IMPLEMENTATION_VERSION);
        }
        if (!filename.contains(VERSION) && !VERSION.equals(implementation)) {
            throw new IllegalStateException("Refusing Connector diagnostic patch: expected " + VERSION
                    + " but jar=" + filename + " Implementation-Version=" + implementation);
        }
    }

    private static void verifyUnsigned(Path connector) throws IOException {
        try (JarFile jar = new JarFile(connector.toFile())) {
            boolean signed = jar.stream().map(e -> e.getName().toUpperCase(Locale.ROOT))
                    .anyMatch(n -> n.startsWith("META-INF/") && (n.endsWith(".SF") || n.endsWith(".RSA") || n.endsWith(".DSA") || n.endsWith(".EC")));
            if (signed) throw new IllegalStateException("Refusing to instrument signed Connector jar: " + connector);
            if (jar.getJarEntry(HOOK_ENTRY) != null) throw new IllegalStateException("Connector jar already contains BootOptim diagnostic hooks: " + connector);
        }
    }

    private ConnectorWarmResidualPatcher() {}
}
