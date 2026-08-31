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
        if (cir.getReturnValue() instanceof IndexedSpanList indexed) {
            indexed.commitMetrics();
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
        return new IndexedSpanList(sprite.width(), sprite.height());
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
        private final int anchorCapacity;
        private final Object[] byKey;
        private final int[] insertionIndexPlusOne;
        private int pendingKey = -1;
        private boolean pendingExpectedNew;
        private boolean unsafe;
        private long indexedLookups;
        private long stockComparisonsSkipped;
        private long createdSpans;
        private long fallbackLookups;
        private long anomalies;

        private IndexedSpanList(int width, int height) {
            super(Math.max(8, 2 * (width + height)));
            this.anchorCapacity = Math.max(1, Math.max(width, height));
            this.byKey = new Object[4 * anchorCapacity];
            this.insertionIndexPlusOne = new int[4 * anchorCapacity];
        }

        private Iterator<?> iteratorFor(Object facing, int x, int y) {
            clearPending();
            if (unsafe || !(facing instanceof Enum<?> enumFacing)) {
                fallbackLookups++;
                return super.iterator();
            }

            int ordinal = enumFacing.ordinal();
            // Minecraft 1.21.1 declares SpanFacing as UP, DOWN, LEFT, RIGHT in exactly this order.
            // isHorizontal() is true only for the first two values.
            if (ordinal < 0 || ordinal >= 4) {
                anomalies++;
                unsafe = true;
                fallbackLookups++;
                return super.iterator();
            }

            int anchor = ordinal < 2 ? y : x;
            if (anchor < 0 || anchor >= anchorCapacity) {
                anomalies++;
                unsafe = true;
                fallbackLookups++;
                return super.iterator();
            }

            indexedLookups++;
            int key = ordinal * anchorCapacity + anchor;
            Object existing = byKey[key];
            pendingKey = key;
            pendingExpectedNew = existing == null;

            if (existing == null) {
                // Stock would inspect every current span and find no matching key.
                stockComparisonsSkipped += size();
                return Collections.emptyIterator();
            }

            int stockPosition = insertionIndexPlusOne[key] - 1;
            if (stockPosition < 0 || stockPosition >= size() || get(stockPosition) != existing) {
                anomalies++;
                unsafe = true;
                clearPending();
                fallbackLookups++;
                return super.iterator();
            }

            // Vanilla would compare all earlier entries plus this one; the singleton keeps the final stock key check.
            stockComparisonsSkipped += stockPosition;
            return Collections.singleton(existing).iterator();
        }

        @Override
        public boolean add(Object span) {
            int key = pendingKey;
            boolean expectedNew = pendingExpectedNew;
            clearPending();

            boolean added = super.add(span);
            if (!added) {
                return false;
            }

            if (unsafe) {
                return true;
            }

            if (!expectedNew || key < 0 || key >= byKey.length || byKey[key] != null) {
                // A vanilla add without the preceding "no existing key" lookup means our structural assumption no
                // longer holds. Keep the list correct and make every later search use the stock iterator.
                anomalies++;
                unsafe = true;
                return true;
            }

            byKey[key] = span;
            insertionIndexPlusOne[key] = size();
            createdSpans++;
            return true;
        }

        private void clearPending() {
            pendingKey = -1;
            pendingExpectedNew = false;
        }

        private void commitMetrics() {
            BOOTOPTIM$INDEXED_LOOKUPS.add(indexedLookups);
            BOOTOPTIM$STOCK_COMPARISONS_SKIPPED.add(stockComparisonsSkipped);
            BOOTOPTIM$CREATED_SPANS.add(createdSpans);
            BOOTOPTIM$FALLBACK_LOOKUPS.add(fallbackLookups);
            BOOTOPTIM$ANOMALIES.add(anomalies);
        }
    }
}
