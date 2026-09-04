package dev.wachipayox.bootoptim.bootstrap;

import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.TargetType;
import cpw.mods.modlauncher.api.TransformerVoteResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.FieldInsnNode;

/**
 * Diagnostic-only early transformer that exposes the exact Xaero DeferredWorkQueue runnable boundary.
 *
 * <p>The transformer does not replace or wrap the Runnable. It inserts a balanced zero-argument
 * marker call immediately before the existing Runnable.run() instruction and before each existing
 * active-container clear in that same synthetic task method. The marker helpers live on
 * DeferredWorkQueue itself so no cross-module BootOptim callback is introduced into FML bytecode.</p>
 */
public final class XaeroDeferredTaskBoundaryTransformer implements ITransformer<ClassNode> {
    public static final String PROFILE_PROPERTY = "boot_optim.profileXaeroDeferredTask";
    public static final String TARGET = "net/neoforged/fml/DeferredWorkQueue";
    public static final String START_FIELD = "bootoptim$xaeroStartNanos";
    public static final String END_FIELD = "bootoptim$xaeroEndNanos";
    public static final String THREAD_FIELD = "bootoptim$xaeroThreadId";
    public static final String STATE_FIELD = "bootoptim$xaeroBoundaryState";

    private static final String MOD_LOADING_CONTEXT = "net/neoforged/fml/ModLoadingContext";
    private static final String MOD_CONTAINER = "net/neoforged/fml/ModContainer";
    private static final String MARK_START = "bootoptim$markXaeroDeferredStart";
    private static final String MARK_END = "bootoptim$markXaeroDeferredEnd";
    private static final int FIELD_ACCESS = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE | Opcodes.ACC_SYNTHETIC;

    @Override
    public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
        if (!Boolean.getBoolean(PROFILE_PROPERTY)) {
            return input;
        }

        MethodNode taskMethod = null;
        MethodInsnNode runnableRun = null;
        int runnableRunCount = 0;
        for (MethodNode method : input.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKEINTERFACE
                        && call.owner.equals("java/lang/Runnable")
                        && call.name.equals("run")
                        && call.desc.equals("()V")) {
                    runnableRunCount++;
                    taskMethod = method;
                    runnableRun = call;
                }
            }
        }

        if (runnableRunCount != 1 || taskMethod == null || runnableRun == null) {
            System.out.printf(
                    "BOOTOPTIM_XAERO_DEFERRED_TRANSFORM status=unmatched runnable_run_count=%d%n",
                    runnableRunCount);
            return input;
        }

        List<MethodInsnNode> ownerClears = new ArrayList<>();
        for (AbstractInsnNode insn : taskMethod.instructions) {
            if (!(insn instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKEVIRTUAL
                    || !call.owner.equals(MOD_LOADING_CONTEXT)
                    || !call.name.equals("setActiveContainer")
                    || !call.desc.equals("(Lnet/neoforged/fml/ModContainer;)V")) {
                continue;
            }
            AbstractInsnNode previous = previousOpcode(call);
            if (previous != null && previous.getOpcode() == Opcodes.ACONST_NULL) {
                ownerClears.add(call);
            }
        }

        if (ownerClears.isEmpty()) {
            System.out.println("BOOTOPTIM_XAERO_DEFERRED_TRANSFORM status=unmatched owner_clear_count=0");
            return input;
        }

        input.fields.add(new FieldNode(FIELD_ACCESS, START_FIELD, "J", null, null));
        input.fields.add(new FieldNode(FIELD_ACCESS, END_FIELD, "J", null, null));
        input.fields.add(new FieldNode(FIELD_ACCESS, THREAD_FIELD, "J", null, null));
        input.fields.add(new FieldNode(FIELD_ACCESS, STATE_FIELD, "I", null, null));
        input.methods.add(createStartMarker());
        input.methods.add(createEndMarker());

        taskMethod.instructions.insertBefore(
                runnableRun,
                new MethodInsnNode(Opcodes.INVOKESTATIC, TARGET, MARK_START, "()V", false));
        for (MethodInsnNode clear : ownerClears) {
            taskMethod.instructions.insertBefore(
                    clear,
                    new MethodInsnNode(Opcodes.INVOKESTATIC, TARGET, MARK_END, "()V", false));
        }

        System.out.printf(
                "BOOTOPTIM_XAERO_DEFERRED_TRANSFORM status=applied method=%s%s owner_clear_count=%d%n",
                taskMethod.name,
                taskMethod.desc,
                ownerClears.size());
        return input;
    }

    private static MethodNode createStartMarker() {
        MethodNode method = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                MARK_START,
                "()V",
                null,
                null);
        InsnList code = method.instructions;
        LabelNode done = new LabelNode();

        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                MOD_LOADING_CONTEXT,
                "get",
                "()Lnet/neoforged/fml/ModLoadingContext;",
                false));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                MOD_LOADING_CONTEXT,
                "getActiveContainer",
                "()Lnet/neoforged/fml/ModContainer;",
                false));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                MOD_CONTAINER,
                "getModId",
                "()Ljava/lang/String;",
                false));
        code.add(new LdcInsnNode("xaeroworldmap"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/String",
                "equals",
                "(Ljava/lang/Object;)Z",
                false));
        code.add(new JumpInsnNode(Opcodes.IFEQ, done));

        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "java/lang/Thread",
                "currentThread",
                "()Ljava/lang/Thread;",
                false));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Thread", "threadId", "()J", false));
        code.add(new FieldInsnNode(Opcodes.PUTSTATIC, TARGET, THREAD_FIELD, "J"));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false));
        code.add(new FieldInsnNode(Opcodes.PUTSTATIC, TARGET, START_FIELD, "J"));
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new FieldInsnNode(Opcodes.PUTSTATIC, TARGET, STATE_FIELD, "I"));

        code.add(done);
        code.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        code.add(new InsnNode(Opcodes.RETURN));
        method.maxStack = 2;
        method.maxLocals = 0;
        return method;
    }

    private static MethodNode createEndMarker() {
        MethodNode method = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                MARK_END,
                "()V",
                null,
                null);
        InsnList code = method.instructions;
        LabelNode done = new LabelNode();

        code.add(new FieldInsnNode(Opcodes.GETSTATIC, TARGET, STATE_FIELD, "I"));
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new JumpInsnNode(Opcodes.IF_ICMPNE, done));
        code.add(new FieldInsnNode(Opcodes.GETSTATIC, TARGET, THREAD_FIELD, "J"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "java/lang/Thread",
                "currentThread",
                "()Ljava/lang/Thread;",
                false));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Thread", "threadId", "()J", false));
        code.add(new InsnNode(Opcodes.LCMP));
        code.add(new JumpInsnNode(Opcodes.IFNE, done));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false));
        code.add(new FieldInsnNode(Opcodes.PUTSTATIC, TARGET, END_FIELD, "J"));
        code.add(new InsnNode(Opcodes.ICONST_2));
        code.add(new FieldInsnNode(Opcodes.PUTSTATIC, TARGET, STATE_FIELD, "I"));

        code.add(done);
        code.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        code.add(new InsnNode(Opcodes.RETURN));
        method.maxStack = 4;
        method.maxLocals = 0;
        return method;
    }

    private static AbstractInsnNode previousOpcode(AbstractInsnNode insn) {
        AbstractInsnNode previous = insn.getPrevious();
        while (previous != null && previous.getOpcode() < 0) {
            previous = previous.getPrevious();
        }
        return previous;
    }

    @Override
    public TransformerVoteResult castVote(ITransformerVotingContext context) {
        return TransformerVoteResult.YES;
    }

    @Override
    public Set<Target<ClassNode>> targets() {
        return Set.of(Target.targetClass(TARGET));
    }

    @Override
    public TargetType<ClassNode> getTargetType() {
        return TargetType.CLASS;
    }
}
