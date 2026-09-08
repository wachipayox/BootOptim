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
    void wrapsExactBootstrapMethodInOrder() {
        var input = bootstrapClass();
        var method = input.methods.getFirst();
        var bodyCall = call("net/minecraft/core/registries/BuiltInRegistries", "bootStrap", "()V");
        method.instructions.add(bodyCall);
        method.instructions.add(new InsnNode(Opcodes.RETURN));

        var output = new MinecraftBootstrapTraceTransformer().transform(input, null);
        assertSame(input, output);

        List<MethodInsnNode> calls = methodCalls(method);
        assertEquals(3, calls.size());
        assertEquals(HOOKS, calls.get(0).owner);
        assertEquals("beginBootstrap", calls.get(0).name);
        assertSame(bodyCall, calls.get(1));
        assertEquals(HOOKS, calls.get(2).owner);
        assertEquals("endBootstrap", calls.get(2).name);
    }

    @Test
    void closesEveryNormalReturn() {
        var input = bootstrapClass();
        var method = input.methods.getFirst();
        method.instructions.add(new InsnNode(Opcodes.NOP));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.instructions.add(new InsnNode(Opcodes.RETURN));

        new MinecraftBootstrapTraceTransformer().transform(input, null);
        assertEquals(3, methodCalls(method).size());
        assertEquals("beginBootstrap", methodCalls(method).get(0).name);
        assertEquals("endBootstrap", methodCalls(method).get(1).name);
        assertEquals("endBootstrap", methodCalls(method).get(2).name);
    }

    @Test
    void rejectsMissingAmbiguousOrWrongTargets() {
        var missing = bootstrapClass();
        missing.methods.getFirst().instructions.add(new InsnNode(Opcodes.ATHROW));
        new MinecraftBootstrapTraceTransformer().transform(missing, null);
        assertEquals(0, methodCalls(missing.methods.getFirst()).size());

        var duplicate = bootstrapClass();
        duplicate.methods.add(new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "bootStrap", "()V", null, null));
        duplicate.methods.getFirst().instructions.add(new InsnNode(Opcodes.RETURN));
        duplicate.methods.get(1).instructions.add(new InsnNode(Opcodes.RETURN));
        new MinecraftBootstrapTraceTransformer().transform(duplicate, null);
        assertEquals(0, methodCalls(duplicate.methods.getFirst()).size());
        assertEquals(0, methodCalls(duplicate.methods.get(1)).size());

        var wrong = bootstrapClass();
        wrong.name = "net/minecraft/client/main/Main";
        wrong.methods.getFirst().instructions.add(new InsnNode(Opcodes.RETURN));
        new MinecraftBootstrapTraceTransformer().transform(wrong, null);
        assertEquals(0, methodCalls(wrong.methods.getFirst()).size());
    }

    private static ClassNode bootstrapClass() {
        var input = new ClassNode();
        input.name = "net/minecraft/server/Bootstrap";
        input.methods.add(new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "bootStrap", "()V", null, null));
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
