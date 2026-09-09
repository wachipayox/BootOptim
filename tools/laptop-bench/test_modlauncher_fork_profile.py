import importlib.util
import unittest
from pathlib import Path


PARSER_PATH = Path(__file__).resolve().parents[1] / "modlauncher-fork-probe" / "parse_profile.py"
SPEC = importlib.util.spec_from_file_location("bootoptim_modlauncher_profile", PARSER_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


def valid_log() -> str:
    return "\n".join([
        "BOOTOPTIM_ML_FORK_IDENTITY probe=agent94-post-accept-v2 module=cpw.mods.modlauncher named=true source=fork implementation=11.0.5 loader=TRANSFORMER",
        "BOOTOPTIM_ML_FORK_REQUEST probe=agent94-post-accept-v2 request_id=1 class=net.minecraft.server.Bootstrap origin=securejar_get_maybe_transformed_bytes raw_context=mixin effective_reason=mixin thread=main loader_class=cpw.mods.modlauncher.TransformingClassLoader loader_name=TRANSFORMER loader_id=17 target_module=minecraft tccl_class=cpw.mods.modlauncher.TransformingClassLoader tccl_name=TRANSFORMER tccl_id=17 caller=org.spongepowered.asm.launch.MixinLaunchPluginLegacy#getClassNode caller_module=org.spongepowered.mixin caller_source=mixin.jar stack=mixin",
        "BOOTOPTIM_ML_FORK probe=agent94-post-accept-v2 request_id=1 class=net.minecraft.server.Bootstrap stage=class_transform_begin mono_ns=100 thread=main",
        "BOOTOPTIM_ML_FORK probe=agent94-post-accept-v2 request_id=1 class=net.minecraft.server.Bootstrap stage=transformer owner=boot_optim labels=[boot_optim_agent94_bootstrap_profile] start_ns=110 end_ns=130 elapsed_ns=20 thread=main",
        "BOOTOPTIM_BOOTSTRAP_FORK_BOUNDARY event=transform_accept mono_ns=120 thread=main",
        "BOOTOPTIM_ML_FORK probe=agent94-post-accept-v2 request_id=1 class=net.minecraft.server.Bootstrap stage=plugins_after start_ns=131 end_ns=140 elapsed_ns=9 thread=main",
        "BOOTOPTIM_ML_FORK probe=agent94-post-accept-v2 request_id=1 class=net.minecraft.server.Bootstrap stage=writer_create start_ns=141 end_ns=150 elapsed_ns=9 thread=main",
        "BOOTOPTIM_ML_FORK probe=agent94-post-accept-v2 request_id=1 class=net.minecraft.server.Bootstrap stage=writer_accept start_ns=151 end_ns=170 elapsed_ns=19 thread=main",
        "BOOTOPTIM_ML_FORK probe=agent94-post-accept-v2 request_id=1 class=net.minecraft.server.Bootstrap stage=writer_to_bytes start_ns=171 end_ns=190 elapsed_ns=19 thread=main",
        "BOOTOPTIM_ML_FORK probe=agent94-post-accept-v2 request_id=1 class=net.minecraft.server.Bootstrap stage=class_transform_return mono_ns=200 thread=main",
        "BOOTOPTIM_ML_FORK_REQUEST_END probe=agent94-post-accept-v2 request_id=1 observed_id=1 class=net.minecraft.server.Bootstrap mono_ns=210 thread=main",
        "BOOTOPTIM_ML_FORK_REQUEST probe=agent94-post-accept-v2 request_id=2 class=net.minecraft.server.Bootstrap origin=securejar_reader_to_class raw_context=null effective_reason=classloading thread=pool-8-thread-1 loader_class=cpw.mods.modlauncher.TransformingClassLoader loader_name=TRANSFORMER loader_id=17 target_module=minecraft tccl_class=cpw.mods.modlauncher.TransformingClassLoader tccl_name=TRANSFORMER tccl_id=17 caller=net.minecraft.client.main.Main#lambda$main$0 caller_module=minecraft caller_source=minecraft.jar stack=classload",
        "BOOTOPTIM_ML_FORK probe=agent94-post-accept-v2 request_id=2 class=net.minecraft.server.Bootstrap stage=class_transform_begin mono_ns=300 thread=pool-8-thread-1",
        "BOOTOPTIM_ML_FORK probe=agent94-post-accept-v2 request_id=2 class=net.minecraft.server.Bootstrap stage=transformer owner=boot_optim labels=[boot_optim_agent94_bootstrap_profile] start_ns=310 end_ns=330 elapsed_ns=20 thread=pool-8-thread-1",
        "BOOTOPTIM_ML_FORK probe=agent94-post-accept-v2 request_id=2 class=net.minecraft.server.Bootstrap stage=plugins_after start_ns=331 end_ns=340 elapsed_ns=9 thread=pool-8-thread-1",
        "BOOTOPTIM_ML_FORK probe=agent94-post-accept-v2 request_id=2 class=net.minecraft.server.Bootstrap stage=writer_create start_ns=341 end_ns=350 elapsed_ns=9 thread=pool-8-thread-1",
        "BOOTOPTIM_ML_FORK probe=agent94-post-accept-v2 request_id=2 class=net.minecraft.server.Bootstrap stage=writer_accept start_ns=351 end_ns=370 elapsed_ns=19 thread=pool-8-thread-1",
        "BOOTOPTIM_ML_FORK probe=agent94-post-accept-v2 request_id=2 class=net.minecraft.server.Bootstrap stage=writer_to_bytes start_ns=371 end_ns=390 elapsed_ns=19 thread=pool-8-thread-1",
        "BOOTOPTIM_ML_FORK probe=agent94-post-accept-v2 request_id=2 class=net.minecraft.server.Bootstrap stage=class_transform_return mono_ns=400 thread=pool-8-thread-1",
        "BOOTOPTIM_ML_FORK_REQUEST_END probe=agent94-post-accept-v2 request_id=2 observed_id=2 class=net.minecraft.server.Bootstrap mono_ns=410 thread=pool-8-thread-1",
        "BOOTOPTIM_BOOTSTRAP_FORK_BOUNDARY event=bootstrap_entry mono_ns=420 accept_to_entry_ns=300 thread=pool-8-thread-1",
    ])


class ModLauncherForkProfileTest(unittest.TestCase):
    def test_requires_exact_mixin_then_classloading_topology(self):
        parsed = MODULE.parse_profile(valid_log())
        self.assertEqual(2, parsed["target_transform_invocation_count"])
        self.assertEqual("mixin_transformed_bytes_then_classloading", parsed["request_contract"])
        self.assertEqual(
            ["securejar_get_maybe_transformed_bytes", "securejar_reader_to_class"],
            [request["origin"] for request in parsed["requests"]],
        )
        self.assertEqual([1, 2], [request["request_id"] for request in parsed["requests"]])

    def test_rejects_wrong_first_origin(self):
        text = valid_log().replace(
            "origin=securejar_get_maybe_transformed_bytes raw_context=mixin",
            "origin=securejar_reader_to_class raw_context=mixin",
            1,
        )
        with self.assertRaisesRegex(ValueError, "request 1 origin mismatch"):
            MODULE.parse_profile(text)

    def test_rejects_extra_target_request(self):
        extra = "\nBOOTOPTIM_ML_FORK_REQUEST probe=agent94-post-accept-v2 request_id=3 class=net.minecraft.server.Bootstrap origin=other raw_context=null effective_reason=classloading thread=extra loader_class=cpw.mods.modlauncher.TransformingClassLoader loader_name=TRANSFORMER loader_id=17 target_module=minecraft tccl_class=cpw.mods.modlauncher.TransformingClassLoader tccl_name=TRANSFORMER tccl_id=17 caller=other caller_module=other caller_source=other stack=other"
        with self.assertRaisesRegex(ValueError, "expected exactly two Bootstrap transform requests"):
            MODULE.parse_profile(valid_log() + extra)

    def test_rejects_reversed_request_records(self):
        lines = valid_log().splitlines()
        first = next(i for i, line in enumerate(lines) if "BOOTOPTIM_ML_FORK_REQUEST " in line and "request_id=1" in line)
        second = next(i for i, line in enumerate(lines) if "BOOTOPTIM_ML_FORK_REQUEST " in line and "request_id=2" in line)
        lines[first], lines[second] = lines[second], lines[first]
        with self.assertRaisesRegex(ValueError, "request ids \[1, 2\] in order"):
            MODULE.parse_profile("\n".join(lines))


if __name__ == "__main__":
    unittest.main()
