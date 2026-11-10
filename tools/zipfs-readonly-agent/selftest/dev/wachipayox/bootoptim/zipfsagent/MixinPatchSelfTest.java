package dev.wachipayox.bootoptim.zipfsagent;

import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks the Mixin name patch on synthetic classes shaped like the two Mixin methods (the real Mixin classes are not
 * on the self-test class path): the patched method returns the same value on every call, the unpatched one does not,
 * and a class without the expected {@code UUID.randomUUID()} call is rejected so the agent falls back to stock.
 */
public final class MixinPatchSelfTest {

    private static final ClassDesc UUID_DESC = ClassDesc.of("java.util.UUID");
    private static final ClassDesc STRING = ConstantDescs.CD_String;
    private static final ClassDesc OBJECT = ConstantDescs.CD_Object;
    private static final ClassDesc CLASS_INFO = ClassDesc.of("org.spongepowered.asm.mixin.transformer.ClassInfo");

    private static final Object INFO = new org.spongepowered.asm.mixin.transformer.ClassInfo();

    public static void main(String[] args) throws Exception {
        List<String> failures = new ArrayList<>();

        // InnerClassGenerator shape: static String getUniqueReference(String, ClassInfo) { ... randomUUID().toString() }
        byte[] inner = build("dev.wachipayox.bootoptim.zipfsagent.SyntheticInnerShape", "getUniqueReference", MethodTypeDesc.of(STRING, STRING, CLASS_INFO), 1);
        byte[] patchedInner = MixinDeterminismTransformer.patch(inner, MixinPatchSelfTest.class.getClassLoader(), false);
        Class<?> innerClass = MethodHandles.lookup().defineClass(patchedInner);
        String first = call(innerClass, "getUniqueReference", new Class<?>[]{String.class, Object.class}, "a$1", INFO);
        MixinNames.resetForTest(); // a second launch starts with an empty set of used keys
        String second = call(innerClass, "getUniqueReference", new Class<?>[]{String.class, Object.class}, "a$1", INFO);
        MixinNames.resetForTest();
        String other = call(innerClass, "getUniqueReference", new Class<?>[]{String.class, Object.class}, "a$2", INFO);
        check(failures, "patched name is stable", first.equals(second));
        check(failures, "patched name differs per inner class", !first.equals(other));
        MixinNames.resetForTest();
        check(failures, "patched name has no random part", first.equals(MixinNames.anonymousUuid("a$1", INFO).toString()));

        // MixinProcessor shape: <init> stores randomUUID().toString()
        byte[] processor = build("dev.wachipayox.bootoptim.zipfsagent.SyntheticProcessorShape", "<init>", MethodTypeDesc.of(ConstantDescs.CD_void), 1);
        byte[] patchedProcessor = MixinDeterminismTransformer.patch(processor, MixinPatchSelfTest.class.getClassLoader(), true);
        Class<?> processorClass = MethodHandles.lookup().defineClass(patchedProcessor);
        processorClass.getDeclaredConstructor().newInstance();
        check(failures, "processor patched", true);

        // Error paths of the hook itself: it must never throw and must leave the class untouched.
        MixinDeterminismTransformer hook = new MixinDeterminismTransformer(null);
        String[] names = {"org/spongepowered/asm/mixin/transformer/MixinProcessor",
                "org/spongepowered/asm/mixin/transformer/InnerClassGenerator"};
        for (String name : names) {
            check(failures, "garbage bytes leave " + name + " untouched",
                    hook.transform(null, null, name, null, null, new byte[]{1, 2, 3}) == null);
            check(failures, "null bytes leave " + name + " untouched",
                    hook.transform(null, null, name, null, null, null) == null);
            check(failures, "valid class without the expected call leaves " + name + " untouched",
                    hook.transform(null, null, name, null, null, build("x.NoCall", "<init>", MethodTypeDesc.of(ConstantDescs.CD_void), 0)) == null);
        }
        check(failures, "unrelated class ignored", hook.transform(null, null, "a/B", null, null, new byte[]{1}) == null);
        check(failures, "null class name ignored", hook.transform(null, null, null, null, null, new byte[]{1}) == null);
        // Name guards: repeated key and unstable target text fall back to random names (original behaviour).
        MixinNames.resetForTest();
        java.util.UUID one = MixinNames.anonymousUuid("a$1", INFO);
        java.util.UUID again = MixinNames.anonymousUuid("a$1", INFO);
        check(failures, "first use of a key is deterministic", one.equals(java.util.UUID.nameUUIDFromBytes("a$1|".concat(String.valueOf(INFO)).getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        check(failures, "repeated key does not repeat the name", !one.equals(again));
        check(failures, "repeated key twice more is still distinct", !again.equals(MixinNames.anonymousUuid("a$1", INFO)));
        MixinNames.resetForTest();
        Object identity = new Object();
        check(failures, "identity-hash target is not deterministic", !MixinNames.anonymousUuid("b$1", identity).equals(MixinNames.anonymousUuid("b$1", identity)));
        Object stable = new Object() { @Override public String toString() { return "pkg/Target"; } };
        MixinNames.resetForTest();
        check(failures, "stable text target is deterministic", MixinNames.anonymousUuid("c$1", stable).equals(java.util.UUID.nameUUIDFromBytes("c$1|pkg/Target".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        check(failures, "null target does not throw", MixinNames.anonymousUuid("d$1", null) != null);
        check(failures, "names never fail", MixinNames.anonymousUuid(null, new Object() {
            @Override public String toString() { throw new IllegalStateException("boom"); }
        }) != null);

        // No randomUUID call, or two of them: rejected (fail open to stock).
        try {
            MixinDeterminismTransformer.patch(build("dev.wachipayox.bootoptim.zipfsagent.SyntheticNoCall", "getUniqueReference",
                    MethodTypeDesc.of(STRING, STRING, CLASS_INFO), 0), MixinPatchSelfTest.class.getClassLoader(), false);
            failures.add("a class without randomUUID was accepted");
        } catch (IllegalStateException expected) {
            // expected
        }
        try {
            MixinDeterminismTransformer.patch(build("dev.wachipayox.bootoptim.zipfsagent.SyntheticTwoCalls", "getUniqueReference",
                    MethodTypeDesc.of(STRING, STRING, CLASS_INFO), 2), MixinPatchSelfTest.class.getClassLoader(), false);
            failures.add("a class with two randomUUID calls was accepted");
        } catch (IllegalStateException expected) {
            // expected
        }

        if (failures.isEmpty()) {
            System.out.println("MIXIN-PATCH SELFTEST PASS");
        } else {
            failures.forEach(line -> System.out.println("MIXIN-PATCH SELFTEST FAIL " + line));
            System.exit(1);
        }
    }

    /** One static (or constructor) method with {@code calls} randomUUID() calls; static methods return a String. */
    private static byte[] build(String name, String method, MethodTypeDesc type, int calls) {
        ClassDesc self = ClassDesc.of(name);
        boolean ctor = method.equals("<init>");
        return ClassFile.of().build(self, cb -> {
            cb.withFlags(ClassFile.ACC_PUBLIC);
            if (ctor) {
                cb.withMethodBody("<init>", type, ClassFile.ACC_PUBLIC, b -> {
                    b.aload(0).invokespecial(OBJECT, "<init>", MethodTypeDesc.of(ConstantDescs.CD_void));
                    for (int i = 0; i < calls; i++) {
                        b.invokestatic(UUID_DESC, "randomUUID", MethodTypeDesc.of(UUID_DESC)).pop();
                    }
                    b.return_();
                });
            } else {
                cb.withMethodBody(method, type, ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, b -> {
                    for (int i = 1; i < calls; i++) {
                        b.invokestatic(UUID_DESC, "randomUUID", MethodTypeDesc.of(UUID_DESC)).pop();
                    }
                    if (calls == 0) {
                        b.aload(0).areturn();
                    } else {
                        b.invokestatic(UUID_DESC, "randomUUID", MethodTypeDesc.of(UUID_DESC))
                                .invokevirtual(UUID_DESC, "toString", MethodTypeDesc.of(STRING)).areturn();
                    }
                });
            }
        });
    }

    private static String call(Class<?> clazz, String method, Class<?>[] types, Object... args) throws Exception {
        // The synthetic ClassInfo parameter is erased to Object by the descriptor; call through a lookup class.
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getName().equals(method)) {
                return (String) m.invoke(null, args);
            }
        }
        throw new IllegalStateException("method not found");
    }

    private static void check(List<String> failures, String what, boolean ok) {
        if (!ok) {
            failures.add(what);
        }
    }
}
