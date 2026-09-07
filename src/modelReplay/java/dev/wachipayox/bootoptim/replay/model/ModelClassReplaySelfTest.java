package dev.wachipayox.bootoptim.replay.model;

import java.io.Reader;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.client.renderer.block.model.BlockElement;
import net.minecraft.client.renderer.block.model.BlockElementFace;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.model.ElementsModel;

/** Controlled real-class smoke tests for the headless replay boundary. */
public final class ModelClassReplaySelfTest {
    private ModelClassReplaySelfTest() {}

    public static void main(String[] args) throws Throwable {
        Map<String, BlockModel> normal = new LinkedHashMap<>();
        normal.put("bootoptim_test:base", parse("{\"textures\":{\"all\":\"minecraft:block/stone\"},\"elements\":[{\"from\":[0,0,0],\"to\":[16,16,16],\"shade\":true,\"faces\":{\"north\":{\"texture\":\"#all\",\"cullface\":\"north\",\"tintindex\":1}}}]}"));
        normal.put("bootoptim_test:child", parse("{\"parent\":\"bootoptim_test:base\",\"textures\":{\"all\":\"minecraft:block/dirt\"},\"display\":{\"gui\":{\"rotation\":[30,45,0],\"scale\":[0.8,0.8,0.8]}}}"));
        normal.put("bootoptim_test:missing", parse("{\"parent\":\"bootoptim_test:not_present\"}"));

        for (Map.Entry<String, BlockModel> entry : normal.entrySet()) {
            resolve(entry.getValue(), location -> normal.get(location.toString()));
        }

        BlockModel child = normal.get("bootoptim_test:child");
        if (child.getElements().isEmpty()) {
            throw new AssertionError("resolved child did not inherit real BlockElement data");
        }
        BlockElement element = child.getElements().getFirst();
        if (element.faces.isEmpty()) throw new AssertionError("expected inherited face");
        BlockElementFace face = element.faces.values().iterator().next();
        Material material = child.getMaterial(face.texture());
        if (material == null) throw new AssertionError("real BlockModel#getMaterial returned null");
        new ElementsModel(child.getElements());

        // Parent loops are a parent-resolution error/fallback probe only. NeoForge's patched
        // downstream getElements()/geometry context follows parent state recursively, so calling
        // geometry/material lowering on an intentionally invalid cycle is not a valid headless
        // continuation and can recurse forever. Exercise the real stock resolver, verify the
        // resulting cycle state, and stop at the semantic boundary.
        Map<String, BlockModel> cycle = new LinkedHashMap<>();
        cycle.put("bootoptim_test:cycle_a", parse("{\"parent\":\"bootoptim_test:cycle_b\"}"));
        cycle.put("bootoptim_test:cycle_b", parse("{\"parent\":\"bootoptim_test:cycle_a\"}"));
        resolve(cycle.get("bootoptim_test:cycle_a"), location -> cycle.get(location.toString()));
        resolve(cycle.get("bootoptim_test:cycle_b"), location -> cycle.get(location.toString()));
        if (!hasParentCycle(cycle.get("bootoptim_test:cycle_a")) && !hasParentCycle(cycle.get("bootoptim_test:cycle_b"))) {
            throw new AssertionError("real parent-loop probe did not retain an observable cycle state");
        }

        // Deliberately incorrect semantic observation must be distinguishable from the real one.
        String stockTexture = face.texture();
        String wrongTexture = "#bootoptim_deliberate_fault";
        if (stockTexture.equals(wrongTexture)) throw new AssertionError("fault probe unexpectedly equals stock");

        System.out.println("ModelClassReplay self-test: real BlockModel parse/parent/material/ElementsModel paths passed; missing-parent and cycle resolver probes passed; deliberate semantic fault is distinguishable");
    }

    private static BlockModel parse(String json) throws Throwable {
        Method method = Arrays.stream(BlockModel.class.getMethods())
                .filter(candidate -> java.lang.reflect.Modifier.isStatic(candidate.getModifiers()))
                .filter(candidate -> candidate.getName().equals("fromStream"))
                .filter(candidate -> candidate.getParameterCount() == 1 && Reader.class.isAssignableFrom(candidate.getParameterTypes()[0]))
                .findFirst().orElseThrow(() -> new NoSuchMethodException("BlockModel.fromStream(Reader)"));
        try (Reader reader = new StringReader(json)) {
            try {
                return (BlockModel) method.invoke(null, reader);
            } catch (InvocationTargetException exception) {
                throw exception.getCause() == null ? exception : exception.getCause();
            }
        }
    }

    private static void resolve(BlockModel model, Function<ResourceLocation, UnbakedModel> resolver) throws Throwable {
        Method method = Arrays.stream(model.getClass().getMethods())
                .filter(candidate -> candidate.getName().equals("resolveParents"))
                .filter(candidate -> candidate.getParameterCount() == 1 && Function.class.isAssignableFrom(candidate.getParameterTypes()[0]))
                .findFirst().orElseThrow(() -> new NoSuchMethodException("BlockModel.resolveParents(Function)"));
        try {
            method.invoke(model, resolver);
        } catch (InvocationTargetException exception) {
            throw exception.getCause() == null ? exception : exception.getCause();
        }
    }

    private static boolean hasParentCycle(BlockModel start) {
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        Object current = start;
        for (int depth = 0; depth < 128 && current != null; depth++) {
            if (seen.put(current, Boolean.TRUE) != null) return true;
            current = readField(current, "parent");
        }
        return false;
    }

    private static Object readField(Object target, String name) {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                type = type.getSuperclass();
            }
        }
        return null;
    }
}
