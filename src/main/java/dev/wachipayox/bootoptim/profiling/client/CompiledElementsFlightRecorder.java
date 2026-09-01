package dev.wachipayox.bootoptim.profiling.client;

import java.nio.file.Files;
import java.nio.file.Path;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Optional allocation/CPU sampling scoped exactly to ModelBakery#bakeModels. */
public final class CompiledElementsFlightRecorder {
    private static final Logger LOGGER = LoggerFactory.getLogger("BootOptim/CompiledElementsJFR");
    private static Recording recording;

    private CompiledElementsFlightRecorder() {}

    public static void start() {
        String destination = System.getProperty("boot_optim.compiledElementsJfrPath", "").trim();
        if (destination.isEmpty() || recording != null) return;
        try {
            Path path = Path.of(destination).toAbsolutePath();
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);

            Recording next = new Recording(Configuration.getConfiguration("profile"));
            next.setName("BootOptim Compiled Elements bakeModels");
            next.setToDisk(true);
            next.setDestination(path);
            next.setMaxSize(128L * 1024L * 1024L);
            next.enable("jdk.ObjectAllocationSample").withStackTrace();
            next.enable("jdk.GarbageCollection");
            next.start();
            recording = next;
            LOGGER.info("BOOTOPTIM_COMPILED_ELEMENTS_JFR event=start path={}", path);
        } catch (Throwable failure) {
            LOGGER.warn("BOOTOPTIM_COMPILED_ELEMENTS_JFR event=disabled reason={}", failure.toString());
        }
    }

    public static void finish() {
        Recording current = recording;
        recording = null;
        if (current == null) return;
        try {
            current.stop();
            LOGGER.info("BOOTOPTIM_COMPILED_ELEMENTS_JFR event=stop destination={}", current.getDestination());
        } catch (Throwable failure) {
            LOGGER.warn("BOOTOPTIM_COMPILED_ELEMENTS_JFR event=stop_failed reason={}", failure.toString());
        } finally {
            current.close();
        }
    }
}
