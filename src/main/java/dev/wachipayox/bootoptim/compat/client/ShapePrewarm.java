package dev.wachipayox.bootoptim.compat.client;

import dev.wachipayox.bootoptim.profiling.StartupProfiler;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.neoforged.fml.loading.FMLLoader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Initializes a small audited set of shape tables after vanilla Bootstrap returns. */
public final class ShapePrewarm {
    private static final boolean ENABLED = Boolean.parseBoolean(
            System.getProperty("boot_optim.shapePrewarm", "true"));
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static final Map<String, String> VALIDATED = new ConcurrentHashMap<>();
    private static final String SIM = "dev/simulated_team/simulated/index/SimBlockShapes";
    private static final String PROPULSION = "dev/propulsionteam/propulsionsimulated/registries/PropulsionShapes";
    private static final String TFMG = "com/drmangotea/tfmg/base/TFMGShapes";
    private static final String ALTERNATOR = "com/mrh0/createaddition/blocks/alternator/AlternatorBlock";
    private static final String CAS = "com/mrh0/createaddition/shapes/CAShapes";
    private static final String ALL_SHAPES = "com/simibubi/create/AllShapes";
    private static final String STATE_HOLDER = "net/minecraft/world/level/block/state/StateHolder";
    private static final String STATE_DEFINITION = "net/minecraft/world/level/block/state/StateDefinition";
    private static final String FERRITE = "malte0811/ferritecore/";
    private static final String KINETIC = "com/simibubi/create/content/kinetics/base/";
    private static final String VOXEL_SHAPER = "net/createmod/catnip/math/VoxelShaper";
    private static final String AUDIT_INTERFACE =
            "dev/wachipayox/bootoptim/mixin/client/ShapePrewarmInterfaceAuditMixin";
    private static final List<String> SHARED = List.of(VOXEL_SHAPER,
            VOXEL_SHAPER + "$DefaultRotationValues", VOXEL_SHAPER + "$HorizontalRotationValues",
            "net/createmod/catnip/math/VecHelper", "net/createmod/catnip/data/Iterate");
    private static final List<Target> TARGETS = List.of(
            new Target("simulated", SIM, List.of(SIM + "$Builder")),
            new Target("createpropulsion", PROPULSION, List.of(PROPULSION + "$ShapeBuilder")),
            new Target("tfmg", TFMG, List.of(TFMG + "$Builder")),
            new Target("createaddition", ALTERNATOR, List.of(CAS, CAS + "$Builder",
                    KINETIC + "DirectionalKineticBlock", KINETIC + "KineticBlock", KINETIC + "IRotate",
                    "com/simibubi/create/content/equipment/wrench/IWrenchable",
                    "com/simibubi/create/foundation/block/IBE")));

    private ShapePrewarm() {}

    static boolean enabled() {
        return ENABLED;
    }

    private record Target(String modId, String owner, List<String> helpers) {
        String mechanism() {
            return "shape-prewarm-" + modId;
        }
    }

    public static void afterBootstrap() {
        if (!ENABLED || !STARTED.compareAndSet(false, true)) {
            return;
        }
        var loading = FMLLoader.getLoadingModList();
        if (loading == null) {
            return;
        }
        for (Target target : TARGETS) {
            if (loading.getModFileById(target.modId) != null) {
                Thread.ofPlatform().daemon().name(target.mechanism()).start(() -> prewarm(target));
            }
        }
        if (loading.getModFileById("create") != null
                && Boolean.parseBoolean(System.getProperty("boot_optim.allShapesPrewarm", "true"))) {
            var helpers = Contracts.BY_OWNER.keySet().stream().filter(owner ->
                    owner.startsWith(FERRITE) || owner.startsWith("net/minecraft/world/level/block/")
                    || owner.equals(ALL_SHAPES + "$Builder")
                    || owner.endsWith("/ChuteShapes") || owner.endsWith("/TrackVoxelShapes")).sorted().toList();
            var target = new Target("create", ALL_SHAPES, helpers);
            Thread.ofPlatform().daemon().name(target.mechanism()).start(() -> prewarm(target));
        }
    }

    private static void prewarm(Target target) {
        String checked = target.owner;
        try {
            for (String owner : SHARED) {
                checked = owner;
                verifyLoaded(owner);
            }
            for (String owner : target.helpers) {
                checked = owner;
                verifyLoaded(owner);
            }
            checked = target.owner;
            verifyLoaded(target.owner);
            if (target.owner.equals(ALL_SHAPES) && !pistonTableReady()) {
                CompatLog.fallback(target.mechanism(), target.modId, "unexpected_piston_state_table");
                return;
            }
        } catch (Exception | LinkageError changed) {
            String reason = VALIDATED.getOrDefault(checked, "class_not_observed");
            if (reason.isEmpty()) {
                reason = "piston_state_probe_failed";
            }
            CompatLog.fallback(target.mechanism(), target.modId, reason + " owner=" + checked);
            return;
        }
        StartupProfiler.markPhase(target.mechanism() + "_begin");
        // All verification precedes initialization. A JVM class whose initializer throws cannot be retried.
        try {
            Class.forName(target.owner.replace('/', '.'), true, ShapePrewarm.class.getClassLoader());
        } catch (ClassNotFoundException impossibleAfterVerification) {
            CompatLog.fallback(target.mechanism(), target.modId, "shape_class_disappeared");
            return;
        }
        StartupProfiler.markPhase(target.mechanism() + "_end");
        CompatLog.active(target.mechanism(), target.modId);
    }

    private static boolean pistonTableReady() throws ReflectiveOperationException {
        var piston = Blocks.PISTON_HEAD;
        if (piston.getClass() != PistonHeadBlock.class) {
            return false;
        }
        var holder = Class.forName(STATE_HOLDER.replace('/', '.'), false, ShapePrewarm.class.getClassLoader());
        var mapGetter = holder.getMethod("getStateMap");
        var indexGetter = holder.getMethod("getStateIndex");
        var propertiesGetter = holder.getMethod("getVanillaPropertyMap");
        Object expectedMap = mapGetter.invoke(piston.defaultBlockState());
        if (expectedMap == null || !expectedMap.getClass().getName().equals(FERRITE.replace('/', '.') + "fastmap.FastMap")) {
            return false;
        }
        var indices = new java.util.HashSet<Integer>();
        for (BlockState state : piston.getStateDefinition().getPossibleStates()) {
            Object properties = propertiesGetter.invoke(state);
            if (state.getClass() != BlockState.class || mapGetter.invoke(state) != expectedMap
                    || !properties.getClass().getName().equals(FERRITE.replace('/', '.') + "impl.FastMapEntryMap")
                    || !(indexGetter.invoke(state) instanceof Integer index) || index < 0 || !indices.add(index)) {
                return false;
            }
        }
        return !indices.isEmpty();
    }

    private static String canonicalMixinName(String owner, String name) {
        if (owner.equals(STATE_HOLDER)) {
            return name.replaceFirst("^redirect\\$[a-zA-Z0-9]+\\$ferritecore\\$getNeighborFromFastMap$",
                    "redirect\\$stable\\$ferritecore\\$getNeighborFromFastMap");
        }
        return name.replaceFirst("^localvar\\$[a-zA-Z0-9]+\\$modernfix\\$useArrayMap$",
                "localvar\\$stable\\$modernfix\\$useArrayMap");
    }

    private static MethodNode contractMethod(ClassNode owner, MethodNode method) {
        if (!owner.name.equals(STATE_HOLDER) && !owner.name.equals(STATE_DEFINITION)) {
            return method;
        }
        var copy = new MethodNode(method.access, canonicalMixinName(owner.name, method.name), method.desc, null, null);
        method.accept(copy);
        for (var instruction : copy.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(owner.name)) {
                call.name = canonicalMixinName(owner.name, call.name);
            }
        }
        return copy;
    }

    private static void verifyLoaded(String owner) throws ClassNotFoundException {
        Class.forName(owner.replace('/', '.'), false, ShapePrewarm.class.getClassLoader());
        if (!"".equals(VALIDATED.get(owner))) {
            throw new IllegalStateException("shape_bytecode_changed_or_unobserved");
        }
    }

    /** The empty audit mixins make the final mixed-in bodies observable without changing their code. */
    static void verifyFinal(ClassNode owner) {
        if (!ENABLED) {
            return;
        }
        var expected = Contracts.BY_OWNER.get(owner.name);
        if (expected == null) {
            return;
        }
        try {
            // Mixin adds this marker to interface targets. It has no default methods or initializer.
            var interfaces = owner.interfaces.stream().filter(name -> !name.equals(AUDIT_INTERFACE)).toList();
            if (!(owner.superName + ":" + String.join(",", interfaces)).equals(expected.get("@type"))) {
                VALIDATED.put(owner.name, "shape_type_hierarchy_changed");
                return;
            }
            Map<String, MethodNode> methods = new HashMap<>();
            for (MethodNode method : owner.methods) {
                var contractMethod = contractMethod(owner, method);
                if (methods.put(contractMethod.name + contractMethod.desc, contractMethod) != null) {
                    VALIDATED.put(owner.name, "ambiguous_shape_method");
                    return;
                }
            }
            for (var contract : expected.entrySet()) {
                if (contract.getKey().equals("@type")) {
                    continue;
                }
                MethodNode method = methods.get(contract.getKey());
                String fingerprint = method == null ? "-" : MethodContract.fingerprint(method);
                if (!List.of(contract.getValue().split(",")).contains(fingerprint)) {
                    VALIDATED.put(owner.name, "shape_method_changed " + contract.getKey());
                    return;
                }
            }
            VALIDATED.put(owner.name, "");
        } catch (Exception | LinkageError failure) {
            VALIDATED.put(owner.name, "shape_code_inspection_failed");
        }
    }

    private static final class Contracts {
        static final Map<String, Map<String, String>> BY_OWNER = load();

        private static Map<String, Map<String, String>> load() {
            try (var input = ShapePrewarm.class.getResourceAsStream("/shape-prewarm-contracts.tsv")) {
                if (input == null) {
                    return Map.of();
                }
                Map<String, Map<String, String>> byOwner = new HashMap<>();
                try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                    for (String line; (line = reader.readLine()) != null;) {
                        String[] entry = line.split("\t", 3);
                        byOwner.computeIfAbsent(entry[0], ignored -> new HashMap<>()).put(entry[1], entry[2]);
                    }
                }
                return byOwner;
            } catch (Exception failure) {
                return Map.of();
            }
        }
    }
}
