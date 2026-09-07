import importlib.util
import json
import tempfile
import unittest
import zipfile
from pathlib import Path

MODULE_PATH = Path(__file__).with_name("prepare_fixture.py")
spec = importlib.util.spec_from_file_location("prepare_fixture", MODULE_PATH)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class FixtureTests(unittest.TestCase):
    def write_pack(self, path: Path, model_text: str):
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("assets/test/models/shared.json", model_text)

    def test_options_order_last_selected_pack_wins_and_shadow_is_recorded(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "resourcepacks").mkdir()
            self.write_pack(root / "resourcepacks" / "low.zip", '{"textures":{"x":"test:low"}}')
            self.write_pack(root / "resourcepacks" / "high.zip", '{"textures":{"x":"test:high"}}')
            (root / "options.txt").write_text('resourcePacks:["file/low.zip","file/high.zip"]\n', encoding="utf-8")
            out = root / "out"
            manifest = module.build(root, out, 4, False)
            entry = next(item for item in manifest["entries"] if item["id"] == "test:shared")
            self.assertEqual("external-pack:high.zip", entry["winner"]["source_id"])
            self.assertEqual(["external-pack:low.zip"], [item["source_id"] for item in entry["shadowed"]])
            content = json.loads((out / entry["content_path"]).read_text())
            self.assertEqual("test:high", content["textures"]["x"])

    def test_disabled_external_pack_does_not_participate(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "resourcepacks").mkdir()
            self.write_pack(root / "resourcepacks" / "enabled.zip", '{"parent":"minecraft:block/cube_all"}')
            self.write_pack(root / "resourcepacks" / "disabled.zip", '{"parent":"minecraft:block/air"}')
            (root / "options.txt").write_text('resourcePacks:["file/enabled.zip"]\n', encoding="utf-8")
            manifest = module.build(root, root / "out", 4, False)
            entry = next(item for item in manifest["entries"] if item["id"] == "test:shared")
            self.assertEqual("external-pack:enabled.zip", entry["winner"]["source_id"])
            self.assertNotIn("disabled.zip", json.dumps(manifest))

    def test_parent_closure_is_materialized_without_resolving_it(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "resourcepacks").mkdir()
            with zipfile.ZipFile(root / "resourcepacks" / "one.zip", "w") as archive:
                archive.writestr("assets/test/models/child.json", '{"parent":"test:base"}')
                archive.writestr("assets/test/models/base.json", '{"textures":{"all":"test:block/x"}}')
            (root / "options.txt").write_text('resourcePacks:["file/one.zip"]\n', encoding="utf-8")
            manifest = module.build(root, root / "out", 1, False)
            ids = {entry["id"] for entry in manifest["entries"]}
            self.assertIn("test:child", ids)
            self.assertIn("test:base", ids)


if __name__ == "__main__":
    unittest.main()
