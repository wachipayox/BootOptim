package dev.wachipayox.bootoptim.mixin;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.mixin.transformer.ext.Extensions;
import org.spongepowered.asm.mixin.transformer.ext.IExtension;
import org.spongepowered.asm.mixin.transformer.ext.ITargetClassContext;

/** Changes no other target; unknown bytecode remains stock. */
public final class StrictPathMixinPlugin implements IMixinConfigPlugin {
    @Override public void onLoad(String mixinPackage) {
        // Register during config selection, before Extensions.select. Do not rewrite in the
        // per-mixin callback: later injectors must still see the original matcher instructions.
        Object transformer = MixinEnvironment.getCurrentEnvironment().getActiveTransformer();
        if (transformer instanceof IMixinTransformer mixinTransformer
                && mixinTransformer.getExtensions() instanceof Extensions extensions
                && extensions.getExtensions().stream().noneMatch(e -> e instanceof FinalOperationExtension))
            extensions.add(new FinalOperationExtension());
    }
    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) { return true; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    private static final class FinalOperationExtension implements IExtension {
        @Override public boolean checkActive(MixinEnvironment environment) {
            return environment.getSide() == MixinEnvironment.Side.CLIENT
                    && (Boolean.getBoolean("boot_optim.strictPathSegmentScan") || Boolean.getBoolean("boot_optim.profileStrictPathSegments"));
        }
        @Override public void preApply(ITargetClassContext context) {}
        @Override public void postApply(ITargetClassContext context) {
            if (context.getClassNode().name.equals("net/minecraft/FileUtil"))
                StrictPathSegmentTransform.rewrite(context.getClassNode());
        }
        @Override public void export(MixinEnvironment environment, String name, boolean force, ClassNode classNode) {}
    }
}
