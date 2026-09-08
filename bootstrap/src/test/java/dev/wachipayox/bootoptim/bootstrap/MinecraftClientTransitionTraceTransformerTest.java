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

class MinecraftClientTransitionTraceTransformerTest {
    private static final String HOOKS = "dev/wachipayox/bootoptim/bootstrap/MinecraftClientTransitionTraceHooks";
    private static final String DESC = "(Lnet/minecraft/client/Minecraft;Lnet/minecraft/server/packs/repository/PackRepository;Lnet/minecraft/server/packs/resources/ReloadableResourceManager;)V";

    @Test
    void marksExactClientModLoaderCallsiteInConstructor() {
        var input = minecraft();
        var ctor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        var call = clientBegin();
        ctor.instructions.add(call);
        ctor.instructions.add(new InsnNode(Opcodes.RETURN));
        input.methods.add(ctor);

        var output = new MinecraftClientTransitionTraceTransformer().transform(input, null);
        assertSame(input, output);
        List<MethodInsnNode> calls = calls(ctor);
        assertEquals(2, calls.size());
        assertEquals(HOOKS, calls.get(0).owner);
        assertEquals("markClientModLoaderEntry", calls.get(0).name);
        assertSame(call, calls.get(1));
    }

    @Test
    void duplicateExactCallFailsClosed() {
        var input = minecraft();
        var ctor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.instructions.add(clientBegin());
        ctor.instructions.add(clientBegin());
        ctor.instructions.add(new InsnNode(Opcodes.RETURN));
        input.methods.add(ctor);

        new MinecraftClientTransitionTraceTransformer().transform(input, null);
        assertEquals(0L, calls(ctor).stream().filter(call -> HOOKS.equals(call.owner)).count());
    }

    @Test
    void exactCallOutsideConstructorFailsClosed() {
        var input = minecraft();
        var method = new MethodNode(Opcodes.ACC_PUBLIC, "tick", "()V", null, null);
        method.instructions.add(clientBegin());
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        input.methods.add(method);

        new MinecraftClientTransitionTraceTransformer().transform(input, null);
        assertEquals(0L, calls(method).stream().filter(call -> HOOKS.equals(call.owner)).count());
    }

    @Test
    void oldNoArgClientMatcherShapeIsNotAccepted() {
        var input = minecraft();
        var ctor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "net/neoforged/neoforge/client/loading/ClientModLoader", "begin", "()V", false));
        ctor.instructions.add(new InsnNode(Opcodes.RETURN));
        input.methods.add(ctor);

        new MinecraftClientTransitionTraceTransformer().transform(input, null);
        assertEquals(0L, calls(ctor).stream().filter(call -> HOOKS.equals(call.owner)).count());
    }

    private static ClassNode minecraft() {
        var input = new ClassNode();
        input.name = "net/minecraft/client/Minecraft";
        return input;
    }

    private static MethodInsnNode clientBegin() {
        return new MethodInsnNode(Opcodes.INVOKESTATIC,
                "net/neoforged/neoforge/client/loading/ClientModLoader", "begin", DESC, false);
    }

    private static List<MethodInsnNode> calls(MethodNode method) {
        List<MethodInsnNode> calls = new ArrayList<>();
        for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call) calls.add(call);
        return calls;
    }
}
