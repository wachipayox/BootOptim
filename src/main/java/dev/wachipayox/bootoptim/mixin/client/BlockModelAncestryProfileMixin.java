package dev.wachipayox.bootoptim.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.datafixers.util.Either;
import dev.wachipayox.bootoptim.profiling.client.ModelAncestryProfiler;
import dev.wachipayox.bootoptim.profiling.client.ModelAncestryProfiler.Kind;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.client.renderer.block.model.*;
import net.minecraft.client.resources.model.*;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Measures current transformed method chains; never caches/skips inputs or callbacks. */
@Mixin(BlockModel.class)
abstract class BlockModelAncestryProfileMixin {
    @Shadow public BlockModel parent;
    @Shadow protected ResourceLocation parentLocation;

    @WrapMethod(method="getDependencies")
    private Collection<ResourceLocation> bootoptim$getDependencies(Operation<Collection<ResourceLocation>> original) {
        if(!ModelAncestryProfiler.ENABLED)return original.call();
        int token=ModelAncestryProfiler.begin(Kind.DEPENDENCIES,false); boolean failed=true;
        try { Collection<ResourceLocation> result=original.call(); failed=false; return result; }
        finally { ModelAncestryProfiler.end(token,failed); }
    }

    @WrapMethod(method="getMaterial")
    private Material bootoptim$getMaterial(String name, Operation<Material> original) {
        if(!ModelAncestryProfiler.ENABLED)return original.call(name);
        int token=ModelAncestryProfiler.begin(Kind.MATERIAL,false); boolean failed=true;
        try { Material result=original.call(name); failed=false; return result; }
        finally { ModelAncestryProfiler.end(token,failed); }
    }

    @WrapMethod(method="findTextureEntry")
    private Either<Material,String> bootoptim$findTextureEntry(String name, Operation<Either<Material,String>> original) {
        if(!ModelAncestryProfiler.ENABLED)return original.call(name);
        int token=ModelAncestryProfiler.begin(Kind.TEXTURE_ENTRY,false); boolean failed=true;
        try { Either<Material,String> result=original.call(name); failed=false; return result; }
        finally { ModelAncestryProfiler.end(token,failed); }
    }

    @WrapMethod(method="getElements")
    private List<BlockElement> bootoptim$getElements(Operation<List<BlockElement>> original) {
        if(!ModelAncestryProfiler.ENABLED)return original.call();
        int token=ModelAncestryProfiler.begin(Kind.ELEMENTS,false); boolean failed=true;
        try { List<BlockElement> result=original.call(); failed=false; return result; }
        finally { ModelAncestryProfiler.end(token,failed); }
    }

    @WrapMethod(method="getRootModel")
    private BlockModel bootoptim$getRootModel(Operation<BlockModel> original) {
        if(!ModelAncestryProfiler.ENABLED)return original.call();
        int token=ModelAncestryProfiler.begin(Kind.ROOT,false); boolean failed=true;
        try { BlockModel result=original.call(); failed=false; return result; }
        finally { ModelAncestryProfiler.end(token,failed); }
    }

    @WrapMethod(method="getTransforms")
    private ItemTransforms bootoptim$getTransforms(Operation<ItemTransforms> original) {
        if(!ModelAncestryProfiler.ENABLED)return original.call();
        int token=ModelAncestryProfiler.begin(Kind.TRANSFORMS,false); boolean failed=true;
        try { ItemTransforms result=original.call(); failed=false; return result; }
        finally { ModelAncestryProfiler.end(token,failed); }
    }

    @WrapMethod(method="resolveParents")
    private void bootoptim$parents(Function<ResourceLocation,UnbakedModel> resolver,Operation<Void> original) {
        if(!ModelAncestryProfiler.ENABLED){original.call(resolver);return;}
        int token=ModelAncestryProfiler.begin(Kind.PARENTS,parentLocation==null || parent!=null); boolean failed=true;
        try{original.call(resolver);failed=false;}finally{ModelAncestryProfiler.end(token,failed);}
    }
    @WrapOperation(method="findTextureEntry",at=@At(value="INVOKE",target="Ljava/util/Map;get(Ljava/lang/Object;)Ljava/lang/Object;"))
    private Object bootoptim$textureProbe(Map<?,?> map,Object key,Operation<Object> original) {
        Object value=original.call(map,key); ModelAncestryProfiler.textureProbe(value!=null); return value;
    }
    @WrapOperation(method="getMaterial",at=@At(value="INVOKE",target="Ljava/util/List;contains(Ljava/lang/Object;)Z"))
    private boolean bootoptim$alias(List<?> list,Object key,Operation<Boolean> original) {
        ModelAncestryProfiler.aliasCheck();return original.call(list,key);
    }

    @WrapOperation(method="resolveParents",at=@At(value="INVOKE",target="Lcom/google/common/collect/Sets;newLinkedHashSet()Ljava/util/LinkedHashSet;"))
    private java.util.LinkedHashSet<UnbakedModel> bootoptim$cycleSet(Operation<java.util.LinkedHashSet<UnbakedModel>> original) {
        java.util.LinkedHashSet<UnbakedModel> result=original.call();
        ModelAncestryProfiler.collectionFactory(Kind.PARENTS); return result;
    }
    @WrapOperation(method="getMaterial",at=@At(value="INVOKE",target="Lcom/google/common/collect/Lists;newArrayList()Ljava/util/ArrayList;"))
    private java.util.ArrayList<String> bootoptim$chainList(Operation<java.util.ArrayList<String>> original) {
        java.util.ArrayList<String> result=original.call();
        ModelAncestryProfiler.collectionFactory(Kind.MATERIAL); return result;
    }
}
