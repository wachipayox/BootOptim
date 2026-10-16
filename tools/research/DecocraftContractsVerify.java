import dev.wachipayox.bootoptim.compat.client.MethodContract;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

/**
 * Manual check (not part of {@code check}): every fingerprint of
 * {@code bootoptim-decocraft-contracts.properties} against an installed Decocraft JAR (argument 0)
 * and the JOML class of the runtime classpath. Prints one line per method; exit code 1 on any mismatch.
 * The JAR is only read; nothing from it is copied into the repository.
 */
public final class DecocraftContractsVerify {
    public static void main(String[] args) throws Exception {
        Properties expected = new Properties();
        try (InputStream in = Files.newInputStream(Path.of("src/main/resources/bootoptim-decocraft-contracts.properties"))) {
            expected.load(in);
        }
        int bad = 0;
        try (ZipFile jar = new ZipFile(args[0])) {
            for (String key : expected.stringPropertyNames()) {
                int split = key.indexOf('#');
                String owner = key.substring(0, split);
                String method = key.substring(split + 1);
                ClassNode node = new ClassNode();
                if (owner.startsWith("org/joml/")) {
                    try (InputStream in = DecocraftContractsVerify.class.getClassLoader().getResourceAsStream(owner + ".class")) {
                        new ClassReader(in).accept(node, 0);
                    }
                } else {
                    var entry = jar.getEntry(owner + ".class");
                    if (entry == null) {
                        System.out.println("MISSING_CLASS " + key);
                        bad++;
                        continue;
                    }
                    try (InputStream in = jar.getInputStream(entry)) {
                        new ClassReader(in).accept(node, 0);
                    }
                }
                var match = node.methods.stream().filter(m -> (m.name + m.desc).equals(method)).findFirst();
                if (match.isEmpty()) {
                    System.out.println("MISSING_METHOD " + key);
                    bad++;
                    continue;
                }
                boolean ok = MethodContract.fingerprint(match.get()).equals(expected.getProperty(key));
                System.out.println((ok ? "OK " : "MISMATCH ") + owner.substring(owner.lastIndexOf('/') + 1) + "#" + method.substring(0, method.indexOf('(')));
                if (!ok) {
                    bad++;
                }
            }
        }
        System.out.println(bad == 0 ? "ALL_FINGERPRINTS_MATCH" : "MISMATCHES=" + bad);
        System.exit(bad == 0 ? 0 : 1);
    }
}
