package dev.wachipayox.bootoptim.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.wachipayox.bootoptim.profiling.client.CitMutableParseBudget;
import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "schm.shsupercm.citresewn.defaults.cit.types.TypeItem", remap = false)
abstract class CitResewnMutableParseBudgetMixin {
    @WrapMethod(method = "getModelForFirstItemType", require = 0)
    private BlockModel bootoptim$first(ResourceManager manager, Operation<BlockModel> original) {
        var scope = CitMutableParseBudget.begin("first");
        boolean success = false, missing = false;
        try { BlockModel result = original.call(manager); missing = result == null; success = true; return result; }
        finally { CitMutableParseBudget.finish(scope, success, missing, true); }
    }
    @WrapMethod(method = "getModelFromOverrideModel", require = 0)
    private BlockModel bootoptim$override(ResourceManager manager, ResourceLocation id, Operation<BlockModel> original) {
        var scope = CitMutableParseBudget.begin("override");
        boolean success = false, missing = false;
        try { BlockModel result = original.call(manager, id); missing = result == null; success = true; return result; }
        finally { CitMutableParseBudget.finish(scope, success, missing, true); }
    }
    @WrapOperation(method = {"getModelForFirstItemType", "getModelFromOverrideModel"}, require = 0,
            at = @At(value = "INVOKE", target = "Lorg/apache/commons/io/IOUtils;toString(Ljava/io/InputStream;Ljava/nio/charset/Charset;)Ljava/lang/String;"))
    private String bootoptim$read(InputStream input, Charset charset, Operation<String> original) throws IOException {
        var scope = CitMutableParseBudget.child(false, null);
        boolean success = false;
        try { String result = original.call(input, charset); success = true; return result; }
        finally { CitMutableParseBudget.finish(scope, success, false, false); }
    }
    @WrapOperation(method = {"getModelForFirstItemType", "getModelFromOverrideModel"}, require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/block/model/BlockModel;fromString(Ljava/lang/String;)Lnet/minecraft/client/renderer/block/model/BlockModel;"))
    private BlockModel bootoptim$parse(String json, Operation<BlockModel> original) {
        var scope = CitMutableParseBudget.child(true, json);
        boolean success = false, missing = false;
        try { BlockModel result = original.call(json); missing = result == null; success = true; return result; }
        finally { CitMutableParseBudget.finish(scope, success, missing, false); }
    }
}
