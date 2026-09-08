#!/usr/bin/env python3
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

MODULE = Path(__file__).with_name("analyze_prebootstrap_jfr.py")
spec = importlib.util.spec_from_file_location("analyze_prebootstrap_jfr", MODULE)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


class PreBootstrapJfrTest(unittest.TestCase):
    def test_phase_windows_and_sample_attribution(self):
        records = [
            {"record":"trace_header","trace_origin_epoch_ms":1000},
            {"record":"event","type":"phase_begin","phase":"modlauncher_transformers_to_minecraft_bootstrap","mono_ns":0},
            {"record":"event","type":"phase_begin","phase":"modlauncher_transformers_to_minecraft_bootstrap_transform_accept","mono_ns":0},
            {"record":"event","type":"phase_end","phase":"modlauncher_transformers_to_minecraft_bootstrap_transform_accept","mono_ns":1_000_000_000},
            {"record":"event","type":"phase_begin","phase":"minecraft_bootstrap_transform_accept_to_entry","mono_ns":1_000_000_000},
            {"record":"event","type":"phase_end","phase":"minecraft_bootstrap_transform_accept_to_entry","mono_ns":2_000_000_000},
            {"record":"event","type":"phase_end","phase":"modlauncher_transformers_to_minecraft_bootstrap","mono_ns":2_000_000_000},
        ]
        with tempfile.TemporaryDirectory() as td:
            trace = Path(td) / "trace.jsonl"
            trace.write_text("\n".join(json.dumps(x) for x in records), encoding="utf-8")
            windows = mod.load_phase_windows(trace)
        sample = {
            "recording": {"events": [
                {"type":"jdk.ExecutionSample","values":{
                    "startTime":"1970-01-01T00:00:01.500000000Z",
                    "sampledThread":{"javaName":"main"},
                    "stackTrace":{"frames":[{"method":{"name":"applyMixins","type":{"name":"org.spongepowered.asm.mixin.transformer.MixinProcessor"}}}]},
                }},
                {"type":"jdk.ExecutionSample","values":{
                    "startTime":"1970-01-01T00:00:02.500000000Z",
                    "sampledThread":{"javaName":"pool-8-thread-1"},
                    "stackTrace":{"frames":[{"method":{"name":"transform","type":{"name":"cpw.mods.modlauncher.ClassTransformer"}}}]},
                }},
            ]}}
        }
        result = mod.summarize(sample, windows)
        parent = result["phases"]["modlauncher_transformers_to_minecraft_bootstrap"]
        first = result["phases"]["modlauncher_transformers_to_minecraft_bootstrap_transform_accept"]
        second = result["phases"]["minecraft_bootstrap_transform_accept_to_entry"]
        self.assertEqual(2, parent["execution_samples"])
        self.assertEqual({"mixin": 1}, first["categories"])
        self.assertEqual({"modlauncher": 1}, second["categories"])
        self.assertEqual(2000.0, parent["wall_ms"])

    def test_category_is_conservative(self):
        self.assertEqual("asm", mod.category("org.objectweb.asm.ClassReader.accept"))
        self.assertEqual("jdk_classloading", mod.category("jdk.internal.loader.BuiltinClassLoader.loadClass"))
        self.assertEqual("other", mod.category("example.Mod.callback"))


if __name__ == "__main__":
    unittest.main()
