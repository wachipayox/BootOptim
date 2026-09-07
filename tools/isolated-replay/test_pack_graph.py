import json
import tempfile
import unittest
import zipfile
from pathlib import Path

from pack_graph import build_fixture


class PackGraphTests(unittest.TestCase):
    def test_builds_parent_and_blockstate_edges_from_pack_and_zip(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "assets" / "demo" / "models" / "block").mkdir(parents=True)
            (root / "assets" / "demo" / "blockstates").mkdir(parents=True)
            (root / "assets" / "demo" / "models" / "block" / "parent.json").write_text(
                json.dumps({"textures": {"all": "demo:block/a"}}), encoding="utf-8"
            )
            (root / "assets" / "demo" / "models" / "block" / "child.json").write_text(
                json.dumps({"parent": "demo:block/parent", "elements": [{"from": [0, 0, 0]}]}), encoding="utf-8"
            )
            (root / "assets" / "demo" / "blockstates" / "thing.json").write_text(
                json.dumps({"variants": {"": {"model": "demo:block/child"}}}), encoding="utf-8"
            )
            (root / "mods").mkdir()
            with zipfile.ZipFile(root / "mods" / "extra.jar", "w") as archive:
                archive.writestr(
                    "assets/demo/models/item/from_zip.json",
                    json.dumps({"parent": "demo:block/parent"}),
                )
            fixture = build_fixture(root)
            tasks = {task["id"]: task for task in fixture["tasks"]}
            self.assertEqual(fixture["metadata"]["models"], 3)
            self.assertEqual(fixture["metadata"]["blockstates"], 1)
            self.assertIn("model:demo:block/parent", tasks["model:demo:block/child"]["depends_on"])
            self.assertIn("model:demo:block/child", tasks["blockstate:demo:thing"]["depends_on"])


if __name__ == "__main__":
    unittest.main()
