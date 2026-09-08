package dev.wachipayox.bootoptim.bootstrap;

import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.TargetType;
import cpw.mods.modlauncher.api.TransformerVoteResult;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Instruments only the game-layer client preamble between ClientModLoader.begin() entry and its delegation to
 * CommonModLoader.begin(...). It never targets FML's bootstrap-loaded ModLoader implementation.
 */
public final class ClientPostBootstrapTraceTransformer implements ITransformer<ClassNode> {
    private static final String TARGET = "net/neoforged/neoforge/client/loading/ClientModLoader";
    private static final String COMMON = "net/neoforged/neoforge/internal/CommonModLoader";
    private static final String LANGUAGE = "net/neoforged/neoforge/server/LanguageHook";
    private static final String HOOKS = "dev/wachipayox/bootoptim/bootstrap/ClientPostBootstrapTraceHooks";
    private static final String COMMON_BEGIN_DESC = "(Ljava/lang/Runnable;Z)V";

    @Override
    public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
        if (input == null || !TARGET.equals(input.name)) return input;

        MethodNode begin = null;
        for (var method : input.methods) {
            if ("begin".equals(method.name) && "()V".equals(method.desc)) {
                if (begin != null) return input;
                begin = method;
            }
        }
        if (begin == null) return input;

        AbstractInsnNode firstExecutable = firstExecutable(begin);
        if (firstExecutable == null) return input;

        MethodInsnNode languageCall = null;
        MethodInsnNode commonBeginCall = null;
        for (var instruction : begin.instructions) {
            if (!(instruction instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESTATIC) continue;
            if (LANGUAGE.equals(call.owner) && "loadBuiltinLanguages".equals(call.name) && "()V".equals(call.desc)) {
                if (languageCall != null) return input;
                languageCall = call;
            }
            if ((TARGET.equals(call.owner) || COMMON.equals(call.owner))
                    && "begin".equals(call.name)
                    && COMMON_BEGIN_DESC.equals(call.desc)) {
                if (commonBeginCall != null) return input;
                commonBeginCall = call;
            }
        }
        if (languageCall == null || commonBeginCall == null || !appearsBefore(languageCall, commonBeginCall)) return input;

        begin.instructions.insertBefore(firstExecutable, call("beginClientPreGather"));
        begin.instructions.insertBefore(languageCall, call("beginBuiltinLanguages"));
        begin.instructions.insert(languageCall, call("endBuiltinLanguages"));
        begin.instructions.insertBefore(commonBeginCall, call("endClientPreGather"));
        return input;
    }

    private static boolean appearsBefore(AbstractInsnNode first, AbstractInsnNode second) {
        for (AbstractInsnNode cursor = first; cursor != null; cursor = cursor.getNext()) {
            if (cursor == second) return true;
        }
        return false;
    }

    private static AbstractInsnNode firstExecutable(MethodNode method) {
        for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null; instruction = instruction.getNext()) {
            if (instruction.getOpcode() >= 0) return instruction;
        }
        return null;
    }

    private static MethodInsnNode call(String name) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, name, "()V", false);
    }

    @Override
    public TransformerVoteResult castVote(ITransformerVotingContext context) {
        return TransformerVoteResult.YES;
    }

    @Override
    public Set<Target<ClassNode>> targets() {
        return Set.of(Target.targetClass("net.neoforged.neoforge.client.loading.ClientModLoader"));
    }

    @Override
    public TargetType<ClassNode> getTargetType() {
        return TargetType.CLASS;
    }

    @Override
    public String[] labels() {
        return new String[] { "boot_optim_client_post_bootstrap_trace" };
    }
}
