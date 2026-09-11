#!/usr/bin/env python3
"""Compatibility entrypoint for the artifact-safe exact-pack scaling planner."""

from pathlib import Path
import sys

_SCRIPT_DIR = str(Path(__file__).resolve().parent)
if _SCRIPT_DIR not in sys.path:
    sys.path.insert(0, _SCRIPT_DIR)

import artifact_scaling as _base  # noqa: E402

# Preserve the original non-partition closure builder for legacy diagnostic
# variants, then replace only the matrix builder with the provider-aware
# physical-artifact implementation.
_base._legacy_build_plan = _base.build_plan
from artifact_scaling_physical import build_plan as _physical_build_plan  # noqa: E402
_base.build_plan = _physical_build_plan

from artifact_scaling import *  # noqa: F401,F403,E402
main = _base.main
build_plan = _physical_build_plan


if __name__ == "__main__":
    main()
