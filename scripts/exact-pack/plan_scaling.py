#!/usr/bin/env python3
"""Compatibility entrypoint for the artifact-safe exact-pack scaling planner."""

from artifact_scaling import *  # noqa: F401,F403
from artifact_scaling import main


if __name__ == "__main__":
    main()
