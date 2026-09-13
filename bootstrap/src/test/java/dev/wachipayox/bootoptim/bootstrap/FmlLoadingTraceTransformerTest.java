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

    @Test
    void wrapsNeoForgeGatherCallInOrder() {
        var input = new ClassNode();
        input.name = "net/neoforged/neoforge/internal/CommonModLoader";
        var method = new MethodNode(Opcodes.ACC_STATIC, "begin", "(Ljava/lang/Runnable;Z)V", null, null);
        var gather = new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "net/neoforged/fml/ModLoader",
                "gatherAndInitializeMods",
                "(Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;Ljava/lang/Runnable;)V",
                false);
        method.instructions.add(gather);
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        input.methods.add(method);

        var output = new FmlLoadingTraceTransformer().transform(input, null);
        assertSame(input, output);

        List<MethodInsnNode> calls = new ArrayList<>();
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call) calls.add(call);
        }

        assertEquals(3, calls.size());
        assertEquals(HOOKS, calls.get(0).owner);
        assertEquals("beginGatherAndInitialize", calls.get(0).name);
        assertSame(gather, calls.get(1));
        assertEquals(HOOKS, calls.get(2).owner);
        assertEquals("endGatherAndInitialize", calls.get(2).name);
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
}
