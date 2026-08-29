package dev.wachipayox.bootoptim.bootstrap;

import cpw.mods.jarhandling.JarContents;
import cpw.mods.jarhandling.SecureJar;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.annotation.ElementType;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.neoforged.fml.ModLoadingException;
import net.neoforged.fml.ModLoadingIssue;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fml.loading.moddiscovery.ModFile;
import net.neoforged.fml.loading.moddiscovery.ModFileParser;
import net.neoforged.fml.loading.moddiscovery.ModJarMetadata;
import net.neoforged.fml.loading.moddiscovery.readers.JarModsDotTomlModFileReader;
import net.neoforged.fml.loading.modscan.ModAnnotation;
import net.neoforged.neoforgespi.language.ModFileScanData;
import net.neoforged.neoforgespi.locating.IModFile;
import net.neoforged.neoforgespi.locating.IModFileReader;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Type;

/**
 * Drop-in replacement for FML's standard mods.toml/manifest reader that only changes class-metadata scanning.
 * Cache failures always fall back to the stock scanner.
 */
public final class CachingModFileReader implements IModFileReader {
    private static final String MODS_TOML = "META-INF/neoforge.mods.toml";
    private static final int PRIORITY = HIGHEST_SYSTEM_PRIORITY + 100;

    @Override
    public int getPriority() {
        return PRIORITY;
    }

    @Override
    public @Nullable IModFile read(JarContents jar, ModFileDiscoveryAttributes attributes) {
        var readerAttributes = attributes.withReader(this);
        var type = getModType(jar);

        if (jar.findFile(MODS_TOML).isPresent()) {
            var metadata = new ModJarMetadata(jar);
            var mod = new CachedModFile(SecureJar.from(jar, metadata), ModFileParser::modsTomlParser, readerAttributes);
            metadata.setModFile(mod);
            return mod;
        }

        if (type != null) {
            return new CachedModFile(
                    SecureJar.from(jar),
                    JarModsDotTomlModFileReader::manifestParser,
                    type,
                    readerAttributes);
        }

        return null;
    }

    private static @Nullable IModFile.Type getModType(JarContents jar) {
        String typeString = jar.getManifest().getMainAttributes().getValue(ModFile.TYPE);
        try {
            return typeString != null ? IModFile.Type.valueOf(typeString) : null;
        } catch (IllegalArgumentException e) {
            throw new ModLoadingException(ModLoadingIssue.error(
                    "fml.modloadingissue.brokenfile.unknownfmlmodtype", typeString).withAffectedPath(jar.getPrimaryPath()));
        }
    }

    private static final class CachedModFile extends ModFile {
        private SecureJar.Status observedSecurityStatus;

        private CachedModFile(SecureJar jar, net.neoforged.neoforgespi.locating.ModFileInfoParser parser,
                ModFileDiscoveryAttributes attributes) {
            super(jar, parser, attributes);
        }

        private CachedModFile(SecureJar jar, net.neoforged.neoforgespi.locating.ModFileInfoParser parser,
                IModFile.Type type, ModFileDiscoveryAttributes attributes) {
            super(jar, parser, type, attributes);
        }

        @Override
        public void setSecurityStatus(SecureJar.Status status) {
            observedSecurityStatus = status;
            super.setSecurityStatus(status);
        }

        @Override
        public ModFileScanData compileContent() {
            Path source = getFilePath();
            if (!Files.isRegularFile(source)) {
                return super.compileContent();
            }

            Path cachePath;
            try {
                cachePath = ScanCache.cachePath(source);
                ScanCache.Entry cached = ScanCache.read(cachePath);
                if (cached != null) {
                    setSecurityStatus(cached.securityStatus());
                    cached.scanData().addModFileInfo(getModFileInfo());
                    ScanCache.mark("hit", source);
                    return cached.scanData();
                }
            } catch (Throwable ignored) {
                ScanCache.mark("fallback", source);
                return super.compileContent();
            }

            ModFileScanData scanned = super.compileContent();
            if (observedSecurityStatus != null) {
                try {
                    ScanCache.write(cachePath, new ScanCache.Entry(observedSecurityStatus, scanned));
                } catch (Throwable ignored) {
                    // Writing the optional cache must never force a second scan or break startup.
                    ScanCache.mark("write_failed", source);
                    return scanned;
                }
            }
            ScanCache.mark("miss", source);
            return scanned;
        }
    }

    private static final class ScanCache {
        private static final int MAGIC = 0x424F5343; // BOSC
        private static final int VERSION = 1;
        private static final String PROFILE_PROPERTY = "boot_optim.profileStartup";

        private record Entry(SecureJar.Status securityStatus, ModFileScanData scanData) {}

        private static Path cachePath(Path source) throws Exception {
            BasicFileAttributes attrs = Files.readAttributes(source, BasicFileAttributes.class);
            String loaderVersion = String.valueOf(ModFile.class.getPackage().getImplementationVersion());
            String identity = source.getFileName() + "\n"
                    + attrs.size() + "\n"
                    + attrs.lastModifiedTime().toMillis() + "\n"
                    + String.valueOf(attrs.fileKey()) + "\n"
                    + loaderVersion + "\n"
                    + Runtime.version().feature() + "\n"
                    + VERSION;
            String key = hex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8)));
            Path dir = FMLPaths.GAMEDIR.get().resolve(".bootoptim").resolve("mod-scan-cache-v1");
            Files.createDirectories(dir);
            return dir.resolve(key + ".bin");
        }

        private static @Nullable Entry read(Path path) {
            if (!Files.isRegularFile(path)) {
                return null;
            }
            try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
                if (in.readInt() != MAGIC || in.readInt() != VERSION) {
                    return null;
                }
                SecureJar.Status status = SecureJar.Status.valueOf(in.readUTF());
                return new Entry(status, readScanData(in));
            } catch (Exception ignored) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignoredDelete) {
                }
                return null;
            }
        }

        private static void write(Path path, Entry entry) throws IOException {
            Path temp = path.resolveSibling(path.getFileName() + ".tmp-" + Thread.currentThread().threadId());
            try {
                try (var out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))) {
                    out.writeInt(MAGIC);
                    out.writeInt(VERSION);
                    out.writeUTF(entry.securityStatus().name());
                    writeScanData(out, entry.scanData());
                }
                try {
                    Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
        }

        private static void writeScanData(DataOutputStream out, ModFileScanData data) throws IOException {
            out.writeInt(data.getClasses().size());
            for (var cls : data.getClasses()) {
                out.writeUTF(cls.clazz().getInternalName());
                out.writeUTF(cls.parent().getInternalName());
                out.writeInt(cls.interfaces().size());
                for (Type iface : cls.interfaces()) {
                    out.writeUTF(iface.getInternalName());
                }
            }

            out.writeInt(data.getAnnotations().size());
            for (var annotation : data.getAnnotations()) {
                out.writeUTF(annotation.annotationType().getInternalName());
                out.writeByte(annotation.targetType().ordinal());
                out.writeUTF(annotation.clazz().getInternalName());
                out.writeUTF(annotation.memberName());
                writeMap(out, annotation.annotationData());
            }
        }

        private static ModFileScanData readScanData(DataInputStream in) throws IOException {
            int classCount = checkedCount(in.readInt());
            Set<ModFileScanData.ClassData> classes = new LinkedHashSet<>(classCount);
            for (int i = 0; i < classCount; i++) {
                Type clazz = Type.getObjectType(in.readUTF());
                Type parent = Type.getObjectType(in.readUTF());
                int interfaceCount = checkedCount(in.readInt());
                Set<Type> interfaces = new HashSet<>(interfaceCount);
                for (int j = 0; j < interfaceCount; j++) {
                    interfaces.add(Type.getObjectType(in.readUTF()));
                }
                classes.add(new ModFileScanData.ClassData(clazz, parent, interfaces));
            }

            int annotationCount = checkedCount(in.readInt());
            Set<ModFileScanData.AnnotationData> annotations = new LinkedHashSet<>(annotationCount);
            for (int i = 0; i < annotationCount; i++) {
                Type annotationType = Type.getObjectType(in.readUTF());
                int targetOrdinal = Byte.toUnsignedInt(in.readByte());
                if (targetOrdinal >= ElementType.values().length) {
                    throw new IOException("Invalid annotation target ordinal");
                }
                ElementType target = ElementType.values()[targetOrdinal];
                Type clazz = Type.getObjectType(in.readUTF());
                String member = in.readUTF();
                annotations.add(new ModFileScanData.AnnotationData(annotationType, target, clazz, member, readMap(in)));
            }
            ModFileScanData data = new ModFileScanData();
            data.getAnnotations().addAll(annotations);
            data.getClasses().addAll(classes);
            return data;
        }

        private static void writeMap(DataOutputStream out, Map<String, Object> values) throws IOException {
            out.writeInt(values.size());
            for (var value : values.entrySet()) {
                out.writeUTF(value.getKey());
                writeValue(out, value.getValue());
            }
        }

        private static Map<String, Object> readMap(DataInputStream in) throws IOException {
            int size = checkedCount(in.readInt());
            Map<String, Object> values = new HashMap<>(size);
            for (int i = 0; i < size; i++) {
                values.put(in.readUTF(), readValue(in));
            }
            return values;
        }

        private static void writeValue(DataOutputStream out, Object value) throws IOException {
            if (value instanceof String v) { out.writeByte(0); out.writeUTF(v); }
            else if (value instanceof Byte v) { out.writeByte(1); out.writeByte(v); }
            else if (value instanceof Boolean v) { out.writeByte(2); out.writeBoolean(v); }
            else if (value instanceof Short v) { out.writeByte(3); out.writeShort(v); }
            else if (value instanceof Character v) { out.writeByte(4); out.writeChar(v); }
            else if (value instanceof Integer v) { out.writeByte(5); out.writeInt(v); }
            else if (value instanceof Long v) { out.writeByte(6); out.writeLong(v); }
            else if (value instanceof Float v) { out.writeByte(7); out.writeFloat(v); }
            else if (value instanceof Double v) { out.writeByte(8); out.writeDouble(v); }
            else if (value instanceof Type v) { out.writeByte(9); out.writeUTF(v.getDescriptor()); }
            else if (value instanceof ModAnnotation.EnumHolder v) { out.writeByte(10); out.writeUTF(v.desc()); out.writeUTF(v.value()); }
            else if (value instanceof List<?> v) {
                out.writeByte(11); out.writeInt(v.size());
                for (Object nested : v) writeValue(out, nested);
            } else if (value instanceof Map<?, ?> v) {
                out.writeByte(12); out.writeInt(v.size());
                for (var nested : v.entrySet()) { out.writeUTF((String) nested.getKey()); writeValue(out, nested.getValue()); }
            } else if (value instanceof byte[] v) { out.writeByte(13); out.writeInt(v.length); out.write(v); }
            else if (value instanceof boolean[] v) { out.writeByte(14); out.writeInt(v.length); for (boolean n : v) out.writeBoolean(n); }
            else if (value instanceof short[] v) { out.writeByte(15); out.writeInt(v.length); for (short n : v) out.writeShort(n); }
            else if (value instanceof char[] v) { out.writeByte(16); out.writeInt(v.length); for (char n : v) out.writeChar(n); }
            else if (value instanceof int[] v) { out.writeByte(17); out.writeInt(v.length); for (int n : v) out.writeInt(n); }
            else if (value instanceof long[] v) { out.writeByte(18); out.writeInt(v.length); for (long n : v) out.writeLong(n); }
            else if (value instanceof float[] v) { out.writeByte(19); out.writeInt(v.length); for (float n : v) out.writeFloat(n); }
            else if (value instanceof double[] v) { out.writeByte(20); out.writeInt(v.length); for (double n : v) out.writeDouble(n); }
            else throw new IOException("Unsupported annotation value type: " + (value == null ? "null" : value.getClass()));
        }

        private static Object readValue(DataInputStream in) throws IOException {
            return switch (Byte.toUnsignedInt(in.readByte())) {
                case 0 -> in.readUTF();
                case 1 -> in.readByte();
                case 2 -> in.readBoolean();
                case 3 -> in.readShort();
                case 4 -> in.readChar();
                case 5 -> in.readInt();
                case 6 -> in.readLong();
                case 7 -> in.readFloat();
                case 8 -> in.readDouble();
                case 9 -> Type.getType(in.readUTF());
                case 10 -> new ModAnnotation.EnumHolder(in.readUTF(), in.readUTF());
                case 11 -> readList(in);
                case 12 -> readMap(in);
                case 13 -> readBytes(in);
                case 14 -> readBooleans(in);
                case 15 -> readShorts(in);
                case 16 -> readChars(in);
                case 17 -> readInts(in);
                case 18 -> readLongs(in);
                case 19 -> readFloats(in);
                case 20 -> readDoubles(in);
                default -> throw new IOException("Unknown annotation value tag");
            };
        }

        private static List<Object> readList(DataInputStream in) throws IOException {
            int size = checkedCount(in.readInt());
            List<Object> values = new ArrayList<>(size);
            for (int i = 0; i < size; i++) values.add(readValue(in));
            return values;
        }

        private static byte[] readBytes(DataInputStream in) throws IOException {
            int n = checkedCount(in.readInt());
            byte[] bytes = in.readNBytes(n);
            if (bytes.length != n) throw new IOException("Truncated cache payload");
            return bytes;
        }
        private static boolean[] readBooleans(DataInputStream in) throws IOException { int n = checkedCount(in.readInt()); boolean[] a = new boolean[n]; for (int i=0;i<n;i++) a[i]=in.readBoolean(); return a; }
        private static short[] readShorts(DataInputStream in) throws IOException { int n = checkedCount(in.readInt()); short[] a = new short[n]; for (int i=0;i<n;i++) a[i]=in.readShort(); return a; }
        private static char[] readChars(DataInputStream in) throws IOException { int n = checkedCount(in.readInt()); char[] a = new char[n]; for (int i=0;i<n;i++) a[i]=in.readChar(); return a; }
        private static int[] readInts(DataInputStream in) throws IOException { int n = checkedCount(in.readInt()); int[] a = new int[n]; for (int i=0;i<n;i++) a[i]=in.readInt(); return a; }
        private static long[] readLongs(DataInputStream in) throws IOException { int n = checkedCount(in.readInt()); long[] a = new long[n]; for (int i=0;i<n;i++) a[i]=in.readLong(); return a; }
        private static float[] readFloats(DataInputStream in) throws IOException { int n = checkedCount(in.readInt()); float[] a = new float[n]; for (int i=0;i<n;i++) a[i]=in.readFloat(); return a; }
        private static double[] readDoubles(DataInputStream in) throws IOException { int n = checkedCount(in.readInt()); double[] a = new double[n]; for (int i=0;i<n;i++) a[i]=in.readDouble(); return a; }

        private static int checkedCount(int value) throws IOException {
            if (value < 0 || value > 10_000_000) throw new IOException("Invalid cache collection size: " + value);
            return value;
        }

        private static String hex(byte[] bytes) {
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) out.append(String.format("%02x", value));
            return out.toString();
        }

        private static void mark(String result, Path source) {
            if (Boolean.getBoolean(PROFILE_PROPERTY)) {
                System.out.printf("BOOTOPTIM_SCAN_CACHE result=%s file=%s%n", result, source.getFileName());
            }
        }
    }
}
