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
 * Version-pinned trace of NeoForge's built-in language load on the GAME layer.
 *
 * <p>This deliberately avoids transforming {@code ClientModLoader.begin()}, whose strict matcher was retired in #206.
 * The target is the ordinary GAME-layer callee. Instrumentation is accepted only when the exact
 * {@code loadBuiltinLanguages()V} method is unique and contains exactly one expected
 * {@code I18nManager.injectTranslations(Map)} terminal publication anchor. Any drift leaves the class untouched.</p>
 */
public final class NeoForgeLanguageTraceTransformer implements ITransformer<ClassNode> {
    private static final String TARGET = "net/neoforged/neoforge/server/LanguageHook";
    private static final String METHOD = "loadBuiltinLanguages";
    private static final String DESC = "()V";
    private static final String I18N_MANAGER = "net/neoforged/fml/i18n/I18nManager";
    private static final String INJECT_DESC = "(Ljava/util/Map;)V";
    private static final String HOOKS = "dev/wachipayox/bootoptim/bootstrap/NeoForgeLanguageTraceHooks";

    @Override
    public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
        if (input == null || !TARGET.equals(input.name)) return input;

        MethodNode target = null;
        for (var method : input.methods) {
            if (!METHOD.equals(method.name) || !DESC.equals(method.desc)) continue;
            if (target != null) return input;
            target = method;
        }
        if (target == null) return input;

        AbstractInsnNode firstExecutable = firstExecutable(target);
        if (firstExecutable == null) return input;

        int publicationAnchors = 0;
        int normalReturns = 0;
        for (var instruction : target.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call
                    && I18N_MANAGER.equals(call.owner)
                    && "injectTranslations".equals(call.name)
                    && INJECT_DESC.equals(call.desc)) {
                publicationAnchors++;
            }
            if (instruction.getOpcode() == Opcodes.RETURN) normalReturns++;
        }
        if (publicationAnchors != 1 || normalReturns == 0) return input;

        target.instructions.insertBefore(firstExecutable, hook("beginBuiltinLanguages"));
        for (var instruction : target.instructions.toArray()) {
            if (instruction.getOpcode() == Opcodes.RETURN) {
                target.instructions.insertBefore(instruction, hook("endBuiltinLanguages"));
            }
        }
        return input;
    }

    private static AbstractInsnNode firstExecutable(MethodNode method) {
        for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null; instruction = instruction.getNext()) {
            if (instruction.getOpcode() >= 0) return instruction;
        }
        return null;
    }

    private static MethodInsnNode hook(String name) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, name, "()V", false);
    }

    @Override
    public TransformerVoteResult castVote(ITransformerVotingContext context) {
        return TransformerVoteResult.YES;
    }

    @Override
    public Set<Target<ClassNode>> targets() {
        return Set.of(Target.targetClass("net.neoforged.neoforge.server.LanguageHook"));
    }

    @Override
    public TargetType<ClassNode> getTargetType() {
        return TargetType.CLASS;
    }

    @Override
    public String[] labels() {
        return new String[] { "boot_optim_neoforge_language_causal_trace" };
    }
}
