package dev.wachipayox.bootoptim.compat.watut;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class WatutIrisParticleShaderCompatTest {
    @Test
    void gatesToParticleShaderWithWatutAndIris() {
        assertTrue(WatutIrisParticleShaderCompat.shouldPatch("particle", true, true));
        assertFalse(WatutIrisParticleShaderCompat.shouldPatch("particle", true, false));
        assertFalse(WatutIrisParticleShaderCompat.shouldPatch("particle", false, true));
        assertFalse(WatutIrisParticleShaderCompat.shouldPatch("position_tex_blur", true, true));
    }

    @Test
    void constructorHeadModifyVariableHandlerRemainsStatic() throws IOException {
        String mixinSource = Files.readString(Path.of(
                "src/main/java/dev/wachipayox/bootoptim/mixin/compat/watut/ShaderInstanceWatutIrisCompatMixin.java"));

        assertTrue(
                mixinSource.contains("private static ResourceProvider bootoptim$wrapWatutParticleResources("),
                "Constructor HEAD @ModifyVariable handlers must be static before this()/super() invocation");
    }

    @Test
    void rewritesLegacyParticleFogDistanceCall() {
        String legacy = "vertexDistance = fog_distance(ModelViewMat, Position, FogShape);";
        String patched = WatutIrisParticleShaderCompat.patchSource("shaders/core/particle.vsh", legacy);

        assertTrue(patched.contains("fog_distance(Position, FogShape)"));
        assertFalse(patched.contains("fog_distance(ModelViewMat, Position, FogShape)"));
    }

    @Test
    void leavesModernParticleShaderUntouched() {
        String modern = "vertexDistance = fog_distance(Position, FogShape);";
        String patched = WatutIrisParticleShaderCompat.patchSource("shaders/core/particle.vsh", modern);

        assertTrue(patched.equals(modern));
    }

    @Test
    void rewritesLegacyFogHelperToMinecraft1211Contract() {
        String legacy = """
                #version 150

                float fog_distance(mat4 modelViewMat, vec3 pos, int shape) {
                    if (shape == 0) {
                        return length((modelViewMat * vec4(pos, 1.0)).xyz);
                    } else {
                        float distXZ = length((modelViewMat * vec4(pos.x, 0.0, pos.z, 1.0)).xyz);
                        float distY = length((modelViewMat * vec4(0.0, pos.y, 0.0, 1.0)).xyz);
                        return max(distXZ, distY);
                    }
                }
                """;
        String patched = WatutIrisParticleShaderCompat.patchSource("shaders/include/fog.glsl", legacy);

        assertTrue(patched.contains("float fog_distance(vec3 pos, int shape)"));
        assertFalse(patched.contains("float fog_distance(mat4 modelViewMat, vec3 pos, int shape)"));
    }
}
