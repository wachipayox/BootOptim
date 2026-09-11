#!/usr/bin/env python3
"""Compatibility entrypoint for the artifact-safe exact-pack scaling planner."""

from pathlib import Path
import sys

_SCRIPT_DIR = str(Path(__file__).resolve().parent)
if _SCRIPT_DIR not in sys.path:
    sys.path.insert(0, _SCRIPT_DIR)

from artifact_scaling import *  # noqa: F401,F403,E402
from artifact_scaling import main  # noqa: E402


if __name__ == "__main__":
    main()
