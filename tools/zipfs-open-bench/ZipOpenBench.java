/*
 * Diagnostic only, not part of the shipped mod. See docs/research/zipfs-jar-open-isWritable-2026-10-16.md.
 *
 * Times one ZipFileSystem open + close per .jar under a directory (recursive), through the same
 * provider call that SecureJarHandler's UnionFileSystem makes
 * (FileSystems.newFileSystem(path) == provider.newFileSystem(path, emptyMap)).
 *
 *   stock    -> env {}                       JDK 25 runs Files.isWritable(zip) on every open
 *   readonly -> env {accessMode=readOnly}    isWritable is skipped; reads are identical
 *
 * Usage (single-file source launcher, Java 11+):
 *   java ZipOpenBench.java <dir> <stock|readonly> [rounds]
 *
 * Read-only: opens files for reading and never writes. Run it only in the user's turn on the PC.
 */
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.spi.FileSystemProvider;
import java.util.List;
import java.util.Map;

public class ZipOpenBench {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: java ZipOpenBench.java <dir> <stock|readonly> [rounds]");
            System.exit(2);
        }
        Path dir = Path.of(args[0]);
        String mode = args[1];
        if (!mode.equals("stock") && !mode.equals("readonly")) {
            throw new IllegalArgumentException("mode must be stock or readonly: " + mode);
        }
        int rounds = args.length > 2 ? Integer.parseInt(args[2]) : 1;

        Map<String, ?> env = mode.equals("readonly") ? Map.of("accessMode", "readOnly") : Map.of();
        FileSystemProvider zip = FileSystemProvider.installedProviders().stream()
                .filter(p -> p.getScheme().equals("jar"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("jdk.zipfs provider not installed"));

        List<Path> jars;
        try (var entries = Files.walk(dir)) {
            jars = entries.filter(p -> p.getFileName().toString().endsWith(".jar") && Files.isRegularFile(p))
                    .sorted().toList();
        }
        if (jars.isEmpty()) {
            throw new IllegalStateException("no .jar files in " + dir);
        }

        for (int round = 0; round < rounds; round++) {
            long start = System.nanoTime();
            for (Path jar : jars) {
                try (FileSystem fs = zip.newFileSystem(jar, env)) {
                    fs.getRootDirectories().iterator().next(); // touch the root, as the loaders do
                }
            }
            long ms = (System.nanoTime() - start) / 1_000_000L;
            System.out.printf("round=%d mode=%s dir=%s jars=%d total_ms=%d per_open_ms=%.2f%n",
                    round, mode, dir, jars.size(), ms, ms / (double) jars.size());
        }
    }
}
