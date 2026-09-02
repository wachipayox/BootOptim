package dev.wachipayox.bootoptim.profiling.client;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModFileInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Diagnostic ceiling experiment for the exact-pack Decocraft resource front.
 *
 * <p>This does not replace UnionFS, resource selection, ZIP entry decoding, PNG decoding or any
 * Minecraft resource API. It performs one sequential read of Decocraft's physical mod file just
 * before Minecraft starts the initial client resource reload, then discards the bytes. The only
 * intended effect is warming the operating-system page cache so the later stock ZipFS reads can be
 * compared against the cold/random-access case.</p>
 *
 * <p>This control build is deliberately disabled by default. Its runtime surface remains otherwise
 * identical to the enabled diagnostic build so exact-pack A/B can isolate the read-ahead itself.
 * Enable explicitly with {@code -Dboot_optim.experimentDecocraftJarReadahead=true}.</p>
 */
public final class DecocraftJarReadAheadCeiling {
    private static final Logger LOGGER = LoggerFactory.getLogger("BootOptim/DecocraftJarReadAhead");
    private static final String MOD_ID = "decocraft";
    private static final int BUFFER_BYTES = 1024 * 1024;
    private static final boolean ENABLED = Boolean.parseBoolean(
            System.getProperty("boot_optim.experimentDecocraftJarReadahead", "false"));
    private static final ThreadMXBean THREAD_MX_BEAN = ManagementFactory.getThreadMXBean();

    private DecocraftJarReadAheadCeiling() {}

    public static void runBeforeInitialResourceReload() {
        if (!ENABLED) {
            LOGGER.info("BOOTOPTIM_DECOCRAFT_JAR_READAHEAD status=disabled");
            return;
        }

        try {
            ModList modList = ModList.get();
            if (modList == null) {
                LOGGER.info("BOOTOPTIM_DECOCRAFT_JAR_READAHEAD status=skipped reason=mod_list_unavailable");
                return;
            }

            IModFileInfo modFileInfo = modList.getModFileById(MOD_ID);
            if (modFileInfo == null || modFileInfo.getFile() == null) {
                LOGGER.info("BOOTOPTIM_DECOCRAFT_JAR_READAHEAD status=skipped reason=mod_missing");
                return;
            }

            Path path = modFileInfo.getFile().getFilePath();
            if (path == null || !Files.isRegularFile(path)) {
                LOGGER.info(
                        "BOOTOPTIM_DECOCRAFT_JAR_READAHEAD status=skipped reason=non_regular_file provider={}",
                        path == null ? "null" : path.getFileSystem().provider().getScheme());
                return;
            }

            long fileBytes = Files.size(path);
            long wallStart = System.nanoTime();
            long cpuStart = currentThreadCpuNanos();
            long bytesRead = 0L;
            ByteBuffer buffer = ByteBuffer.allocate(BUFFER_BYTES);

            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
                while (true) {
                    buffer.clear();
                    int count = channel.read(buffer);
                    if (count < 0) {
                        break;
                    }
                    bytesRead += count;
                }
            }

            long cpuEnd = currentThreadCpuNanos();
            long wallNanos = System.nanoTime() - wallStart;
            long cpuNanos = cpuStart >= 0L && cpuEnd >= cpuStart ? cpuEnd - cpuStart : -1L;
            String fileName = path.getFileName() == null ? "unknown" : path.getFileName().toString();

            LOGGER.info(
                    "BOOTOPTIM_DECOCRAFT_JAR_READAHEAD status=completed file={} bytes_read={} file_bytes={} wall_ms={} cpu_ms={} cpu_time={}",
                    fileName,
                    bytesRead,
                    fileBytes,
                    millis(wallNanos),
                    cpuNanos >= 0L ? millis(cpuNanos) : "unavailable",
                    cpuNanos >= 0L);
        } catch (Exception exception) {
            LOGGER.warn(
                    "BOOTOPTIM_DECOCRAFT_JAR_READAHEAD status=failed_open reason={} message={}",
                    exception.getClass().getName(),
                    safeMessage(exception));
        }
    }

    private static long currentThreadCpuNanos() {
        if (!THREAD_MX_BEAN.isCurrentThreadCpuTimeSupported() || !THREAD_MX_BEAN.isThreadCpuTimeEnabled()) {
            return -1L;
        }
        return THREAD_MX_BEAN.getCurrentThreadCpuTime();
    }

    private static String millis(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0D);
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return "none";
        }
        return message.replace('\n', ' ').replace('\r', ' ');
    }
}
