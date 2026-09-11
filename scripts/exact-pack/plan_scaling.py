#!/usr/bin/env python3
"""Compatibility entrypoint for the artifact-safe exact-pack scaling planner."""

from pathlib import Path
import sys

_SCRIPT_DIR = str(Path(__file__).resolve().parent)
if _SCRIPT_DIR not in sys.path:
    sys.path.insert(0, _SCRIPT_DIR)

import artifact_scaling as _base  # noqa: E402

# Tests/materializer can load this entrypoint more than once under different
# module names. Preserve the real legacy function exactly once; otherwise a
# second import would save the already-patched function and recurse forever.
if not hasattr(_base, "_legacy_build_plan"):
    _base._legacy_build_plan = _base.build_plan
from artifact_scaling_physical import build_plan as _physical_build_plan  # noqa: E402
_base.build_plan = _physical_build_plan

from artifact_scaling import *  # noqa: F401,F403,E402
main = _base.main
build_plan = _physical_build_plan


if __name__ == "__main__":
    main()
