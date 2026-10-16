package dev.wachipayox.bootoptim.optimization.client;

import dev.wachipayox.bootoptim.compat.client.DecocraftContracts;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicBoolean;
import org.joml.Matrix4f;

/**
 * Two temporaries of Decocraft's {@code BlockbenchBakery} rotation helpers, reused only at the
 * audited sites (the method bodies are fingerprint-guarded, see {@code DecocraftContracts}):
 *
 * <ul>
 *   <li>{@code applyModelRotation} builds a fresh identity {@link Matrix4f} just to call
 *       {@code equals} on it; a shared never-mutated identity gives the same answer. Only an
 *       argument of exactly {@code Matrix4f.class} is compared against it, so a subclass with its
 *       own {@code equals} still gets a fresh matrix.</li>
 *   <li>{@code applyElementRotation} copies the element origin into a new {@code Vector3} that the
 *       method only reads afterwards; one owned snapshot per bakery instance and thread is
 *       overwritten instead. It is never an alias of a model's mutable origin, other threads and
 *       an unexpected {@code Vector3} shape take the original constructor.</li>
 * </ul>
 *
 * <p>Allocation only (mobile, warmed synthetic paths: 80 → 0 and 24 → 0 B per call); it does not
 * change retained memory or promise time. Kill switch: {@code -Dboot_optim.decocraftRotationTemporaries=false}.</p>
 */
public final class DecocraftRotationScratch {
    private static final String MECHANISM = "decocraft-rotation-temporaries";
    private static final String VECTOR = "com.razz.decocraft.models.libgdx.Vector3";
    private static final AtomicBoolean REPORTED = new AtomicBoolean();

    private Object vector;
    private VarHandle x;
    private VarHandle y;
    private VarHandle z;
    private long owner;
    private boolean unavailable;

    public static boolean active() {
        return DecocraftContracts.active(MECHANISM);
    }

    /** True when the shared identity may be compared with this exact-class argument. */
    public static boolean activeFor(Matrix4f matrix) {
        if (!active() || matrix == null || matrix.getClass() != Matrix4f.class) {
            return false;
        }
        if (REPORTED.compareAndSet(false, true)) {
            DecocraftContracts.used(MECHANISM);
        }
        return true;
    }

    public synchronized Object reuse(float a, float b, float c) {
        if (unavailable || vector == null || owner != Thread.currentThread().threadId()) {
            return null;
        }
        x.set(vector, a);
        y.set(vector, b);
        z.set(vector, c);
        if (REPORTED.compareAndSet(false, true)) {
            DecocraftContracts.used(MECHANISM);
        }
        return vector;
    }

    public synchronized void initialize(Object created) {
        if (vector != null || unavailable) {
            return;
        }
        try {
            Class<?> type = created.getClass();
            if (!type.getName().equals(VECTOR)) {
                throw new IllegalStateException();
            }
            for (String name : new String[] {"x", "y", "z"}) {
                var field = type.getField(name);
                if (field.getType() != float.class || Modifier.isStatic(field.getModifiers())
                        || Modifier.isFinal(field.getModifiers())) {
                    throw new IllegalStateException();
                }
            }
            var lookup = MethodHandles.publicLookup();
            x = lookup.findVarHandle(type, "x", float.class);
            y = lookup.findVarHandle(type, "y", float.class);
            z = lookup.findVarHandle(type, "z", float.class);
            owner = Thread.currentThread().threadId();
            vector = created;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            unavailable = true;
            DecocraftContracts.disable(MECHANISM, "origin_vector_shape_changed");
        }
    }
}
