package dev.wachipayox.bootoptim.replay.model;

import java.io.Reader;
import java.io.StringReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import net.minecraft.client.renderer.block.model.BlockElement;
import net.minecraft.client.renderer.block.model.BlockElementFace;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.resources.model.Material;
import net.neoforged.neoforge.client.model.ElementsModel;

/** Controlled smoke test for the real classes that are proven runnable before the FML parent boundary. */
public final class ModelClassReplaySelfTest {
    private ModelClassReplaySelfTest() {}

    public static void main(String[] args) throws Throwable {
        BlockModel model = parse("{\"gui_light\":\"front\",\"textures\":{\"all\":\"minecraft:block/stone\"},\"display\":{\"gui\":{\"rotation\":[30,45,0],\"scale\":[0.8,0.8,0.8]}},\"elements\":[{\"from\":[0,0,0],\"to\":[16,16,16],\"shade\":true,\"faces\":{\"north\":{\"texture\":\"#all\",\"cullface\":\"north\",\"tintindex\":1}}}]}"));
        if (model.getElements().size() != 1) throw new AssertionError("real parser did not preserve element count");
        BlockElement element = model.getElements().getFirst();
        if (element.faces.size() != 1) throw new AssertionError("real parser did not preserve face count");
        BlockElementFace face = element.faces.values().iterator().next();
        if (!"#all".equals(face.texture())) throw new AssertionError("real parser changed texture reference: " + face.texture());
        Material material = model.getMaterial(face.texture());
        if (material == null) throw new AssertionError("real BlockModel#getMaterial returned null");
        new ElementsModel(model.getElements());

        String wrongTexture = "#bootoptim_deliberate_fault";
        if (face.texture().equals(wrongTexture)) throw new AssertionError("fault probe unexpectedly equals stock");
        System.out.println("MODEL_CLASS_HEADLESS_PRE_PARENT_OK parser=BlockModel.fromStream material=BlockModel.getMaterial lowering=ElementsModel texture_ref=" + face.texture());
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
}
