package dev.wachipayox.bootoptim.compat.client;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.objectweb.asm.Handle;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Verifies after application that every injector of a BootOptim mixin actually reached its
 * target. All injectors use {@code require = 0}, so a changed target silently leaves stock
 * behaviour; this turns that silent case into a {@code [WBOMBO-COMPAT] FALLBACK} warning.
 * It also reports {@code @Pseudo} targets whose class disappeared from a loaded mod.
 */
public final class CompatInjectionCheck {
    /** Package prefix of a third-party target class -> owning mod id. Vanilla targets map to null. */
    private static final Map<String, String> MOD_BY_PACKAGE = Map.ofEntries(
            Map.entry("xaero.map.", "xaeroworldmap"),
            Map.entry("net.caffeinemc.mods.sodium.", "sodium"),
            Map.entry("com.cinemamod.mcef.", "mcef"),
            Map.entry("de.keksuccino.fancymenu.", "fancymenu"),
            Map.entry("schm.shsupercm.citresewn.", "citresewn"),
            Map.entry("ca.fxco.moreculling.", "moreculling"),
            Map.entry("com.github.talrey.createdeco.", "createdeco"),
            Map.entry("dev.averageanime.", "createfood"),
            Map.entry("traben.entity_model_features.", "entity_model_features"),
            Map.entry("com.razz.decocraft.", "decocraft"));
    private static final Set<String> DECLARED_TARGETS = ConcurrentHashMap.newKeySet();
    private static final Set<String> APPLIED_TARGETS = ConcurrentHashMap.newKeySet();
    /** Internal target name -> injectors to verify once the class is fully transformed. */
    private static final Map<String, List<Pending>> PENDING = new ConcurrentHashMap<>();

    private record Pending(String mixin, String targetClassName, List<String> handlers) {}

    private CompatInjectionCheck() {}

    static void declareTargets(Set<String> targets) {
        DECLARED_TARGETS.addAll(targets);
    }

    /**
     * Mixin config plugin postApply: only records the injectors. MixinExtras injectors such as
     * {@code @WrapOperation} are applied later, so verification waits for {@link #verifyFinal}.
     */
    static void afterApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        APPLIED_TARGETS.add(targetClassName);
        try {
            List<String> handlers = new ArrayList<>();
            for (MethodNode method : mixinInfo.getClassNode(0).methods) {
                if (isInjector(method)) {
                    handlers.add(method.name);
                }
            }
            if (!handlers.isEmpty()) {
                PENDING.computeIfAbsent(targetClass.name, key -> new ArrayList<>())
                        .add(new Pending(simpleName(mixinClassName), targetClassName, handlers));
            }
        } catch (RuntimeException failure) {
            // The check is diagnostic only; never let it affect class transformation.
        }
    }

    /** Mixin extension postApply: the class now carries every mixin and MixinExtras injection. */
    static void verifyFinal(ClassNode targetClass) {
        List<Pending> pending = PENDING.remove(targetClass.name);
        if (pending == null) {
            return;
        }
        try {
            for (Pending entry : pending) {
                List<String> missing = new ArrayList<>();
                for (String handler : entry.handlers()) {
                    if (!isReferenced(targetClass, handler)) {
                        missing.add(handler);
                    }
                }
                if (!missing.isEmpty()) {
                    CompatLog.fallback(entry.mixin(), modFor(entry.targetClassName()),
                            "injection_target_not_found target=" + entry.targetClassName() + " handlers=" + missing);
                    DecocraftContracts.injectionMissing(entry.mixin());
                }
            }
        } catch (RuntimeException failure) {
            // Diagnostic only.
        }
    }

    /** Called once mods are loaded: a declared target of a loaded mod that no longer exists. */
    public static void reportMissingPseudoTargets() {
        for (String target : DECLARED_TARGETS) {
            String modId = modFor(target);
            if (modId == null || APPLIED_TARGETS.contains(target) || !isModLoaded(modId)) {
                continue;
            }
            String resource = target.replace('.', '/') + ".class";
            ClassLoader loader = CompatInjectionCheck.class.getClassLoader();
            if (loader != null && loader.getResource(resource) == null) {
                CompatLog.fallback("target:" + target, modId, "target_class_not_found");
            }
        }
    }

    private static boolean isInjector(MethodNode method) {
        return hasInjectorAnnotation(method.visibleAnnotations) || hasInjectorAnnotation(method.invisibleAnnotations);
    }

    private static boolean hasInjectorAnnotation(List<AnnotationNode> annotations) {
        if (annotations == null) {
            return false;
        }
        for (AnnotationNode annotation : annotations) {
            if (annotation.desc.startsWith("Lorg/spongepowered/asm/mixin/injection/")
                    || annotation.desc.startsWith("Lcom/llamalad7/mixinextras/injector/")) {
                return true;
            }
        }
        return false;
    }

    /** The handler is merged under its own or a prefixed name; it must be called somewhere. */
    private static boolean isReferenced(ClassNode target, String handler) {
        Set<String> merged = new HashSet<>();
        for (MethodNode method : target.methods) {
            if (method.name.equals(handler) || method.name.endsWith("$" + handler)) {
                merged.add(method.name);
            }
        }
        if (merged.isEmpty()) {
            return false;
        }
        for (MethodNode method : target.methods) {
            if (method.instructions == null) {
                continue;
            }
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.owner.equals(target.name) && merged.contains(call.name)) {
                    return true;
                }
                if (insn instanceof InvokeDynamicInsnNode indy && indy.bsmArgs != null) {
                    for (Object argument : indy.bsmArgs) {
                        if (argument instanceof Handle handle && merged.contains(handle.getName())) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static String modFor(String className) {
        String dotted = className.replace('/', '.');
        for (Map.Entry<String, String> entry : MOD_BY_PACKAGE.entrySet()) {
            if (dotted.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static boolean isModLoaded(String modId) {
        try {
            return net.neoforged.fml.ModList.get() != null && net.neoforged.fml.ModList.get().isLoaded(modId);
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private static String simpleName(String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? className : className.substring(dot + 1);
    }
}
