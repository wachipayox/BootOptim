package dev.wachipayox.bootoptim.compat.watut;

import com.mojang.logging.LogUtils;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceProvider;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

/**
 * Adapts WATUT 1.21.0/1.21.1's legacy particle core shader to Minecraft 1.21.1's fog-distance contract when Iris is present.
 *
 * <p>WATUT's legacy shader calls {@code fog_distance(ModelViewMat, Position, FogShape)} and ships a matching three-argument
 * {@code fog.glsl}. Minecraft 1.21.1 changed the vanilla helper to {@code fog_distance(Position, FogShape)}. Iris can resolve
 * WATUT's unqualified {@code <fog.glsl>} import against the vanilla helper, which makes the shader fail compilation and forces
 * Minecraft through a full fallback resource reload. The wrapper is enabled only for the particle shader while WATUT and Iris
 * are loaded, and it mutates resources only when the exact legacy WATUT source patterns are present.</p>
 */
public final class WatutIrisParticleShaderCompat {
    private static final String WATUT_MOD_ID = "watut";
    private static final String IRIS_MOD_ID = "iris";
    private static final String PARTICLE_SHADER_NAME = "particle";
    private static final String PARTICLE_VERTEX_PATH = "shaders/core/particle.vsh";
    private static final String FOG_INCLUDE_PATH = "shaders/include/fog.glsl";

    private static final String LEGACY_PARTICLE_CALL = "fog_distance(ModelViewMat, Position, FogShape)";
    private static final String MODERN_PARTICLE_CALL = "fog_distance(Position, FogShape)";
    private static final String LEGACY_FOG_FUNCTION = """
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
    private static final String MODERN_FOG_FUNCTION = """
            float fog_distance(vec3 pos, int shape) {
                if (shape == 0) {
                    return length(pos);
                } else {
                    float distXZ = length(pos.xz);
                    float distY = abs(pos.y);
                    return max(distXZ, distY);
                }
            }
            """;

    private static final AtomicBoolean APPLIED = new AtomicBoolean();

    private WatutIrisParticleShaderCompat() {
    }

    public static ResourceProvider wrapIfNeeded(ResourceProvider original, String shaderName) {
        if (!shouldPatch(shaderName, modsPresent(WATUT_MOD_ID), modsPresent(IRIS_MOD_ID))) {
            return original;
        }

        return location -> patchResource(original, location);
    }

    static boolean shouldPatch(String shaderName, boolean watutLoaded, boolean irisLoaded) {
        return PARTICLE_SHADER_NAME.equals(shaderName)
                && watutLoaded
                && irisLoaded;
    }

    static String patchSource(String path, String source) {
        if (PARTICLE_VERTEX_PATH.equals(path) && source.contains(LEGACY_PARTICLE_CALL)) {
            return source.replace(LEGACY_PARTICLE_CALL, MODERN_PARTICLE_CALL);
        }
        if (FOG_INCLUDE_PATH.equals(path) && source.contains("float fog_distance(mat4 modelViewMat, vec3 pos, int shape)")) {
            return source.replace(LEGACY_FOG_FUNCTION, MODERN_FOG_FUNCTION);
        }
        return source;
    }

    private static Optional<Resource> patchResource(ResourceProvider original, ResourceLocation location) {
        Optional<Resource> resource = original.getResource(location);
        if (resource.isEmpty()) {
            return resource;
        }

        String path = location.getPath();
        if (!PARTICLE_VERTEX_PATH.equals(path) && !FOG_INCLUDE_PATH.equals(path)) {
            return resource;
        }

        Resource sourceResource = resource.get();
        return Optional.of(new Resource(sourceResource.source(), () -> {
            byte[] originalBytes;
            try (var stream = sourceResource.open()) {
                originalBytes = stream.readAllBytes();
            }
            String originalSource = new String(originalBytes, StandardCharsets.UTF_8);
            String patchedSource = patchSource(path, originalSource);
            if (!patchedSource.equals(originalSource)) {
                markApplied(path);
            }
            return new ByteArrayInputStream(patchedSource.getBytes(StandardCharsets.UTF_8));
        }));
    }

    private static boolean modsPresent(String modId) {
        return ModList.get().isLoaded(modId);
    }

    private static void markApplied(String path) {
        if (APPLIED.compareAndSet(false, true)) {
            logger().info(
                    "BOOTOPTIM_COMPAT id=watut_iris_particle_shader status=applied strategy=mc1_21_1_fog_distance path={}",
                    path);
        }
    }

    private static Logger logger() {
        return LogUtils.getLogger();
    }
}
