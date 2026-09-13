#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(sys.argv[1])
list_path = root / "src/main/java/net/neoforged/accesstransformer/parser/AccessTransformerList.java"
engine_path = root / "src/main/java/net/neoforged/accesstransformer/AccessTransformerEngineImpl.java"
test_path = root / "src/test/java/net/neoforged/accesstransformer/parser/InProcessTopologyOracleTest.java"

src = list_path.read_text()
old = '''    public void loadAT(final CharStream stream) {
        LOGGER.debug(AXFORM_MARKER, "Loading access transformer {}", stream.getSourceName());
        final AtLexer lexer = new AtLexer(stream);
        final CommonTokenStream tokenStream = new CommonTokenStream(lexer);
        final AtParser parser = new AtParser(tokenStream);
        parser.addErrorListener(new AtParserErrorListener());
        final AtParser.FileContext file = parser.file();
        final AccessTransformVisitor accessTransformVisitor = new AccessTransformVisitor(stream.getSourceName());
        file.accept(accessTransformVisitor);
        final HashMap<Target<?>, AccessTransformer> localATCopy = new HashMap<>(accessTransformers);
        mergeAccessTransformers(accessTransformVisitor.getAccessTransformers(), localATCopy, stream.getSourceName());
        final List<AccessTransformer> invalidTransformers = invalidTransformers(localATCopy);
        if (!invalidTransformers.isEmpty()) {
            invalidTransformers.forEach(at -> LOGGER.error(AXFORM_MARKER,"Invalid access transform final state for target {}. Referred in resources {}.",at.getTarget(), at.getOrigins()));
            throw new IllegalArgumentException("Invalid AT final conflicts");
        }
        this.accessTransformers.clear();
        this.accessTransformers.putAll(localATCopy);
        this.targetedClassCache = this.accessTransformers.keySet().stream().map(Target::getASMType).collect(Collectors.toSet());
        LOGGER.debug(AXFORM_MARKER,"Loaded access transformer {}", stream.getSourceName());
    }
'''
new = '''    private final boolean oracleTouchedValidation;

    public AccessTransformerList() {
        this(false);
    }

    AccessTransformerList(final boolean oracleTouchedValidation) {
        this.oracleTouchedValidation = oracleTouchedValidation;
    }

    public void loadAT(final CharStream stream) {
        LOGGER.debug(AXFORM_MARKER, "Loading access transformer {}", stream.getSourceName());
        final List<AccessTransformer> parsed = parseForOracle(stream);
        loadParsedForOracle(parsed, stream.getSourceName());
        LOGGER.debug(AXFORM_MARKER,"Loaded access transformer {}", stream.getSourceName());
    }

    static List<AccessTransformer> parseForOracle(final CharStream stream) {
        final AtLexer lexer = new AtLexer(stream);
        final CommonTokenStream tokenStream = new CommonTokenStream(lexer);
        final AtParser parser = new AtParser(tokenStream);
        parser.addErrorListener(new AtParserErrorListener());
        final AtParser.FileContext file = parser.file();
        final AccessTransformVisitor accessTransformVisitor = new AccessTransformVisitor(stream.getSourceName());
        file.accept(accessTransformVisitor);
        return accessTransformVisitor.getAccessTransformers();
    }

    void loadParsedForOracle(final List<AccessTransformer> parsed, final String resourceName) {
        final HashMap<Target<?>, AccessTransformer> localATCopy = new HashMap<>(accessTransformers);
        mergeAccessTransformers(parsed, localATCopy, resourceName);
        final boolean invalid = oracleTouchedValidation
                ? parsed.stream().map(AccessTransformer::getTarget).map(localATCopy::get).anyMatch(at -> !at.isValid())
                : !invalidTransformers(localATCopy).isEmpty();
        if (invalid) {
            final List<AccessTransformer> invalidTransformers = invalidTransformers(localATCopy);
            invalidTransformers.forEach(at -> LOGGER.error(AXFORM_MARKER,"Invalid access transform final state for target {}. Referred in resources {}.",at.getTarget(), at.getOrigins()));
            throw new IllegalArgumentException("Invalid AT final conflicts");
        }
        this.accessTransformers.clear();
        this.accessTransformers.putAll(localATCopy);
        this.targetedClassCache = this.accessTransformers.keySet().stream().map(Target::getASMType).collect(Collectors.toSet());
    }

    List<String> oracleRawEntries() {
        return accessTransformers.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.toList());
    }

    List<String> oracleInvalidOrder(final List<AccessTransformer> parsed, final String resourceName) {
        final HashMap<Target<?>, AccessTransformer> localATCopy = new HashMap<>(accessTransformers);
        mergeAccessTransformers(parsed, localATCopy, resourceName);
        return invalidTransformers(localATCopy).stream().map(at -> at.getTarget() + " origins=" + at.getOrigins()).collect(Collectors.toList());
    }
'''
if src.count(old) != 1:
    raise SystemExit("exact AccessTransformerList loadAT block mismatch")
src = src.replace(old, new)
list_path.write_text(src)

engine = engine_path.read_text()
old_engine = '    private final AccessTransformerList masterList = new AccessTransformerList();\n'
new_engine = '''    private final AccessTransformerList masterList;

    public AccessTransformerEngineImpl() {
        this(new AccessTransformerList());
    }

    public AccessTransformerEngineImpl(final AccessTransformerList masterList) {
        this.masterList = masterList;
    }
'''
if engine.count(old_engine) != 1:
    raise SystemExit("exact AccessTransformerEngineImpl field mismatch")
engine_path.write_text(engine.replace(old_engine, new_engine))

test_path.write_text(r'''package net.neoforged.accesstransformer.parser;

import net.neoforged.accesstransformer.AccessTransformer;
import net.neoforged.accesstransformer.AccessTransformerEngineImpl;
import net.neoforged.accesstransformer.TargetType;
import org.antlr.v4.runtime.CharStreams;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Diagnostic oracle: parse each file once, then feed the exact same AccessTransformer/Target
 * object graph to two real AccessTransformerList instances. The reference differs only in the
 * healthy validation scan; HashMap copy, merge, clear/putAll publication and target-set rebuild
 * remain literal stock operations.
 */
class InProcessTopologyOracleTest {
    @Test
    void sharedIdentityMatchesEveryCollectionSurfaceAfterEachFile() {
        var stock = new AccessTransformerList(false);
        var reference = new AccessTransformerList(true);
        var classes = collidingNames(5);
        for (int i = 0; i < classes.size(); i++) {
            String cls = "oracle." + classes.get(i);
            String origin = "collision-" + i + ".cfg";
            String text = "public-f " + cls + "\nprotected " + cls + " Aa\npublic " + cls + " BB\n";
            loadShared(stock, reference, text, origin);
            assertAllSurfaces(stock, reference);
        }
    }

    @Test
    void publicationOriginsRollbackAndConflictDiagnosticOrderMatch() {
        var stock = new AccessTransformerList(false);
        var reference = new AccessTransformerList(true);
        loadShared(stock, reference,
                "public+f oracle.Aa Aa\npublic+f oracle.BB BB\npublic oracle.Aa\n", "base.cfg");
        assertAllSurfaces(stock, reference);

        String origin = "conflict.cfg";
        List<AccessTransformer> shared = AccessTransformerList.parseForOracle(CharStreams.fromString(
                "public-f oracle.Aa Aa\npublic-f oracle.BB BB\n", origin));
        assertEquals(stock.oracleInvalidOrder(shared, origin), reference.oracleInvalidOrder(shared, origin));
        List<String> stockBefore = stock.oracleRawEntries();
        List<String> referenceBefore = reference.oracleRawEntries();
        IllegalArgumentException a = assertThrows(IllegalArgumentException.class, () -> stock.loadParsedForOracle(shared, origin));
        IllegalArgumentException b = assertThrows(IllegalArgumentException.class, () -> reference.loadParsedForOracle(shared, origin));
        assertEquals(a.getMessage(), b.getMessage());
        assertEquals(stockBefore, stock.oracleRawEntries());
        assertEquals(referenceBefore, reference.oracleRawEntries());
        assertAllSurfaces(stock, reference);
    }

    @Test
    void malformedParserFailureFingerprintMatches() {
        assertEquals(captureMalformed(new AccessTransformerList(false)), captureMalformed(new AccessTransformerList(true)));
    }

    @Test
    void targetSetIdentityMutabilityAndReplacementMatchStock() {
        var stock = new AccessTransformerList(false);
        var reference = new AccessTransformerList(true);
        Type external = Type.getObjectType("external/Mutation");
        assertThrows(UnsupportedOperationException.class, () -> stock.getTargets().add(external));
        assertThrows(UnsupportedOperationException.class, () -> reference.getTargets().add(external));
        loadShared(stock, reference, "public oracle.Alpha\n", "one.cfg");
        Set<Type> s1 = stock.getTargets();
        Set<Type> r1 = reference.getTargets();
        assertEquals(new ArrayList<>(s1), new ArrayList<>(r1));
        s1.add(external); r1.add(external);
        assertSame(s1, stock.getTargets());
        assertSame(r1, reference.getTargets());
        loadShared(stock, reference, "public oracle.Bravo\n", "two.cfg");
        assertNotSame(s1, stock.getTargets());
        assertNotSame(r1, reference.getTargets());
        assertFalse(stock.getTargets().contains(external));
        assertFalse(reference.getTargets().contains(external));
        assertEquals(new ArrayList<>(stock.getTargets()), new ArrayList<>(reference.getTargets()));
    }

    @Test
    void actualEngineTransformBytesMatch() {
        var stock = new AccessTransformerList(false);
        var reference = new AccessTransformerList(true);
        loadShared(stock, reference,
                "public-f oracle.Aa\nprotected oracle.Aa Aa\npublic oracle.Aa BB\n"
                        + "public oracle.BB\nprotected oracle.BB Aa\npublic oracle.BB BB\n", "transform.cfg");
        var stockEngine = new AccessTransformerEngineImpl(stock);
        var referenceEngine = new AccessTransformerEngineImpl(reference);
        for (Type type : stock.getTargets()) {
            ClassNode a = sample(type), b = sample(type);
            assertEquals(stockEngine.transform(a, type), referenceEngine.transform(b, type));
            assertArrayEquals(bytes(a), bytes(b));
        }
    }

    private static void assertAllSurfaces(AccessTransformerList a, AccessTransformerList b) {
        assertEquals(a.oracleRawEntries(), b.oracleRawEntries(), "raw HashMap iteration");
        assertEquals(accessGroups(a.getAccessTransformers()), accessGroups(b.getAccessTransformers()), "getAccessTransformers");
        assertEquals(new ArrayList<>(a.getTargets()), new ArrayList<>(b.getTargets()), "getTargets iteration");
        assertEquals(a.getTargets(), b.getTargets(), "getTargets membership");
        for (Type t : a.getTargets()) {
            assertEquals(targetGroups(a.getTransformersForTarget(t)), targetGroups(b.getTransformersForTarget(t)), "getTransformersForTarget " + t);
        }
    }

    private static List<String> accessGroups(Map<String, List<AccessTransformer>> groups) {
        List<String> out = new ArrayList<>();
        groups.forEach((k, v) -> out.add(k + "=" + v.stream().map(x -> x.getTarget().toString() + "@" + x.getOrigins()).toList()));
        return out;
    }

    private static List<String> targetGroups(Map<TargetType, Map<String, AccessTransformer>> groups) {
        List<String> out = new ArrayList<>();
        groups.forEach((kind, map) -> {
            List<String> inner = new ArrayList<>();
            map.forEach((k, v) -> inner.add(k + "=" + v.getTarget() + "@" + v.getOrigins()));
            out.add(kind + "=" + inner);
        });
        return out;
    }

    private static void loadShared(AccessTransformerList a, AccessTransformerList b, String text, String origin) {
        List<AccessTransformer> shared = AccessTransformerList.parseForOracle(CharStreams.fromString(text, origin));
        a.loadParsedForOracle(shared, origin);
        b.loadParsedForOracle(shared, origin);
    }

    private static Failure captureMalformed(AccessTransformerList list) {
        PrintStream old = System.err;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Throwable thrown;
        try {
            System.setErr(new PrintStream(bytes, true, StandardCharsets.UTF_8));
            thrown = assertThrows(Throwable.class, () -> list.loadAT(CharStreams.fromString("public oracle.Bad broken(\n", "malformed.cfg")));
        } finally {
            System.setErr(old);
        }
        return new Failure(thrown.getClass().getName(), Objects.toString(thrown.getMessage(), "<null>"), bytes.toString(StandardCharsets.UTF_8));
    }

    private static ClassNode sample(Type type) {
        ClassNode n = new ClassNode();
        n.version = Opcodes.V17;
        n.access = Opcodes.ACC_PRIVATE;
        n.name = type.getInternalName();
        n.superName = "java/lang/Object";
        n.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "Aa", "I", null, null));
        n.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "BB", "I", null, null));
        return n;
    }

    private static byte[] bytes(ClassNode n) {
        ClassWriter w = new ClassWriter(0); n.accept(w); return w.toByteArray();
    }

    private static List<String> collidingNames(int depth) {
        List<String> names = List.of("");
        for (int i = 0; i < depth; i++) {
            List<String> next = new ArrayList<>();
            for (String p : names) { next.add(p + "Aa"); next.add(p + "BB"); }
            names = next;
        }
        return names;
    }

    private record Failure(String throwableClass, String message, String stderr) {}
}
''')
