package dev.wachipayox.bootoptim.optimization.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.wachipayox.bootoptim.compat.client.DecocraftContracts;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.Direction;

/**
 * Hands Decocraft's {@code BlockbenchBakery.calculateFacing} one shared {@link Direction#values()}
 * array instead of a fresh clone per call (the method runs once per baked quad).
 *
 * <p>Only that call site is wrapped, and only while the method body still matches the audited
 * instruction stream ({@link DecocraftContracts}): the loop reads the array and never writes it.
 * The enum's own {@code values()} and every other caller are untouched. Kill switch:
 * {@code -Dboot_optim.decocraftPrivateDirections=false}.</p>
 */
public final class DecocraftPrivateDirections {
    private static final String MECHANISM = "decocraft-private-directions";
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final AtomicBoolean REPORTED = new AtomicBoolean();

    private DecocraftPrivateDirections() {}

    public static Direction[] directions(Operation<Direction[]> original) {
        if (!DecocraftContracts.active(MECHANISM)) {
            return original.call();
        }
        if (REPORTED.compareAndSet(false, true)) {
            DecocraftContracts.used(MECHANISM);
        }
        return DIRECTIONS;
    }
}
