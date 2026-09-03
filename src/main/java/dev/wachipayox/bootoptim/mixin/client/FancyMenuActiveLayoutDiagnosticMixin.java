package dev.wachipayox.bootoptim.mixin.client;

import com.mojang.logging.LogUtils;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * DIAGNOSTIC ONLY. Dumps FancyMenu's resolved active layouts/backgrounds for the title and the
 * exact-pack AnalogAudio welcome screen so MCEF video compatibility can be attributed precisely.
 */
@Pseudo
@Mixin(targets = "de.keksuccino.fancymenu.customization.layer.ScreenCustomizationLayer", remap = false)
abstract class FancyMenuActiveLayoutDiagnosticMixin {
    private static final Logger BOOTOPTIM_LOGGER = LogUtils.getLogger();

    @Inject(
            method = "onInitOrResizeScreenPre(Lde/keksuccino/fancymenu/events/screen/InitOrResizeScreenEvent$Pre;)V",
            at = @At("RETURN"),
            require = 0)
    private void bootoptim$dumpResolvedLayouts(CallbackInfo ci) {
        try {
            String screen = String.valueOf(bootoptim$readField(this, "screenIdentifier"));
            if (!"title_screen".equals(screen) && !screen.endsWith("LavaplayerWelcomeScreen")) {
                return;
            }

            List<?> activeLayouts = bootoptim$asList(bootoptim$readField(this, "activeLayouts"));
            Object layoutBase = bootoptim$readField(this, "layoutBase");
            List<?> backgrounds = layoutBase == null
                    ? List.of()
                    : bootoptim$asList(bootoptim$readField(layoutBase, "menuBackgrounds"));
            Object backgroundDrawable = bootoptim$readField(this, "backgroundDrawable");

            BOOTOPTIM_LOGGER.info(
                    "BOOTOPTIM_FANCYMENU_LAYOUT_DIAG event=resolved screen={} active_layouts={} backgrounds={} background_drawable={} layout_base_class={}",
                    screen,
                    activeLayouts.size(),
                    backgrounds.size(),
                    backgroundDrawable,
                    layoutBase == null ? "null" : layoutBase.getClass().getName());

            for (int i = 0; i < activeLayouts.size(); i++) {
                Object layout = activeLayouts.get(i);
                Object fileValue = bootoptim$readField(layout, "layoutFile");
                String file = fileValue instanceof File layoutFile ? layoutFile.getPath() : String.valueOf(fileValue);
                BOOTOPTIM_LOGGER.info(
                        "BOOTOPTIM_FANCYMENU_LAYOUT_DIAG event=layout screen={} index={} file={} name={} runtime_id={} declared_screen={} random={} random_group={} enabled={} layout_index={}",
                        screen,
                        i,
                        file,
                        bootoptim$invokeNoArgs(layout, "getLayoutName"),
                        bootoptim$readField(layout, "runtimeLayoutIdentifier"),
                        bootoptim$readField(layout, "screenIdentifier"),
                        bootoptim$readField(layout, "randomMode"),
                        bootoptim$readField(layout, "randomGroup"),
                        bootoptim$invokeNoArgs(layout, "isEnabled"),
                        bootoptim$readField(layout, "layoutIndex"));
            }

            for (int i = 0; i < backgrounds.size(); i++) {
                Object background = backgrounds.get(i);
                Object parentLayout = bootoptim$invokeNoArgs(background, "getParentLayout");
                Object parentFileValue = parentLayout == null ? null : bootoptim$readField(parentLayout, "layoutFile");
                String parentFile = parentFileValue instanceof File layoutFile
                        ? layoutFile.getPath()
                        : String.valueOf(parentFileValue);
                Object showProperty = bootoptim$readField(background, "showBackground");
                Object show = showProperty == null ? null : bootoptim$invokeNoArgs(showProperty, "get");

                BOOTOPTIM_LOGGER.info(
                        "BOOTOPTIM_FANCYMENU_LAYOUT_DIAG event=background screen={} index={} class={} instance={} parent_file={} parent_name={} show_background={}",
                        screen,
                        i,
                        background.getClass().getName(),
                        bootoptim$invokeNoArgs(background, "getInstanceIdentifier"),
                        parentFile,
                        parentLayout == null ? "null" : bootoptim$invokeNoArgs(parentLayout, "getLayoutName"),
                        show);
            }
        } catch (Throwable throwable) {
            BOOTOPTIM_LOGGER.error(
                    "BOOTOPTIM_FANCYMENU_LAYOUT_DIAG event=error target_class={}",
                    this.getClass().getName(),
                    throwable);
        }
    }

    private static List<?> bootoptim$asList(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private static Object bootoptim$readField(Object target, String name) throws ReflectiveOperationException {
        if (target == null) {
            return null;
        }
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(target.getClass().getName() + "." + name);
    }

    private static Object bootoptim$invokeNoArgs(Object target, String name) throws ReflectiveOperationException {
        if (target == null) {
            return null;
        }
        Method method = target.getClass().getMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }
}
