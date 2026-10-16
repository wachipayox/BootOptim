package dev.wachipayox.bootoptim.zipfsagent;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.ReadOnlyFileSystemException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Smoke test for the agent. Run once without and once with {@code -javaagent}; the expectations depend on the
 * {@code expectAgent} argument. Touches only a temporary folder given as the first argument.
 *
 * <p>Usage: {@code java -javaagent:zipfs-readonly-agent.jar -cp <out> ...SelfTest <tmpRoot> <expectAgent>}
 */
public final class SelfTest {

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        boolean expectAgent = Boolean.parseBoolean(args[1]);
        Files.createDirectories(root);
        Path status = root.resolve("status.log");
        Files.deleteIfExists(status);
        System.setProperty(ZipfsReadOnlyPolicy.STATUS_PROPERTY, status.toString());

        Path mods = root.resolve(".minecraft").resolve("mods");
        Path library = root.resolve("libraries").resolve("net").resolve("x").resolve("lib.jar");
        Path outside = root.resolve("outside").resolve("other.jar");
        Path modJar = mods.resolve("mod.jar");
        for (Path jar : List.of(modJar, library, outside)) {
            writeJar(jar);
        }

        List<String> failures = new ArrayList<>();
        expect(failures, "mods jar readOnly", readOnly(modJar, Map.of()), expectAgent);
        expect(failures, "libraries jar readOnly", readOnly(library, Map.of()), expectAgent);
        expect(failures, "outside jar stays writable", readOnly(outside, Map.of()), false);
        expect(failures, "create=true untouched", readOnly(outside, Map.of("create", false)), false);
        expect(failures, "explicit accessMode untouched", readOnly(modJar, Map.of("accessMode", "readWrite")), false);

        if (expectAgent) {
            try (FileSystem fs = FileSystems.newFileSystem(modJar, Map.of())) {
                try {
                    Files.writeString(fs.getPath("/written-by-test.txt"), "x", StandardCharsets.UTF_8);
                    failures.add("write through zipfs on managed jar was accepted");
                } catch (ReadOnlyFileSystemException expected) {
                    // expected: the managed jar is opened read-only
                }
            }
        }

        String statusText = Files.exists(status) ? Files.readString(status, StandardCharsets.UTF_8) : "";
        long active = statusText.lines().filter(line -> line.contains(
                "ACTIVE project=BootOptim mechanism=zipfs-readonly-agent")).count();
        long fallback = statusText.lines().filter(line -> line.contains("FALLBACK project=BootOptim")).count();
        expect(failures, "status ACTIVE lines", (int) active, expectAgent ? 1 : 0);
        expect(failures, "status FALLBACK lines", (int) fallback, 0);

        if (failures.isEmpty()) {
            System.out.println("SELFTEST PASS expectAgent=" + expectAgent);
        } else {
            failures.forEach(line -> System.out.println("SELFTEST FAIL " + line));
            System.exit(1);
        }
    }

    private static boolean readOnly(Path jar, Map<String, ?> env) throws IOException {
        try (FileSystem fs = FileSystems.newFileSystem(jar, env)) {
            return fs.isReadOnly();
        }
    }

    private static void expect(List<String> failures, String label, int actual, int expected) {
        if (actual != expected) {
            failures.add(label + ": expected " + expected + " got " + actual);
        }
    }

    private static void expect(List<String> failures, String label, boolean actual, boolean expected) {
        if (actual != expected) {
            failures.add(label + ": expected " + expected + " got " + actual);
        }
    }

    private static void writeJar(Path jar) throws IOException {
        Files.createDirectories(jar.getParent());
        try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            zip.write("Manifest-Version: 1.0\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }
}
