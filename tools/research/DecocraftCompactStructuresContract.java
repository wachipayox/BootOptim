import com.razz.decocraft.models.bbmodel.BBModel;
import com.razz.decocraft.models.bbmodel.BBModelParts;
import dev.wachipayox.bootoptim.compat.client.DecocraftContracts;
import dev.wachipayox.bootoptim.compat.client.MethodContract;
import com.razz.decocraft.models.libgdx.Vector3;
import dev.wachipayox.bootoptim.optimization.client.DecocraftFaceMaps;
import dev.wachipayox.bootoptim.optimization.client.DecocraftRotationScratch;
import dev.wachipayox.bootoptim.optimization.client.SmallOrderedMap;
import java.lang.reflect.Field;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import net.minecraft.core.Direction;
import org.joml.Matrix4f;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Host-only contract for the Decocraft compact-structure mechanisms (no Minecraft bootstrap, no
 * Decocraft JAR): SmallOrderedMap against LinkedHashMap, the parser shape preflight of
 * DecocraftFaceMaps on stand-in classes, and the code fingerprint tolerance rules.
 */
public final class DecocraftCompactStructuresContract {
    private static int checks;

    public static void main(String[] args) throws Exception {
        smallOrderedMapMatchesLinkedHashMap();
        smallOrderedMapEdgeSemantics();
        faceMapsInactiveLeavesModelUntouched();
        activate("decocraft-compact-face-maps");
        faceMapsKeepOrderAndIdentity();
        faceMapsPreflightIsAtomic();
        fingerprintIgnoresMetadataButNotBody();
        rotationScratchIsOwnedPerBakeryAndThread();
        System.out.println("DECOCRAFT_COMPACT_STRUCTURES_CONTRACT PASS checks=" + checks);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
        checks++;
    }

    private static void same(Map<Integer, Integer> expected, Map<Integer, Integer> actual) {
        check(expected.equals(actual) && actual.equals(expected) && expected.hashCode() == actual.hashCode(), "equals/hashCode");
        check(new ArrayList<>(expected.entrySet()).equals(new ArrayList<>(actual.entrySet())), "entry order");
        check(new ArrayList<>(expected.keySet()).equals(new ArrayList<>(actual.keySet())), "key order");
        check(new ArrayList<>(expected.values()).equals(new ArrayList<>(actual.values())), "value order");
    }

    private static void smallOrderedMapMatchesLinkedHashMap() {
        Random random = new Random(93475);
        Map<Integer, Integer> a = new LinkedHashMap<>();
        Map<Integer, Integer> b = new SmallOrderedMap<>(a);
        for (int n = 0; n < 100_000; n++) {
            Integer k = random.nextInt(20) == 0 ? null : random.nextInt(12);
            Integer v = random.nextInt(9) == 0 ? null : random.nextInt(1000);
            Integer next = v;
            switch (random.nextInt(15)) {
                case 0, 1 -> check(Objects.equals(a.put(k, v), b.put(k, v)), "put");
                case 2 -> check(Objects.equals(a.remove(k), b.remove(k)), "remove");
                case 3 -> check(Objects.equals(a.get(k), b.get(k)), "get");
                case 4 -> check(Objects.equals(a.putIfAbsent(k, v), b.putIfAbsent(k, v)), "putIfAbsent");
                case 5 -> check(Objects.equals(a.compute(k, (key, old) -> next), b.compute(k, (key, old) -> next)), "compute");
                case 6 -> check(Objects.equals(a.computeIfAbsent(k, key -> next), b.computeIfAbsent(k, key -> next)), "computeIfAbsent");
                case 7 -> check(Objects.equals(a.computeIfPresent(k, (key, old) -> next), b.computeIfPresent(k, (key, old) -> next)), "computeIfPresent");
                case 8 -> {
                    if (v != null) {
                        check(Objects.equals(a.merge(k, v, (x, y) -> (x + y) % 5 == 0 ? null : x + y),
                                b.merge(k, v, (x, y) -> (x + y) % 5 == 0 ? null : x + y)), "merge");
                    }
                }
                case 9 -> check(Objects.equals(a.replace(k, v), b.replace(k, v)), "replace");
                case 10 -> check(a.entrySet().remove(new AbstractMap.SimpleEntry<>(k, v))
                        == b.entrySet().remove(new AbstractMap.SimpleEntry<>(k, v)), "entry remove");
                case 11 -> {
                    if (!a.isEmpty()) {
                        var x = a.entrySet().iterator();
                        var y = b.entrySet().iterator();
                        check(Objects.equals(x.next(), y.next()), "iterator next");
                        if (random.nextBoolean()) {
                            x.remove();
                            y.remove();
                        } else {
                            check(Objects.equals(a.entrySet().iterator().next().setValue(v),
                                    b.entrySet().iterator().next().setValue(v)), "setValue");
                        }
                    }
                }
                case 12 -> {
                    if (n % 97 == 0) {
                        a.clear();
                        b.clear();
                    } else {
                        a.replaceAll((key, old) -> old == null ? next : Integer.valueOf(old + 1));
                        b.replaceAll((key, old) -> old == null ? next : Integer.valueOf(old + 1));
                    }
                }
                case 13 -> check(a.values().remove(v) == b.values().remove(v), "values remove");
                default -> check(Objects.equals(a.keySet().remove(k), b.keySet().remove(k)), "keySet remove");
            }
            same(a, b);
        }
    }

    private static void smallOrderedMapEdgeSemantics() {
        Map<Integer, Integer> seed = new LinkedHashMap<>();
        for (int i = 0; i < 6; i++) {
            seed.put(i, i);
        }
        SmallOrderedMap<Integer, Integer> map = new SmallOrderedMap<>(seed);
        var first = map.entrySet().iterator().next();
        check(first == map.entrySet().iterator().next(), "stable entry identity");
        first.setValue(99);
        check(map.get(0) == 99 && seed.get(0) == 0, "copy is independent of the source");
        var iterator = map.entrySet().iterator();
        map.put(100, 100);
        try {
            iterator.next();
            check(false, "missing fail-fast");
        } catch (ConcurrentModificationException expected) {
            check(true, "fail-fast");
        }
        var keys = map.keySet();
        map.put(200, null);
        check(keys.contains(200) && map.containsKey(200), "live views and null value");
        try {
            map.compute(0, (key, value) -> {
                map.put(300, 1);
                return value;
            });
            check(false, "callback mutation not detected");
        } catch (ConcurrentModificationException expected) {
            check(true, "callback mutation");
        }
    }

    private static BBModelParts.Element element() {
        var element = new BBModelParts.Element();
        for (Direction d : List.of(Direction.NORTH, Direction.SOUTH, Direction.UP, Direction.DOWN, Direction.EAST, Direction.WEST)) {
            element.faces.put(d, new BBModelParts.Face());
        }
        return element;
    }

    private static void activate(String mechanism) throws Exception {
        Field ready = DecocraftContracts.class.getDeclaredField("ready");
        ready.setAccessible(true);
        ready.set(null, Set.of(mechanism));
    }

    private static void faceMapsInactiveLeavesModelUntouched() {
        var model = new BBModel();
        var element = element();
        model.elements.add(element);
        var original = element.faces;
        DecocraftFaceMaps.compact(model);
        check(element.faces == original, "inactive mechanism must not touch the model");
    }

    private static void faceMapsKeepOrderAndIdentity() {
        var model = new BBModel();
        var first = element();
        model.elements.add(first);
        var original = first.faces;
        var originalEntries = new ArrayList<>(original.entrySet());
        DecocraftFaceMaps.compact(model);
        check(first.faces instanceof SmallOrderedMap, "map replaced");
        check(originalEntries.equals(new ArrayList<>(first.faces.entrySet())), "exact key order");
        for (var entry : first.faces.entrySet()) {
            check(entry.getValue() == original.get(entry.getKey()), "Face identity kept");
        }
        var alias = first.faces;
        DecocraftFaceMaps.compact(model);
        check(first.faces == alias, "idempotent");
    }

    private static void faceMapsPreflightIsAtomic() throws Exception {
        activate("decocraft-compact-face-maps");
        var bad = new BBModel();
        var good = element();
        var wrong = element();
        wrong.faces = new HashMap<>(wrong.faces);
        bad.elements.add(good);
        bad.elements.add(wrong);
        var untouched = good.faces;
        DecocraftFaceMaps.compact(bad);
        check(good.faces == untouched, "no partial write when a later element has another shape");
        check(!DecocraftContracts.active("decocraft-compact-face-maps"), "mechanism disabled after shape change");
    }

    private static void rotationScratchIsOwnedPerBakeryAndThread() throws Exception {
        var scratch = new DecocraftRotationScratch();
        var owned = new Vector3(0f, 0f, 0f);
        var source = new Vector3(3f, 5f, 7f);
        check(scratch.reuse(1, 2, 3) == null, "uninitialized scratch builds a fresh vector");
        scratch.initialize(owned);
        Random random = new Random(5516);
        for (int i = 0; i < 100_000; i++) {
            float a = Float.intBitsToFloat(random.nextInt());
            float b = Float.intBitsToFloat(random.nextInt());
            float c = Float.intBitsToFloat(random.nextInt());
            Object result = scratch.reuse(a, b, c);
            check(result == owned
                    && Float.floatToRawIntBits(owned.x) == Float.floatToRawIntBits(a)
                    && Float.floatToRawIntBits(owned.y) == Float.floatToRawIntBits(b)
                    && Float.floatToRawIntBits(owned.z) == Float.floatToRawIntBits(c), "raw-bit snapshot");
        }
        check(source.x == 3 && source.y == 5 && source.z == 7, "source origin never touched");
        final Object[] foreign = {owned};
        Thread other = new Thread(() -> foreign[0] = scratch.reuse(1, 2, 3));
        other.start();
        other.join();
        check(foreign[0] == null, "another thread takes the original constructor");
        var second = new DecocraftRotationScratch();
        second.initialize(new Vector3(1f, 2f, 3f));
        check(second.reuse(4, 5, 6) != owned, "scratch is per bakery");
        var invalid = new DecocraftRotationScratch();
        invalid.initialize(new Object());
        check(invalid.reuse(1, 2, 3) == null, "unknown shape falls back");
        Matrix4f identity = new Matrix4f();
        for (int i = 0; i < 10_000; i++) {
            Matrix4f m = new Matrix4f();
            if (i % 3 != 0) {
                m.m00(Float.intBitsToFloat(random.nextInt()));
            }
            if (i % 5 == 0) {
                m.m31(-0f);
            }
            check(m.equals(identity) == m.equals(new Matrix4f()), "shared identity equals a fresh one");
        }
        check(!DecocraftRotationScratch.activeFor(new Matrix4f() {}), "subclass never sees the shared identity");
    }

    private static void fingerprintIgnoresMetadataButNotBody() throws Exception {
        var method = new MethodNode(Opcodes.ACC_PUBLIC, "m", "()V", null, null);
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        String hash = MethodContract.fingerprint(method);
        method.maxStack = 7;
        method.maxLocals = 9;
        check(hash.equals(MethodContract.fingerprint(method)), "frame sizes are not part of the contract");
        method.instructions.insert(new InsnNode(Opcodes.NOP));
        check(!hash.equals(MethodContract.fingerprint(method)), "changed body must change the fingerprint");
    }
}
