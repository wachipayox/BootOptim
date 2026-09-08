import importlib.util
import contextlib
import io
import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from types import SimpleNamespace


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

SUMMARY_SCRIPT = Path(__file__).resolve().parents[2] / "scripts/exact-pack/summarize_startup.py"
SUMMARY_SPEC = importlib.util.spec_from_file_location("summarize_startup", SUMMARY_SCRIPT)
SUMMARY = importlib.util.module_from_spec(SUMMARY_SPEC)
assert SUMMARY_SPEC.loader is not None
sys.modules[SUMMARY_SPEC.name] = SUMMARY
SUMMARY_SPEC.loader.exec_module(SUMMARY)


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

    def test_balanced_partitions_are_deterministic_and_preserve_dependency_closure(self):
        with tempfile.TemporaryDirectory() as raw:
            pack = Path(raw)
            mods = pack / "mods"
            mods.mkdir()
            make_mod(mods / "base.jar", "base")
            make_mod(mods / "feature.jar", "feature", ["base"])
            make_mod(mods / "other.jar", "other")
            make_mod(mods / "last.jar", "last")

            plan = PLAN.build_plan(pack, [], [], balanced_partitions=2)
            by_id = {variant["id"]: variant for variant in plan["variants"]}
            self.assertEqual(by_id["partition-1"]["roots"], ["base", "last"])
            self.assertEqual(by_id["partition-2"]["roots"], ["feature", "other"])
            self.assertEqual(by_id["partition-1"]["runnable_roots"], ["base", "last"])
            self.assertEqual(by_id["partition-2"]["runnable_roots"], ["feature", "other"])
            self.assertEqual(by_id["partition-1"]["mod_ids"], ["base", "last"])
            self.assertEqual(by_id["partition-2"]["mod_ids"], ["base", "feature", "other"])
            self.assertEqual(by_id["partition-2"]["missing_dependencies"], [])

    def test_balanced_partition_excludes_only_roots_with_unmaterializable_dependencies(self):
        with tempfile.TemporaryDirectory() as raw:
            pack = Path(raw)
            mods = pack / "mods"
            mods.mkdir()
            make_mod(mods / "clean.jar", "clean")
            make_mod(mods / "broken.jar", "broken", ["not-shipped"])

            plan = PLAN.build_plan(pack, [], [], balanced_partitions=1)
            variant = next(item for item in plan["variants"] if item["id"] == "partition-1")
            self.assertEqual(variant["roots"], ["broken", "clean"])
            self.assertEqual(variant["runnable_roots"], ["clean"])
            self.assertEqual(variant["mod_ids"], ["clean"])
            self.assertEqual(variant["missing_dependencies"], [])
            self.assertEqual(variant["excluded_roots"], [{
                "id": "broken",
                "missing_dependencies": ["not-shipped"],
            }])

    def test_balanced_complements_retain_the_other_pack_block(self):
        with tempfile.TemporaryDirectory() as raw:
            pack = Path(raw)
            mods = pack / "mods"
            mods.mkdir()
            make_mod(mods / "base.jar", "base")
            make_mod(mods / "feature.jar", "feature", ["base"])
            make_mod(mods / "other.jar", "other")
            make_mod(mods / "last.jar", "last")

            plan = PLAN.build_plan(pack, [], [], balanced_partitions=2)
            by_id = {variant["id"]: variant for variant in plan["variants"]}

            # complement-1 removes base/last; feature is explicitly recorded as
            # unmaterializable rather than silently pretending it can run.
            self.assertEqual(by_id["complement-1"]["kind"], "balanced_complement")
            self.assertEqual(by_id["complement-1"]["mod_ids"], ["other"])
            self.assertTrue(any(
                item["id"] == "feature"
                and item["reason"] == "depends_on_excluded_or_missing"
                for item in by_id["complement-1"]["excluded_roots"]
            ))
            # complement-2 removes feature/other, while the remaining base and
            # last form a valid pack closure.
            self.assertEqual(by_id["complement-2"]["mod_ids"], ["base", "last"])
            self.assertEqual(by_id["complement-2"]["artifacts"], ["base.jar", "last.jar"])

    def test_balanced_complements_exclude_runtime_families_together(self):
        with tempfile.TemporaryDirectory() as raw:
            pack = Path(raw)
            mods = pack / "mods"
            mods.mkdir()
            make_mod(mods / "alpha.jar", "alpha")
            make_mod(mods / "beta.jar", "beta")
            make_mod(mods / "other.jar", "other")

            plan = PLAN.build_plan(
                pack,
                [],
                [],
                balanced_partitions=3,
                compatibility_groups=["family=alpha,beta"],
            )
            variant = next(item for item in plan["variants"] if item["id"] == "complement-1")
            excluded = {item["id"] for item in variant["excluded_roots"]}
            self.assertIn("alpha", excluded)
            self.assertIn("beta", excluded)
            self.assertNotIn("alpha", variant["mod_ids"])
            self.assertNotIn("beta", variant["mod_ids"])

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

    def test_platform_dependencies_are_not_reported_as_missing_artifacts(self):
        with tempfile.TemporaryDirectory() as raw:
            pack = Path(raw)
            mods = pack / "mods"
            mods.mkdir()
            make_mod(mods / "feature.jar", "feature", ["minecraft", "neoforge", "missing"])

            plan = PLAN.build_plan(pack, [], [])
            variant = next(item for item in plan["variants"] if item["id"] == "mod-feature")
            self.assertEqual(variant["missing_dependencies"], ["missing"])

    def test_quoted_dependency_table_owner_is_joined_to_mod(self):
        with tempfile.TemporaryDirectory() as raw:
            pack = Path(raw)
            mods = pack / "mods"
            mods.mkdir()
            metadata = (
                'modLoader="javafml"\n'
                '[[mods]]\nmodId="feature"\nversion="1.0"\n'
                '[[dependencies."feature"]]\n'
                'modId="base"\ntype="required"\n'
                '[[dependencies."feature"]]\n'
                'modId="optional"\ntype="optional"\n'
            )
            with zipfile.ZipFile(mods / "feature.jar", "w") as archive:
                archive.writestr("META-INF/neoforge.mods.toml", metadata)

            plan = PLAN.build_plan(pack, [], [])
            variant = next(item for item in plan["variants"] if item["id"] == "mod-feature")
            self.assertEqual(variant["mod_ids"], ["feature"])
            self.assertEqual(variant["missing_dependencies"], ["base"])

    def test_present_optional_dependency_is_kept_in_a_runnable_closure(self):
        with tempfile.TemporaryDirectory() as raw:
            pack = Path(raw)
            mods = pack / "mods"
            mods.mkdir()
            metadata = (
                'modLoader="javafml"\n'
                '[[mods]]\nmodId="feature"\nversion="1.0"\n'
                '[[dependencies.feature]]\nmodId="optional"\ntype="optional"\n'
            )
            with zipfile.ZipFile(mods / "feature.jar", "w") as archive:
                archive.writestr("META-INF/neoforge.mods.toml", metadata)
            make_mod(mods / "optional.jar", "optional")

            plan = PLAN.build_plan(pack, [], [])
            variant = next(item for item in plan["variants"] if item["id"] == "mod-feature")
            self.assertEqual(variant["mod_ids"], ["feature", "optional"])
            feature = next(item for item in plan["mods"] if item["id"] == "feature")
            self.assertEqual(feature["required_dependencies"], [])
            self.assertEqual(feature["optional_dependencies"], ["optional"])

    def test_nested_jarjar_metadata_satisfies_dependency_and_keeps_top_level_artifact(self):
        with tempfile.TemporaryDirectory() as raw:
            pack = Path(raw)
            mods = pack / "mods"
            mods.mkdir()
            outer = io.BytesIO()
            with zipfile.ZipFile(outer, "w") as nested:
                nested.writestr(
                    "META-INF/neoforge.mods.toml",
                    'modLoader="javafml"\n'
                    '[[mods]]\nmodId="embedded"\nversion="1.0"\n',
                )
            with zipfile.ZipFile(mods / "feature.jar", "w") as archive:
                archive.writestr(
                    "META-INF/neoforge.mods.toml",
                    'modLoader="javafml"\n'
                    '[[mods]]\nmodId="feature"\nversion="1.0"\n'
                    '[[dependencies.feature]]\nmodId="embedded"\ntype="required"\n',
                )
                archive.writestr("META-INF/jarjar/embedded.jar", outer.getvalue())

            plan = PLAN.build_plan(pack, [], [], balanced_partitions=1)
            variant = next(item for item in plan["variants"] if item["id"] == "mod-feature")
            self.assertEqual(variant["missing_dependencies"], [])
            self.assertEqual(variant["artifacts"], ["feature.jar"])
            self.assertIn("embedded", variant["mod_ids"])

    def test_selecting_artifact_also_closes_every_co_located_mod(self):
        with tempfile.TemporaryDirectory() as raw:
            pack = Path(raw)
            mods = pack / "mods"
            mods.mkdir()
            metadata = (
                'modLoader="javafml"\n'
                '[[mods]]\nmodId="visible"\nversion="1.0"\n'
                '[[mods]]\nmodId="hidden_helper"\nversion="1.0"\n'
                '[[dependencies.hidden_helper]]\n'
                'modId="helper_runtime"\ntype="required"\n'
            )
            with zipfile.ZipFile(mods / "combined.jar", "w") as archive:
                archive.writestr("META-INF/neoforge.mods.toml", metadata)
            make_mod(mods / "helper.jar", "helper_runtime")

            plan = PLAN.build_plan(pack, [], [])
            variant = next(item for item in plan["variants"] if item["id"] == "mod-visible")
            self.assertEqual(
                variant["mod_ids"],
                ["helper_runtime", "hidden_helper", "visible"],
            )
            self.assertEqual(variant["missing_dependencies"], [])
            self.assertEqual(variant["artifacts"], ["combined.jar", "helper.jar"])

    def test_compatibility_group_keeps_runtime_family_together(self):
        with tempfile.TemporaryDirectory() as raw:
            pack = Path(raw)
            mods = pack / "mods"
            mods.mkdir()
            make_mod(mods / "iris.jar", "iris")
            make_mod(mods / "sodium.jar", "sodium")
            plan = PLAN.build_plan(pack, [], [], compatibility_groups=["render=iris,sodium"])
            variant = next(item for item in plan["variants"] if item["id"] == "mod-iris")
            self.assertEqual(variant["mod_ids"], ["iris", "sodium"])
            self.assertEqual(plan["compatibility_groups"], [{"name": "render", "mod_ids": ["iris", "sodium"]}])

    def test_explicit_partition_exclusion_is_recorded(self):
        with tempfile.TemporaryDirectory() as raw:
            pack = Path(raw)
            mods = pack / "mods"
            mods.mkdir()
            make_mod(mods / "audio.jar", "audio")
            make_mod(mods / "clean.jar", "clean")
            plan = PLAN.build_plan(pack, [], [], balanced_partitions=1, excluded_roots=["audio"])
            variant = next(item for item in plan["variants"] if item["id"] == "partition-1")
            self.assertEqual(variant["runnable_roots"], ["clean"])
            self.assertEqual(variant["excluded_roots"], [{
                "id": "audio",
                "reason": "operator_excluded",
                "missing_dependencies": [],
            }])

    def test_explicit_partition_exclusion_blocks_present_optional_dependency(self):
        with tempfile.TemporaryDirectory() as raw:
            pack = Path(raw)
            mods = pack / "mods"
            mods.mkdir()
            metadata = (
                'modLoader="javafml"\n'
                '[[mods]]\nmodId="feature"\nversion="1.0"\n'
                '[[dependencies.feature]]\nmodId="audio"\ntype="optional"\n'
            )
            with zipfile.ZipFile(mods / "feature.jar", "w") as archive:
                archive.writestr("META-INF/neoforge.mods.toml", metadata)
            make_mod(mods / "audio.jar", "audio")

            plan = PLAN.build_plan(pack, [], [], balanced_partitions=1, excluded_roots=["audio"])
            variant = next(item for item in plan["variants"] if item["id"] == "partition-1")
            self.assertNotIn("audio", variant["mod_ids"])
            self.assertNotIn("audio.jar", variant["artifacts"])
            self.assertEqual(variant["excluded_roots"], [{
                "id": "audio",
                "reason": "operator_excluded",
                "missing_dependencies": [],
            }])

    def test_source_pack_rejects_bootoptim_duplicate(self):
        with tempfile.TemporaryDirectory() as raw:
            pack = Path(raw)
            mods = pack / "mods"
            mods.mkdir()
            make_mod(mods / "bootoptim-dev.jar", "boot_optim")
            with self.assertRaises(ValueError):
                PLAN.build_plan(pack, [], [])

    def test_duplicate_mod_ids_select_all_matching_artifacts(self):
        with tempfile.TemporaryDirectory() as raw:
            pack = Path(raw)
            mods = pack / "mods"
            mods.mkdir(parents=True)
            make_mod(mods / "tfmg-dep.jar", "tfmg")
            make_mod(mods / "tfmg-main.jar", "tfmg")

            plan = PLAN.build_plan(pack, [], [])
            full = next(item for item in plan["variants"] if item["id"] == "full")
            single = next(item for item in plan["variants"] if item["id"] == "mod-tfmg")
            self.assertEqual(full["artifacts"], ["tfmg-dep.jar", "tfmg-main.jar"])
            self.assertEqual(single["artifacts"], ["tfmg-dep.jar", "tfmg-main.jar"])
            self.assertEqual(plan["mods"][0]["artifacts"], ["tfmg-dep.jar", "tfmg-main.jar"])

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
            self.assertEqual(manifest["compatibility_groups"], [])
            self.assertEqual(manifest["explicitly_excluded_roots"], [])
            self.assertTrue((destination / "config" / "example.cfg").is_file())
            self.assertTrue((destination / "mods" / "mcef-libraries" / "native.bin").is_file())
            self.assertTrue((destination / "mods" / "base.jar").is_file())
            self.assertFalse((destination / "mods" / "feature.jar").exists())
            self.assertTrue((destination / ".bootoptim-scaling-variant.json").is_file())
            self.assertTrue((pack / "mods" / "feature.jar").is_file())

    def test_aggregate_marks_scaling_resource_contract_as_diagnostic(self):
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            results = root / "results" / "scaling-mod-feature"
            results.mkdir(parents=True)
            (results / "result.json").write_text(
                json.dumps({
                    "variant": "scaling-mod-feature",
                    "startup_total_ms": 1234,
                    "mod_entrypoint_ms": 400,
                    "post_mod_entrypoint_ms": 834,
                    "mcef_init_ms": None,
                    "reload_to_fancymenu_finish_ms": 700,
                    "fancymenu_panorama_ms": None,
                    "bootoptim_mixin_errors": 0,
                    "decocraft_status": None,
                    "decocraft_models_remapped": None,
                    "decocraft_atlas_removed": None,
                    "blocks_atlas_width": None,
                    "blocks_atlas_height": None,
                    "blocks_atlas_levels": None,
                    "resource_contract_valid": False,
                    "diagnostic_only": True,
                }),
                encoding="utf-8",
            )
            markdown = root / "summary.md"
            summary_json = root / "summary.json"
            with contextlib.redirect_stdout(io.StringIO()):
                SUMMARY.parse_aggregate(SimpleNamespace(
                    results_dir=str(root / "results"),
                    output=str(markdown),
                    json_output=str(summary_json),
                ))
            summary = json.loads(summary_json.read_text(encoding="utf-8"))
            row = summary["scaling-mod-feature"]
            self.assertEqual(row["resource_contract_invalid_runs"], 1)
            self.assertEqual(row["diagnostic_only_runs"], 1)
            self.assertIn("resource-invalid", markdown.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
