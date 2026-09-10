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
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * Diagnostic-only, version-pinned profiler for Minecraft 1.21.1 CrashReport.preload().
 *
 * <p>The transformer is fail-closed: it first validates the complete preload and friendly-report
 * topology, and only then inserts observational marker calls. It does not skip, defer, replace,
 * parallelize, catch, or otherwise reorder any stock crash-path work.</p>
 */
public final class CrashReportPreloadProfileTransformer implements ITransformer<ClassNode> {
    private static final String TARGET = "net/minecraft/CrashReport";
    private static final String MEMORY_RESERVE = "net/minecraft/util/MemoryReserve";
    private static final String REPORT_TYPE = "net/minecraft/ReportType";
    private static final String HOOKS = "dev/wachipayox/bootoptim/bootstrap/CrashReportPreloadProfileHooks";

    @Override
    public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
        if (input == null || !TARGET.equals(input.name)) return input;

        MethodNode preload = uniqueMethod(input, "preload", "()V");
        MethodNode friendlyCore = uniqueMethod(
                input,
                "getFriendlyReport",
                "(Lnet/minecraft/ReportType;Ljava/util/List;)Ljava/lang/String;");
        if (preload == null || friendlyCore == null) return input;

        AbstractInsnNode preloadFirst = firstExecutable(preload);
        AbstractInsnNode friendlyFirst = firstExecutable(friendlyCore);
        MethodInsnNode memoryAllocate = uniqueCall(preload, MEMORY_RESERVE, "allocate", "()V");
        TypeInsnNode dummyNew = uniqueNew(preload, TARGET);
        MethodInsnNode dummyInit = uniqueCall(preload, TARGET, "<init>", "(Ljava/lang/String;Ljava/lang/Throwable;)V");
        MethodInsnNode friendlyOuter = uniqueCall(
                preload,
                TARGET,
                "getFriendlyReport",
                "(Lnet/minecraft/ReportType;)Ljava/lang/String;");
        MethodInsnNode exceptionMessage = uniqueCall(friendlyCore, TARGET, "getExceptionMessage", "()Ljava/lang/String;");
        MethodInsnNode details = uniqueCall(friendlyCore, TARGET, "getDetails", "(Ljava/lang/StringBuilder;)V");
        if (preloadFirst == null || friendlyFirst == null || memoryAllocate == null || dummyNew == null
                || dummyInit == null || friendlyOuter == null || exceptionMessage == null || details == null) {
            return input;
        }

        List<AbstractInsnNode> preloadOriginal = List.of(preload.instructions.toArray());
        int memoryIndex = preloadOriginal.indexOf(memoryAllocate);
        int dummyNewIndex = preloadOriginal.indexOf(dummyNew);
        int dummyInitIndex = preloadOriginal.indexOf(dummyInit);
        int friendlyIndex = preloadOriginal.indexOf(friendlyOuter);
        if (!(memoryIndex >= 0 && memoryIndex < dummyNewIndex && dummyNewIndex < dummyInitIndex && dummyInitIndex < friendlyIndex)) {
            return input;
        }

        // Pin the tiny 1.21.1 method shape: one normal return and no exception handler/branch topology.
        List<AbstractInsnNode> preloadReturns = returns(preload, Opcodes.RETURN);
        if (preloadReturns.size() != 1 || !preload.tryCatchBlocks.isEmpty() || hasControlFlow(preload)) return input;

        List<AbstractInsnNode> friendlyOriginal = List.of(friendlyCore.instructions.toArray());
        int exceptionIndex = friendlyOriginal.indexOf(exceptionMessage);
        int detailsIndex = friendlyOriginal.indexOf(details);
        if (!(exceptionIndex >= 0 && exceptionIndex < detailsIndex)) return input;
        List<AbstractInsnNode> friendlyReturns = returns(friendlyCore, Opcodes.ARETURN);
        if (friendlyReturns.size() != 1) return input;

        // Validate that preload still references ReportType.CRASH exactly once before the render call.
        int crashFieldCount = 0;
        int crashFieldIndex = -1;
        for (int i = 0; i < preloadOriginal.size(); i++) {
            AbstractInsnNode instruction = preloadOriginal.get(i);
            if (instruction instanceof org.objectweb.asm.tree.FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETSTATIC
                    && REPORT_TYPE.equals(field.owner)
                    && "CRASH".equals(field.name)
                    && "Lnet/minecraft/ReportType;".equals(field.desc)) {
                crashFieldCount++;
                crashFieldIndex = i;
            }
        }
        if (crashFieldCount != 1 || !(dummyInitIndex < crashFieldIndex && crashFieldIndex < friendlyIndex)) return input;

        // Mutation begins only after every topology guard above has passed.
        preload.instructions.insertBefore(preloadFirst, hook("preloadEntry"));
        surround(preload, memoryAllocate, "beforeMemoryReserve", "afterMemoryReserve");
        preload.instructions.insertBefore(dummyNew, hook("beforeDummyConstruction"));
        preload.instructions.insert(dummyInit, hook("afterDummyConstruction"));
        surround(preload, friendlyOuter, "beforeFriendlyReport", "afterFriendlyReport");
        preload.instructions.insertBefore(preloadReturns.get(0), hook("preloadExit"));

        friendlyCore.instructions.insertBefore(friendlyFirst, hook("friendlyCoreEntry"));
        surround(friendlyCore, exceptionMessage, "beforeExceptionMessage", "afterExceptionMessage");
        surround(friendlyCore, details, "beforeDetails", "afterDetails");
        friendlyCore.instructions.insertBefore(friendlyReturns.get(0), hook("friendlyCoreExit"));
        return input;
    }

    private static MethodNode uniqueMethod(ClassNode input, String name, String desc) {
        MethodNode found = null;
        for (MethodNode method : input.methods) {
            if (name.equals(method.name) && desc.equals(method.desc)) {
                if (found != null) return null;
                found = method;
            }
        }
        return found;
    }

    private static MethodInsnNode uniqueCall(MethodNode method, String owner, String name, String desc) {
        MethodInsnNode found = null;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner)
                    && name.equals(call.name)
                    && desc.equals(call.desc)) {
                if (found != null) return null;
                found = call;
            }
        }
        return found;
    }

    private static TypeInsnNode uniqueNew(MethodNode method, String desc) {
        TypeInsnNode found = null;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof TypeInsnNode type
                    && type.getOpcode() == Opcodes.NEW
                    && desc.equals(type.desc)) {
                if (found != null) return null;
                found = type;
            }
        }
        return found;
    }

    private static List<AbstractInsnNode> returns(MethodNode method, int opcode) {
        List<AbstractInsnNode> found = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction.getOpcode() == opcode) found.add(instruction);
        }
        return found;
    }

    private static boolean hasControlFlow(MethodNode method) {
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            int type = instruction.getType();
            if (type == AbstractInsnNode.JUMP_INSN
                    || type == AbstractInsnNode.TABLESWITCH_INSN
                    || type == AbstractInsnNode.LOOKUPSWITCH_INSN) {
                return true;
            }
        }
        return false;
    }

    private static void surround(MethodNode method, MethodInsnNode call, String before, String after) {
        method.instructions.insertBefore(call, hook(before));
        method.instructions.insert(call, hook(after));
    }

    private static MethodInsnNode hook(String name) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, name, "()V", false);
    }

    private static AbstractInsnNode firstExecutable(MethodNode method) {
        for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null; instruction = instruction.getNext()) {
            if (instruction.getOpcode() >= 0) return instruction;
        }
        return null;
    }

    @Override
    public TransformerVoteResult castVote(ITransformerVotingContext context) {
        return TransformerVoteResult.YES;
    }

    @Override
    public Set<Target<ClassNode>> targets() {
        return Set.of(Target.targetClass("net.minecraft.CrashReport"));
    }

    @Override
    public TargetType<ClassNode> getTargetType() {
        return TargetType.CLASS;
    }

    @Override
    public String[] labels() {
        return new String[] { "boot_optim_agent100_crash_report_preload_profile" };
    }
}
