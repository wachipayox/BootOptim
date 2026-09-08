package dev.wachipayox.bootoptim.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

class NeoForgeLanguageTraceTransformerTest {
    private static final String TARGET = "net/neoforged/neoforge/server/LanguageHook";
    private static final String HOOKS = "dev/wachipayox/bootoptim/bootstrap/NeoForgeLanguageTraceHooks";

    @Test
    void bracketsExactBuiltinLanguageMethodWithPinnedPublicationAnchor() {
        var input = languageHook();
        MethodNode method = builtinMethod();
        var load = new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "net/minecraft/locale/Language",
                "loadFromJson",
                "()V",
                false);
        method.instructions.add(load);
        var inject = injectCall();
        method.instructions.add(inject);
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        input.methods.add(method);

        var output = new NeoForgeLanguageTraceTransformer().transform(input, null);
        assertSame(input, output);

        List<MethodInsnNode> calls = calls(method);
        assertEquals(4, calls.size());
        assertHook(calls.get(0), "beginBuiltinLanguages");
        assertSame(load, calls.get(1));
        assertSame(inject, calls.get(2));
        assertHook(calls.get(3), "endBuiltinLanguages");
    }

    @Test
    void duplicatePublicationAnchorFailsClosed() {
        var input = languageHook();
        MethodNode method = builtinMethod();
        method.instructions.add(injectCall());
        method.instructions.add(injectCall());
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        input.methods.add(method);

        new NeoForgeLanguageTraceTransformer().transform(input, null);

        assertEquals(2L, calls(method).stream().filter(call -> "injectTranslations".equals(call.name)).count());
        assertEquals(0L, calls(method).stream().filter(call -> HOOKS.equals(call.owner)).count());
    }

    @Test
    void duplicateExactMethodFailsClosed() {
        var input = languageHook();
        MethodNode first = builtinMethod();
        first.instructions.add(injectCall());
        first.instructions.add(new InsnNode(Opcodes.RETURN));
        MethodNode second = builtinMethod();
        second.instructions.add(injectCall());
        second.instructions.add(new InsnNode(Opcodes.RETURN));
        input.methods.add(first);
        input.methods.add(second);

        new NeoForgeLanguageTraceTransformer().transform(input, null);

        assertEquals(0L, calls(first).stream().filter(call -> HOOKS.equals(call.owner)).count());
        assertEquals(0L, calls(second).stream().filter(call -> HOOKS.equals(call.owner)).count());
    }

    @Test
    void ignoresDifferentGameLayerClass() {
        var input = new ClassNode();
        input.name = "net/neoforged/neoforge/client/loading/ClientModLoader";
        MethodNode method = builtinMethod();
        method.instructions.add(injectCall());
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        input.methods.add(method);

        var output = new NeoForgeLanguageTraceTransformer().transform(input, null);
        assertSame(input, output);
        assertEquals(0L, calls(method).stream().filter(call -> HOOKS.equals(call.owner)).count());
    }

    private static ClassNode languageHook() {
        var input = new ClassNode();
        input.name = TARGET;
        return input;
    }

    private static MethodNode builtinMethod() {
        return new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "loadBuiltinLanguages", "()V", null, null);
    }

    private static MethodInsnNode injectCall() {
        return new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "net/neoforged/fml/i18n/I18nManager",
                "injectTranslations",
                "(Ljava/util/Map;)V",
                false);
    }

    private static List<MethodInsnNode> calls(MethodNode method) {
        List<MethodInsnNode> calls = new ArrayList<>();
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call) calls.add(call);
        }
        return calls;
    }

    private static void assertHook(MethodInsnNode call, String name) {
        assertEquals(HOOKS, call.owner);
        assertEquals(name, call.name);
        assertEquals("()V", call.desc);
    }
}
