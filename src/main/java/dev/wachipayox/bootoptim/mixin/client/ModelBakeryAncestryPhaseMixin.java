package dev.wachipayox.bootoptim.mixin.client;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.wachipayox.bootoptim.profiling.client.ModelAncestryProfiler;
import net.minecraft.client.resources.model.ModelBakery;
import org.spongepowered.asm.mixin.Mixin;
@Mixin(ModelBakery.class)
abstract class ModelBakeryAncestryPhaseMixin {
    @WrapMethod(method="bakeModels")
    private void bootoptim$bakePhase(ModelBakery.TextureGetter getter,Operation<Void> original) {
        ModelAncestryProfiler.enterBake();
        try{original.call(getter);}finally{ModelAncestryProfiler.exitBake();}
    }
}
