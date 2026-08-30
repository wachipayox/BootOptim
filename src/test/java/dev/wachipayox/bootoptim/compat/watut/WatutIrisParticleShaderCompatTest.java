package dev.wachipayox.bootoptim.compat.watut;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WatutIrisParticleShaderCompatTest {
    @Test
    void gatesToWatutParticleShaderWithIris() {
        assertTrue(WatutIrisParticleShaderCompat.shouldPatch(
                "particle", "com.corosus.watut.ShaderInstanceBlur", true, true));
        assertFalse(WatutIrisParticleShaderCompat.shouldPatch(
                "particle", "com.corosus.watut.ShaderInstanceBlur", true, false));
        assertFalse(WatutIrisParticleShaderCompat.shouldPatch(
                "position_tex_blur", "com.corosus.watut.ShaderInstanceBlur", true, true));
        assertFalse(WatutIrisParticleShaderCompat.shouldPatch(
                "particle", "net.minecraft.client.renderer.ShaderInstance", true, true));
    }

    @Test
    void rewritesLegacyParticleFogDistanceCall() {
        String legacy = "vertexDistance = fog_distance(ModelViewMat, Position, FogShape);";
        String patched = WatutIrisParticleShaderCompat.patchSource("shaders/core/particle.vsh", legacy);

        assertTrue(patched.contains("fog_distance(Position, FogShape)"));
        assertFalse(patched.contains("fog_distance(ModelViewMat, Position, FogShape)"));
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
