package dev.wachipayox.bootoptim.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;
import net.neoforged.fml.loading.progress.ProgressMeter;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

class FmlLoadingTraceTransformerTest {
    private static final String HOOKS = "dev/wachipayox/bootoptim/bootstrap/FmlLoadingTraceHooks";
    private static final String GATHER_DESC =
            "(Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;Ljava/lang/Runnable;)V";

    @Test
    void wrapsNeoForgeGatherCallAndPeriodicCallbackInOrder() {
        var input = new ClassNode();
        input.name = "net/neoforged/neoforge/internal/CommonModLoader";
        var method = new MethodNode(Opcodes.ACC_STATIC, "begin", "(Ljava/lang/Runnable;Z)V", null, null);
        var gather = gatherCall(GATHER_DESC);
        method.instructions.add(gather);
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        input.methods.add(method);

        var output = new FmlLoadingTraceTransformer().transform(input, null);
        assertSame(input, output);

        List<MethodInsnNode> calls = methodCalls(method);
        assertEquals(4, calls.size());
        assertEquals(HOOKS, calls.get(0).owner);
        assertEquals("beginGatherAndInitialize", calls.get(0).name);
        assertEquals(HOOKS, calls.get(1).owner);
        assertEquals("wrapPeriodicTask", calls.get(1).name);
        assertEquals("(Ljava/lang/Runnable;)Ljava/lang/Runnable;", calls.get(1).desc);
        assertSame(gather, calls.get(2));
        assertEquals(HOOKS, calls.get(3).owner);
        assertEquals("endGatherAndInitialize", calls.get(3).name);
    }

    @Test
    void exactProgressNamesMapToCoarseGatherStages() {
        assertEquals(0, FmlLoadingTraceHooks.progressStage(List.of()));
        assertEquals(0, FmlLoadingTraceHooks.progressStage(List.of(new ProgressMeter("Registry initialization", 1, 0, null))));
        assertEquals(1, FmlLoadingTraceHooks.progressStage(List.of(new ProgressMeter("Mod Construction", 10, 3, null))));
        assertEquals(2, FmlLoadingTraceHooks.progressStage(List.of(new ProgressMeter("Mod Construction: Deferred Queue", 0, 0, null))));
    }

    @Test
    void failsOpenWhenGatherDescriptorChanges() {
        var input = new ClassNode();
        input.name = "net/neoforged/neoforge/internal/CommonModLoader";
        var method = new MethodNode(Opcodes.ACC_STATIC, "begin", "()V", null, null);
        var gather = gatherCall("(Ljava/lang/Runnable;)V");
        method.instructions.add(gather);
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        input.methods.add(method);

        new FmlLoadingTraceTransformer().transform(input, null);
        assertEquals(List.of(gather), methodCalls(method));
    }

    @Test
    void failsOpenWhenCallsiteIsNotUnique() {
        var input = new ClassNode();
        input.name = "net/neoforged/neoforge/internal/CommonModLoader";
        var method = new MethodNode(Opcodes.ACC_STATIC, "begin", "()V", null, null);
        var first = gatherCall(GATHER_DESC);
        var second = gatherCall(GATHER_DESC);
        method.instructions.add(first);
        method.instructions.add(second);
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        input.methods.add(method);

        new FmlLoadingTraceTransformer().transform(input, null);
        assertEquals(List.of(first, second), methodCalls(method));
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

    private static MethodInsnNode gatherCall(String desc) {
        return new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "net/neoforged/fml/ModLoader",
                "gatherAndInitializeMods",
                desc,
                false);
    }

    private static List<MethodInsnNode> methodCalls(MethodNode method) {
        List<MethodInsnNode> calls = new ArrayList<>();
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call) calls.add(call);
        }
        return calls;
    }
}
