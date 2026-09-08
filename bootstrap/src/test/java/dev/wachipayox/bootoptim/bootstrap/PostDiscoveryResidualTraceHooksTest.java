package dev.wachipayox.bootoptim.bootstrap;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

class PostDiscoveryResidualTraceHooksTest {
    private static final String TRACE = "dev/wachipayox/bootoptim/trace/StructuredBootTrace";
    private static final String RESIDUAL_HOOKS =
            "dev/wachipayox/bootoptim/bootstrap/PostDiscoveryResidualTraceHooks";
    private static final String TRANSITION_HOOKS =
            "dev/wachipayox/bootoptim/bootstrap/ModLauncherTransitionTraceHooks";

    @Test
    void dependencyDiscoveryTaskEndsBeforeResidualPhaseBegins() throws IOException {
        var profiler = readClass(DiscoveryProfiler.class);
        var end = methodNamed(profiler, "end");
        var calls = methodCalls(end);

        int taskEnd = indexOfCall(calls, TRACE, "endTask");
        int residualBegin = indexOfCall(calls, RESIDUAL_HOOKS, "beginAtDependencyDiscoveryEnd");
        assertTrue(taskEnd >= 0, "dependency discovery must retain its normal task end");
        assertTrue(residualBegin > taskEnd, "residual phase must begin after dependency_discovery task end");
    }

    @Test
    void serviceCompleteScanMarksOnlyBootOptimCallback() throws IOException {
        var service = readClass(EarlyStartupProbeService.class);
        var completeScan = methodNamed(service, "completeScan");
        var calls = methodCalls(completeScan);

        assertTrue(
                indexOfCall(calls, RESIDUAL_HOOKS, "atCompleteScanCallback") >= 0,
                "BootOptim completeScan callback must expose the literal intermediate edge");
    }

    @Test
    void transformersClosesResidualBeforeExistingBootstrapTransitionBegins() throws IOException {
        var service = readClass(EarlyStartupProbeService.class);
        var transformers = methodNamed(service, "transformers");
        var calls = methodCalls(transformers);

        int residualEnd = indexOfCall(calls, RESIDUAL_HOOKS, "endAtTransformersCallback");
        int transitionBegin = indexOfCall(calls, TRANSITION_HOOKS, "beginTransition");
        assertTrue(residualEnd >= 0, "transformers callback must close the post-discovery residual");
        assertTrue(transitionBegin > residualEnd, "residual must close before #205 transition phase begins");
    }

    private static ClassNode readClass(Class<?> type) throws IOException {
        try (var input = type.getResourceAsStream(type.getSimpleName() + ".class")) {
            if (input == null) throw new IOException("missing class resource for " + type.getName());
            var node = new ClassNode();
            new ClassReader(input).accept(node, 0);
            return node;
        }
    }

    private static MethodNode methodNamed(ClassNode type, String name) {
        return type.methods.stream().filter(method -> method.name.equals(name)).findFirst().orElseThrow();
    }

    private static List<MethodInsnNode> methodCalls(MethodNode method) {
        List<MethodInsnNode> calls = new ArrayList<>();
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call) calls.add(call);
        }
        return calls;
    }

    private static int indexOfCall(List<MethodInsnNode> calls, String owner, String name) {
        for (int i = 0; i < calls.size(); i++) {
            var call = calls.get(i);
            if (call.owner.equals(owner) && call.name.equals(name)) return i;
        }
        return -1;
    }
}
