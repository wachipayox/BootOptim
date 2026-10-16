package dev.wachipayox.bootoptim.optimization.client;

import dev.wachipayox.bootoptim.compat.client.CompatLog;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;

/**
 * Moves the synchronous prefix of YACL's {@code YACLImageReloadListener.prepare} off the calling thread.
 *
 * <p>YACL runs {@code prepare} synchronously from {@code reload0}. Its prefix only lists the
 * {@code textures} resources, matches them against the preloaded GIF/WebP factories and builds the
 * factory objects; the image decoding already runs on the background executor, and the texture
 * upload ({@code completeImage}, {@code TextureManager.register}) stays in YACL's apply step on the
 * game executor. Nothing in the moved prefix touches OpenGL or {@code RenderSystem}
 * (checked against YACL 3.8.2 bytecode, see the research note).</p>
 *
 * <p>Only manual reloads after the game has loaded are dispatched; the initial reload keeps stock
 * behaviour. If the dispatched prefix fails, the stock call runs on the calling thread and a
 * {@code [WBOMBO-COMPAT] FALLBACK} line is logged. Kill switch:
 * {@code -Dboot_optim.yaclImagePreparation=false}.</p>
 */
public final class YaclImagePreparation {
    public static final String PROPERTY = "boot_optim.yaclImagePreparation";
    public static final String MECHANISM = "yacl-image-preparation";
    public static final String MOD_ID = "yet_another_config_lib_v3";
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty(PROPERTY, "true"));
    private static final AtomicBoolean DISABLED = new AtomicBoolean();

    private YaclImagePreparation() {}

    /** True when this {@code prepare} call may be dispatched; everything else stays stock. */
    public static boolean shouldDispatch() {
        if (!ENABLED || DISABLED.get()) {
            return false;
        }
        Minecraft client = Minecraft.getInstance();
        return client != null && client.isGameLoadFinished();
    }

    /**
     * Runs {@code prepare} on {@code executor} and returns its future. A failure of the dispatched
     * call falls back to a stock call on the current thread, so the result is the stock result.
     */
    public static CompletableFuture<Object> dispatch(Supplier<CompletableFuture<?>> prepare, Executor executor) {
        CompletableFuture<Object> dispatched = CompletableFuture.supplyAsync(prepare, executor)
                .thenCompose(future -> future.thenApply(value -> (Object) value));
        dispatched.thenRun(() -> CompatLog.active(MECHANISM, MOD_ID));
        return dispatched.exceptionallyCompose(failure -> {
            if (DISABLED.compareAndSet(false, true)) {
                CompatLog.fallback(MECHANISM, MOD_ID, "dispatched_prepare_failed " + failure);
            }
            return prepare.get().thenApply(value -> (Object) value);
        });
    }
}
