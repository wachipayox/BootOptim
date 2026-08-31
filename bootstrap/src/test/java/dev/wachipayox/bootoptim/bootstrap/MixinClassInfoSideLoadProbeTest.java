package dev.wachipayox.bootoptim.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cpw.mods.modlauncher.serviceapi.ILaunchPluginService;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MixinClassInfoSideLoadProbeTest {
    @Test
    void delegatesEveryRequestWithoutChangingResultsOrFailures() throws Exception {
        byte[] expected = new byte[] {1, 2, 3, 4};
        ClassNotFoundException expectedFailure = new ClassNotFoundException("missing.Type");
        AtomicInteger calls = new AtomicInteger();

        ILaunchPluginService.ITransformerLoader delegate = className -> {
            calls.incrementAndGet();
            if ("ok.Type".equals(className)) {
                return expected;
            }
            if ("missing.Type".equals(className)) {
                throw expectedFailure;
            }
            throw new AssertionError("Unexpected class " + className);
        };

        MixinClassInfoSideLoadProbe probe = new MixinClassInfoSideLoadProbe(
                delegate,
                MixinClassInfoSideLoadProbeTest.class.getClassLoader());

        assertSame(expected, probe.buildTransformedClassNodeFor("ok.Type"));
        assertSame(expectedFailure,
                assertThrows(ClassNotFoundException.class,
                        () -> probe.buildTransformedClassNodeFor("missing.Type")));
        assertSame(expectedFailure,
                assertThrows(ClassNotFoundException.class,
                        () -> probe.buildTransformedClassNodeFor("missing.Type")));

        assertEquals(3, calls.get(), "observe-only probe must delegate repeated requests too");
        assertEquals(1L, probe.repeatedClassNotFoundForTests());
    }
}
