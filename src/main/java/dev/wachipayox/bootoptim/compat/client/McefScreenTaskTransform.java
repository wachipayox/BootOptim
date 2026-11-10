package dev.wachipayox.bootoptim.compat.client;

import java.util.Arrays;
import java.util.List;
import net.neoforged.fml.ModList;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Finds the single MCEF download-success task after all Minecraft mixins have run. */
final class McefScreenTaskTransform {
    static final String MECHANISM = "mcef-first-screen-task";
    private static final String MINECRAFT = "net/minecraft/client/Minecraft";
    private static final String MCEF = "com/cinemamod/mcef/MCEF";
    private static final String LOGGER = "org/slf4j/Logger";
    private static final String TASK = "(Ljava/lang/Runnable;)V";
    private static final boolean ENABLED = Boolean.parseBoolean(
            System.getProperty("boot_optim.mcefFirstScreenTask", "true"))
            && Boolean.parseBoolean(System.getProperty(McefFirstConsumerDefer.PROPERTY, "true"));
    private static volatile boolean installed;

    private McefScreenTaskTransform() {}

    static boolean enabled() {
        return ENABLED;
    }

    static void install(ClassNode owner) {
        if (ENABLED && owner.name.equals(MINECRAFT)) {
            installed = rewrite(owner);
        }
    }

    static void reportMissing() {
        if (ENABLED && !installed && ModList.get().isLoaded("mcef")) {
            CompatLog.fallback(MECHANISM, "mcef", "audited_screen_task_not_found");
        }
    }

    static boolean rewrite(ClassNode owner) {
        MethodNode handler = null;
        MethodInsnNode submission = null;
        for (MethodNode method : owner.methods) {
            if (!method.name.contains("$mcef$redirScreen")) {
                continue;
            }
            List<AbstractInsnNode> code = code(method);
            for (int i = 1; i + 1 < code.size(); i++) {
                if (!call(code.get(i - 1), Opcodes.INVOKESTATIC, owner.name, "getInstance", "()L" + owner.name + ";")
                        || !(code.get(i) instanceof InvokeDynamicInsnNode factory)
                        || !call(code.get(i + 1), Opcodes.INVOKEVIRTUAL, owner.name, "execute", TASK)
                        || !isAuditedFactory(owner, factory)) {
                    continue;
                }
                if (submission != null) {
                    return false;
                }
                handler = method;
                submission = (MethodInsnNode) code.get(i + 1);
            }
        }
        if (submission == null) {
            return false;
        }
        handler.instructions.set(submission, new MethodInsnNode(Opcodes.INVOKESTATIC,
                "dev/wachipayox/bootoptim/compat/client/McefFirstConsumerDefer", "submitAuditedScreenTask",
                "(L" + owner.name + ";Ljava/lang/Runnable;)V", false));
        return true;
    }

    private static boolean isAuditedFactory(ClassNode owner, InvokeDynamicInsnNode factory) {
        if (!factory.name.equals("run") || !factory.desc.equals("()Ljava/lang/Runnable;")
                || !factory.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")
                || !factory.bsm.getName().equals("metafactory") || factory.bsm.getTag() != Opcodes.H_INVOKESTATIC
                || factory.bsmArgs.length != 3 || !Type.getMethodType("()V").equals(factory.bsmArgs[0])
                || !(factory.bsmArgs[1] instanceof Handle body) || !Type.getMethodType("()V").equals(factory.bsmArgs[2])
                || !body.getOwner().equals(owner.name) || !body.getDesc().equals("()V")
                || body.getTag() != Opcodes.H_INVOKESTATIC) {
            return false;
        }
        return owner.methods.stream().anyMatch(method -> method.name.equals(body.getName())
                && method.desc.equals(body.getDesc()) && (method.access & Opcodes.ACC_STATIC) != 0
                && isAuditedBody(method));
    }

    private static boolean isAuditedBody(MethodNode method) {
        List<AbstractInsnNode> code = code(method);
        if (code.size() != 14 || method.tryCatchBlocks.size() != 1
                || !call(code.get(0), Opcodes.INVOKESTATIC, MCEF, "getLogger", "()L" + LOGGER + ";")
                || !(code.get(1) instanceof LdcInsnNode message && message.cst instanceof String)
                || !call(code.get(2), Opcodes.INVOKEINTERFACE, LOGGER, "debug", "(Ljava/lang/String;)V")
                || !(code.get(3) instanceof LdcInsnNode delay && Long.valueOf(1000L).equals(delay.cst))
                || !call(code.get(4), Opcodes.INVOKESTATIC, "java/lang/Thread", "sleep", "(J)V")
                || !(code.get(5) instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.GOTO)
                || !(code.get(6) instanceof VarInsnNode store && store.getOpcode() == Opcodes.ASTORE)
                || !call(code.get(7), Opcodes.INVOKESTATIC, MCEF, "getLogger", "()L" + LOGGER + ";")
                || !(code.get(8) instanceof LdcInsnNode error && error.cst instanceof String)
                || !(code.get(9) instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD && load.var == store.var)
                || !call(code.get(10), Opcodes.INVOKEINTERFACE, LOGGER, "error", "(Ljava/lang/String;Ljava/lang/Throwable;)V")
                || !call(code.get(11), Opcodes.INVOKESTATIC, MCEF, "initialize", "()Z")
                || code.get(12).getOpcode() != Opcodes.POP || code.get(13).getOpcode() != Opcodes.RETURN) {
            return false;
        }
        var handler = method.tryCatchBlocks.getFirst();
        return "java/lang/InterruptedException".equals(handler.type)
                && nextOpcode(handler.start) == code.get(3) && nextOpcode(handler.end) == code.get(5)
                && nextOpcode(handler.handler) == code.get(6) && nextOpcode(jump.label) == code.get(11);
    }

    private static List<AbstractInsnNode> code(MethodNode method) {
        return Arrays.stream(method.instructions.toArray()).filter(node -> node.getOpcode() >= 0).toList();
    }

    private static AbstractInsnNode nextOpcode(AbstractInsnNode node) {
        while (node != null && node.getOpcode() < 0) {
            node = node.getNext();
        }
        return node;
    }

    private static boolean call(AbstractInsnNode node, int opcode, String owner, String name, String descriptor) {
        return node instanceof MethodInsnNode method && method.getOpcode() == opcode
                && method.owner.equals(owner) && method.name.equals(name) && method.desc.equals(descriptor);
    }
}
