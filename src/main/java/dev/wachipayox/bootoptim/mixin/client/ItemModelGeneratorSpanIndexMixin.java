package dev.wachipayox.bootoptim.mixin.client;

import com.google.common.collect.Lists;
import net.minecraft.client.renderer.block.model.ItemModelGenerator;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

/**
 * Experimental replacement for the linear span lookup inside {@link ItemModelGenerator#getSpans}.
 *
 * <p>Vanilla creates at most one Span for each (SpanFacing, anchor) pair. Despite that invariant,
 * createOrExpandSpan linearly scans every previously-created Span for every opaque-to-transparent
 * transition. This mixin keeps the exact vanilla Span objects and exact vanilla create/expand code,
 * but indexes those objects by their already-unique key so the search is O(1).</p>
 *
 * <p>No private Span is instantiated, copied or mutated by BootOptim. If any unexpected call shape is
 * observed, the custom list marks itself unsafe and subsequent lookups fall back to the stock iterator.</p>
 */
@Mixin(ItemModelGenerator.class)
abstract class ItemModelGeneratorSpanIndexMixin {
    @Unique private static final Logger BOOTOPTIM$LOGGER = LoggerFactory.getLogger("BootOptim/GeneratedItemSpanIndex");
    @Unique private static final boolean BOOTOPTIM$ENABLED = Boolean.parseBoolean(
            System.getProperty("boot_optim.generatedItemSpanIndex", "true"));
    @Unique private static final ThreadLocal<Long> BOOTOPTIM$SPAN_START = ThreadLocal.withInitial(() -> 0L);
    @Unique private static final LongAdder BOOTOPTIM$GET_SPANS_CALLS = new LongAdder();
    @Unique private static final LongAdder BOOTOPTIM$GET_SPANS_NS = new LongAdder();
    @Unique private static final LongAdder BOOTOPTIM$INDEXED_LOOKUPS = new LongAdder();
    @Unique private static final LongAdder BOOTOPTIM$STOCK_COMPARISONS_SKIPPED = new LongAdder();
    @Unique private static final LongAdder BOOTOPTIM$CREATED_SPANS = new LongAdder();
    @Unique private static final LongAdder BOOTOPTIM$FALLBACK_LOOKUPS = new LongAdder();
    @Unique private static final LongAdder BOOTOPTIM$ANOMALIES = new LongAdder();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> BOOTOPTIM$LOGGER.info(
                "BOOTOPTIM_GENERATED_ITEM_SPAN_INDEX summary=shutdown enabled={} get_spans_calls={} get_spans_ms={} indexed_lookups={} stock_comparisons_skipped={} created_spans={} fallback_lookups={} anomalies={}",
                BOOTOPTIM$ENABLED,
                BOOTOPTIM$GET_SPANS_CALLS.sum(),
                String.format(java.util.Locale.ROOT, "%.3f", BOOTOPTIM$GET_SPANS_NS.sum() / 1_000_000.0),
                BOOTOPTIM$INDEXED_LOOKUPS.sum(),
                BOOTOPTIM$STOCK_COMPARISONS_SKIPPED.sum(),
                BOOTOPTIM$CREATED_SPANS.sum(),
                BOOTOPTIM$FALLBACK_LOOKUPS.sum(),
                BOOTOPTIM$ANOMALIES.sum()), "BootOptim-generated-item-span-index-report"));
    }

    @Inject(
            method = "getSpans(Lnet/minecraft/client/renderer/texture/SpriteContents;)Ljava/util/List;",
            at = @At("HEAD"))
    private void bootoptim$beginGetSpans(SpriteContents sprite, CallbackInfoReturnable<List<?>> cir) {
        BOOTOPTIM$SPAN_START.set(System.nanoTime());
    }

    @Inject(
            method = "getSpans(Lnet/minecraft/client/renderer/texture/SpriteContents;)Ljava/util/List;",
            at = @At("RETURN"))
    private void bootoptim$endGetSpans(SpriteContents sprite, CallbackInfoReturnable<List<?>> cir) {
        long start = BOOTOPTIM$SPAN_START.get();
        BOOTOPTIM$SPAN_START.set(0L);
        if (start != 0L) {
            BOOTOPTIM$GET_SPANS_CALLS.increment();
            BOOTOPTIM$GET_SPANS_NS.add(System.nanoTime() - start);
        }
    }

    @Redirect(
            method = "getSpans(Lnet/minecraft/client/renderer/texture/SpriteContents;)Ljava/util/List;",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/google/common/collect/Lists;newArrayList()Ljava/util/ArrayList;"))
    private ArrayList<?> bootoptim$createSpanList(SpriteContents sprite) {
        if (!BOOTOPTIM$ENABLED) {
            return Lists.newArrayList();
        }
        return new IndexedSpanList(Math.max(sprite.width(), sprite.height()));
    }

    @Redirect(
            method = "createOrExpandSpan",
            at = @At(value = "INVOKE", target = "Ljava/util/List;iterator()Ljava/util/Iterator;"))
    @SuppressWarnings({"rawtypes", "unchecked"})
    private Iterator<?> bootoptim$findIndexedSpan(
            List<?> iteratorReceiver,
            List<?> spansArgument,
            @Coerce Object facing,
            int x,
            int y) {
        if (iteratorReceiver != spansArgument) {
            BOOTOPTIM$FALLBACK_LOOKUPS.increment();
            return iteratorReceiver.iterator();
        }
        if (BOOTOPTIM$ENABLED && iteratorReceiver instanceof IndexedSpanList indexed) {
            return indexed.iteratorFor(facing, x, y);
        }
        return iteratorReceiver.iterator();
    }

    @Unique
    private static final class IndexedSpanList extends ArrayList<Object> {
        private final Object[][] byKey;
        private final int[][] insertionIndex;
        private int pendingFacing = -1;
        private int pendingAnchor = -1;
        private boolean pendingExpectedNew;
        private boolean unsafe;

        private IndexedSpanList(int maxAnchor) {
            super(Math.max(8, maxAnchor * 2));
            this.byKey = new Object[4][Math.max(1, maxAnchor)];
            this.insertionIndex = new int[4][Math.max(1, maxAnchor)];
            for (int i = 0; i < insertionIndex.length; i++) {
                java.util.Arrays.fill(insertionIndex[i], -1);
            }
        }

        private Iterator<?> iteratorFor(Object facing, int x, int y) {
            clearPending();
            if (unsafe || !(facing instanceof Enum<?> enumFacing)) {
                BOOTOPTIM$FALLBACK_LOOKUPS.increment();
                return super.iterator();
            }

            int ordinal = enumFacing.ordinal();
            String name = enumFacing.name();
            boolean horizontal;
            if ("UP".equals(name) || "DOWN".equals(name)) {
                horizontal = true;
            } else if ("LEFT".equals(name) || "RIGHT".equals(name)) {
                horizontal = false;
            } else {
                BOOTOPTIM$ANOMALIES.increment();
                unsafe = true;
                BOOTOPTIM$FALLBACK_LOOKUPS.increment();
                return super.iterator();
            }

            int anchor = horizontal ? y : x;
            if (ordinal < 0 || ordinal >= byKey.length || anchor < 0 || anchor >= byKey[ordinal].length) {
                BOOTOPTIM$ANOMALIES.increment();
                unsafe = true;
                BOOTOPTIM$FALLBACK_LOOKUPS.increment();
                return super.iterator();
            }

            BOOTOPTIM$INDEXED_LOOKUPS.increment();
            Object existing = byKey[ordinal][anchor];
            pendingFacing = ordinal;
            pendingAnchor = anchor;
            pendingExpectedNew = existing == null;

            if (existing == null) {
                // Stock would inspect every current span and find no matching key.
                BOOTOPTIM$STOCK_COMPARISONS_SKIPPED.add(size());
                return Collections.emptyIterator();
            }

            int stockPosition = insertionIndex[ordinal][anchor];
            if (stockPosition < 0 || stockPosition >= size() || get(stockPosition) != existing) {
                BOOTOPTIM$ANOMALIES.increment();
                unsafe = true;
                clearPending();
                BOOTOPTIM$FALLBACK_LOOKUPS.increment();
                return super.iterator();
            }

            // Vanilla would compare all earlier entries plus this one; we retain the final comparison itself.
            BOOTOPTIM$STOCK_COMPARISONS_SKIPPED.add(stockPosition);
            return Collections.singleton(existing).iterator();
        }

        @Override
        public boolean add(Object span) {
            int facing = pendingFacing;
            int anchor = pendingAnchor;
            boolean expectedNew = pendingExpectedNew;
            clearPending();

            boolean added = super.add(span);
            if (!added) {
                return false;
            }

            if (unsafe) {
                return true;
            }

            if (!expectedNew || facing < 0 || facing >= byKey.length || anchor < 0 || anchor >= byKey[facing].length
                    || byKey[facing][anchor] != null) {
                // A vanilla add without the preceding "no existing key" lookup means our structural assumption no
                // longer holds. Keep the list correct and make every later search use the stock iterator.
                BOOTOPTIM$ANOMALIES.increment();
                unsafe = true;
                return true;
            }

            byKey[facing][anchor] = span;
            insertionIndex[facing][anchor] = size() - 1;
            BOOTOPTIM$CREATED_SPANS.increment();
            return true;
        }

        private void clearPending() {
            pendingFacing = -1;
            pendingAnchor = -1;
            pendingExpectedNew = false;
        }
    }
}
