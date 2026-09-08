import importlib.util
import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[2] / "scripts/exact-pack/plan_scaling.py"
SPEC = importlib.util.spec_from_file_location("plan_scaling", SCRIPT)
PLAN = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = PLAN
SPEC.loader.exec_module(PLAN)

MATERIALIZER_SCRIPT = Path(__file__).resolve().parents[2] / "scripts/exact-pack/materialize_scaling_variant.py"
MATERIALIZER_SPEC = importlib.util.spec_from_file_location("materialize_scaling_variant", MATERIALIZER_SCRIPT)
MATERIALIZER = importlib.util.module_from_spec(MATERIALIZER_SPEC)
assert MATERIALIZER_SPEC.loader is not None
sys.modules[MATERIALIZER_SPEC.name] = MATERIALIZER
MATERIALIZER_SPEC.loader.exec_module(MATERIALIZER)


def make_mod(path: Path, mod_id: str, dependencies: list[str] = ()) -> None:
    dependency_text = "".join(
        f"\n[dependencies.{mod_id}.{dependency}]\nmodId=\"{dependency}\"\nmandatory=true\n"
        for dependency in dependencies
    )
    metadata = (
        "modLoader=\"javafml\"\nloaderVersion=\"[4,)\"\n"
        "[[mods]]\nmodId=\"{0}\"\nversion=\"1.0\"\n".format(mod_id)
        + dependency_text
    )
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("META-INF/neoforge.mods.toml", metadata)


class ScalingPlanTest(unittest.TestCase):
    def test_plan_contains_full_single_closure_and_group_variants(self):
        with tempfile.TemporaryDirectory() as raw:
            pack = Path(raw)
            mods = pack / "mods"
            mods.mkdir()
            make_mod(mods / "base.jar", "base")
            make_mod(mods / "feature.jar", "feature", ["base"])
            make_mod(mods / "other.jar", "other")

            plan = PLAN.build_plan(pack, ["ui=feature,other"], ["base"])
            by_id = {variant["id"]: variant for variant in plan["variants"]}
            self.assertEqual(plan["mod_count"], 3)
            self.assertEqual(by_id["mod-feature"]["mod_ids"], ["base", "feature"])
            self.assertEqual(by_id["group-ui"]["mod_ids"], ["base", "feature", "other"])
            self.assertEqual(by_id["baseline"]["artifacts"], ["base.jar"])

    def test_missing_required_dependency_is_reported_not_silently_dropped(self):
        with tempfile.TemporaryDirectory() as raw:
            pack = Path(raw)
            mods = pack / "mods"
            mods.mkdir()
            make_mod(mods / "feature.jar", "feature", ["missing"])

            plan = PLAN.build_plan(pack, [], [])
            variant = next(item for item in plan["variants"] if item["id"] == "mod-feature")
            self.assertEqual(variant["missing_dependencies"], ["missing"])
            self.assertEqual(variant["artifacts"], ["feature.jar"])

    def test_source_pack_rejects_bootoptim_duplicate(self):
        with tempfile.TemporaryDirectory() as raw:
            pack = Path(raw)
            mods = pack / "mods"
            mods.mkdir()
            make_mod(mods / "bootoptim-dev.jar", "boot_optim")
            with self.assertRaises(ValueError):
                PLAN.build_plan(pack, [], [])

    def test_materializer_copies_non_mod_state_and_only_selected_jars(self):
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            pack = root / "source"
            mods = pack / "mods"
            mods.mkdir(parents=True)
            (pack / "config").mkdir()
            (pack / "config" / "example.cfg").write_text("keep", encoding="utf-8")
            make_mod(mods / "base.jar", "base")
            make_mod(mods / "feature.jar", "feature", ["base"])
            (mods / "mcef-libraries").mkdir()
            (mods / "mcef-libraries" / "native.bin").write_bytes(b"native")
            plan = PLAN.build_plan(pack, [], ["base"])
            plan_path = root / "plan.json"
            plan_path.write_text(json.dumps(plan), encoding="utf-8")
            destination = root / "variant"

            manifest = MATERIALIZER.materialize(pack, destination, plan_path, "baseline")

            self.assertEqual(manifest["selected_artifacts"], ["base.jar"])
            self.assertTrue((destination / "config" / "example.cfg").is_file())
            self.assertTrue((destination / "mods" / "mcef-libraries" / "native.bin").is_file())
            self.assertTrue((destination / "mods" / "base.jar").is_file())
            self.assertFalse((destination / "mods" / "feature.jar").exists())
            self.assertTrue((destination / ".bootoptim-scaling-variant.json").is_file())
            self.assertTrue((pack / "mods" / "feature.jar").is_file())


if __name__ == "__main__":
    unittest.main()
