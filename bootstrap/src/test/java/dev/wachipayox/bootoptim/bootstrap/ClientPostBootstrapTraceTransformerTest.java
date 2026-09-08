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

class ClientPostBootstrapTraceTransformerTest {
    private static final String HOOKS = "dev/wachipayox/bootoptim/bootstrap/ClientPostBootstrapTraceHooks";
    private static final String TARGET = "net/neoforged/neoforge/client/loading/ClientModLoader";

    @Test
    void wrapsExactClientPreambleAndBuiltinLanguagesInOrder() {
        var input = clientClass();
        var method = input.methods.getFirst();
        var language = call("net/neoforged/neoforge/server/LanguageHook", "loadBuiltinLanguages", "()V");
        var commonBegin = call(TARGET, "begin", "(Ljava/lang/Runnable;Z)V");
        method.instructions.add(new InsnNode(Opcodes.NOP));
        method.instructions.add(language);
        method.instructions.add(commonBegin);
        method.instructions.add(new InsnNode(Opcodes.RETURN));

        var output = new ClientPostBootstrapTraceTransformer().transform(input, null);
        assertSame(input, output);

        List<MethodInsnNode> calls = methodCalls(method);
        assertEquals(6, calls.size());
        assertHook(calls.get(0), "beginClientPreGather");
        assertHook(calls.get(1), "beginBuiltinLanguages");
        assertSame(language, calls.get(2));
        assertHook(calls.get(3), "endBuiltinLanguages");
        assertHook(calls.get(4), "endClientPreGather");
        assertSame(commonBegin, calls.get(5));
    }

    @Test
    void acceptsExplicitCommonModLoaderOwner() {
        var input = clientClass();
        var method = input.methods.getFirst();
        method.instructions.add(call("net/neoforged/neoforge/server/LanguageHook", "loadBuiltinLanguages", "()V"));
        method.instructions.add(call("net/neoforged/neoforge/internal/CommonModLoader", "begin", "(Ljava/lang/Runnable;Z)V"));
        method.instructions.add(new InsnNode(Opcodes.RETURN));

        new ClientPostBootstrapTraceTransformer().transform(input, null);
        assertEquals(6, methodCalls(method).size());
    }

    @Test
    void rejectsMissingAmbiguousReversedAndWrongTargets() {
        var missing = clientClass();
        missing.methods.getFirst().instructions.add(new InsnNode(Opcodes.RETURN));
        new ClientPostBootstrapTraceTransformer().transform(missing, null);
        assertEquals(0, methodCalls(missing.methods.getFirst()).size());

        var duplicate = clientClass();
        var duplicateMethod = duplicate.methods.getFirst();
        duplicateMethod.instructions.add(call("net/neoforged/neoforge/server/LanguageHook", "loadBuiltinLanguages", "()V"));
        duplicateMethod.instructions.add(call("net/neoforged/neoforge/server/LanguageHook", "loadBuiltinLanguages", "()V"));
        duplicateMethod.instructions.add(call(TARGET, "begin", "(Ljava/lang/Runnable;Z)V"));
        duplicateMethod.instructions.add(new InsnNode(Opcodes.RETURN));
        new ClientPostBootstrapTraceTransformer().transform(duplicate, null);
        assertEquals(3, methodCalls(duplicateMethod).size());

        var reversed = clientClass();
        var reversedMethod = reversed.methods.getFirst();
        reversedMethod.instructions.add(call(TARGET, "begin", "(Ljava/lang/Runnable;Z)V"));
        reversedMethod.instructions.add(call("net/neoforged/neoforge/server/LanguageHook", "loadBuiltinLanguages", "()V"));
        reversedMethod.instructions.add(new InsnNode(Opcodes.RETURN));
        new ClientPostBootstrapTraceTransformer().transform(reversed, null);
        assertEquals(2, methodCalls(reversedMethod).size());

        var wrong = clientClass();
        wrong.name = "net/neoforged/fml/ModLoader";
        var wrongMethod = wrong.methods.getFirst();
        wrongMethod.instructions.add(call("net/neoforged/neoforge/server/LanguageHook", "loadBuiltinLanguages", "()V"));
        wrongMethod.instructions.add(call(TARGET, "begin", "(Ljava/lang/Runnable;Z)V"));
        wrongMethod.instructions.add(new InsnNode(Opcodes.RETURN));
        new ClientPostBootstrapTraceTransformer().transform(wrong, null);
        assertEquals(2, methodCalls(wrongMethod).size());
    }

    private static ClassNode clientClass() {
        var input = new ClassNode();
        input.name = TARGET;
        input.methods.add(new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "begin", "()V", null, null));
        return input;
    }

    private static MethodInsnNode call(String owner, String name, String desc) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, owner, name, desc, false);
    }

    private static List<MethodInsnNode> methodCalls(MethodNode method) {
        List<MethodInsnNode> calls = new ArrayList<>();
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call) calls.add(call);
        }
        return calls;
    }

    private static void assertHook(MethodInsnNode call, String name) {
        assertEquals(HOOKS, call.owner);
        assertEquals(name, call.name);
    }
}
