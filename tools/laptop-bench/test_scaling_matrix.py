import tempfile
import unittest
from pathlib import Path

import scaling_matrix_legacy_cases as legacy


class ScalingPlanTest(legacy.ScalingPlanTest):
    """Legacy #249 coverage with artifact-unit contract expectations."""

    def test_balanced_partitions_are_deterministic_and_preserve_dependency_closure(self):
        with tempfile.TemporaryDirectory() as raw:
            pack = Path(raw)
            mods = pack / "mods"
            mods.mkdir()
            legacy.make_mod(mods / "base.jar", "base")
            legacy.make_mod(mods / "feature.jar", "feature", ["base"])
            legacy.make_mod(mods / "other.jar", "other")
            legacy.make_mod(mods / "last.jar", "last")

            plan = legacy.PLAN.build_plan(pack, [], [], balanced_partitions=2)
            by_id = {variant["id"]: variant for variant in plan["variants"]}
            self.assertEqual(by_id["partition-1"]["roots"], ["base", "last"])
            self.assertEqual(by_id["partition-2"]["roots"], ["feature", "other"])
            self.assertEqual(by_id["partition-1"]["runnable_roots"], ["base", "last"])
            self.assertEqual(by_id["partition-2"]["runnable_roots"], ["other"])
            self.assertEqual(by_id["partition-1"]["artifacts"], ["base.jar", "last.jar"])
            self.assertEqual(by_id["partition-2"]["artifacts"], ["other.jar"])
            self.assertEqual(plan["partition_validation"]["shared_artifacts"], [])

    def test_balanced_partition_excludes_only_roots_with_unmaterializable_dependencies(self):
        with tempfile.TemporaryDirectory() as raw:
            pack = Path(raw)
            mods = pack / "mods"
            mods.mkdir()
            legacy.make_mod(mods / "clean.jar", "clean")
            legacy.make_mod(mods / "broken.jar", "broken", ["not-shipped"])

            plan = legacy.PLAN.build_plan(pack, [], [], balanced_partitions=1)
            variant = next(item for item in plan["variants"] if item["id"] == "partition-1")
            self.assertEqual(variant["roots"], ["broken", "clean"])
            self.assertEqual(variant["runnable_roots"], ["clean"])
            self.assertEqual(variant["artifacts"], ["clean.jar"])
            self.assertEqual(variant["physical_closure_removed_artifacts"], ["broken.jar"])

    def test_balanced_complements_retain_the_other_pack_block(self):
        with tempfile.TemporaryDirectory() as raw:
            pack = Path(raw)
            mods = pack / "mods"
            mods.mkdir()
            legacy.make_mod(mods / "base.jar", "base")
            legacy.make_mod(mods / "feature.jar", "feature", ["base"])
            legacy.make_mod(mods / "other.jar", "other")
            legacy.make_mod(mods / "last.jar", "last")

            plan = legacy.PLAN.build_plan(pack, [], [], balanced_partitions=2)
            by_id = {variant["id"]: variant for variant in plan["variants"]}
            self.assertEqual(by_id["complement-1"]["artifacts"], ["other.jar"])
            decisions = {item["artifact"]: item for item in by_id["complement-1"]["artifact_decisions"]}
            self.assertEqual(decisions["feature.jar"]["reason"], "closure_depends_on_excluded_or_missing")
            self.assertEqual(by_id["complement-2"]["artifacts"], ["base.jar", "last.jar"])
            self.assertEqual(plan["partition_validation"]["shared_artifacts"], [])

    def test_custom_complement_removes_named_block_but_keeps_closed_remainder(self):
        with tempfile.TemporaryDirectory() as raw:
            pack = Path(raw)
            mods = pack / "mods"
            mods.mkdir()
            legacy.make_mod(mods / "base.jar", "base")
            legacy.make_mod(mods / "feature.jar", "feature", ["base"])
            legacy.make_mod(mods / "other.jar", "other")

            plan = legacy.PLAN.build_plan(pack, [], [], complement_groups=["remove-base=base"])
            variant = next(item for item in plan["variants"] if item["id"] == "complement-group-remove-base")
            self.assertEqual(variant["artifacts"], ["other.jar"])
            decisions = {item["artifact"]: item for item in variant["artifact_decisions"]}
            self.assertEqual(decisions["base.jar"]["reason"], "partition_unit_exclusion")
            self.assertEqual(decisions["feature.jar"]["reason"], "closure_depends_on_excluded_or_missing")

    def test_balanced_complements_exclude_runtime_families_together(self):
        with tempfile.TemporaryDirectory() as raw:
            pack = Path(raw)
            mods = pack / "mods"
            mods.mkdir()
            legacy.make_mod(mods / "alpha.jar", "alpha")
            legacy.make_mod(mods / "beta.jar", "beta")
            legacy.make_mod(mods / "other.jar", "other")

            plan = legacy.PLAN.build_plan(
                pack, [], [], balanced_partitions=2,
                compatibility_groups=["family=alpha,beta"],
            )
            units = [set(item["artifacts"]) for item in plan["artifact_units"]]
            self.assertIn({"alpha.jar", "beta.jar"}, units)
            owners = [
                variant for variant in plan["variants"]
                if variant.get("kind") == "balanced_partition"
                and "alpha.jar" in variant.get("assigned_artifacts", [])
            ]
            self.assertEqual(len(owners), 1)
            self.assertIn("beta.jar", owners[0]["assigned_artifacts"])

    def test_explicit_partition_exclusion_is_recorded(self):
        with tempfile.TemporaryDirectory() as raw:
            pack = Path(raw)
            mods = pack / "mods"
            mods.mkdir()
            legacy.make_mod(mods / "audio.jar", "audio")
            legacy.make_mod(mods / "clean.jar", "clean")
            plan = legacy.PLAN.build_plan(pack, [], [], balanced_partitions=1, excluded_roots=["audio"])
            variant = next(item for item in plan["variants"] if item["id"] == "partition-1")
            self.assertEqual(variant["runnable_roots"], ["clean"])
            self.assertNotIn("audio.jar", variant["artifacts"])
            complement = next(item for item in plan["variants"] if item["id"] == "complement-1")
            decisions = {item["artifact"]: item for item in complement["artifact_decisions"]}
            self.assertEqual(decisions["audio.jar"]["reason"], "operator_exclusion")

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
            import zipfile
            with zipfile.ZipFile(mods / "feature.jar", "w") as archive:
                archive.writestr("META-INF/neoforge.mods.toml", metadata)
            legacy.make_mod(mods / "audio.jar", "audio")

            plan = legacy.PLAN.build_plan(pack, [], [], balanced_partitions=1, excluded_roots=["audio"])
            variant = next(item for item in plan["variants"] if item["id"] == "partition-1")
            self.assertNotIn("audio", variant["mod_ids"])
            self.assertNotIn("audio.jar", variant["artifacts"])


if __name__ == "__main__":
    unittest.main()
