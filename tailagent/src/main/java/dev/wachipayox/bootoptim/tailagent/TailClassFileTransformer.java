package dev.wachipayox.bootoptim.tailagent;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandles;
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
    private static final String MIXIN_ANCHOR = "org.spongepowered.asm.service.modlauncher.MixinServiceModLauncher";
    private static final String HELPER_PACKAGE = "org.spongepowered.asm.service.modlauncher";
    private static final String HELPER_BINARY = HELPER_PACKAGE + ".BootOptimTailRuntime";
    private static final String HELPER_INTERNAL = HELPER_BINARY.replace('.', '/');
    private static final String CLASS_TRANSFORM_DESC = "([BLjava/lang/String;Ljava/lang/String;)[B";
    private static final String MIXIN_PROCESS_DESC =
            "(Lcpw/mods/modlauncher/serviceapi/ILaunchPluginService$Phase;"
                    + "Lorg/objectweb/asm/tree/ClassNode;Lorg/objectweb/asm/Type;Ljava/lang/String;)Z";

    private final Instrumentation instrumentation;
    private volatile Class<?> helperClass;

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
            if (MIXIN_HANDLER.equals(className)) {
                ensureHelperDefined(module, loader);
            } else {
                grantModLauncherHelperAccess(module);
            }

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

    /**
     * Define the runtime helper inside Mixin's own module and loader. Calling a helper in the
     * javaagent loader from ModLauncher's ModuleClassLoader is not reliable under SecureJarHandler.
     */
    private void ensureHelperDefined(Module mixinModule, ClassLoader loader) throws Throwable {
        if (helperClass != null) {
            return;
        }
        synchronized (this) {
            if (helperClass != null) {
                return;
            }

            Class<?> anchor = Class.forName(MIXIN_ANCHOR, false, loader);
            if (anchor.getModule() != mixinModule) {
                throw new IllegalStateException("Mixin anchor resolved from unexpected module");
            }

            Module agentModule = TailClassFileTransformer.class.getModule();
            if (mixinModule.isNamed()) {
                instrumentation.redefineModule(
                        mixinModule,
                        Set.of(),
                        Map.of(),
                        Map.of(HELPER_PACKAGE, Set.of(agentModule)),
                        Set.of(),
                        Map.<Class<?>, List<Class<?>>>of());
            }

            byte[] helperBytes = helperBytes();
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(anchor, MethodHandles.lookup());
            Class<?> defined;
            try {
                defined = lookup.defineClass(helperBytes);
            } catch (LinkageError alreadyDefined) {
                defined = Class.forName(HELPER_BINARY, false, loader);
            }
            if (!HELPER_BINARY.equals(defined.getName()) || defined.getModule() != mixinModule) {
                throw new IllegalStateException("Tail helper was not defined in Mixin module");
            }
            helperClass = defined;
            emit("helper=defined module=" + moduleName(mixinModule) + " loader=" + loaderName(defined.getClassLoader()));
        }
    }

    /** Make only the helper package readable/exported to ModLauncher's module. */
    private void grantModLauncherHelperAccess(Module modLauncherModule) {
        Class<?> helper = helperClass;
        if (helper == null) {
            throw new IllegalStateException("Mixin tail helper was not defined before ClassTransformer");
        }
        Module mixinModule = helper.getModule();
        if (!modLauncherModule.isNamed() || !mixinModule.isNamed()) {
            return;
        }

        instrumentation.redefineModule(
                modLauncherModule,
                Set.of(mixinModule),
                Map.of(),
                Map.of(),
                Set.of(),
                Map.<Class<?>, List<Class<?>>>of());
        instrumentation.redefineModule(
                mixinModule,
                Set.of(),
                Map.of(HELPER_PACKAGE, Set.of(modLauncherModule)),
                Map.of(),
                Set.of(),
                Map.<Class<?>, List<Class<?>>>of());
    }

    private static byte[] helperBytes() throws IOException {
        String resource = HELPER_INTERNAL + ".class";
        ClassLoader agentLoader = TailClassFileTransformer.class.getClassLoader();
        try (InputStream stream = agentLoader.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IOException("Missing helper resource " + resource);
            }
            return stream.readAllBytes();
        }
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

        // Capture className/reason at method entry. In the stock 0.8.7 stack-map table these
        // arguments become TOP at some later return frames once their original lifetime is over.
        AbstractInsnNode first = target.instructions.getFirst();
        InsnList entry = new InsnList();
        entry.add(new VarInsnNode(Opcodes.ALOAD, 3));
        entry.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "org/objectweb/asm/Type",
                "getClassName",
                "()Ljava/lang/String;",
                false));
        entry.add(new VarInsnNode(Opcodes.ALOAD, 4));
        entry.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                HELPER_INTERNAL,
                "beginMixinProcess",
                "(Ljava/lang/String;Ljava/lang/String;)V",
                false));
        target.instructions.insertBefore(first, entry);

        int patchedReturns = 0;
        for (AbstractInsnNode insn = target.instructions.getFirst(); insn != null; ) {
            AbstractInsnNode next = insn.getNext();
            if (insn.getOpcode() == Opcodes.IRETURN) {
                target.instructions.insertBefore(insn, new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        HELPER_INTERNAL,
                        "recordMixinResult",
                        "(Z)Z",
                        false));
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

        // Capture stable method arguments at entry. Later writer-tail stack-map frames are free to
        // mark dead arguments as TOP, so timing hooks below intentionally load no original arguments.
        AbstractInsnNode first = target.instructions.getFirst();
        InsnList entry = new InsnList();
        entry.add(new VarInsnNode(Opcodes.ALOAD, 2));
        entry.add(new VarInsnNode(Opcodes.ALOAD, 1));
        entry.add(new InsnNode(Opcodes.ARRAYLENGTH));
        entry.add(new VarInsnNode(Opcodes.ALOAD, 3));
        entry.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                HELPER_INTERNAL,
                "beginClassTransform",
                "(Ljava/lang/String;ILjava/lang/String;)V",
                false));
        target.instructions.insertBefore(first, entry);

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

        InsnList captureFlags = new InsnList();
        captureFlags.add(new VarInsnNode(Opcodes.ILOAD, flagsLocal));
        captureFlags.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, HELPER_INTERNAL, "setClassTransformFlags", "(I)V", false));
        target.instructions.insertBefore(flagsLoad, captureFlags);

        int acceptCalls = 0;
        int toByteArrayCalls = 0;
        int returns = 0;
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
                target.instructions.insertBefore(classNodeLoad, new MethodInsnNode(
                        Opcodes.INVOKESTATIC, HELPER_INTERNAL, "beginAccept", "()V", false));
                target.instructions.insert(call, new MethodInsnNode(
                        Opcodes.INVOKESTATIC, HELPER_INTERNAL, "endAccept", "()V", false));
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
                target.instructions.insertBefore(writerLoad, new MethodInsnNode(
                        Opcodes.INVOKESTATIC, HELPER_INTERNAL, "beginToByteArray", "()V", false));
                InsnList after = new InsnList();
                after.add(new InsnNode(Opcodes.DUP));
                after.add(new InsnNode(Opcodes.ARRAYLENGTH));
                after.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC, HELPER_INTERNAL, "endToByteArray", "(I)V", false));
                target.instructions.insert(call, after);
                toByteArrayCalls++;
            } else if (insn.getOpcode() == Opcodes.ARETURN) {
                target.instructions.insertBefore(insn, new MethodInsnNode(
                        Opcodes.INVOKESTATIC, HELPER_INTERNAL, "endClassTransform", "()V", false));
                returns++;
            }
            insn = next;
        }

        if (acceptCalls != 1) {
            throw new IllegalStateException("Expected exactly one ClassNode.accept call, found " + acceptCalls);
        }
        if (toByteArrayCalls < 1) {
            throw new IllegalStateException("No ClassWriter.toByteArray calls found");
        }
        if (returns == 0) {
            throw new IllegalStateException("No ARETURN instructions found in ClassTransformer.transform");
        }
        return write(node);
    }

    private static ClassNode read(byte[] input) {
        ClassReader reader = new ClassReader(input);
        ClassNode node = new ClassNode(Opcodes.ASM9);
        reader.accept(node, 0);
        return node;
    }

    private static byte[] write(ClassNode node) {
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

    private static String moduleName(Module module) {
        return module == null ? "null" : String.valueOf(module.getName());
    }

    private static String loaderName(ClassLoader loader) {
        return loader == null ? "bootstrap" : loader.getClass().getName();
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
