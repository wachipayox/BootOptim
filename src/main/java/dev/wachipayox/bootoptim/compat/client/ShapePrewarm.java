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
import org.objectweb.asm.tree.ClassNode;
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
        } catch (Exception | LinkageError changed) {
            String reason = VALIDATED.getOrDefault(checked, "class_not_observed");
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
                methods.put(method.name + method.desc, method);
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
