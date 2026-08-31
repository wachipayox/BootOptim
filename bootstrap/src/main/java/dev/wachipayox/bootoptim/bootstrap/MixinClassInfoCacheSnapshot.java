package dev.wachipayox.bootoptim.bootstrap;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import sun.misc.Unsafe;

/** Read-only shutdown-time snapshot of Mixin's ClassInfo metadata cache. */
final class MixinClassInfoCacheSnapshot {
    private static final String CLASS_INFO = "org.spongepowered.asm.mixin.transformer.ClassInfo";

    private MixinClassInfoCacheSnapshot() {
    }

    static Set<String> nullEntries(ClassLoader mixinClassLoader) {
        try {
            // At shutdown ClassInfo has already been used on any launch where this diagnostic matters.
            // Initialising it here only affects the degenerate case where Mixin never touched ClassInfo.
            Class<?> classInfo = Class.forName(CLASS_INFO, true, mixinClassLoader);
            Field cacheField = classInfo.getDeclaredField("cache");
            Unsafe unsafe = unsafe();
            Object base = unsafe.staticFieldBase(cacheField);
            Object value = unsafe.getObject(base, unsafe.staticFieldOffset(cacheField));
            if (!(value instanceof Map<?, ?> cache)) {
                return Set.of();
            }

            HashSet<String> nullEntries = new HashSet<>();
            for (Map.Entry<?, ?> entry : cache.entrySet()) {
                if (entry.getValue() == null && entry.getKey() instanceof String name) {
                    nullEntries.add(name);
                }
            }
            return nullEntries;
        } catch (Throwable t) {
            StartupDiagnostics.failure("mixin_classinfo_cache_snapshot", t);
            return Set.of();
        }
    }

    private static Unsafe unsafe() throws ReflectiveOperationException {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }
}
