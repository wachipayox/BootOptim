package dev.wachipayox.bootoptim.profiling.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Diagnostic-only census of ModernFix 5.27.14 effective mixin configuration.
 *
 * <p>This deliberately uses reflection so BootOptim retains no runtime dependency on ModernFix. It is
 * invoked only after the main-menu marker has already been recorded, so loading structural marker
 * classes cannot inflate TTMM. The probe never mutates ModernFix configuration.</p>
 */
public final class ModernFixCompatibilityProbe {
    private static final Logger LOGGER = LoggerFactory.getLogger("BootOptim/ModernFixCompat");
    private static final String ENABLE_PROPERTY = "boot_optim.probeModernFixCompat";
    private static final String EXPECTED_VERSION = "5.27.14+mc1.21.1";
    private static final AtomicBoolean RAN = new AtomicBoolean();

    private ModernFixCompatibilityProbe() {
    }

    public static void runAfterMainMenuMarker() {
        if (!Boolean.getBoolean(ENABLE_PROPERTY) || !RAN.compareAndSet(false, true)) {
            return;
        }

        String version;
        try {
            version = ModList.get()
                    .getModContainerById("modernfix")
                    .map(container -> container.getModInfo().getVersion().toString())
                    .orElse(null);
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.info(
                    "BOOTOPTIM_MODERNFIX_COMPAT status=probe_failed stage=version reason={}",
                    failure.getClass().getName());
            return;
        }

        if (version == null) {
            LOGGER.info("BOOTOPTIM_MODERNFIX_COMPAT status=absent");
            return;
        }

        ClassLoader loader = ModernFixCompatibilityProbe.class.getClassLoader();
        try {
            Class<?> pluginClass = Class.forName("org.embeddedt.modernfix.core.ModernFixMixinPlugin", false, loader);
            Object plugin = pluginClass.getField("instance").get(null);
            if (plugin == null) {
                LOGGER.info(
                        "BOOTOPTIM_MODERNFIX_COMPAT status=present version={} expected_version_match={} plugin=not_initialized",
                        version,
                        EXPECTED_VERSION.equals(version));
                return;
            }

            Object config = pluginClass.getField("config").get(plugin);
            Method effective = config.getClass().getMethod("getEffectiveOptionForMixin", String.class);
            Object disabledValue = config.getClass().getMethod("getPermanentlyDisabledMixins").invoke(config);
            Map<?, ?> permanentlyDisabled = disabledValue instanceof Map<?, ?> map ? map : Map.of();

            Class<?> earlyConfigClass = Class.forName(
                    "org.embeddedt.modernfix.core.config.ModernFixEarlyConfig", false, loader);
            Object featureLevel = earlyConfigClass.getField("ACTIVE_FEATURE_LEVEL").get(null);

            LOGGER.info(
                    "BOOTOPTIM_MODERNFIX_COMPAT status=present version={} expected_version_match={} feature_level={}",
                    version,
                    EXPECTED_VERSION.equals(version),
                    featureLevel);

            probeOption(loader, config, effective, permanentlyDisabled,
                    "mixin.perf.dynamic_resources", "perf.dynamic_resources.ModelManagerMixin");
            probeOption(loader, config, effective, permanentlyDisabled,
                    "mixin.perf.dynamic_resources", "perf.dynamic_resources.ModelBakeryMixin");
            probeOption(loader, config, effective, permanentlyDisabled,
                    "mixin.perf.dynamic_resources", "perf.dynamic_resources.BlockStateModelLoaderMixin");
            probeOption(loader, config, effective, permanentlyDisabled,
                    "mixin.perf.resourcepacks", "perf.resourcepacks.FilePackResourcesMixin");
            probeOption(loader, config, effective, permanentlyDisabled,
                    "mixin.perf.resourcepacks", "perf.resourcepacks.PathPackResourcesMixin");
            probeOption(loader, config, effective, permanentlyDisabled,
                    "mixin.perf.faster_texture_stitching", "perf.faster_texture_stitching.StitcherMixin");
            probeOption(loader, config, effective, permanentlyDisabled,
                    "mixin.perf.deduplicate_wall_shapes", "perf.deduplicate_wall_shapes.WallBlockMixin");
        } catch (Throwable failure) {
            LOGGER.info(
                    "BOOTOPTIM_MODERNFIX_COMPAT status=probe_failed stage=config reason={}",
                    failure.getClass().getName());
        }
    }

    private static void probeOption(
            ClassLoader loader,
            Object config,
            Method effective,
            Map<?, ?> permanentlyDisabled,
            String category,
            String mixinPath) {
        try {
            Object option = effective.invoke(config, mixinPath);
            if (option == null) {
                LOGGER.info(
                        "BOOTOPTIM_MODERNFIX_COMPAT category={} mixin={} effective=unmatched permanent_disable=unknown selected_by_modernfix=false applied_structural={}",
                        category,
                        mixinPath,
                        structuralMarker(loader, mixinPath));
                return;
            }

            Class<?> optionClass = option.getClass();
            boolean enabled = Boolean.TRUE.equals(optionClass.getMethod("isEnabled").invoke(option));
            boolean userDefined = Boolean.TRUE.equals(optionClass.getMethod("isUserDefined").invoke(option));
            boolean modDefined = Boolean.TRUE.equals(optionClass.getMethod("isModDefined").invoke(option));
            boolean overridden = Boolean.TRUE.equals(optionClass.getMethod("isOverridden").invoke(option));
            String rule = String.valueOf(optionClass.getMethod("getName").invoke(option));
            Object definingModsValue = optionClass.getMethod("getDefiningMods").invoke(option);
            String definingMods = definingModsValue instanceof Collection<?> collection
                    ? collection.toString()
                    : String.valueOf(definingModsValue);
            Object permanentReasonValue = permanentlyDisabled.get(mixinPath);
            String permanentReason = permanentReasonValue == null ? "none" : String.valueOf(permanentReasonValue);
            boolean selected = enabled && permanentReasonValue == null;

            LOGGER.info(
                    "BOOTOPTIM_MODERNFIX_COMPAT category={} mixin={} effective={} controlling_rule={} user_defined={} mod_defined={} overridden={} defining_mods={} permanent_disable={} selected_by_modernfix={} applied_structural={}",
                    category,
                    mixinPath,
                    enabled,
                    rule,
                    userDefined,
                    modDefined,
                    overridden,
                    definingMods,
                    permanentReason,
                    selected,
                    structuralMarker(loader, mixinPath));
        } catch (Throwable failure) {
            LOGGER.info(
                    "BOOTOPTIM_MODERNFIX_COMPAT category={} mixin={} effective=probe_failed reason={}",
                    category,
                    mixinPath,
                    failure.getClass().getName());
        }
    }

    private static String structuralMarker(ClassLoader loader, String mixinPath) {
        try {
            return switch (mixinPath) {
                case "perf.dynamic_resources.ModelManagerMixin" -> Boolean.toString(
                        Class.forName("org.embeddedt.modernfix.duck.IExtendedModelManager", false, loader)
                                .isAssignableFrom(Class.forName(
                                        "net.minecraft.client.resources.model.ModelManager", false, loader)));
                case "perf.dynamic_resources.ModelBakeryMixin" -> Boolean.toString(
                        Class.forName("org.embeddedt.modernfix.duck.IExtendedModelBakery", false, loader)
                                .isAssignableFrom(Class.forName(
                                        "net.minecraft.client.resources.model.ModelBakery", false, loader)));
                case "perf.dynamic_resources.BlockStateModelLoaderMixin" -> Boolean.toString(
                        Class.forName("org.embeddedt.modernfix.duck.IBlockStateModelLoader", false, loader)
                                .isAssignableFrom(Class.forName(
                                        "net.minecraft.client.resources.model.BlockStateModelLoader", false, loader)));
                case "perf.resourcepacks.FilePackResourcesMixin" -> Boolean.toString(hasDeclaredField(
                        Class.forName("net.minecraft.server.packs.FilePackResources", false, loader), "mf$packIndex"));
                case "perf.resourcepacks.PathPackResourcesMixin" -> Boolean.toString(
                        Class.forName("org.embeddedt.modernfix.resources.ICachingResourcePack", false, loader)
                                .isAssignableFrom(Class.forName(
                                        "net.minecraft.server.packs.PathPackResources", false, loader)));
                case "perf.faster_texture_stitching.StitcherMixin" -> Boolean.toString(hasDeclaredField(
                        Class.forName("net.minecraft.client.renderer.texture.Stitcher", false, loader),
                        "loadableSpriteInfos"));
                case "perf.deduplicate_wall_shapes.WallBlockMixin" -> Boolean.toString(hasDeclaredField(
                        Class.forName("net.minecraft.world.level.block.WallBlock", false, loader),
                        "CACHE_BY_SHAPE_VALS"));
                default -> "unknown_marker";
            };
        } catch (Throwable failure) {
            return "probe_failed:" + failure.getClass().getSimpleName();
        }
    }

    private static boolean hasDeclaredField(Class<?> target, String fieldName) {
        try {
            Field ignored = target.getDeclaredField(fieldName);
            return true;
        } catch (NoSuchFieldException ignored) {
            return false;
        }
    }
}
