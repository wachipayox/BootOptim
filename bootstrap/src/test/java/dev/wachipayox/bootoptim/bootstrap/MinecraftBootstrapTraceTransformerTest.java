package dev.wachipayox.bootoptim.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

class MinecraftBootstrapTraceTransformerTest {
    private static final String HOOKS = "dev/wachipayox/bootoptim/bootstrap/MinecraftBootstrapTraceHooks";
    private static final String TRANSITION_HOOKS = "dev/wachipayox/bootoptim/bootstrap/ModLauncherTransitionTraceHooks";
    private static final String TRACE = "dev/wachipayox/bootoptim/trace/StructuredBootTrace";

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

    @Test
    void acceptedTransformBoundaryPrecedesExistingBytecodeMutation() throws IOException {
        var transformerClass = readClass(MinecraftBootstrapTraceTransformer.class);
        var transform = methodNamed(transformerClass, "transform");
        var calls = methodCalls(transform);

        int accepted = indexOfCall(calls, TRANSITION_HOOKS, "minecraftBootstrapTransformAccepted");
        int firstMutation = indexOfCall(calls, "org/objectweb/asm/tree/InsnList", "insertBefore");
        assertTrue(accepted >= 0, "strict Bootstrap acceptance must emit the causal split edge");
        assertTrue(firstMutation > accepted, "accepted-transform edge must precede diagnostic bytecode mutation");
    }

    @Test
    void closesTransitionPhaseBeforeOpeningBootstrapTask() throws IOException {
        var hooksClass = readClass(MinecraftBootstrapTraceHooks.class);
        var begin = methodNamed(hooksClass, "beginBootstrap");
        var calls = methodCalls(begin);

        int transitionEnd = indexOfCall(calls, TRANSITION_HOOKS, "endTransitionAtMinecraftBootstrap");
        int bootstrapBegin = indexOfCall(calls, TRACE, "beginTask");
        assertTrue(transitionEnd >= 0, "bootstrap begin must close the SERVICE-to-game transition phase");
        assertTrue(bootstrapBegin > transitionEnd, "transition phase end must precede minecraft_bootstrap task begin");
    }

    @Test
    void serviceCallbackKeepsItsTaskThreadLocalAroundTransformerConstruction() throws IOException {
        var serviceClass = readClass(EarlyStartupProbeService.class);
        var transformers = methodNamed(serviceClass, "transformers");
        var calls = methodCalls(transformers);

        int transitionBegin = indexOfCall(calls, TRANSITION_HOOKS, "beginTransition");
        int bootstrapTransformerCtor = indexOfCall(
                calls,
                "dev/wachipayox/bootoptim/bootstrap/MinecraftBootstrapTraceTransformer",
                "<init>");
        int callbackEnd = indexOfCall(calls, TRANSITION_HOOKS, "endTransformersCallback");
        assertTrue(transitionBegin >= 0, "SERVICE transformers callback must emit the transition begin edge");
        assertTrue(bootstrapTransformerCtor > transitionBegin, "callback task must open before diagnostic transformer construction");
        assertTrue(callbackEnd > bootstrapTransformerCtor, "callback task must close on the SERVICE thread after transformer construction");
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

    private static ClassNode readClass(Class<?> type) throws IOException {
        try (var input = type.getResourceAsStream(type.getSimpleName() + ".class")) {
            if (input == null) throw new IOException("missing class resource for " + type.getName());
            var node = new ClassNode();
            new ClassReader(input).accept(node, 0);
            return node;
        }
    }

    private static MethodNode methodNamed(ClassNode type, String name) {
        return type.methods.stream().filter(method -> method.name.equals(name)).findFirst().orElseThrow();
    }

    private static int indexOfCall(List<MethodInsnNode> calls, String owner, String name) {
        for (int i = 0; i < calls.size(); i++) {
            var call = calls.get(i);
            if (call.owner.equals(owner) && call.name.equals(name)) return i;
        }
        return -1;
    }
}
