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

class MinecraftBootstrapTraceTransformerTest {
    private static final String HOOKS = "dev/wachipayox/bootoptim/bootstrap/MinecraftBootstrapTraceHooks";

    @Test
    void wrapsPatchedBootstrapBoundaryInOrder() {
        var input = mainClass();
        var method = input.methods.getFirst();
        var runAndTick = call("net/neoforged/fml/loading/BackgroundWaiter", "runAndTick", "(Ljava/lang/Runnable;Ljava/lang/Runnable;)V");
        var validate = call("net/minecraft/server/Bootstrap", "validate", "()V");
        var begin = call("net/neoforged/neoforge/client/loading/ClientModLoader", "begin", "()V");
        method.instructions.add(runAndTick);
        method.instructions.add(validate);
        method.instructions.add(begin);
        method.instructions.add(new InsnNode(Opcodes.RETURN));

        var output = new MinecraftBootstrapTraceTransformer().transform(input, null);
        assertSame(input, output);

        List<MethodInsnNode> calls = methodCalls(method);
        assertEquals(5, calls.size());
        assertEquals(HOOKS, calls.get(0).owner);
        assertEquals("beginBootstrapAndValidate", calls.get(0).name);
        assertSame(runAndTick, calls.get(1));
        assertSame(validate, calls.get(2));
        assertEquals(HOOKS, calls.get(3).owner);
        assertEquals("endBootstrapAndValidate", calls.get(3).name);
        assertSame(begin, calls.get(4));
    }

    @Test
    void rejectsMissingOrAmbiguousAnchors() {
        var missingEnd = mainClass();
        missingEnd.methods.getFirst().instructions.add(call(
                "net/neoforged/fml/loading/BackgroundWaiter", "runAndTick", "()V"));
        missingEnd.methods.getFirst().instructions.add(new InsnNode(Opcodes.RETURN));
        new MinecraftBootstrapTraceTransformer().transform(missingEnd, null);
        assertEquals(1, methodCalls(missingEnd.methods.getFirst()).size());

        var duplicateStart = mainClass();
        duplicateStart.methods.getFirst().instructions.add(call(
                "net/neoforged/fml/loading/BackgroundWaiter", "runAndTick", "()V"));
        duplicateStart.methods.getFirst().instructions.add(call(
                "net/neoforged/fml/loading/BackgroundWaiter", "runAndTick", "()V"));
        duplicateStart.methods.getFirst().instructions.add(call(
                "net/neoforged/neoforge/client/loading/ClientModLoader", "begin", "()V"));
        duplicateStart.methods.getFirst().instructions.add(new InsnNode(Opcodes.RETURN));
        new MinecraftBootstrapTraceTransformer().transform(duplicateStart, null);
        assertEquals(3, methodCalls(duplicateStart.methods.getFirst()).size());
    }

    @Test
    void ignoresNonMainTarget() {
        var input = mainClass();
        input.name = "net/minecraft/client/Minecraft";
        input.methods.getFirst().instructions.add(call(
                "net/neoforged/fml/loading/BackgroundWaiter", "runAndTick", "()V"));
        input.methods.getFirst().instructions.add(call(
                "net/neoforged/neoforge/client/loading/ClientModLoader", "begin", "()V"));
        input.methods.getFirst().instructions.add(new InsnNode(Opcodes.RETURN));

        new MinecraftBootstrapTraceTransformer().transform(input, null);
        assertEquals(2, methodCalls(input.methods.getFirst()).size());
    }

    private static ClassNode mainClass() {
        var input = new ClassNode();
        input.name = "net/minecraft/client/main/Main";
        input.methods.add(new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "main", "([Ljava/lang/String;)V", null, null));
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
}
