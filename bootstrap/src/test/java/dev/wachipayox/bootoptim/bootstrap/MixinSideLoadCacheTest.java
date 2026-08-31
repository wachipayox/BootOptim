package dev.wachipayox.bootoptim.bootstrap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cpw.mods.modlauncher.serviceapi.ILaunchPluginService;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MixinSideLoadCacheTest {
    @Test
    void reusesOnlySuccessfulRepeatedRequestsAndKeepsCachedSnapshotImmutable() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        byte[] authoritative = new byte[] {1, 2, 3, 4};
        ILaunchPluginService.ITransformerLoader delegate = className -> {
            calls.incrementAndGet();
            return authoritative;
        };

        MixinSideLoadCache cache = new MixinSideLoadCache(delegate);
        byte[] first = cache.buildTransformedClassNodeFor("example.Target");
        first[0] = 99;
        byte[] second = cache.buildTransformedClassNodeFor("example.Target");
        byte[] third = cache.buildTransformedClassNodeFor("example.Target");

        assertEquals(1, calls.get(), "only the first request may invoke the stock transformer loader");
        assertArrayEquals(new byte[] {1, 2, 3, 4}, second, "caller mutation must not corrupt the cache snapshot");
        assertArrayEquals(second, third);
        assertNotSame(second, third, "cache hits must return defensive copies");
    }

    @Test
    void doesNotCacheFailures() {
        AtomicInteger calls = new AtomicInteger();
        ILaunchPluginService.ITransformerLoader delegate = className -> {
            calls.incrementAndGet();
            throw new ClassNotFoundException(className);
        };

        MixinSideLoadCache cache = new MixinSideLoadCache(delegate);
        assertThrows(ClassNotFoundException.class, () -> cache.buildTransformedClassNodeFor("missing.Type"));
        assertThrows(ClassNotFoundException.class, () -> cache.buildTransformedClassNodeFor("missing.Type"));
        assertEquals(2, calls.get(), "failures must always fall back to the authoritative loader");
    }
}
