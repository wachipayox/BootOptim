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
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Agent 99 diagnostic-only, version-pinned observation of Minecraft 1.21.1 DataFixers startup.
 *
 * <p>The matcher fails closed unless the exact create/optimize topology is present. It records
 * boundaries only; it does not initialize DataFixers early, replace/cache the fixer, wrap the
 * returned future, change the executor, move work, or alter exception/thread semantics.</p>
 */
public final class MinecraftDataFixersLifecycleTransformer implements ITransformer<ClassNode> {
    private static final String TARGET = "net/minecraft/util/datafix/DataFixers";
    private static final String RESULT = "com/mojang/datafixers/DataFixerBuilder$Result";
    private static final String EXECUTORS = "java/util/concurrent/Executors";
    private static final String HOOKS = "dev/wachipayox/bootoptim/bootstrap/MinecraftMainLifecycleHooks";
    private static final String RESULT_DESC = "Lcom/mojang/datafixers/DataFixerBuilder$Result;";

    @Override
    public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
        if (input == null || !TARGET.equals(input.name)) return input;

        MethodNode clinit = uniqueMethod(input, "<clinit>", "()V");
        MethodNode create = uniqueMethod(input, "createFixerUpper", "()" + RESULT_DESC);
        MethodNode optimize = uniqueMethod(input, "optimize", "(Ljava/util/Set;)Ljava/util/concurrent/CompletableFuture;");
        if (clinit == null || create == null || optimize == null) return input;

        MethodInsnNode createCall = uniqueCall(clinit, Opcodes.INVOKESTATIC, TARGET, "createFixerUpper", "()" + RESULT_DESC);
        FieldInsnNode clinitPut = uniqueField(clinit, Opcodes.PUTSTATIC, TARGET, "DATA_FIXER", RESULT_DESC);
        if (createCall == null || clinitPut == null || indexOf(clinit, createCall) >= indexOf(clinit, clinitPut)) return input;

        MethodInsnNode buildCall = uniqueCall(create, Opcodes.INVOKEVIRTUAL, "com/mojang/datafixers/DataFixerBuilder", "build", "()" + RESULT_DESC);
        List<AbstractInsnNode> createReturns = returns(create, Opcodes.ARETURN);
        if (buildCall == null || createReturns.size() != 1 || indexOf(create, buildCall) >= indexOf(create, createReturns.get(0))) return input;

        MethodInsnNode executorCreate = uniqueCall(
                optimize,
                Opcodes.INVOKESTATIC,
                EXECUTORS,
                "newSingleThreadExecutor",
                "(Ljava/util/concurrent/ThreadFactory;)Ljava/util/concurrent/ExecutorService;");
        FieldInsnNode resultGet = uniqueField(optimize, Opcodes.GETSTATIC, TARGET, "DATA_FIXER", RESULT_DESC);
        MethodInsnNode resultOptimize = uniqueCall(
                optimize,
                Opcodes.INVOKEVIRTUAL,
                RESULT,
                "optimize",
                "(Ljava/util/Set;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;");
        List<AbstractInsnNode> optimizeReturns = returns(optimize, Opcodes.ARETURN);
        if (executorCreate == null || resultGet == null || resultOptimize == null || optimizeReturns.size() != 2) return input;
        int executorIndex = indexOf(optimize, executorCreate);
        int getIndex = indexOf(optimize, resultGet);
        int submitIndex = indexOf(optimize, resultOptimize);
        if (!(executorIndex >= 0 && executorIndex < getIndex && getIndex < submitIndex)) return input;

        AbstractInsnNode clinitFirst = firstExecutable(clinit);
        AbstractInsnNode createFirst = firstExecutable(create);
        AbstractInsnNode optimizeFirst = firstExecutable(optimize);
        List<AbstractInsnNode> clinitReturns = returns(clinit, Opcodes.RETURN);
        if (clinitFirst == null || createFirst == null || optimizeFirst == null || clinitReturns.size() != 1) return input;

        clinit.instructions.insertBefore(clinitFirst, hook("dataFixersClinitEntry"));
        surround(clinit, createCall, "beforeCreateFixerUpperCall", "afterCreateFixerUpperCall");
        clinit.instructions.insertBefore(clinitReturns.get(0), hook("dataFixersClinitExit"));

        create.instructions.insertBefore(createFirst, hook("createFixerUpperEntry"));
        create.instructions.insertBefore(createReturns.get(0), hook("createFixerUpperReturn"));

        optimize.instructions.insertBefore(optimizeFirst, hook("dataFixersOptimizeEntry"));
        surround(optimize, executorCreate, "beforeExecutorCreate", "afterExecutorCreate");
        surround(optimize, resultGet, "beforeResultGet", "afterResultGet");
        surround(optimize, resultOptimize, "beforeResultOptimize", "afterResultOptimize");
        for (AbstractInsnNode ret : optimizeReturns) {
            optimize.instructions.insertBefore(ret, hook("dataFixersOptimizeReturn"));
        }
        return input;
    }

    private static MethodNode uniqueMethod(ClassNode owner, String name, String desc) {
        MethodNode found = null;
        for (MethodNode method : owner.methods) {
            if (name.equals(method.name) && desc.equals(method.desc)) {
                if (found != null) return null;
                found = method;
            }
        }
        return found;
    }

    private static MethodInsnNode uniqueCall(MethodNode method, int opcode, String owner, String name, String desc) {
        MethodInsnNode found = null;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == opcode
                    && owner.equals(call.owner)
                    && name.equals(call.name)
                    && desc.equals(call.desc)) {
                if (found != null) return null;
                found = call;
            }
        }
        return found;
    }

    private static FieldInsnNode uniqueField(MethodNode method, int opcode, String owner, String name, String desc) {
        FieldInsnNode found = null;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == opcode
                    && owner.equals(field.owner)
                    && name.equals(field.name)
                    && desc.equals(field.desc)) {
                if (found != null) return null;
                found = field;
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

    private static int indexOf(MethodNode method, AbstractInsnNode needle) {
        return List.of(method.instructions.toArray()).indexOf(needle);
    }

    private static void surround(MethodNode method, AbstractInsnNode instruction, String before, String after) {
        method.instructions.insertBefore(instruction, hook(before));
        method.instructions.insert(instruction, hook(after));
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
        return Set.of(Target.targetClass("net.minecraft.util.datafix.DataFixers"));
    }

    @Override
    public TargetType<ClassNode> getTargetType() {
        return TargetType.CLASS;
    }

    @Override
    public String[] labels() {
        return new String[] { "boot_optim_agent99_datafixers_lifecycle_profile" };
    }
}
