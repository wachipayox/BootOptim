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

class FmlLoadingTraceTransformerTest {
    private static final String HOOKS = "dev/wachipayox/bootoptim/bootstrap/FmlLoadingTraceHooks";
    private static final String FML_MOD_LOADER = "net/neoforged/fml/ModLoader";

    @Test
    void bracketsExactCommonModLoaderPrefixAndGatherInOrder() {
        var input = new ClassNode();
        input.name = "net/neoforged/neoforge/internal/CommonModLoader";
        var method = new MethodNode(Opcodes.ACC_STATIC, "begin", "(Ljava/lang/Runnable;Z)V", null, null);
        var syncExecutor = new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "net/neoforged/fml/ModWorkManager",
                "syncExecutor",
                "()Ljava/util/concurrent/Executor;",
                false);
        var gather = gatherCall();
        method.instructions.add(syncExecutor);
        method.instructions.add(gather);
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        input.methods.add(method);

        var output = new FmlLoadingTraceTransformer().transform(input, null);
        assertSame(input, output);

        List<MethodInsnNode> calls = calls(method);
        assertEquals(6, calls.size());
        assertHook(calls.get(0), "beginCommonModLoaderPrefix");
        assertSame(syncExecutor, calls.get(1));
        assertHook(calls.get(2), "endCommonModLoaderPrefix");
        assertHook(calls.get(3), "beginGatherAndInitialize");
        assertSame(gather, calls.get(4));
        assertHook(calls.get(5), "endGatherAndInitialize");
    }

    @Test
    void duplicateGatherCallRejectsOnlyPrefixMatcher() {
        var input = new ClassNode();
        input.name = "net/neoforged/neoforge/internal/CommonModLoader";
        var method = new MethodNode(Opcodes.ACC_STATIC, "begin", "(Ljava/lang/Runnable;Z)V", null, null);
        method.instructions.add(gatherCall());
        method.instructions.add(gatherCall());
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        input.methods.add(method);

        new FmlLoadingTraceTransformer().transform(input, null);

        List<MethodInsnNode> calls = calls(method);
        assertEquals(6, calls.size());
        assertEquals(0L, calls.stream().filter(call -> HOOKS.equals(call.owner)
                && ("beginCommonModLoaderPrefix".equals(call.name) || "endCommonModLoaderPrefix".equals(call.name))).count());
        assertEquals(2L, calls.stream().filter(call -> HOOKS.equals(call.owner) && "beginGatherAndInitialize".equals(call.name)).count());
        assertEquals(2L, calls.stream().filter(call -> HOOKS.equals(call.owner) && "endGatherAndInitialize".equals(call.name)).count());
    }

    @Test
    void ignoresBootstrapFmlModLoaderClass() {
        var input = new ClassNode();
        input.name = "net/neoforged/fml/ModLoader";
        var method = new MethodNode(Opcodes.ACC_STATIC, "gatherAndInitializeMods", "()V", null, null);
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        input.methods.add(method);

        var output = new FmlLoadingTraceTransformer().transform(input, null);
        assertSame(input, output);
        assertEquals(1, method.instructions.size());
    }

    private static MethodInsnNode gatherCall() {
        return new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                FML_MOD_LOADER,
                "gatherAndInitializeMods",
                "(Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;Ljava/lang/Runnable;)V",
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
    }
}
