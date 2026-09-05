#!/usr/bin/env python3
import importlib.util
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

SCRIPT = Path(__file__).with_name("migrate_cit_resource_pack.py")
spec = importlib.util.spec_from_file_location("cit_migration", SCRIPT)
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
assert spec.loader is not None
spec.loader.exec_module(module)


class PropertyMigrationTests(unittest.TestCase):
    def test_rhs_and_line_endings_are_byte_exact(self):
        original = (
            b"# comment with nbt.display.Name=ignored\r\n"
            b"  nbt.display.Name =iregex:^Trim\\u00A7[A-Z]+$  \r\n"
            b"texture=armor\\layer\r\n"
        )
        migrated, count, _ = module.migrate_payload(original)
        self.assertEqual(count, 1)
        self.assertIn(
            b"  components.minecraft\\:custom_name =iregex:^Trim\\u00A7[A-Z]+$  \r\n",
            migrated,
        )
        self.assertEqual(module.reverse_payload(migrated), original)

    def test_continuation_and_pattern_are_preserved(self):
        original = b"nbt.display.Name=pattern:*Amethyst?\\\n  Trim\n"
        migrated, count, _ = module.migrate_payload(original)
        self.assertEqual(count, 1)
        self.assertEqual(
            migrated,
            b"components.minecraft\\:custom_name=pattern:*Amethyst?\\\n  Trim\n",
        )
        self.assertEqual(module.reverse_payload(migrated), original)

    def test_unicode_payload_is_untouched(self):
        original = "nbt.display.Name=Élite 鎧 armor\n".encode("utf-8")
        migrated, count, _ = module.migrate_payload(original)
        self.assertEqual(count, 1)
        self.assertEqual(
            migrated.split(b"=", 1)[1],
            original.split(b"=", 1)[1],
        )

    def test_modern_collision_is_rejected(self):
        original = (
            b"nbt.display.Name=one\n"
            b"components.minecraft\\:custom_name=two\n"
        )
        with self.assertRaisesRegex(ValueError, "collision"):
            module.migrate_payload(original)

    def test_comments_do_not_count(self):
        audit = module.audit_payload(
            b"# nbt.display.Name=x\n!nbt.display.Name=y\nnbt.display.Name=z\n"
        )
        self.assertEqual(len(audit["legacy"]), 1)

    def test_repack_control_and_candidate_share_reconstruction_digest(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            source = root / module.PACK_NAME
            info_prop = zipfile.ZipInfo("assets/minecraft/optifine/cit/a.properties", (2025, 1, 2, 3, 4, 6))
            info_prop.compress_type = zipfile.ZIP_DEFLATED
            info_prop.external_attr = 0o100644 << 16
            info_prop.comment = b"entry-comment"
            info_bin = zipfile.ZipInfo("assets/example.bin", (2024, 2, 2, 2, 2, 2))
            info_bin.compress_type = zipfile.ZIP_STORED
            with zipfile.ZipFile(source, "w") as archive:
                archive.comment = b"archive-comment"
                archive.writestr(info_prop, b"type=item\nnbt.display.Name=regex:^A.*$\ntexture=x\n")
                archive.writestr(info_bin, b"\x00\xffpayload")

            original_constants = (
                module.EXPECTED_SHA256,
                module.EXPECTED_SIZE,
                module.EXPECTED_ENTRIES,
                module.EXPECTED_PROPERTIES,
                module.EXPECTED_LEGACY_KEYS,
                module.EXPECTED_LEGACY_FILES,
            )
            try:
                module.EXPECTED_SHA256 = module.sha256_file(source)
                module.EXPECTED_SIZE = source.stat().st_size
                module.EXPECTED_ENTRIES = 2
                module.EXPECTED_PROPERTIES = 1
                module.EXPECTED_LEGACY_KEYS = 1
                module.EXPECTED_LEGACY_FILES = 1
                control = module.build_variant(source, root / "control.zip", "control")
                candidate = module.build_variant(source, root / "candidate.zip", "candidate")
            finally:
                (
                    module.EXPECTED_SHA256,
                    module.EXPECTED_SIZE,
                    module.EXPECTED_ENTRIES,
                    module.EXPECTED_PROPERTIES,
                    module.EXPECTED_LEGACY_KEYS,
                    module.EXPECTED_LEGACY_FILES,
                ) = original_constants

            self.assertEqual(
                control["source"]["ordered_payload_sha256"],
                candidate["output"]["reconstructed_ordered_payload_sha256"],
            )
            self.assertEqual(control["output"]["legacy_keys"], 1)
            self.assertEqual(control["output"]["modern_keys"], 0)
            self.assertEqual(candidate["output"]["legacy_keys"], 0)
            self.assertEqual(candidate["output"]["modern_keys"], 1)
            with zipfile.ZipFile(root / "control.zip") as control_zip, zipfile.ZipFile(root / "candidate.zip") as candidate_zip:
                self.assertEqual(control_zip.read("assets/example.bin"), candidate_zip.read("assets/example.bin"))
                self.assertEqual(control_zip.comment, candidate_zip.comment)


if __name__ == "__main__":
    unittest.main(verbosity=2)
