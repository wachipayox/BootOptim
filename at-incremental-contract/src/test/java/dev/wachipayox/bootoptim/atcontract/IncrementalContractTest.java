package dev.wachipayox.bootoptim.atcontract;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.neoforged.accesstransformer.AccessTransformer;
import net.neoforged.accesstransformer.Target;
import net.neoforged.accesstransformer.TargetType;
import net.neoforged.accesstransformer.api.AccessTransformerEngine;
import net.neoforged.accesstransformer.generated.AtLexer;
import net.neoforged.accesstransformer.generated.AtParser;
import net.neoforged.accesstransformer.parser.AccessTransformVisitor;
import net.neoforged.accesstransformer.parser.AccessTransformerList;
import net.neoforged.accesstransformer.parser.AtParserErrorListener;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.LoggerFactory;

/**
 * Differential contract for the AccessTransformers 10.0.1 per-file transaction.
 *
 * <p>This is deliberately an isolated test model, not production code. The reference keeps only an
 * overlay for targets touched by the current file. On the exceptional conflict path it recreates the
 * stock HashMap copy solely to preserve the current implementation's diagnostic iteration order.</p>
 */
final class IncrementalContractTest {
    private static final Field MODIFIER_FIELD = field("targetAccess");
    private static final Field FINALITY_FIELD = field("targetFinalState");

    private static final List<Fixture> SUCCESS = List.of(
            new Fixture("01-base.cfg", """
                    protected example.Target
                    protected example.Target value
                    protected+f example.Target other
                    protected example.Target hidden()V
                    """),
            new Fixture("02-merge.cfg", """
                    public example.Target
                    public-f example.Target value
                    public+f example.Target other
                    public example.Target hidden()V
                    """),
            new Fixture("03-repeat.cfg", """
                    public example.Target value
                    public example.Target hidden()V
                    """));

    @Test
    void publishedArtifactIsPinnedTo1001() {
        String implementationVersion = AccessTransformerEngine.class.getPackage().getImplementationVersion();
        assertNotNull(implementationVersion, "published Maven artifact must retain implementation version metadata");
        assertTrue(implementationVersion.startsWith("10.0.1+"), implementationVersion);
    }

    @Test
    void successfulSequenceMatchesAfterEveryFileAndTransformsIdentically() {
        AccessTransformerList stock = new AccessTransformerList();
        IncrementalReference reference = new IncrementalReference();

        for (Fixture fixture : SUCCESS) {
            stock.loadAT(fixture.stream());
            reference.load(fixture.stream());
            assertEquals(snapshot(stock), reference.snapshot(), fixture.name());
            assertEquals(targetDescriptors(stock.getTargets()), reference.targetDescriptors(), fixture.name());
        }

        AccessTransformerEngine stockEngine = AccessTransformerEngine.newEngine();
        for (Fixture fixture : SUCCESS) stockEngine.loadAT(fixture.stream());

        ClassNode stockNode = sampleClass();
        ClassNode referenceNode = sampleClass();
        Type target = Type.getObjectType("example/Target");
        assertEquals(stockEngine.transform(stockNode, target), reference.transform(referenceNode, target));
        assertArrayEquals(classBytes(stockNode), classBytes(referenceNode));
    }

    @Test
    void repeatedRulesPreserveExactSyntheticAndEncounterOriginOrder() {
        AccessTransformerList stock = new AccessTransformerList();
        for (Fixture fixture : SUCCESS) stock.loadAT(fixture.stream());

        StateEntry value = snapshot(stock).stream()
                .filter(entry -> entry.target().contains("|FIELD|value"))
                .findFirst()
                .orElseThrow();

        assertEquals("PUBLIC", value.modifier());
        assertEquals("REMOVEFINAL", value.finality());
        assertEquals(List.of(
                "03-repeat.cfg:merge:0",
                "02-merge.cfg:merge:0",
                "01-base.cfg:2",
                "02-merge.cfg:2",
                "03-repeat.cfg:1"), value.origins());
    }

    @Test
    void conflictingFileFailsAtSameBoundaryLogsSameOrderedConflictsAndPublishesNothing() {
        Fixture base = new Fixture("10-final-base.cfg", """
                public+f example.Target value
                public+f example.Target other
                """);
        Fixture conflict = new Fixture("11-final-conflict.cfg", """
                public-f example.Target value
                public-f example.Target other
                """);

        AccessTransformerList stock = new AccessTransformerList();
        IncrementalReference reference = new IncrementalReference();
        stock.loadAT(base.stream());
        reference.load(base.stream());
        List<StateEntry> stockBefore = snapshot(stock);
        List<StateEntry> referenceBefore = reference.snapshot();
        Set<String> targetsBefore = targetDescriptors(stock.getTargets());

        CapturedStockConflict stockConflict = captureStockConflict(stock, conflict);
        IllegalArgumentException referenceFailure = assertThrows(IllegalArgumentException.class,
                () -> reference.load(conflict.stream()));

        assertEquals(IllegalArgumentException.class.getName(), stockConflict.throwableClass());
        assertEquals("Invalid AT final conflicts", stockConflict.message());
        assertEquals("Invalid AT final conflicts", referenceFailure.getMessage());
        assertEquals(reference.lastConflictMessages(), stockConflict.errorMessages());
        assertEquals(stockBefore, snapshot(stock), "stock transaction must roll back");
        assertEquals(referenceBefore, reference.snapshot(), "reference transaction must publish nothing");
        assertEquals(targetsBefore, targetDescriptors(stock.getTargets()), "target cache must remain at previous publication");
        assertEquals(targetsBefore, reference.targetDescriptors(), "reference target publication must remain unchanged");
    }

    @Test
    void malformedParserInputFailsInSameFileWithSameLineColumnMessageAndNoPublication() {
        Fixture valid = new Fixture("20-valid.cfg", "public example.Target value\n");
        Fixture malformed = new Fixture("21-malformed.cfg", "public example.Target broken(\n");

        AccessTransformerList stock = new AccessTransformerList();
        IncrementalReference reference = new IncrementalReference();
        stock.loadAT(valid.stream());
        reference.load(valid.stream());
        List<StateEntry> before = snapshot(stock);

        FailureFingerprint stockFailure = captureParserFailure(() -> stock.loadAT(malformed.stream()), malformed.name());
        FailureFingerprint referenceFailure = captureParserFailure(() -> reference.load(malformed.stream()), malformed.name());

        assertEquals(stockFailure, referenceFailure);
        assertEquals(RuntimeException.class.getName(), stockFailure.throwableClass());
        assertEquals("", stockFailure.message());
        assertTrue(stockFailure.stderr().contains("line 1:"), stockFailure.stderr());
        assertEquals(before, snapshot(stock));
        assertEquals(before, reference.snapshot());
    }

    private static CapturedStockConflict captureStockConflict(AccessTransformerList stock, Fixture fixture) {
        Logger logger = (Logger) LoggerFactory.getLogger("AXFORM");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        Throwable thrown;
        try {
            thrown = assertThrows(Throwable.class, () -> stock.loadAT(fixture.stream()));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
        List<String> errors = appender.list.stream()
                .filter(event -> event.getLevel().equals(Level.ERROR))
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
        return new CapturedStockConflict(thrown.getClass().getName(), Objects.toString(thrown.getMessage(), "<null>"), errors);
    }

    private static FailureFingerprint captureParserFailure(Runnable call, String sourceName) {
        PrintStream previous = System.err;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Throwable thrown;
        try (PrintStream replacement = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            System.setErr(replacement);
            thrown = assertThrows(Throwable.class, call::run);
        } finally {
            System.setErr(previous);
        }
        String stderr = bytes.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
        return new FailureFingerprint(sourceName, thrown.getClass().getName(), Objects.toString(thrown.getMessage(), "<null>"), stderr);
    }

    private static List<StateEntry> snapshot(AccessTransformerList list) {
        return list.getAccessTransformers().values().stream()
                .flatMap(Collection::stream)
                .map(IncrementalContractTest::entry)
                .sorted(Comparator.comparing(StateEntry::target))
                .toList();
    }

    private static StateEntry entry(AccessTransformer transformer) {
        try {
            return new StateEntry(
                    targetKey(transformer.getTarget()),
                    ((Enum<?>) MODIFIER_FIELD.get(transformer)).name(),
                    ((Enum<?>) FINALITY_FIELD.get(transformer)).name(),
                    List.copyOf(transformer.getOrigins()));
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    private static Field field(String name) {
        try {
            Field field = AccessTransformer.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static String targetKey(Target<?> target) {
        return target.getClass().getName() + "|" + target.getClassName() + "|" + target.getType() + "|" + target.targetName();
    }

    private static Set<String> targetDescriptors(Set<Type> targets) {
        return targets.stream().map(Type::getDescriptor).collect(Collectors.toCollection(java.util.TreeSet::new));
    }

    private static byte[] classBytes(ClassNode node) {
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static ClassNode sampleClass() {
        ClassNode node = new ClassNode();
        node.version = Opcodes.V17;
        node.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL;
        node.name = "example/Target";
        node.superName = "java/lang/Object";
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "value", "I", null, null));
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "other", "I", null, null));

        MethodNode hidden = new MethodNode(Opcodes.ACC_PRIVATE, "hidden", "()V", null, null);
        hidden.instructions.add(new InsnNode(Opcodes.RETURN));
        hidden.maxStack = 0;
        hidden.maxLocals = 1;
        node.methods.add(hidden);

        MethodNode caller = new MethodNode(Opcodes.ACC_PUBLIC, "caller", "()V", null, null);
        caller.instructions.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
        caller.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "example/Target", "hidden", "()V", false));
        caller.instructions.add(new InsnNode(Opcodes.RETURN));
        caller.maxStack = 1;
        caller.maxLocals = 1;
        node.methods.add(caller);
        return node;
    }

    private record Fixture(String name, String text) {
        CharStream stream() {
            return CharStreams.fromString(text, name);
        }
    }

    private record StateEntry(String target, String modifier, String finality, List<String> origins) {}
    private record FailureFingerprint(String sourceName, String throwableClass, String message, String stderr) {}
    private record CapturedStockConflict(String throwableClass, String message, List<String> errorMessages) {}

    private static final class IncrementalReference {
        private final HashMap<Target<?>, AccessTransformer> state = new HashMap<>();
        private Set<Type> targets = Set.of();
        private List<String> lastConflictMessages = List.of();

        void load(CharStream stream) {
            List<AccessTransformer> parsed = parse(stream);
            LinkedHashMap<Target<?>, AccessTransformer> updates = new LinkedHashMap<>();
            for (AccessTransformer incoming : parsed) {
                Target<?> target = incoming.getTarget();
                AccessTransformer current = updates.containsKey(target) ? updates.get(target) : state.get(target);
                updates.put(target, current == null ? incoming : current.mergeStates(incoming, stream.getSourceName()));
            }

            boolean hasInvalid = updates.values().stream().anyMatch(transformer -> !transformer.isValid());
            if (hasInvalid) {
                // Recreate only the exceptional stock copy so current HashMap diagnostic order is preserved.
                HashMap<Target<?>, AccessTransformer> stockShadow = new HashMap<>(state);
                updates.forEach(stockShadow::put);
                lastConflictMessages = stockShadow.values().stream()
                        .filter(transformer -> !transformer.isValid())
                        .map(transformer -> "Invalid access transform final state for target " + transformer.getTarget()
                                + ". Referred in resources " + transformer.getOrigins() + ".")
                        .toList();
                throw new IllegalArgumentException("Invalid AT final conflicts");
            }

            updates.forEach(state::put);
            targets = state.keySet().stream().map(Target::getASMType).collect(Collectors.toSet());
            lastConflictMessages = List.of();
        }

        private static List<AccessTransformer> parse(CharStream stream) {
            AtLexer lexer = new AtLexer(stream);
            CommonTokenStream tokenStream = new CommonTokenStream(lexer);
            AtParser parser = new AtParser(tokenStream);
            parser.addErrorListener(new AtParserErrorListener());
            AtParser.FileContext file = parser.file();
            AccessTransformVisitor visitor = new AccessTransformVisitor(stream.getSourceName());
            file.accept(visitor);
            return visitor.getAccessTransformers();
        }

        List<StateEntry> snapshot() {
            return state.values().stream()
                    .map(IncrementalContractTest::entry)
                    .sorted(Comparator.comparing(StateEntry::target))
                    .toList();
        }

        Set<String> targetDescriptors() {
            return IncrementalContractTest.targetDescriptors(targets);
        }

        List<String> lastConflictMessages() {
            return lastConflictMessages;
        }

        boolean transform(ClassNode clazzNode, Type classType) {
            if (!targets.contains(classType)) return false;
            Set<String> privateChanged = new HashSet<>();
            Map<TargetType, Map<String, AccessTransformer>> byType = state.entrySet().stream()
                    .filter(entry -> classType.equals(entry.getKey().getASMType()))
                    .map(Map.Entry::getValue)
                    .collect(Collectors.groupingBy(
                            transformer -> transformer.getTarget().getType(),
                            HashMap::new,
                            Collectors.toMap(transformer -> transformer.getTarget().targetName(), Function.identity())));

            Map<String, AccessTransformer> classTransformers = byType.get(TargetType.CLASS);
            if (classTransformers != null) {
                classTransformers.forEach((ignored, transformer) -> transformer.applyModifier(clazzNode, ClassNode.class, privateChanged));
            }
            Map<String, AccessTransformer> fieldTransformers = byType.get(TargetType.FIELD);
            if (fieldTransformers != null) {
                clazzNode.fields.stream()
                        .filter(field -> fieldTransformers.containsKey(field.name))
                        .forEach(field -> fieldTransformers.get(field.name).applyModifier(field, FieldNode.class, privateChanged));
            }
            Map<String, AccessTransformer> methodTransformers = byType.get(TargetType.METHOD);
            if (methodTransformers != null) {
                clazzNode.methods.stream()
                        .filter(method -> methodTransformers.containsKey(method.name + method.desc))
                        .forEach(method -> methodTransformers.get(method.name + method.desc).applyModifier(method, MethodNode.class, privateChanged));
            }
            if (!privateChanged.isEmpty()) {
                clazzNode.methods.forEach(method -> {
                    for (var instruction : method.instructions) {
                        if (instruction.getOpcode() == Opcodes.INVOKESPECIAL && instruction instanceof MethodInsnNode call
                                && privateChanged.contains(call.name + call.desc)) {
                            call.setOpcode(Opcodes.INVOKEVIRTUAL);
                        }
                    }
                });
            }
            return true;
        }
    }
}
