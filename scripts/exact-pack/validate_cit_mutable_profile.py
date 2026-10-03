"""Validate original-path coverage; diagnostic durations are not a savings claim."""
import json
import re
import sys
from pathlib import Path


def validate(log):
    content = re.findall(r"BOOTOPTIM_CIT_MUTABLE_CONTENT (.*)", log)
    rows = re.findall(r"BOOTOPTIM_CIT_MUTABLE_STAGE (.*)", log)
    if len(content) != 1 or len(rows) != 4:
        raise ValueError("one complete menu snapshot required")
    c = dict(re.findall(r"(\w+)=(\S+)", content[0]))
    stages = {}
    for row in rows:
        fields = dict(re.findall(r"(\w+)=(\S+)", row))
        name = fields.pop("stage")
        if name in stages:
            raise ValueError("duplicate stage")
        stages[name] = {k: int(v) for k, v in fields.items()}
    if set(stages) != {"first", "override", "read", "parse"}:
        raise ValueError("incomplete method coverage")
    if c["inflight"] != "0" or c["truncated"] != "false":
        raise ValueError("unfinished or truncated snapshot")
    for s in stages.values():
        if s["calls"] <= 0 or s["failures"] or s["nulls"]:
            raise ValueError("inactive/failed original path")
        if not 0 < s["samples"] == s["cpu_samples"] <= s["calls"]:
            raise ValueError("invalid CPU coverage")
        if s["sampled_cpu_ns"] <= 0 or s["sampled_wall_ns"] < 0:
            raise ValueError("invalid clock")
    roots = stages["first"]["calls"] + stages["override"]["calls"]
    if not roots <= stages["read"]["calls"] == stages["parse"]["calls"] == int(c["calls"]):
        raise ValueError("read/parse count mismatch")
    if not 0 < int(c["unique"]) <= int(c["calls"]):
        raise ValueError("invalid content census")
    root_samples = stages["first"]["samples"] + stages["override"]["samples"]
    if not root_samples <= stages["read"]["samples"] == stages["parse"]["samples"]:
        raise ValueError("sample partition mismatch")
    return {"valid": True, "content": c, "stages": stages}


if __name__ == "__main__":
    print(json.dumps(validate(Path(sys.argv[1]).read_text(encoding="utf-8", errors="replace")), indent=2))
