package dev.wachipayox.bootoptim.optimization.client;

import dev.wachipayox.bootoptim.compat.client.DecocraftContracts;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Replaces each parsed Decocraft element's {@code faces} {@link LinkedHashMap} (at most six
 * entries, 384 B of table and entry objects per map in the reference runtime) by a
 * {@link SmallOrderedMap} with the same iteration order, mutability and views.
 *
 * <p>Runs at the return of the loader's two public {@code loadModel} entry points, before the
 * original caller can publish the model, and only when the whole model has the exact audited
 * shape: every element is a {@code BBModelParts$Element}, {@code faces} is a plain public
 * non-final {@code Map} field holding a {@code LinkedHashMap} of at most six {@code Direction}
 * keys and {@code BBModelParts$Face} values. The shape is checked for the complete model before
 * the first write, and the new map is compared entry by entry with the old one; any mismatch
 * leaves the model untouched and disables the mechanism with {@code [WBOMBO-COMPAT] FALLBACK}.
 * The {@code Face}, UV and vector objects stay independent and are not shared.</p>
 *
 * <p>Kill switch: {@code -Dboot_optim.decocraftCompactFaceMaps=false}.</p>
 */
public final class DecocraftFaceMaps {
    private static final String MECHANISM = "decocraft-compact-face-maps";
    private static final Logger LOGGER = LoggerFactory.getLogger("BootOptim/DecocraftFaceMaps");
    private static final String MODEL = "com.razz.decocraft.models.bbmodel.BBModel";
    private static final String ELEMENT = "com.razz.decocraft.models.bbmodel.BBModelParts$Element";
    private static final String FACE = "com.razz.decocraft.models.bbmodel.BBModelParts$Face";
    private static final String DIRECTION = "net.minecraft.core.Direction";
    private static final int MAX_ELEMENTS = 100_000;
    private static final int MAX_FACES = 6;

    private static long models;
    private static long maps;
    private static long entries;

    private record Replacement(Object owner, VarHandle field, Map<?, ?> original, Map<?, ?> compact) {}

    private DecocraftFaceMaps() {}

    public static void compact(Object model) {
        if (model == null || !DecocraftContracts.active(MECHANISM)) {
            return;
        }
        try {
            if (!model.getClass().getName().equals(MODEL)) {
                throw new IllegalArgumentException("model_type");
            }
            Object value = model.getClass().getField("elements").get(model);
            if (!(value instanceof List<?> elements) || elements.size() > MAX_ELEMENTS) {
                throw new IllegalArgumentException("elements_shape");
            }
            List<Replacement> planned = new ArrayList<>();
            Map<Class<?>, VarHandle> handles = new HashMap<>();
            IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
            for (Object element : elements) {
                if (element == null || !element.getClass().getName().equals(ELEMENT)
                        || seen.put(element, Boolean.TRUE) != null) {
                    continue;
                }
                Class<?> type = element.getClass();
                VarHandle handle = handles.get(type);
                if (handle == null) {
                    var field = type.getField("faces");
                    int flags = field.getModifiers();
                    if (field.getType() != Map.class || Modifier.isStatic(flags) || Modifier.isFinal(flags)) {
                        throw new IllegalArgumentException("faces_field_shape");
                    }
                    handle = MethodHandles.publicLookup().findVarHandle(type, "faces", Map.class);
                    handles.put(type, handle);
                }
                Object current = handle.get(element);
                if (current instanceof SmallOrderedMap<?, ?>) {
                    continue;
                }
                if (!(current instanceof Map<?, ?> map) || current.getClass() != LinkedHashMap.class
                        || map.size() > MAX_FACES) {
                    throw new IllegalArgumentException("faces_map_shape");
                }
                for (var entry : map.entrySet()) {
                    if (!(entry.getKey() instanceof Enum<?> direction)
                            || !direction.getDeclaringClass().getName().equals(DIRECTION)) {
                        throw new IllegalArgumentException("face_key_shape");
                    }
                    if (entry.getValue() != null && !entry.getValue().getClass().getName().equals(FACE)) {
                        throw new IllegalArgumentException("face_value_shape");
                    }
                }
                SmallOrderedMap<?, ?> compact = new SmallOrderedMap<>(map);
                if (!new ArrayList<>(map.entrySet()).equals(new ArrayList<>(compact.entrySet()))) {
                    throw new IllegalStateException("ordered_copy_changed");
                }
                planned.add(new Replacement(element, handle, map, compact));
            }
            for (Replacement next : planned) {
                if (next.field().get(next.owner()) != next.original()) {
                    throw new IllegalStateException("faces_changed_during_preflight");
                }
            }
            long changed = 0;
            long values = 0;
            for (Replacement next : planned) {
                next.field().set(next.owner(), next.compact());
                changed++;
                values += next.original().size();
            }
            record(changed, values);
        } catch (Exception | LinkageError changed) {
            DecocraftContracts.disable(MECHANISM, "parser_data_contract_" + changed.getClass().getSimpleName());
        }
    }

    private static synchronized void record(long changed, long values) {
        models++;
        maps += changed;
        entries += values;
        if (models == 1) {
            DecocraftContracts.used(MECHANISM);
        }
        if (models == 1 || models % 1000 == 0) {
            LOGGER.info("BOOTOPTIM_DECOCRAFT_FACE_MAPS models={} maps={} entries={}", models, maps, entries);
        }
    }
}
