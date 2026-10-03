package dev.wachipayox.bootoptim.mixin.client;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.wachipayox.bootoptim.profiling.client.PathPrefixBudget;
import java.nio.file.Path;
import net.minecraft.server.packs.PathPackResources;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
@Mixin(PathPackResources.class)
abstract class PathPrefixBudgetMixin {
 @WrapOperation(method="getResource(Lnet/minecraft/server/packs/PackType;Lnet/minecraft/resources/ResourceLocation;)Lnet/minecraft/server/packs/resources/IoSupplier;",at=@At(value="INVOKE",target="Ljava/nio/file/Path;resolve(Ljava/lang/String;)Ljava/nio/file/Path;",ordinal=0))
 private Path bootoptim$prefix(Path root,String directory,Operation<Path> original){
  Path result=original.call(root,directory);
  if(PathPrefixBudget.ENABLED)PathPrefixBudget.record(root,directory);
  return result;
 }
}
