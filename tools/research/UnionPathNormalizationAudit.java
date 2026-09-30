import cpw.mods.niofs.union.UnionFileSystem;
import cpw.mods.niofs.union.UnionFileSystemProvider;
import cpw.mods.niofs.union.UnionPath;
import java.nio.file.*;
import java.util.*;

/** Offline exact-version source-premise audit; never installed in the game. */
public final class UnionPathNormalizationAudit {
    static String stock(UnionPath path) {
        Path relative = path.isAbsolute() ? path.getFileSystem().getRoot().relativize(path) : path;
        return relative.normalize().toString();
    }
    static String proposed(UnionPath path) {
        String rendered = path.normalize().toString();
        return path.isAbsolute() ? rendered.substring(1) : rendered;
    }
    static volatile long consumed;
    static void costProbe(UnionFileSystem fs) {
        UnionPath[] paths = new UnionPath[256];
        for (int i=0;i<paths.length;i++) paths[i]=(UnionPath)fs.getPath("/assets/decocraft/models/block/furniture/variant_"+i+".json");
        for (int i=0;i<100_000;i++) { consumed += stock(paths[i&255]).length(); consumed += proposed(paths[i&255]).length(); }
        com.sun.management.ThreadMXBean bean=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();
        for (int round=0;round<6;round++) {
            boolean candidate=(round&1)==1;
            long bytes=bean.getThreadAllocatedBytes(Thread.currentThread().threadId());
            long cpu=bean.getCurrentThreadCpuTime();
            long total=0;
            for (int i=0;i<500_000;i++) total+=(candidate?proposed(paths[i&255]):stock(paths[i&255])).length();
            long cpuSpent=bean.getCurrentThreadCpuTime()-cpu;
            long allocated=bean.getThreadAllocatedBytes(Thread.currentThread().threadId())-bytes;
            consumed=total;
            System.out.printf(java.util.Locale.ROOT,"isolated %s round=%d cpu_ms=%.3f bytes_per_call=%.3f%n",candidate?"normalize-original-strip":"stock-relative-normalize",round,cpuSpent/1e6,allocated/500000.0);
        }
    }
    static int checked;
    static void check(UnionFileSystem fs, String raw) {
        UnionPath path = (UnionPath) fs.getPath(raw);
        String actual = proposed(path);
        String expected = stock(path);
        if (!actual.equals(expected)) throw new AssertionError(raw+": "+expected+" != "+actual);
        // Repeat on the same immutable object to exercise its existing normalization memo.
        if (!proposed(path).equals(expected)) throw new AssertionError("repeat "+raw);
        if (!path.toString().equals(((UnionPath) fs.getPath(raw)).toString())) throw new AssertionError("mutated "+raw);
        checked++;
    }
    static void enumerate(UnionFileSystem fs, String prefix, String[] tokens, int remaining) {
        check(fs, prefix);
        check(fs, "/"+prefix);
        if (remaining == 0) return;
        for (String token : tokens) enumerate(fs, prefix+"/"+token, tokens, remaining-1);
    }
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("bootoptim-union-normalization-audit-");
        try (UnionFileSystem fs = new UnionFileSystemProvider().newFileSystem(null, dir)) {
            enumerate(fs, "", new String[]{"a", "b", ".", "..", "", " ", "textures.png", "\u00f1"}, 5);
            for (String raw : List.of("", "/", "//", "///", ".", "..", "../../a", "/../../a", "a/./b/../c", "C:/assets/minecraft/a.png", "a\\b\\..\\c", "\\assets\\minecraft", "assets/\u00f1/\ud83d\ude00.png", "/a//b///")) check(fs, raw);
            if (args.length>0 && args[0].equals("--cost-probe")) costProbe(fs);
            System.out.println("UnionPath 3.0.8 normalized-resolution strings: matches="+checked+" mismatches=0; no game/runtime patch");
        } finally { Files.delete(dir); }
    }
}
