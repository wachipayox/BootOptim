import java.lang.reflect.*;
import java.util.*;

/** Offline exact-binary counterexample checker; never installs a game transformer. */
public final class JeiEdgePromotionCheck {
    static Class<?> node, substring;
    static Constructor<?> subCtor, nodeCtor;
    static Method addEdge;
    static Field edges;
    static Object leaf(char c) throws Exception {
        return nodeCtor.newInstance(subCtor.newInstance(String.valueOf(c)));
    }
    static List<Integer> order(Object root) throws Exception {
        Object map = edges.get(root);
        Collection<?> values = (Collection<?>) map.getClass().getMethod("values").invoke(map);
        List<Integer> result = new ArrayList<>();
        Method charAt = substring.getMethod("charAt", int.class);
        for (Object value : values) result.add((int) (Character) charAt.invoke(value, 0));
        return result;
    }
    public static void main(String[] args) throws Exception {
        node = Class.forName("mezz.jei.core.search.suffixtree.Node");
        substring = Class.forName("mezz.jei.core.util.SubString");
        subCtor = substring.getConstructor(String.class);
        nodeCtor = node.getDeclaredConstructor(substring); nodeCtor.setAccessible(true);
        addEdge = node.getDeclaredMethod("addEdge", node); addEdge.setAccessible(true);
        edges = node.getDeclaredField("edges"); edges.setAccessible(true);
        Random random = new Random(20261002);
        for (int trial = 0; trial < 10000; trial++) {
            Object stock = leaf('!'), candidate = leaf('!');
            LinkedHashSet<Character> chars = new LinkedHashSet<>();
            while (chars.size() < 8) chars.add((char) (32 + random.nextInt(200)));
            for (char c : chars) { addEdge.invoke(stock, leaf(c)); addEdge.invoke(candidate, leaf(c)); }
            char replace = chars.iterator().next();
            // First replacement promotes the eight-entry array map in both arms.
            addEdge.invoke(stock, leaf(replace)); addEdge.invoke(candidate, leaf(replace));
            Object prior = edges.get(stock);
            List<Integer> before = order(stock);
            addEdge.invoke(stock, leaf(replace));
            Object direct = edges.get(candidate);
            direct.getClass().getMethod("put", char.class, Object.class).invoke(direct, replace, leaf(replace));
            if (prior == edges.get(stock)) throw new AssertionError("expected repeated clone");
            if (!order(stock).equals(order(candidate))) {
                System.out.println("NO-GO callback traversal order differs at trial=" + trial);
                System.out.println("before=" + before + " stock=" + order(stock) + " skipClone=" + order(candidate));
                return;
            }
        }
        System.out.println("PASS 10000 synthetic maps; repeated clone confirmed, no order counterexample in corpus (not runtime cost proof)");
    }
}
