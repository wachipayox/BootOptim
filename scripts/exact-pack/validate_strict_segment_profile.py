"""Validate observed-workload replay, never infer TTMM from pure-operation CPU."""
import json
import re
import sys
from pathlib import Path


def validate(log):
    rows = re.findall(r"BOOTOPTIM_STRICT_SEGMENT (.*)", log)
    if len(rows) != 1:
        raise ValueError("one replay endpoint required")
    d = dict(re.findall(r"(\w+)=(\S+)", rows[0]))
    if d.get("status") != "complete" or d.get("equivalent") != "true" or d.get("truncated") != "false":
        raise ValueError("incomplete/inconsistent corpus")
    if int(d.get("rewritten", "0")) != int(d["calls"]):
        raise ValueError("instruction replacement inactive or partial")
    if int(d["inflight"]) != 0 or int(d["skipped"]) != 0:
        raise ValueError("unfinished or skipped observations")
    if not 0 < int(d["rows"]) <= int(d["calls"]) <= 10_000_000:
        raise ValueError("invalid observation counts")
    if not 0 <= int(d["rejected"]) <= int(d["calls"]) or int(d["characters"]) < 0:
        raise ValueError("invalid corpus")
    for key in ["c1_cpu_ns", "b1_cpu_ns", "b2_cpu_ns", "c2_cpu_ns"]:
        if int(d[key]) <= 0:
            raise ValueError("unavailable/quantized CPU result")
    return {"valid": True, "origin": "post-main-menu weighted validator corpus replay",
            "endpoint": "one complete C/B/B/C replay", "result": d,
            "paired_cpu_removed_ns": [int(d["c1_cpu_ns"]) - int(d["b1_cpu_ns"]),
                                      int(d["c2_cpu_ns"]) - int(d["b2_cpu_ns"])],
            "ttmm_claim": False}


if __name__ == "__main__":
    print(json.dumps(validate(Path(sys.argv[1]).read_text(encoding="utf-8", errors="replace")), indent=2))
