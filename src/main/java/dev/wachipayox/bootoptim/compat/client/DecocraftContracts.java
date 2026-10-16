package dev.wachipayox.bootoptim.compat.client;

import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.moddiscovery.ModInfo;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Code-shape contracts for the Decocraft memory mechanisms (wbombo compat policy, 2026-10-14).
 *
 * <p>A mechanism is applied only when the Decocraft methods it touches still have the exact
 * instruction stream it was written against ({@link MethodContract}); no mod version, JAR size
 * or JAR hash is read. The check reads the class bytes from the discovered mod resources without
 * initialising or transforming the class. Any mismatch, missing class or mixin failure turns the
 * whole mechanism off (all coupled mixins together) and logs
 * {@code [WBOMBO-COMPAT] FALLBACK}. Helper code asks {@link #active(String)} before it changes
 * anything, so a disabled mechanism leaves the original behaviour.</p>
 *
 * <p>Ownership: BootOptim owns these mechanisms. A legacy Android Support build that still carries
 * the same mixin on an Android runtime applies it itself; BootOptim then yields (FALLBACK
 * {@code legacy_support_owns_mechanism}) so the optimisation is never applied twice. A mechanism in
 * use publishes {@code -Dwbombo.decocraft.<mechanism>=BootOptim} for any later build that wants to
 * detect the owner.</p>
 */
public final class DecocraftContracts {
    public static final String MOD = "decocraft";
    private static final String CONTRACT_RESOURCE = "/bootoptim-decocraft-contracts.properties";
    private static final String LEGACY_SUPPORT_PACKAGE = "net/wachiland/atlasbatch/mixin/";
    private static final String BBLOADER = "com/razz/decocraft/models/bbmodel/BBModelLoader#";
    private static final String BAKERY = "com/razz/decocraft/models/bbmodel/BlockbenchBakery#";
    private static final String MATRIX = "org/joml/Matrix4f#";

    /** Kill-switch property, contract methods and the mixins that must apply together. */
    private record Contract(String property, List<String> methods, Set<String> mixins) {}

    private static final Map<String, Contract> CONTRACTS = Map.of(
            "decocraft-private-directions", new Contract("boot_optim.decocraftPrivateDirections",
                    List.of("com/razz/decocraft/models/bbmodel/BlockbenchBakery#calculateFacing([Lcom/razz/decocraft/models/bbmodel/BlockbenchBakery$VertexData;)Lnet/minecraft/core/Direction;"),
                    Set.of("DecocraftPrivateDirectionsMixin")),
            "decocraft-compact-face-maps", new Contract("boot_optim.decocraftCompactFaceMaps",
                    List.of(BBLOADER + "loadModel(Ljava/io/Reader;)Lcom/razz/decocraft/models/bbmodel/BBModel;",
                            BBLOADER + "loadModel(Lcom/google/gson/JsonDeserializationContext;Lcom/google/gson/JsonObject;)Lcom/razz/decocraft/models/bbmodel/BBModel;",
                            BBLOADER + "parse(Lcom/razz/decocraft/models/bbmodel/BBModel;)V",
                            BBLOADER + "parseOutlinerCube(Lcom/razz/decocraft/models/bbmodel/BBModel;Lcom/razz/decocraft/models/bbmodel/BBModelParts$OutlinerCube;Lcom/razz/decocraft/models/bbmodel/BBModelParts$OutlinerGroup;)V",
                            BBLOADER + "parseOutlinerGroup(Lcom/razz/decocraft/models/bbmodel/BBModel;Lcom/razz/decocraft/models/bbmodel/BBModelParts$OutlinerGroup;Lcom/razz/decocraft/models/bbmodel/BBModelParts$OutlinerGroup;)V",
                            BBLOADER + "parseLocator(Lcom/razz/decocraft/models/bbmodel/BBModel;Lcom/razz/decocraft/models/bbmodel/BBModelParts$Locator;Lcom/razz/decocraft/models/bbmodel/BBModelParts$OutlinerGroup;)V",
                            BBLOADER + "parseOutliner(Lcom/razz/decocraft/models/bbmodel/BBModel;Lcom/razz/decocraft/models/bbmodel/BBModelParts$OutlinerNode;Lcom/razz/decocraft/models/bbmodel/BBModelParts$OutlinerGroup;)V"),
                    Set.of("DecocraftCompactFaceMapsMixin")),
            "decocraft-rotation-temporaries", new Contract("boot_optim.decocraftRotationTemporaries",
                    rotationMethods(),
                    Set.of("DecocraftRotationTemporariesMixin")));

    private static List<String> rotationMethods() {
        List<String> methods = new ArrayList<>(List.of(
                "com/razz/decocraft/models/libgdx/Vector3#set(FFF)Lcom/razz/decocraft/models/libgdx/Vector3;",
                BAKERY + "applyModelRotation(Lcom/razz/decocraft/models/libgdx/Vector3;Lcom/razz/decocraft/models/libgdx/Vector3;Lorg/joml/Matrix4f;)V",
                BAKERY + "applyElementRotation(Lcom/razz/decocraft/models/libgdx/Vector3;Lcom/razz/decocraft/models/bbmodel/BBModelParts$Element;)V",
                "com/razz/decocraft/models/libgdx/Vector3#<init>(FFF)V",
                MATRIX + "<init>()V",
                MATRIX + "equals(Ljava/lang/Object;)Z"));
        for (int row = 0; row < 4; row++) {
            for (int column = 0; column < 4; column++) {
                methods.add(MATRIX + "m" + row + column + "()F");
            }
        }
        return List.copyOf(methods);
    }

    private static final Map<String, Boolean> VERIFIED = new HashMap<>();
    private static final Map<String, Set<String>> APPLIED = new HashMap<>();
    private static final Set<String> DISABLED = new HashSet<>();
    private static volatile Set<String> ready = Set.of();
    private static final Properties EXPECTED = new Properties();

    static {
        try (InputStream input = DecocraftContracts.class.getResourceAsStream(CONTRACT_RESOURCE)) {
            if (input != null) {
                EXPECTED.load(input);
            }
        } catch (Exception unavailable) {
            // Empty table: every verification fails closed.
        }
    }

    private DecocraftContracts() {}

    /** Mechanism id owning a mixin class (simple name), or null. */
    public static String mechanism(String mixinSimpleName) {
        for (Map.Entry<String, Contract> entry : CONTRACTS.entrySet()) {
            if (entry.getValue().mixins().contains(mixinSimpleName)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /** Mixin config plugin hook: false keeps this mixin out of the game. */
    public static synchronized boolean shouldApply(String mixinSimpleName) {
        String mechanism = mechanism(mixinSimpleName);
        if (mechanism == null) {
            return true;
        }
        Contract contract = CONTRACTS.get(mechanism);
        if (DISABLED.contains(mechanism)) {
            return false;
        }
        if (!Boolean.parseBoolean(System.getProperty(contract.property(), "true")) || !decocraftPresent()) {
            // Switched off by the user, or the optional mod is simply not installed: not an error.
            DISABLED.add(mechanism);
            return false;
        }
        if (legacySupportCarries(contract)) {
            disable(mechanism, "legacy_support_owns_mechanism");
            return false;
        }
        return verify(mechanism);
    }

    private static boolean verify(String mechanism) {
        Boolean known = VERIFIED.get(mechanism);
        if (known != null) {
            return known;
        }
        try {
            for (String key : CONTRACTS.get(mechanism).methods()) {
                int split = key.indexOf('#');
                ClassNode node = read(key.substring(0, split));
                String method = key.substring(split + 1);
                MethodNode match = node.methods.stream()
                        .filter(m -> (m.name + m.desc).equals(method))
                        .findFirst()
                        .orElseThrow();
                if (!MethodContract.fingerprint(match).equals(EXPECTED.getProperty(key))) {
                    throw new IllegalStateException("target_code_changed");
                }
            }
            VERIFIED.put(mechanism, true);
            return true;
        } catch (Exception | LinkageError mismatch) {
            VERIFIED.put(mechanism, false);
            disable(mechanism, "target_code_unavailable_or_changed_" + mismatch.getClass().getSimpleName());
            return false;
        }
    }

    /** Re-checks the target as other mixins left it, right before this one applies. */
    public static synchronized void beforeApply(String mixinSimpleName, ClassNode target) {
        String mechanism = mechanism(mixinSimpleName);
        if (mechanism == null || DISABLED.contains(mechanism)) {
            return;
        }
        try {
            for (String key : CONTRACTS.get(mechanism).methods()) {
                int split = key.indexOf('#');
                if (!key.substring(0, split).equals(target.name)) {
                    continue;
                }
                String method = key.substring(split + 1);
                MethodNode match = target.methods.stream()
                        .filter(m -> (m.name + m.desc).equals(method))
                        .findFirst()
                        .orElseThrow();
                if (!MethodContract.fingerprint(match).equals(EXPECTED.getProperty(key))) {
                    throw new IllegalStateException();
                }
            }
        } catch (Exception | LinkageError changed) {
            disable(mechanism, "target_changed_before_apply");
        }
    }

    /** Mixin config plugin postApply: the mechanism is ready once all its mixins are in. */
    public static synchronized void installed(String mixinSimpleName) {
        String mechanism = mechanism(mixinSimpleName);
        if (mechanism == null) {
            return;
        }
        APPLIED.computeIfAbsent(mechanism, key -> new HashSet<>()).add(mixinSimpleName);
        if (!DISABLED.contains(mechanism)
                && Boolean.TRUE.equals(VERIFIED.get(mechanism))
                && APPLIED.get(mechanism).containsAll(CONTRACTS.get(mechanism).mixins())) {
            Set<String> next = new HashSet<>(ready);
            next.add(mechanism);
            ready = Set.copyOf(next);
        }
    }

    /** A BootOptim injector of this mixin did not reach its target: the coupled hooks go together. */
    static void injectionMissing(String mixinSimpleName) {
        String mechanism = mechanism(mixinSimpleName);
        if (mechanism != null) {
            disable(mechanism, "mixin_injection_failed");
        }
    }

    public static boolean active(String mechanism) {
        return ready.contains(mechanism);
    }

    public static synchronized void disable(String mechanism, String reason) {
        if (!CONTRACTS.containsKey(mechanism)) {
            return;
        }
        DISABLED.add(mechanism);
        Set<String> next = new HashSet<>(ready);
        next.remove(mechanism);
        ready = Set.copyOf(next);
        CompatLog.fallback(mechanism, MOD, reason);
    }

    /** Logs ACTIVE (once) at the first real use and publishes the ownership capability. */
    public static void used(String mechanism) {
        System.setProperty("wbombo.decocraft." + mechanism, "BootOptim");
        CompatLog.active(mechanism, MOD);
    }

    private static boolean decocraftPresent() {
        var list = FMLLoader.getLoadingModList();
        return list != null && list.getMods().stream().map(ModInfo::getModId).anyMatch(MOD::equals);
    }

    /** Old Android Support build with the same mixin class, running where it applies it. */
    private static boolean legacySupportCarries(Contract contract) {
        boolean android = System.getProperty("os.version", "").startsWith("Android")
                || System.getenv("MOD_ANDROID_RUNTIME") != null;
        var list = FMLLoader.getLoadingModList();
        if (!android || list == null) {
            return false;
        }
        for (String mixin : contract.mixins()) {
            if (list.findResource(LEGACY_SUPPORT_PACKAGE + legacyName(mixin) + ".class") != null) {
                return true;
            }
        }
        return false;
    }

    /** Legacy Support used other class names for these two mixins. */
    private static String legacyName(String mixin) {
        return switch (mixin) {
            case "DecocraftPrivateDirectionsMixin" -> "DecocraftDirectionValuesMixin";
            case "DecocraftCompactFaceMapsMixin" -> "DecocraftFaceMapsMixin";
            default -> mixin;
        };
    }

    private static ClassNode read(String owner) throws Exception {
        var list = FMLLoader.getLoadingModList();
        if (list == null) {
            throw new IllegalStateException("mod_discovery_unavailable");
        }
        var resource = list.findResource(owner + ".class");
        if (resource == null) {
            if (owner.equals("org/joml/Matrix4f")) {
                // JOML is a library outside the mod roots: read its packaged bytes, nothing is initialised.
                try (InputStream input = DecocraftContracts.class.getClassLoader().getResourceAsStream(owner + ".class")) {
                    if (input == null) {
                        throw new ClassNotFoundException(owner);
                    }
                    ClassNode library = new ClassNode();
                    new ClassReader(input).accept(library, 0);
                    return library;
                }
            }
            throw new ClassNotFoundException(owner);
        }
        ClassNode node = new ClassNode();
        try (InputStream input = Files.newInputStream(resource)) {
            new ClassReader(input).accept(node, 0);
        }
        return node;
    }
}
