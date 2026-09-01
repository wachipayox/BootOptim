package dev.wachipayox.bootoptim.mixin.client;

import dev.wachipayox.bootoptim.optimization.client.CompiledElementsBakePlan;
import dev.wachipayox.bootoptim.optimization.client.CompiledElementsVerificationState;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockElement;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelState;
import net.minecraft.core.Direction;
import net.neoforged.neoforge.client.model.ElementsModel;
import net.neoforged.neoforge.client.model.IModelBuilder;
import net.neoforged.neoforge.client.model.geometry.IGeometryBakingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Experimental flattened traversal for repeated vanilla/NeoForge ElementsModel geometry.
 *
 * <p>Verification is on by default. In verification mode the candidate writes only to a recording builder,
 * then the real transformed ElementsModel body executes unchanged and remains authoritative.</p>
 */
@Mixin(ElementsModel.class)
public abstract class ElementsModelLivePlanMixin {
    @Unique
    private static final Logger BOOTOPTIM_LOGGER = LoggerFactory.getLogger("BootOptim/CompiledElementsLivePlan");

    @Unique
    private static final boolean BOOTOPTIM_ENABLED = Boolean.parseBoolean(
            System.getProperty("boot_optim.compiledElementsLivePlan", "true"));

    @Unique
    private static final boolean BOOTOPTIM_VERIFY = Boolean.parseBoolean(
            System.getProperty("boot_optim.compiledElementsLivePlan.verify", "true"));

    @Unique private static final LongAdder BOOTOPTIM_STOCK_FIRST_CALLS = new LongAdder();
    @Unique private static final LongAdder BOOTOPTIM_CANDIDATE_CALLS = new LongAdder();
    @Unique private static final LongAdder BOOTOPTIM_CANDIDATE_FACES = new LongAdder();
    @Unique private static final LongAdder BOOTOPTIM_CANDIDATE_NS = new LongAdder();
    @Unique private static final LongAdder BOOTOPTIM_STOCK_VERIFY_NS = new LongAdder();
    @Unique private static final LongAdder BOOTOPTIM_VERIFY_MATCHES = new LongAdder();
    @Unique private static final LongAdder BOOTOPTIM_VERIFY_MISMATCHES = new LongAdder();
    @Unique private static final LongAdder BOOTOPTIM_FALLBACKS = new LongAdder();
    @Unique private static final AtomicInteger BOOTOPTIM_LOGGED_MISMATCHES = new AtomicInteger();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> BOOTOPTIM_LOGGER.info(
                "BOOTOPTIM_COMPILED_ELEMENTS_LIVE_PLAN summary=shutdown enabled={} verify={} first_stock_calls={} candidate_calls={} candidate_faces={} candidate_ms={} stock_verify_ms={} verify_matches={} verify_mismatches={} fallbacks={}",
                BOOTOPTIM_ENABLED,
                BOOTOPTIM_VERIFY,
                BOOTOPTIM_STOCK_FIRST_CALLS.sum(),
                BOOTOPTIM_CANDIDATE_CALLS.sum(),
                BOOTOPTIM_CANDIDATE_FACES.sum(),
                bootoptim$millis(BOOTOPTIM_CANDIDATE_NS),
                bootoptim$millis(BOOTOPTIM_STOCK_VERIFY_NS),
                BOOTOPTIM_VERIFY_MATCHES.sum(),
                BOOTOPTIM_VERIFY_MISMATCHES.sum(),
                BOOTOPTIM_FALLBACKS.sum()), "BootOptim-compiled-elements-live-plan-report"));
    }

    @Shadow @Final
    private List<BlockElement> elements;

    @Unique
    private CompiledElementsVerificationState bootoptim$verification;

    @Inject(method = "addQuads", at = @At("HEAD"), cancellable = true, require = 1)
    private void bootoptim$tryCompiledElementsPlan(
            IGeometryBakingContext context,
            IModelBuilder<?> modelBuilder,
            ModelBaker baker,
            Function<Material, TextureAtlasSprite> spriteGetter,
            ModelState modelState,
            CallbackInfo ci) {
        if (!BOOTOPTIM_ENABLED) {
            return;
        }

        CompiledElementsBakePlan plan = CompiledElementsBakePlan.acquire(context, elements);
        if (plan == null) {
            BOOTOPTIM_STOCK_FIRST_CALLS.increment();
            return;
        }

        if (BOOTOPTIM_VERIFY) {
            CompiledElementsBakePlan.RecordingBuilder candidateBuilder = new CompiledElementsBakePlan.RecordingBuilder();
            long candidateStart = System.nanoTime();
            boolean candidateValid = plan.bake(context, candidateBuilder, baker, spriteGetter, modelState);
            BOOTOPTIM_CANDIDATE_NS.add(System.nanoTime() - candidateStart);
            if (!candidateValid) {
                BOOTOPTIM_FALLBACKS.increment();
                return;
            }

            BOOTOPTIM_CANDIDATE_CALLS.increment();
            BOOTOPTIM_CANDIDATE_FACES.add(plan.faceCount());
            bootoptim$verification = new CompiledElementsVerificationState(
                    context.getModelName(),
                    List.copyOf(candidateBuilder.records()),
                    new ArrayList<>(plan.faceCount()),
                    System.nanoTime());
            return; // Stock transformed body remains authoritative.
        }

        long candidateStart = System.nanoTime();
        boolean candidateValid = plan.bake(context, modelBuilder, baker, spriteGetter, modelState);
        BOOTOPTIM_CANDIDATE_NS.add(System.nanoTime() - candidateStart);
        if (!candidateValid) {
            BOOTOPTIM_FALLBACKS.increment();
            return;
        }

        BOOTOPTIM_CANDIDATE_CALLS.increment();
        BOOTOPTIM_CANDIDATE_FACES.add(plan.faceCount());
        ci.cancel();
    }

    @Redirect(
            method = "addQuads",
            at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/client/model/IModelBuilder;addUnculledFace(Lnet/minecraft/client/renderer/block/model/BakedQuad;)Lnet/neoforged/neoforge/client/model/IModelBuilder;"),
            require = 1)
    private IModelBuilder<?> bootoptim$recordStockUnculled(IModelBuilder<?> builder, BakedQuad quad) {
        CompiledElementsVerificationState verification = bootoptim$verification;
        if (verification != null) {
            verification.stock.add(new CompiledElementsBakePlan.QuadRecord(null, quad));
        }
        return builder.addUnculledFace(quad);
    }

    @Redirect(
            method = "addQuads",
            at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/client/model/IModelBuilder;addCulledFace(Lnet/minecraft/core/Direction;Lnet/minecraft/client/renderer/block/model/BakedQuad;)Lnet/neoforged/neoforge/client/model/IModelBuilder;"),
            require = 1)
    private IModelBuilder<?> bootoptim$recordStockCulled(IModelBuilder<?> builder, Direction direction, BakedQuad quad) {
        CompiledElementsVerificationState verification = bootoptim$verification;
        if (verification != null) {
            verification.stock.add(new CompiledElementsBakePlan.QuadRecord(direction, quad));
        }
        return builder.addCulledFace(direction, quad);
    }

    @Inject(method = "addQuads", at = @At("RETURN"), require = 1)
    private void bootoptim$verifyCompiledElementsPlan(
            IGeometryBakingContext context,
            IModelBuilder<?> modelBuilder,
            ModelBaker baker,
            Function<Material, TextureAtlasSprite> spriteGetter,
            ModelState modelState,
            CallbackInfo ci) {
        CompiledElementsVerificationState verification = bootoptim$verification;
        if (verification == null) {
            return;
        }
        bootoptim$verification = null;

        BOOTOPTIM_STOCK_VERIFY_NS.add(System.nanoTime() - verification.stockStartNanos);
        String mismatch = CompiledElementsBakePlan.compare(verification.stock, verification.candidate);
        if (mismatch == null) {
            BOOTOPTIM_VERIFY_MATCHES.increment();
            return;
        }

        BOOTOPTIM_VERIFY_MISMATCHES.increment();
        if (BOOTOPTIM_LOGGED_MISMATCHES.getAndIncrement() < 16) {
            BOOTOPTIM_LOGGER.warn(
                    "Compiled Elements live-plan verifier mismatch model={} reason={}",
                    verification.modelName,
                    mismatch);
        }
    }

    @Unique
    private static String bootoptim$millis(LongAdder nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos.sum() / 1_000_000.0);
    }
}
