package dev.wachipayox.bootoptim.tailagent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Bytecode-only diagnostic patches for the exact Mixin/ModLauncher versions used by the pack. */
final class TailClassFileTransformer implements ClassFileTransformer {
    private static final String CLASS_TRANSFORMER = "cpw/mods/modlauncher/ClassTransformer";
    private static final String MIXIN_HANDLER = "org/spongepowered/asm/service/modlauncher/MixinTransformationHandler";
    private static final String HOOK = "dev/wachipayox/bootoptim/tailagent/TailRuntime";
    private static final String CLASS_TRANSFORM_DESC = "([BLjava/lang/String;Ljava/lang/String;)[B";
    private static final String MIXIN_PROCESS_DESC =
            "(Lcpw/mods/modlauncher/serviceapi/ILaunchPluginService$Phase;"
                    + "Lorg/objectweb/asm/tree/ClassNode;Lorg/objectweb/asm/Type;Ljava/lang/String;)Z";

    private final Instrumentation instrumentation;

    TailClassFileTransformer(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
    }

    @Override
    public byte[] transform(
            Module module,
            ClassLoader loader,
            String className,
            Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain,
            byte[] classfileBuffer) {
        if (!CLASS_TRANSFORMER.equals(className) && !MIXIN_HANDLER.equals(className)) {
            return null;
        }

        String source = codeSource(protectionDomain);
        if (CLASS_TRANSFORMER.equals(className) && !supportedModLauncher(source)) {
            emit("instrument=skipped target=ClassTransformer reason=unsupported_source source=" + source);
            return null;
        }
        if (MIXIN_HANDLER.equals(className) && !supportedMixin(source)) {
            emit("instrument=skipped target=MixinTransformationHandler reason=unsupported_source source=" + source);
            return null;
        }

        try {
            addAgentReadEdge(module);
            byte[] patched = CLASS_TRANSFORMER.equals(className)
                    ? patchClassTransformer(classfileBuffer)
                    : patchMixinHandler(classfileBuffer);
            emit("instrument=success target=" + (CLASS_TRANSFORMER.equals(className)
                    ? "ClassTransformer"
                    : "MixinTransformationHandler") + " source=" + source);
            return patched;
        } catch (Throwable t) {
            emit("instrument=failed target=" + className + " type=" + t.getClass().getName()
                    + " message=" + String.valueOf(t.getMessage()).replace(' ', '_'));
            return null;
        }
    }

    private void addAgentReadEdge(Module target) {
        if (target == null || !target.isNamed() || target.canRead(TailRuntime.class.getModule())) {
            return;
        }
        instrumentation.redefineModule(
                target,
                Set.of(TailRuntime.class.getModule()),
                Map.of(),
                Map.of(),
                Set.of(),
                Map.<Class<?>, List<Class<?>>>of());
    }

    private static byte[] patchMixinHandler(byte[] input) {
        ClassNode node = read(input);
        MethodNode target = null;
        for (MethodNode method : node.methods) {
            if ("processClass".equals(method.name) && MIXIN_PROCESS_DESC.equals(method.desc)) {
                target = method;
                break;
            }
        }
        if (target == null) {
            throw new IllegalStateException("Exact Mixin processClass method not found");
        }

        int patchedReturns = 0;
        for (AbstractInsnNode insn = target.instructions.getFirst(); insn != null; ) {
            AbstractInsnNode next = insn.getNext();
            if (insn.getOpcode() == Opcodes.IRETURN) {
                InsnList before = new InsnList();
                // Existing boolean return value stays on the stack. Resolve the class name using the
                // target module's own ASM Type, then let the hook observe and return the same boolean.
                before.add(new VarInsnNode(Opcodes.ALOAD, 3));
                before.add(new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL,
                        "org/objectweb/asm/Type",
                        "getClassName",
                        "()Ljava/lang/String;",
                        false));
                before.add(new VarInsnNode(Opcodes.ALOAD, 4));
                before.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        HOOK,
                        "recordMixinResult",
                        "(ZLjava/lang/String;Ljava/lang/String;)Z",
                        false));
                target.instructions.insertBefore(insn, before);
                patchedReturns++;
            }
            insn = next;
        }
        if (patchedReturns == 0) {
            throw new IllegalStateException("No IRETURN instructions patched in Mixin processClass");
        }
        return write(node);
    }

    private static byte[] patchClassTransformer(byte[] input) {
        ClassNode node = read(input);
        MethodNode target = null;
        for (MethodNode method : node.methods) {
            if ("transform".equals(method.name) && CLASS_TRANSFORM_DESC.equals(method.desc)) {
                target = method;
                break;
            }
        }
        if (target == null) {
            throw new IllegalStateException("Exact ClassTransformer.transform method not found");
        }

        MethodInsnNode writerFactory = null;
        for (AbstractInsnNode insn : target.instructions) {
            if (insn instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && "cpw/mods/modlauncher/TransformerClassWriter".equals(call.owner)
                    && "createClassWriter".equals(call.name)) {
                writerFactory = call;
                break;
            }
        }
        if (writerFactory == null) {
            throw new IllegalStateException("TransformerClassWriter factory call not found");
        }

        AbstractInsnNode clazzLoad = previousReal(writerFactory);
        AbstractInsnNode thisLoad = previousReal(clazzLoad);
        AbstractInsnNode flagsLoad = previousReal(thisLoad);
        if (!(flagsLoad instanceof VarInsnNode flagsVar)
                || flagsVar.getOpcode() != Opcodes.ILOAD
                || !(thisLoad instanceof VarInsnNode)
                || thisLoad.getOpcode() != Opcodes.ALOAD
                || !(clazzLoad instanceof VarInsnNode)
                || clazzLoad.getOpcode() != Opcodes.ALOAD) {
            throw new IllegalStateException("Unable to infer mergedFlags local from writer factory call");
        }
        int flagsLocal = flagsVar.var;

        int acceptCalls = 0;
        int toByteArrayCalls = 0;
        for (AbstractInsnNode insn = target.instructions.getFirst(); insn != null; ) {
            AbstractInsnNode next = insn.getNext();
            if (insn instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && "org/objectweb/asm/tree/ClassNode".equals(call.owner)
                    && "accept".equals(call.name)
                    && "(Lorg/objectweb/asm/ClassVisitor;)V".equals(call.desc)) {
                AbstractInsnNode writerLoad = previousReal(call);
                AbstractInsnNode classNodeLoad = previousReal(writerLoad);
                if (writerLoad == null || classNodeLoad == null) {
                    throw new IllegalStateException("Unable to locate ClassNode.accept operand loads");
                }
                target.instructions.insertBefore(classNodeLoad, beginHook("beginAccept", flagsLocal));
                target.instructions.insert(call, new MethodInsnNode(
                        Opcodes.INVOKESTATIC, HOOK, "endAccept", "()V", false));
                acceptCalls++;
            } else if (insn instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && "org/objectweb/asm/ClassWriter".equals(call.owner)
                    && "toByteArray".equals(call.name)
                    && "()[B".equals(call.desc)) {
                AbstractInsnNode writerLoad = previousReal(call);
                if (writerLoad == null) {
                    throw new IllegalStateException("Unable to locate ClassWriter.toByteArray operand load");
                }
                target.instructions.insertBefore(writerLoad, beginHook("beginToByteArray", flagsLocal));
                InsnList after = new InsnList();
                // Preserve the exact byte[] result on the stack while passing only its cheap length to the hook.
                after.add(new InsnNode(Opcodes.DUP));
                after.add(new InsnNode(Opcodes.ARRAYLENGTH));
                after.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC, HOOK, "endToByteArray", "(I)V", false));
                target.instructions.insert(call, after);
                toByteArrayCalls++;
            }
            insn = next;
        }

        if (acceptCalls != 1) {
            throw new IllegalStateException("Expected exactly one ClassNode.accept call, found " + acceptCalls);
        }
        if (toByteArrayCalls < 1) {
            throw new IllegalStateException("No ClassWriter.toByteArray calls found");
        }
        return write(node);
    }

    private static InsnList beginHook(String name, int flagsLocal) {
        InsnList hook = new InsnList();
        // transform(byte[] inputClass, String className, String reason): locals 1,2,3 are stable arguments.
        hook.add(new VarInsnNode(Opcodes.ALOAD, 2));
        hook.add(new VarInsnNode(Opcodes.ILOAD, flagsLocal));
        hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
        hook.add(new InsnNode(Opcodes.ARRAYLENGTH));
        hook.add(new VarInsnNode(Opcodes.ALOAD, 3));
        hook.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                HOOK,
                name,
                "(Ljava/lang/String;IILjava/lang/String;)V",
                false));
        return hook;
    }

    private static ClassNode read(byte[] input) {
        ClassReader reader = new ClassReader(input);
        ClassNode node = new ClassNode(Opcodes.ASM9);
        reader.accept(node, 0);
        return node;
    }

    private static byte[] write(ClassNode node) {
        // We add no branches and preserve the existing frame nodes. Recompute max stack only so the
        // injected straight-line calls do not trigger ClassWriter hierarchy lookups while patching ModLauncher.
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static AbstractInsnNode previousReal(AbstractInsnNode node) {
        AbstractInsnNode current = node == null ? null : node.getPrevious();
        while (current != null && current.getOpcode() < 0) {
            current = current.getPrevious();
        }
        return current;
    }

    private static boolean supportedModLauncher(String source) {
        return source != null && (source.contains("modlauncher-11.0.5")
                || source.contains("/modlauncher/11.0.5/"));
    }

    private static boolean supportedMixin(String source) {
        return source != null && source.contains("sponge-mixin") && source.contains("0.8.7");
    }

    private static String codeSource(ProtectionDomain protectionDomain) {
        try {
            return protectionDomain == null
                    || protectionDomain.getCodeSource() == null
                    || protectionDomain.getCodeSource().getLocation() == null
                    ? null
                    : protectionDomain.getCodeSource().getLocation().toExternalForm();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void emit(String payload) {
        System.out.println("BOOTOPTIM_MODLAUNCHER_TAIL " + payload);
    }
}
