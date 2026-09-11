import importlib.util
import io
import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/exact-pack/plan_scaling.py"
SPEC = importlib.util.spec_from_file_location("artifact_safe_plan_scaling", SCRIPT)
PLAN = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = PLAN
SPEC.loader.exec_module(PLAN)

MATERIALIZER_SCRIPT = ROOT / "scripts/exact-pack/materialize_scaling_variant.py"
MSPEC = importlib.util.spec_from_file_location("artifact_safe_materializer", MATERIALIZER_SCRIPT)
MATERIALIZER = importlib.util.module_from_spec(MSPEC)
assert MSPEC.loader is not None
sys.modules[MSPEC.name] = MATERIALIZER
MSPEC.loader.exec_module(MATERIALIZER)


def make_multi_id_mod(path: Path, mod_ids: list[str], dependencies: dict[str, list[str]] | None = None):
    chunks = ['modLoader="javafml"', 'loaderVersion="[4,)"']
    for mod_id in mod_ids:
        chunks.extend(["[[mods]]", f'modId="{mod_id}"', 'version="1.0"'])
    for owner, values in (dependencies or {}).items():
        for dependency in values:
            chunks.extend([
                f"[[dependencies.{owner}]]",
                f'modId="{dependency}"',
                'type="required"',
            ])
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("META-INF/neoforge.mods.toml", "\n".join(chunks) + "\n")


def make_create_like(path: Path):
    nested_payloads = []
    for nested_id in ("flywheel", "ponder"):
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w") as nested:
            nested.writestr(
                "META-INF/neoforge.mods.toml",
                f'modLoader="javafml"\n[[mods]]\nmodId="{nested_id}"\nversion="1.0"\n',
            )
        nested_payloads.append((nested_id, buffer.getvalue()))
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr(
            "META-INF/neoforge.mods.toml",
            'modLoader="javafml"\n[[mods]]\nmodId="create"\nversion="1.0"\n',
        )
        for nested_id, payload in nested_payloads:
            archive.writestr(f"META-INF/jarjar/{nested_id}.jar", payload)


class ArtifactUnitScalingTest(unittest.TestCase):
    def test_fapi_like_51_ids_are_one_physical_partition_unit(self):
        with tempfile.TemporaryDirectory() as raw:
            pack = Path(raw)
            mods = pack / "mods"
            mods.mkdir()
            fapi_ids = ["fabric_api"] + [f"fabric_module_{index:02d}" for index in range(50)]
            make_multi_id_mod(mods / "a-forgified-fabric-api.jar", fapi_ids)
            make_create_like(mods / "b-create.jar")
            make_multi_id_mod(mods / "c-other.jar", ["other"])

            plan = PLAN.build_plan(pack, [], [], balanced_partitions=2)
            self.assertEqual(plan["selection_unit"], "top_level_physical_artifact")
            self.assertEqual(plan["partition_validation"]["shared_artifacts"], [])
            partitions = [
                variant for variant in plan["variants"]
                if variant["kind"] == "balanced_partition"
            ]
            owning = [
                variant for variant in partitions
                if "a-forgified-fabric-api.jar" in variant["assigned_artifacts"]
            ]
            self.assertEqual(len(owning), 1)
            self.assertTrue(set(fapi_ids).issubset(set(owning[0]["roots"])))
            other = next(variant for variant in partitions if variant is not owning[0])
            self.assertFalse(set(fapi_ids).intersection(other["roots"]))

    def test_create_nested_ids_cannot_be_split_from_top_level_create_artifact(self):
        with tempfile.TemporaryDirectory() as raw:
            pack = Path(raw)
            mods = pack / "mods"
            mods.mkdir()
            make_multi_id_mod(mods / "a-base.jar", ["base"])
            make_create_like(mods / "b-create.jar")
            make_multi_id_mod(mods / "c-other.jar", ["other"])

            plan = PLAN.build_plan(pack, [], [], balanced_partitions=2)
            unit = next(item for item in plan["artifact_units"] if "b-create.jar" in item["artifacts"])
            self.assertEqual(unit["artifacts"], ["b-create.jar"])
            self.assertEqual(unit["mod_ids"], ["create", "flywheel", "ponder"])
            partition = next(
                item for item in plan["variants"]
                if item.get("kind") == "balanced_partition"
                and "b-create.jar" in item.get("assigned_artifacts", [])
            )
            self.assertTrue({"create", "flywheel", "ponder"}.issubset(partition["roots"]))

    def test_custom_complement_expands_one_fapi_id_to_entire_physical_artifact(self):
        with tempfile.TemporaryDirectory() as raw:
            pack = Path(raw)
            mods = pack / "mods"
            mods.mkdir()
            fapi_ids = ["fabric_api"] + [f"fabric_module_{index:02d}" for index in range(50)]
            make_multi_id_mod(mods / "forgified-fabric-api.jar", fapi_ids)
            make_multi_id_mod(mods / "consumer.jar", ["consumer"], {"consumer": ["fabric_module_17"]})
            make_multi_id_mod(mods / "independent.jar", ["independent"])

            plan = PLAN.build_plan(pack, [], [], complement_groups=["fapi=fabric_module_00"])
            variant = next(item for item in plan["variants"] if item["id"] == "complement-group-fapi")
            self.assertEqual(variant["assigned_artifacts"], ["forgified-fabric-api.jar"])
            self.assertNotIn("consumer.jar", variant["artifacts"])
            self.assertIn("independent.jar", variant["artifacts"])
            decisions = {item["artifact"]: item for item in variant["artifact_decisions"]}
            self.assertEqual(decisions["forgified-fabric-api.jar"]["reason"], "partition_unit_exclusion")
            self.assertEqual(decisions["consumer.jar"]["reason"], "closure_depends_on_excluded_or_missing")

    def test_compatibility_family_is_collapsed_before_partitioning(self):
        with tempfile.TemporaryDirectory() as raw:
            pack = Path(raw)
            mods = pack / "mods"
            mods.mkdir()
            make_create_like(mods / "create.jar")
            make_multi_id_mod(mods / "bits.jar", ["bits_n_bobs"])
            make_multi_id_mod(mods / "other.jar", ["other"])

            plan = PLAN.build_plan(
                pack, [], [], balanced_partitions=2,
                compatibility_groups=["create-bundle=create,flywheel,ponder,bits_n_bobs"],
            )
            unit = next(item for item in plan["artifact_units"] if "create.jar" in item["artifacts"])
            self.assertEqual(unit["artifacts"], ["bits.jar", "create.jar"])
            partitions = [item for item in plan["variants"] if item["kind"] == "balanced_partition"]
            self.assertEqual(sum("create.jar" in item["assigned_artifacts"] for item in partitions), 1)
            self.assertEqual(sum("bits.jar" in item["assigned_artifacts"] for item in partitions), 1)
            owner_create = next(item for item in partitions if "create.jar" in item["assigned_artifacts"])
            self.assertIn("bits.jar", owner_create["assigned_artifacts"])

    def test_shared_physical_artifact_assignment_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "shares physical artifacts"):
            PLAN.validate_disjoint_partition_assignments([
                ("arm-a", ["fapi.jar", "other.jar"]),
                ("arm-b", ["fapi.jar", "create.jar"]),
            ])

    def test_materializer_manifest_covers_every_artifact_and_rechecks_fingerprint(self):
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            pack = root / "source"
            mods = pack / "mods"
            mods.mkdir(parents=True)
            make_multi_id_mod(mods / "a.jar", ["a1", "a2"])
            make_multi_id_mod(mods / "b.jar", ["b"])
            plan = PLAN.build_plan(pack, [], [], balanced_partitions=2)
            plan_path = root / "plan.json"
            plan_path.write_text(json.dumps(plan), encoding="utf-8")
            destination = root / "variant"
            manifest = MATERIALIZER.materialize(pack, destination, plan_path, "complement-1")
            self.assertEqual(manifest["selection_unit"], "top_level_physical_artifact")
            self.assertEqual(manifest["source_pack_fingerprint"], plan["pack_fingerprint"])
            self.assertEqual(manifest["artifact_unit_fingerprint"], plan["artifact_unit_fingerprint"])
            self.assertEqual(
                {item["artifact"] for item in manifest["artifact_decisions"]},
                {"a.jar", "b.jar"},
            )
            self.assertEqual(
                set(manifest["selected_artifacts"]) | set(manifest["excluded_artifacts"]),
                {"a.jar", "b.jar"},
            )

            # A stale plan must never materialize against a changed source.
            with zipfile.ZipFile(mods / "b.jar", "a") as archive:
                archive.writestr("changed.txt", "changed")
            with self.assertRaisesRegex(ValueError, "fingerprint changed"):
                MATERIALIZER.materialize(pack, root / "stale", plan_path, "complement-1")


if __name__ == "__main__":
    unittest.main()
