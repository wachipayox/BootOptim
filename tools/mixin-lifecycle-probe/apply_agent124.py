#!/usr/bin/env python3
from __future__ import annotations

import argparse
from pathlib import Path

PROCESSOR = Path("src/main/java/org/spongepowered/asm/mixin/transformer/MixinProcessor.java")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one exact source match after Agent96 patch, found {count}")
    return text.replace(old, new, 1)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--checkout", required=True, type=Path)
    args = parser.parse_args()
    path = args.checkout.resolve() / PROCESSOR
    text = path.read_text(encoding="utf-8")

    text = replace_once(text,
        """                MixinProcessor.logger.log(this.verboseLoggingLevel, \"Preparing {} ({})\", config, config.getDeclaredMixinCount());\n                config.prepare(extensions);\n                totalMixins += config.getMixinCount();\n""",
        """                MixinProcessor.logger.log(this.verboseLoggingLevel, \"Preparing {} ({})\", config, config.getDeclaredMixinCount());\n                long bootOptimOneConfigStart = bootOptimNow();\n                String bootOptimOneConfigDetail = config.getName() + \"|declared=\" + config.getDeclaredMixinCount();\n                try {\n                    config.prepare(extensions);\n                } finally {\n                    bootOptimTrace(\"config_prepare_one\", bootOptimOneConfigStart, bootOptimOneConfigDetail);\n                }\n                totalMixins += config.getMixinCount();\n""", "per-config prepare timing")

    text = replace_once(text,
        """            plugin.acceptTargets(config.getTargetsSet(), Collections.<String>unmodifiableSet(otherTargets));\n""",
        """            long bootOptimOnePluginStart = bootOptimNow();\n            String bootOptimOnePluginDetail = config.getName() + \"|plugin=\" + plugin.getClass().getName()\n                    + \"|targets=\" + config.getTargetsSet().size() + \"|other_targets=\" + otherTargets.size();\n            try {\n                plugin.acceptTargets(config.getTargetsSet(), Collections.<String>unmodifiableSet(otherTargets));\n            } finally {\n                bootOptimTrace(\"plugin_accept_targets_one\", bootOptimOnePluginStart, bootOptimOnePluginDetail);\n            }\n""", "per-plugin acceptTargets timing")

    text = replace_once(text,
        """            try {\n                config.postInitialise(this.extensions);\n""",
        """            try {\n                long bootOptimOnePostStart = bootOptimNow();\n                String bootOptimOnePostDetail = config.getName() + \"|declared=\" + config.getDeclaredMixinCount();\n                try {\n                    config.postInitialise(this.extensions);\n                } finally {\n                    bootOptimTrace(\"post_initialise_one\", bootOptimOnePostStart, bootOptimOnePostDetail);\n                }\n""", "per-config postInitialise timing")

    path.write_text(text, encoding="utf-8")
    print("applied Agent124 observational per-config/plugin Mixin attribution")


if __name__ == "__main__":
    main()
