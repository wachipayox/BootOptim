package dev.wachipayox.bootoptim.optimization.client;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Points equal embedded {@code data:image} source Strings of the given objects at one String
 * instance. Text is never changed, the objects stay distinct and nothing is closed or freed.
 *
 * <p>The whole shape is validated before the first write. A field is replaced with a
 * compare-and-set, so a value the owning mod replaced meanwhile is left alone. The canonical
 * instance comes from a map local to the call (no {@link String#intern()}, no JVM-wide table).</p>
 */
public final class ExactSourceStrings {
    public record Result(int fields, int replaced, long uniqueCharactersBefore, long uniqueCharactersAfter) {}

    private record Access(Object target, VarHandle source) {}

    private static final int MAX_TEXTURES = 20_000;
    private static final long MAX_CHARACTERS = 64L * 1024 * 1024;

    private ExactSourceStrings() {}

    public static Result share(Collection<?> textures) throws ReflectiveOperationException {
        if (textures.size() > MAX_TEXTURES) {
            throw new IllegalArgumentException("texture_count_limit");
        }
        Map<Class<?>, VarHandle> handles = new HashMap<>();
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        IdentityHashMap<String, Boolean> before = new IdentityHashMap<>();
        List<Access> accesses = new ArrayList<>();
        long characters = 0;
        // Validate the complete shape before changing any source reference.
        for (Object texture : textures) {
            if (texture == null || seen.put(texture, Boolean.TRUE) != null) {
                continue;
            }
            Class<?> type = texture.getClass();
            VarHandle handle = handles.get(type);
            if (handle == null) {
                var field = type.getField("source");
                int modifiers = field.getModifiers();
                if (field.getType() != String.class || Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers)) {
                    throw new IllegalArgumentException("source_field_shape");
                }
                handle = MethodHandles.publicLookup().findVarHandle(type, "source", String.class);
                handles.put(type, handle);
            }
            String value = (String) handle.getVolatile(texture);
            if (!eligible(value)) {
                continue;
            }
            if (before.put(value, Boolean.TRUE) == null) {
                characters += value.length();
            }
            if (characters > MAX_CHARACTERS) {
                throw new IllegalArgumentException("source_character_limit");
            }
            accesses.add(new Access(texture, handle));
        }
        Map<String, String> canonicalByText = new HashMap<>();
        int replaced = 0;
        for (Access access : accesses) {
            String original = (String) access.source().getVolatile(access.target());
            if (!eligible(original)) {
                continue;
            }
            String canonical = canonicalByText.putIfAbsent(original, original);
            if (canonical != null && canonical != original
                    && access.source().compareAndSet(access.target(), original, canonical)) {
                replaced++;
            }
        }
        IdentityHashMap<String, Boolean> after = new IdentityHashMap<>();
        long retained = 0;
        for (Access access : accesses) {
            String value = (String) access.source().getVolatile(access.target());
            if (eligible(value) && after.put(value, Boolean.TRUE) == null) {
                retained += value.length();
            }
        }
        return new Result(accesses.size(), replaced, characters, retained);
    }

    private static boolean eligible(String value) {
        return value != null
                && value.length() >= 1024
                && value.length() <= 4 * 1024 * 1024
                && value.startsWith("data:image/")
                && value.indexOf(";base64,") > 0;
    }
}
