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
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * Diagnostic-only profiler for the exact Minecraft 1.21.1 SharedConstants/version topology.
 *
 * <p>Agent 103 extends the validated Agent 101 probe with coarse boundaries inside
 * SharedConstants.<clinit>. The matcher is deliberately fail-closed on the exact active-use
 * sequence that can trigger transitive initialization. Hooks only timestamp existing boundaries;
 * they do not read or write the observed fields, force another class to initialize, replace calls,
 * alter failures, or move work between threads.</p>
 */
public final class MinecraftVersionLifecycleTransformer implements ITransformer<ClassNode> {
    private static final String SHARED = "net/minecraft/SharedConstants";
    private static final String DETECTED = "net/minecraft/DetectedVersion";
    private static final String GSON_HELPER = "net/minecraft/util/GsonHelper";
    private static final String RESOURCE_LEAK_DETECTOR = "io/netty/util/ResourceLeakDetector";
    private static final String RESOURCE_LEAK_LEVEL = "io/netty/util/ResourceLeakDetector$Level";
    private static final String COMMAND_SYNTAX = "com/mojang/brigadier/exceptions/CommandSyntaxException";
    private static final String BRIGADIER_EXCEPTIONS = "net/minecraft/commands/BrigadierExceptions";
    private static final String WORLD_VERSION = "Lnet/minecraft/WorldVersion;";
    private static final String HOOKS = "dev/wachipayox/bootoptim/bootstrap/MinecraftMainLifecycleHooks";

    @Override
    public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
        if (input == null) return input;
        return switch (input.name) {
            case SHARED -> transformShared(input);
            case DETECTED -> transformDetected(input);
            case GSON_HELPER -> transformGsonHelper(input);
            default -> input;
        };
    }

    private static ClassNode transformShared(ClassNode input) {
        MethodNode clinit = uniqueMethod(input, "<clinit>", "()V");
        MethodNode detect = uniqueMethod(input, "tryDetectVersion", "()V");
        if (clinit == null || detect == null) return input;

        FieldInsnNode nettyLevelGet = uniqueField(clinit, Opcodes.GETSTATIC, RESOURCE_LEAK_LEVEL, "DISABLED", "Lio/netty/util/ResourceLeakDetector$Level;");
        FieldInsnNode nettyLevelPut = uniqueField(clinit, Opcodes.PUTSTATIC, SHARED, "NETTY_LEAK_DETECTION", "Lio/netty/util/ResourceLeakDetector$Level;");
        MethodInsnNode durationOfMillis = uniqueCall(clinit, "java/time/Duration", "ofMillis", "(J)Ljava/time/Duration;");
        MethodInsnNode durationToNanos = uniqueCall(clinit, "java/time/Duration", "toNanos", "()J");
        FieldInsnNode maxTickPut = uniqueField(clinit, Opcodes.PUTSTATIC, SHARED, "MAXIMUM_TICK_TIME_NANOS", "J");
        FieldInsnNode illegalCharsPut = uniqueField(clinit, Opcodes.PUTSTATIC, SHARED, "ILLEGAL_FILE_CHARACTERS", "[C");
        MethodInsnNode setLeakLevel = uniqueCall(clinit, RESOURCE_LEAK_DETECTOR, "setLevel", "(Lio/netty/util/ResourceLeakDetector$Level;)V");
        FieldInsnNode stackTracePut = uniqueField(clinit, Opcodes.PUTSTATIC, COMMAND_SYNTAX, "ENABLE_COMMAND_STACK_TRACES", "Z");
        TypeInsnNode brigadierNew = uniqueType(clinit, Opcodes.NEW, BRIGADIER_EXCEPTIONS);
        MethodInsnNode brigadierCtor = uniqueCall(clinit, BRIGADIER_EXCEPTIONS, "<init>", "()V");
        FieldInsnNode builtInPut = uniqueField(clinit, Opcodes.PUTSTATIC, COMMAND_SYNTAX, "BUILT_IN_EXCEPTIONS", "Lcom/mojang/brigadier/exceptions/BuiltInExceptionProvider;");
        List<AbstractInsnNode> clinitReturns = returns(clinit, Opcodes.RETURN);
        if (nettyLevelGet == null || nettyLevelPut == null || durationOfMillis == null || durationToNanos == null
                || maxTickPut == null || illegalCharsPut == null || setLeakLevel == null || stackTracePut == null
                || brigadierNew == null || brigadierCtor == null || builtInPut == null || clinitReturns.size() != 1
                || !ordered(clinit, nettyLevelGet, nettyLevelPut, durationOfMillis, durationToNanos, maxTickPut,
                        illegalCharsPut, setLeakLevel, stackTracePut, brigadierNew, brigadierCtor, builtInPut,
                        clinitReturns.get(0))) return input;

        FieldInsnNode currentGet = uniqueField(detect, Opcodes.GETSTATIC, SHARED, "CURRENT_VERSION", WORLD_VERSION);
        MethodInsnNode detectedCall = uniqueCall(detect, DETECTED, "tryDetectVersion", "()Lnet/minecraft/WorldVersion;");
        FieldInsnNode currentPut = uniqueField(detect, Opcodes.PUTSTATIC, SHARED, "CURRENT_VERSION", WORLD_VERSION);
        List<AbstractInsnNode> detectReturns = returns(detect, Opcodes.RETURN);
        if (currentGet == null || detectedCall == null || currentPut == null || detectReturns.size() != 1
                || !ordered(detect, currentGet, detectedCall, currentPut, detectReturns.get(0))) return input;

        AbstractInsnNode clinitFirst = firstExecutable(clinit);
        AbstractInsnNode detectFirst = firstExecutable(detect);
        if (clinitFirst == null || detectFirst == null) return input;

        clinit.instructions.insertBefore(clinitFirst, hook("sharedConstantsClinitEnter"));
        clinit.instructions.insertBefore(nettyLevelGet, hook("beforeNettyLeakLevelResolve"));
        clinit.instructions.insert(nettyLevelPut, hook("afterNettyLeakLevelPublish"));
        clinit.instructions.insertBefore(durationOfMillis, hook("beforeDurationConstant"));
        clinit.instructions.insert(maxTickPut, hook("afterDurationConstant"));
        clinit.instructions.insertBefore(setLeakLevel, hook("beforeResourceLeakDetectorSetLevel"));
        clinit.instructions.insert(setLeakLevel, hook("afterResourceLeakDetectorSetLevel"));
        clinit.instructions.insertBefore(stackTracePut, hook("beforeCommandSyntaxStackTracePublish"));
        clinit.instructions.insert(stackTracePut, hook("afterCommandSyntaxStackTracePublish"));
        clinit.instructions.insertBefore(brigadierNew, hook("beforeBrigadierExceptionsConstruction"));
        clinit.instructions.insert(brigadierCtor, hook("afterBrigadierExceptionsConstruction"));
        clinit.instructions.insert(builtInPut, hook("afterBrigadierProviderPublish"));
        clinit.instructions.insertBefore(clinitReturns.get(0), hook("sharedConstantsClinitExit"));

        detect.instructions.insertBefore(detectFirst, hook("sharedConstantsTryDetectEntry"));
        detect.instructions.insertBefore(detectedCall, hook("beforeDetectedVersionCall"));
        detect.instructions.insertBefore(currentPut, hook("beforeVersionPublication"));
        detect.instructions.insert(currentPut, hook("afterVersionPublication"));
        detect.instructions.insertBefore(detectReturns.get(0), hook("sharedConstantsTryDetectExit"));
        return input;
    }

    private static ClassNode transformDetected(ClassNode input) {
        MethodNode clinit = uniqueMethod(input, "<clinit>", "()V");
        MethodNode detect = uniqueMethod(input, "tryDetectVersion", "()Lnet/minecraft/WorldVersion;");
        if (clinit == null || detect == null) return input;

        MethodInsnNode logger = uniqueCall(clinit, "com/mojang/logging/LogUtils", "getLogger", "()Lorg/slf4j/Logger;");
        MethodInsnNode builtinCtor = uniqueCall(clinit, DETECTED, "<init>", "()V");
        FieldInsnNode loggerPut = uniqueField(clinit, Opcodes.PUTSTATIC, DETECTED, "LOGGER", "Lorg/slf4j/Logger;");
        FieldInsnNode builtinPut = uniqueField(clinit, Opcodes.PUTSTATIC, DETECTED, "BUILT_IN", WORLD_VERSION);
        List<AbstractInsnNode> clinitReturns = returns(clinit, Opcodes.RETURN);
        if (logger == null || builtinCtor == null || loggerPut == null || builtinPut == null || clinitReturns.size() != 1
                || !ordered(clinit, logger, loggerPut, builtinCtor, builtinPut, clinitReturns.get(0))) return input;

        MethodInsnNode resource = uniqueCall(detect, "java/lang/Class", "getResourceAsStream", "(Ljava/lang/String;)Ljava/io/InputStream;");
        MethodInsnNode readerCtor = uniqueCall(detect, "java/io/InputStreamReader", "<init>", "(Ljava/io/InputStream;)V");
        MethodInsnNode jsonCtor = uniqueCall(detect, DETECTED, "<init>", "(Lcom/google/gson/JsonObject;)V");
        TypeInsnNode versionNew = uniqueType(detect, Opcodes.NEW, DETECTED);
        List<AbstractInsnNode> returns = returns(detect, Opcodes.ARETURN);
        if (resource == null || readerCtor == null || jsonCtor == null || versionNew == null || returns.size() != 2
                || !ordered(detect, resource, readerCtor, versionNew, jsonCtor)) return input;
        AbstractInsnNode previous = previousExecutable(resource);
        if (!(previous instanceof LdcInsnNode ldc) || !"/version.json".equals(ldc.cst)) return input;

        AbstractInsnNode clinitFirst = firstExecutable(clinit);
        AbstractInsnNode detectFirst = firstExecutable(detect);
        if (clinitFirst == null || detectFirst == null) return input;

        clinit.instructions.insertBefore(clinitFirst, hook("detectedVersionClinitEnter"));
        clinit.instructions.insertBefore(clinitReturns.get(0), hook("detectedVersionClinitExit"));
        detect.instructions.insertBefore(detectFirst, hook("detectedVersionTryDetectEntry"));
        detect.instructions.insertBefore(resource, hook("beforeVersionResourceOpen"));
        detect.instructions.insert(resource, hook("afterVersionResourceOpen"));
        detect.instructions.insertBefore(versionNew, hook("beforeJsonVersionExpression"));
        detect.instructions.insert(jsonCtor, hook("afterJsonVersionConstruction"));
        for (AbstractInsnNode ret : returns) detect.instructions.insertBefore(ret, hook("detectedVersionTryDetectExit"));
        return input;
    }

    private static ClassNode transformGsonHelper(ClassNode input) {
        MethodNode clinit = uniqueMethod(input, "<clinit>", "()V");
        MethodNode parse = uniqueMethod(input, "parse", "(Ljava/io/Reader;)Lcom/google/gson/JsonObject;");
        if (clinit == null || parse == null) return input;

        MethodInsnNode builderCtor = uniqueCall(clinit, "com/google/gson/GsonBuilder", "<init>", "()V");
        MethodInsnNode create = uniqueCall(clinit, "com/google/gson/GsonBuilder", "create", "()Lcom/google/gson/Gson;");
        FieldInsnNode gsonPut = uniqueField(clinit, Opcodes.PUTSTATIC, GSON_HELPER, "GSON", "Lcom/google/gson/Gson;");
        List<AbstractInsnNode> clinitReturns = returns(clinit, Opcodes.RETURN);
        MethodInsnNode delegatedParse = uniqueCall(parse, GSON_HELPER, "parse", "(Ljava/io/Reader;Z)Lcom/google/gson/JsonObject;");
        List<AbstractInsnNode> parseReturns = returns(parse, Opcodes.ARETURN);
        if (builderCtor == null || create == null || gsonPut == null || clinitReturns.size() != 1
                || delegatedParse == null || parseReturns.size() != 1
                || !ordered(clinit, builderCtor, create, gsonPut, clinitReturns.get(0))) return input;

        AbstractInsnNode clinitFirst = firstExecutable(clinit);
        AbstractInsnNode parseFirst = firstExecutable(parse);
        if (clinitFirst == null || parseFirst == null) return input;
        clinit.instructions.insertBefore(clinitFirst, hook("gsonHelperClinitEnter"));
        clinit.instructions.insertBefore(clinitReturns.get(0), hook("gsonHelperClinitExit"));
        parse.instructions.insertBefore(parseFirst, hook("gsonVersionParseEnter"));
        parse.instructions.insertBefore(parseReturns.get(0), hook("gsonVersionParseExit"));
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
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call && owner.equals(call.owner) && name.equals(call.name)
                    && (desc == null || desc.equals(call.desc))) {
                if (found != null) return null;
                found = call;
            }
        }
        return found;
    }

    private static FieldInsnNode uniqueField(MethodNode method, int opcode, String owner, String name, String desc) {
        FieldInsnNode found = null;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof FieldInsnNode field && field.getOpcode() == opcode && owner.equals(field.owner)
                    && name.equals(field.name) && desc.equals(field.desc)) {
                if (found != null) return null;
                found = field;
            }
        }
        return found;
    }

    private static TypeInsnNode uniqueType(MethodNode method, int opcode, String desc) {
        TypeInsnNode found = null;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof TypeInsnNode type && type.getOpcode() == opcode && desc.equals(type.desc)) {
                if (found != null) return null;
                found = type;
            }
        }
        return found;
    }

    private static List<AbstractInsnNode> returns(MethodNode method, int opcode) {
        List<AbstractInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions.toArray()) if (insn.getOpcode() == opcode) result.add(insn);
        return result;
    }

    private static boolean ordered(MethodNode method, AbstractInsnNode... nodes) {
        List<AbstractInsnNode> all = List.of(method.instructions.toArray());
        int last = -1;
        for (AbstractInsnNode node : nodes) {
            int index = all.indexOf(node);
            if (index <= last) return false;
            last = index;
        }
        return true;
    }

    private static AbstractInsnNode firstExecutable(MethodNode method) {
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) if (insn.getOpcode() >= 0) return insn;
        return null;
    }

    private static AbstractInsnNode previousExecutable(AbstractInsnNode insn) {
        for (AbstractInsnNode prev = insn.getPrevious(); prev != null; prev = prev.getPrevious()) if (prev.getOpcode() >= 0) return prev;
        return null;
    }

    private static MethodInsnNode hook(String name) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, name, "()V", false);
    }

    @Override
    public TransformerVoteResult castVote(ITransformerVotingContext context) { return TransformerVoteResult.YES; }

    @Override
    public Set<Target<ClassNode>> targets() {
        return Set.of(
                Target.targetClass("net.minecraft.SharedConstants"),
                Target.targetClass("net.minecraft.DetectedVersion"),
                Target.targetClass("net.minecraft.util.GsonHelper"));
    }

    @Override
    public TargetType<ClassNode> getTargetType() { return TargetType.CLASS; }

    @Override
    public String[] labels() { return new String[] { "boot_optim_agent103_sharedconstants_clinit_profile" }; }
}
