package dev.wachipayox.bootoptim.optimization.client;

import dev.wachipayox.bootoptim.compat.client.DecocraftContracts;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owned, non-escaping staging for one Decocraft {@code BlockbenchBakery}: while one {@code bakeQuad}
 * runs on the owning thread, the four {@code VertexData} temporaries (five floats each) and the
 * selected-position {@code Vector3} are reused for the next quad instead of being allocated.
 *
 * <p>The final {@code BakedQuad}, its {@code int[]} payload and the array of four vertex references
 * stay fresh and independent per quad. The scratch is a lease: the first thread to bake owns it, any
 * other thread takes the original allocations, and a re-entrant or interrupted {@code bakeQuad}
 * (depth other than 1) disables the mechanism with {@code [WBOMBO-COMPAT] FALLBACK}. Unexpected
 * {@code VertexData}/{@code Vector3} field shapes also disable it. The mixin sites are only applied
 * while nine Decocraft method bodies match the audited code (including the local index of the vertex
 * loop), see {@code DecocraftContracts}.</p>
 *
 * <p>Allocation only (mobile, whole bake: 448 → 224 B per quad; needed output 192 B/quad). Kill
 * switch: {@code -Dboot_optim.decocraftBakeScratch=false}.</p>
 */
public final class DecocraftBakeScratch {
    private static final String MECHANISM = "decocraft-bake-scratch";
    private static final String VERTEX = "com.razz.decocraft.models.bbmodel.BlockbenchBakery$VertexData";
    private static final String VECTOR = "com.razz.decocraft.models.libgdx.Vector3";
    private static final AtomicBoolean REPORTED = new AtomicBoolean();

    private final Object[] vertices = new Object[4];
    private volatile long owner;
    private int depth;
    private boolean disabled;
    private Class<?> vertexType;
    private Object position;
    private VarHandle x;
    private VarHandle y;
    private VarHandle z;

    public static boolean active() {
        return DecocraftContracts.active(MECHANISM);
    }

    private synchronized void claim() {
        if (owner == 0) {
            owner = Thread.currentThread().threadId();
        }
    }

    public void enter() {
        if (owner == 0) {
            claim();
        }
        if (owner != Thread.currentThread().threadId()) {
            return;
        }
        if (depth != 0) {
            fallback("nested_or_interrupted_bake");
        }
        depth++;
    }

    public void exit() {
        if (owner == Thread.currentThread().threadId() && depth > 0) {
            depth--;
        }
    }

    private boolean eligible() {
        return !disabled && depth == 1 && owner == Thread.currentThread().threadId();
    }

    private void fallback(String reason) {
        disabled = true;
        DecocraftContracts.disable(MECHANISM, reason);
    }

    public Object vertex(int index) {
        if (eligible() && index >= 0 && index < 4 && vertices[index] != null) {
            if (REPORTED.compareAndSet(false, true)) {
                DecocraftContracts.used(MECHANISM);
            }
            return vertices[index];
        }
        return null;
    }

    public void rememberVertex(int index, Object created) {
        if (!eligible() || index < 0 || index >= 4 || vertices[index] != null) {
            return;
        }
        if (vertexType == null) {
            Class<?> type = created.getClass();
            Set<String> fields = new HashSet<>();
            for (var field : type.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    if (field.getType() != float.class) {
                        fallback("vertex_fields_changed");
                        return;
                    }
                    fields.add(field.getName());
                }
            }
            if (!type.getName().equals(VERTEX) || !fields.equals(Set.of("x", "y", "z", "u", "v"))) {
                fallback("vertex_shape_changed");
                return;
            }
            vertexType = type;
        }
        if (created.getClass() != vertexType) {
            fallback("vertex_type_changed");
            return;
        }
        vertices[index] = created;
    }

    public Object position(float a, float b, float c) {
        if (!eligible() || position == null) {
            return null;
        }
        x.set(position, a);
        y.set(position, b);
        z.set(position, c);
        return position;
    }

    public void rememberPosition(Object created) {
        if (!eligible() || position != null) {
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
            position = created;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            fallback("position_shape_changed");
        }
    }
}
