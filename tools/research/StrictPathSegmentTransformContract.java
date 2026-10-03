import dev.wachipayox.bootoptim.compat.client.StrictPathSegmentTransform;
import java.lang.reflect.Method;
import java.util.regex.Pattern;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Executable bytecode contract: preserve mod-like head/tail side effects and short-circuit branches. */
public class StrictPathSegmentTransformContract implements Opcodes {
    private static int checks;
    private static void require(boolean test) { if (!test) throw new AssertionError(); checks++; }
    private static ClassNode fixture() {
        ClassNode c = new ClassNode(); c.version = V21; c.access = ACC_PUBLIC; c.name = "net/minecraft/FileUtil";
        c.superName = "java/lang/Object";
        c.fields.add(new FieldNode(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "STRICT_PATH_SEGMENT_CHECK", "Ljava/util/regex/Pattern;", null, null));
        for (String name : new String[]{"before", "after"}) c.fields.add(new FieldNode(ACC_PUBLIC | ACC_STATIC, name, "I", null, null));
        MethodNode init = new MethodNode(ACC_STATIC, "<clinit>", "()V", null, null);
        init.instructions.add(new LdcInsnNode("[-._a-z0-9]+"));
        init.instructions.add(new MethodInsnNode(INVOKESTATIC, "java/util/regex/Pattern", "compile", "(Ljava/lang/String;)Ljava/util/regex/Pattern;", false));
        init.instructions.add(new FieldInsnNode(PUTSTATIC, c.name, "STRICT_PATH_SEGMENT_CHECK", "Ljava/util/regex/Pattern;"));
        init.instructions.add(new InsnNode(RETURN)); c.methods.add(init);
        MethodNode m = new MethodNode(ACC_PUBLIC | ACC_STATIC, "isValidStrictPathSegment", "(Ljava/lang/String;)Z", null, null);
        increment(m, "before");
        m.instructions.add(new LdcInsnNode("blocked")); m.instructions.add(new VarInsnNode(ALOAD, 0));
        m.instructions.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false));
        LabelNode proceed = new LabelNode(); m.instructions.add(new JumpInsnNode(IFEQ, proceed));
        m.instructions.add(new InsnNode(ICONST_0)); m.instructions.add(new InsnNode(IRETURN)); m.instructions.add(proceed);
        sequence(m);
        m.instructions.add(new VarInsnNode(ISTORE, 1)); increment(m, "after");
        m.instructions.add(new VarInsnNode(ILOAD, 1)); m.instructions.add(new InsnNode(IRETURN)); c.methods.add(m);
        return c;
    }
    private static void increment(MethodNode m, String field) {
        m.instructions.add(new FieldInsnNode(GETSTATIC, "net/minecraft/FileUtil", field, "I"));
        m.instructions.add(new InsnNode(ICONST_1)); m.instructions.add(new InsnNode(IADD));
        m.instructions.add(new FieldInsnNode(PUTSTATIC, "net/minecraft/FileUtil", field, "I"));
    }
    private static void sequence(MethodNode m) {
        m.instructions.add(new FieldInsnNode(GETSTATIC, "net/minecraft/FileUtil", "STRICT_PATH_SEGMENT_CHECK", "Ljava/util/regex/Pattern;"));
        m.instructions.add(new VarInsnNode(ALOAD, 0));
        m.instructions.add(new MethodInsnNode(INVOKEVIRTUAL, "java/util/regex/Pattern", "matcher", "(Ljava/lang/CharSequence;)Ljava/util/regex/Matcher;", false));
        m.instructions.add(new MethodInsnNode(INVOKEVIRTUAL, "java/util/regex/Matcher", "matches", "()Z", false));
    }
    private static MethodNode method(ClassNode c) { return c.methods.getLast(); }
    private static void unchanged(ClassNode c) {
        int size = method(c).instructions.size(); require(!StrictPathSegmentTransform.rewrite(c));
        require(size == method(c).instructions.size());
    }
    private static Class<?> load(ClassNode node) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS); node.accept(writer);
        byte[] bytes = writer.toByteArray();
        return new ClassLoader(StrictPathSegmentTransformContract.class.getClassLoader()) {
            Class<?> define() { return defineClass("net.minecraft.FileUtil", bytes, 0, bytes.length); }
        }.define();
    }
    public static void main(String[] args) throws Exception {
        ClassNode c = fixture(); require(StrictPathSegmentTransform.rewrite(c)); require(!StrictPathSegmentTransform.rewrite(c));
        Class<?> stock = load(fixture()), candidate = load(c);
        Method a = stock.getMethod("isValidStrictPathSegment", String.class), b = candidate.getMethod("isValidStrictPathSegment", String.class);
        for (String s : new String[]{"", ".", "..", "valid", "blocked", "UPPER", "with/slash", "long_012-abc", "\uD800"})
            require(a.invoke(null, s).equals(b.invoke(null, s)));
        for (String name : new String[]{"before", "after"}) require(stock.getField(name).getInt(null) == candidate.getField(name).getInt(null));
        for (Method m : new Method[]{a, b}) {
            try { m.invoke(null, (Object)null); throw new AssertionError("null accepted"); }
            catch (java.lang.reflect.InvocationTargetException e) { require(e.getCause() instanceof NullPointerException); }
        }
        ClassNode unknown = fixture(); unknown.name = "other/FileUtil"; unchanged(unknown);
        unknown = fixture(); method(unknown).desc = "(Ljava/lang/String;I)Z"; unchanged(unknown);
        unknown = fixture(); method(unknown).name = "renamedMixinOriginal"; require(StrictPathSegmentTransform.rewrite(unknown));
        unknown = fixture(); sequence(method(unknown)); unchanged(unknown); // ambiguous pair, no partial edits
        unknown = fixture();
        for (AbstractInsnNode n : method(unknown).instructions) if (n instanceof MethodInsnNode call && call.name.equals("matches")) {
            method(unknown).instructions.insertBefore(n, new LabelNode()); break;
        }
        unchanged(unknown); // potential branch into sequence, never remove its target
        unknown = fixture();
        for (AbstractInsnNode n : method(unknown).instructions) if (n instanceof FieldInsnNode f && f.name.equals("STRICT_PATH_SEGMENT_CHECK")) { f.name = "CUSTOM_PATTERN"; break; }
        unchanged(unknown);
        System.out.println("STRICT_PATH_TRANSFORM_CONTRACT PASS checks=" + checks);
    }
}
