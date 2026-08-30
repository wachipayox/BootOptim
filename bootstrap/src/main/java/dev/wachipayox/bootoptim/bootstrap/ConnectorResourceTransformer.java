package dev.wachipayox.bootoptim.bootstrap;

import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.TransformerVoteResult;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

public final class ConnectorResourceTransformer implements ITransformer<ClassNode> {
    private static final String MINECRAFT = "net/minecraft/client/Minecraft";
    private static final String CONNECTOR_LOADER = "org/sinytra/connector/mod/ConnectorLoader";
    private static final String COMPAT = "dev/wachipayox/bootoptim/bootstrap/ConnectorResourceCompat";
    private static final Set<Target> TARGETS = Set.of(
            Target.targetClass("net.minecraft.client.Minecraft"),
            Target.targetClass("org.sinytra.connector.mod.ConnectorLoader"));

    @Override
    public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
        if (MINECRAFT.equals(input.name)) {
            transformMinecraft(input);
        } else if (CONNECTOR_LOADER.equals(input.name)) {
            transformConnectorLoader(input);
        }
        return input;
    }

    @Override
    public TransformerVoteResult castVote(ITransformerVotingContext context) {
        return TransformerVoteResult.YES;
    }

    @Override
    public Set<Target> targets() {
        return TARGETS;
    }

    private static void transformMinecraft(ClassNode node) {
        boolean injected = false;
        for (MethodNode method : node.methods) {
            if (!"<init>".equals(method.name)) continue;
            InsnList hook = new InsnList();
            hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, COMPAT, "beforeMinecraftConstruction", "()V", false));
            method.instructions.insert(hook);
            injected = true;
        }
        if (!injected) {
            StartupDiagnostics.event("CONNECTOR_RESOURCES", "result=minecraft_constructor_target_missing action=compat_inactive");
        }
    }

    private static void transformConnectorLoader(ClassNode node) {
        MethodNode setup = null;
        for (MethodNode method : node.methods) {
            if ("setup".equals(method.name) && "()V".equals(method.desc)) {
                setup = method;
                break;
            }
        }
        if (setup == null) {
            StartupDiagnostics.event("CONNECTOR_RESOURCES", "result=connector_setup_target_missing action=compat_inactive");
            return;
        }

        LabelNode continueSetup = new LabelNode();
        InsnList guard = new InsnList();
        guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC, COMPAT, "shouldSkipConnectorSetup", "()Z", false));
        guard.add(new JumpInsnNode(Opcodes.IFEQ, continueSetup));
        guard.add(new InsnNode(Opcodes.RETURN));
        guard.add(continueSetup);
        setup.instructions.insert(guard);

        for (var instruction : setup.instructions.toArray()) {
            if (instruction.getOpcode() == Opcodes.RETURN) {
                InsnList markComplete = new InsnList();
                markComplete.add(new MethodInsnNode(Opcodes.INVOKESTATIC, COMPAT, "markConnectorSetupCompleted", "()V", false));
                setup.instructions.insertBefore(instruction, markComplete);
            }
        }
        ConnectorResourceCompat.markSetupGuardInstalled();
    }
}
