#!/usr/bin/env python3
import importlib.util
import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

SCRIPT = Path(__file__).with_name("migrate_glowing_trim_custom_name.py")
spec = importlib.util.spec_from_file_location("glowing_trim_migration", SCRIPT)
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
assert spec.loader is not None
spec.loader.exec_module(module)


class GlowingTrimMigrationTests(unittest.TestCase):
    def test_regex_iregex_escape_and_crlf_are_byte_preserved(self):
        original = (
            b"# untouched\r\n"
            b"  nbt.display.Name =iregex:^Trim\\u00A7[A-Z]+$  \r\n"
        )
        migrated, records = module.migrate_payload(original, "a.properties")
        self.assertEqual(len(records), 1)
        self.assertEqual(records[0]["matcher"], "iregex")
        self.assertIn(
            b"  components.minecraft\\:custom_name =iregex:^Trim\\u00A7[A-Z]+$  \r\n",
            migrated,
        )
        self.assertEqual(module.inverse_payload(migrated, "a.properties"), original)

    def test_pattern_continuation_bytes_are_preserved(self):
        original = b"nbt.display.Name=pattern:*Amethyst?\\\n  Trim\n"
        migrated, records = module.migrate_payload(original, "a.properties")
        self.assertEqual(len(records), 1)
        self.assertEqual(records[0]["matcher"], "pattern")
        self.assertEqual(
            migrated,
            b"components.minecraft\\:custom_name=pattern:*Amethyst?\\\n  Trim\n",
        )
        self.assertEqual(module.inverse_payload(migrated, "a.properties"), original)

    def test_unaudited_separator_forms_are_not_broadened(self):
        colon = b"nbt.display.Name:regex:^A$\n"
        whitespace = b"nbt.display.Name regex:^A$\n"
        self.assertEqual(module.migrate_payload(colon, "a.properties"), (colon, []))
        self.assertEqual(module.migrate_payload(whitespace, "a.properties"), (whitespace, []))

    def test_semantic_key_decodes_escaped_namespace_colon(self):
        audit = module.audit_payload(b"components.minecraft\\:custom_name=value\n")
        self.assertEqual(len(audit["modern"]), 1)
        self.assertEqual(audit["modern"][0].semantic_key, module.MODERN_KEY)

    def test_modern_collision_is_rejected(self):
        original = (
            b"nbt.display.Name=one\n"
            b"components.minecraft\\:custom_name=two\n"
        )
        with self.assertRaisesRegex(ValueError, "collision"):
            module.migrate_payload(original, "a.properties")

    def test_suffix_form_is_rejected_for_this_hash_contract(self):
        original = b"nbt.display.Name.foo=value\n"
        with self.assertRaisesRegex(ValueError, "suffix"):
            module.migrate_payload(original, "a.properties")

    def test_comments_do_not_count(self):
        audit = module.audit_payload(
            b"# nbt.display.Name=x\n!nbt.display.Name=y\nnbt.display.Name=z\n"
        )
        self.assertEqual(len(audit["legacy"]), 1)

    def test_direct_utf8_section_sign_rhs_is_untouched(self):
        original = "nbt.display.Name=Élite §6Trim\n".encode("utf-8")
        migrated, records = module.migrate_payload(original, "a.properties")
        self.assertEqual(records[0]["matcher"], "direct")
        self.assertTrue(records[0]["rhs_has_section_sign"])
        self.assertEqual(
            migrated.split(b"=", 1)[1],
            original.split(b"=", 1)[1],
        )

    def test_synthetic_zip_preserves_metadata_backup_manifest_and_inverse(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            source = root / module.PACK_NAME
            prop = zipfile.ZipInfo("assets/minecraft/optifine/cit/a.properties", (2025, 1, 2, 3, 4, 6))
            prop.compress_type = zipfile.ZIP_DEFLATED
            prop.external_attr = 0o100644 << 16
            prop.comment = b"entry-comment"
            binary = zipfile.ZipInfo("assets/example.bin", (2024, 2, 2, 2, 2, 2))
            binary.compress_type = zipfile.ZIP_STORED
            with zipfile.ZipFile(source, "w") as zf:
                zf.comment = b"archive-comment"
                zf.writestr(prop, b"type=item\nnbt.display.Name=regex:^A.*$\ntexture=x\n")
                zf.writestr(binary, b"\x00\xffpayload")

            saved = (
                module.SOURCE_SHA256,
                module.SOURCE_SIZE,
                module.SOURCE_ENTRIES,
                module.SOURCE_PROPERTIES,
                module.SOURCE_LEGACY_RULES,
                module.SOURCE_LEGACY_FILES,
                module.SOURCE_MODERN_RULES,
            )
            try:
                module.SOURCE_SHA256 = module.sha256_file(source)
                module.SOURCE_SIZE = source.stat().st_size
                module.SOURCE_ENTRIES = 2
                module.SOURCE_PROPERTIES = 1
                module.SOURCE_LEGACY_RULES = 1
                module.SOURCE_LEGACY_FILES = 1
                module.SOURCE_MODERN_RULES = 0
                output = root / "candidate.zip"
                backup = root / "source.backup.zip"
                manifest = root / "manifest.json"
                report = module.build_candidate(source, output, backup, manifest)
            finally:
                (
                    module.SOURCE_SHA256,
                    module.SOURCE_SIZE,
                    module.SOURCE_ENTRIES,
                    module.SOURCE_PROPERTIES,
                    module.SOURCE_LEGACY_RULES,
                    module.SOURCE_LEGACY_FILES,
                    module.SOURCE_MODERN_RULES,
                ) = saved

            self.assertEqual(report["output"]["changed_rules"], 1)
            self.assertEqual(report["output"]["legacy_rules"], 0)
            self.assertEqual(report["output"]["modern_rules"], 1)
            self.assertEqual(module.sha256_file(backup), report["backup"]["sha256"])
            self.assertTrue(manifest.exists())
            persisted = json.loads(manifest.read_text(encoding="utf-8"))
            self.assertEqual(persisted["rules"][0]["matcher"], "regex")
            with zipfile.ZipFile(source) as before, zipfile.ZipFile(output) as after:
                self.assertEqual(before.comment, after.comment)
                self.assertEqual(before.read("assets/example.bin"), after.read("assets/example.bin"))
                self.assertEqual(
                    module.inverse_payload(after.read(prop.filename), prop.filename),
                    before.read(prop.filename),
                )
                for left, right in zip(before.infolist(), after.infolist()):
                    self.assertEqual(left.filename, right.filename)
                    self.assertEqual(left.date_time, right.date_time)
                    self.assertEqual(left.compress_type, right.compress_type)
                    self.assertEqual(left.comment, right.comment)
                    self.assertEqual(left.extra, right.extra)
                    self.assertEqual(left.external_attr, right.external_attr)


if __name__ == "__main__":
    unittest.main(verbosity=2)
