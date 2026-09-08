import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]
HELPER = ROOT / "src/main/java/dev/wachipayox/bootoptim/profiling/client/FlywheelShaderSourcesProbe.java"
RELOADER_MIXIN = ROOT / "src/main/java/dev/wachipayox/bootoptim/mixin/client/FlywheelProgramsReloaderProbeMixin.java"
SOURCES_MIXIN = ROOT / "src/main/java/dev/wachipayox/bootoptim/mixin/client/FlywheelShaderSourcesProbeMixin.java"
MIXIN_CONFIG = ROOT / "src/main/resources/boot_optim.mixins.json"


class FlywheelShaderSourcesProbeContractTest(unittest.TestCase):
    def test_probe_is_default_off_and_version_bounded(self):
        text = HELPER.read_text(encoding="utf-8")
        self.assertIn('boot_optim.profileFlywheelShaderSources', text)
        self.assertIn('System.getProperty(PROPERTY, "false")', text)
        self.assertIn('EXPECTED_VERSION = "1.0.6"', text)
        self.assertIn('getModContainerById("flywheel")', text)

    def test_probe_does_not_construct_or_schedule_shader_sources(self):
        combined = "\n".join(
            path.read_text(encoding="utf-8")
            for path in (HELPER, RELOADER_MIXIN, SOURCES_MIXIN)
        )
        self.assertNotIn("new ShaderSources", combined)
        self.assertNotIn("Class.forName", combined)
        self.assertNotIn("supplyAsync", combined)
        self.assertNotIn("runAsync", combined)
        self.assertNotIn("Executors.", combined)

    def test_optional_mixins_target_only_flywheel_probe_boundaries(self):
        reloader = RELOADER_MIXIN.read_text(encoding="utf-8")
        sources = SOURCES_MIXIN.read_text(encoding="utf-8")
        config = MIXIN_CONFIG.read_text(encoding="utf-8")
        self.assertIn("@Pseudo", reloader)
        self.assertIn("dev.engine_room.flywheel.backend.compile.FlwProgramsReloader", reloader)
        self.assertIn("@Pseudo", sources)
        self.assertIn("dev.engine_room.flywheel.backend.glsl.ShaderSources", sources)
        self.assertIn("private static void bootoptim$sourcesStart", sources)
        self.assertIn("private static void bootoptim$sourcesEnd", sources)
        self.assertIn("FlywheelProgramsReloaderProbeMixin", config)
        self.assertIn("FlywheelShaderSourcesProbeMixin", config)

    def test_required_marker_names_are_present(self):
        text = HELPER.read_text(encoding="utf-8")
        for marker in (
            "flywheel_sources_prepare_start",
            "flywheel_sources_prepare_end",
            "flywheel_commit_start",
            "flywheel_commit_end",
            "allPreparations",
            "allDone",
            "main_menu_presented",
            "first_world_render",
        ):
            self.assertIn(marker, text)


if __name__ == "__main__":
    unittest.main()
