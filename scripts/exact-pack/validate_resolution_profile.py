"""Validate diagnostic accounting; never extrapolate sampled CPU into a saving."""
import json
import re
import sys
from pathlib import Path


def validate(text, require_details=True):
    def rows(tag):
        return [dict(re.findall(r"(\w+)=([^\s]+)", line.split(tag, 1)[1]))
                for line in text.splitlines() if tag in line]

    headers = rows("BOOTOPTIM_RESOLUTION ")
    providers = rows("BOOTOPTIM_RESOLUTION_PROVIDER ")
    families = rows("BOOTOPTIM_RESOLUTION_FAMILY ")
    queries = rows("BOOTOPTIM_RESOLUTION_QUERY ")
    if len(headers) != 1 or not providers:
        raise ValueError("Missing/ambiguous owner snapshot or provider rows")
    h = headers[0]
    number = lambda row, key: int(row[key])
    if h["status"] != "snapshot" or h["truncated"] != "false" or number(h, "inflight"):
        raise ValueError("Incomplete owner snapshot")
    if number(h, "calls") <= 0 or number(h, "failures") or number(h, "cpu_samples") != number(h, "sampled_roots"):
        raise ValueError("Failed/empty owner or unavailable CPU coverage")
    if number(h, "calls") != number(h, "unique") + number(h, "repeats"):
        raise ValueError("Query identity accounting mismatch")
    for p in providers:
        if number(p, "hits") > number(p, "calls") or number(p, "failures"):
            raise ValueError("Invalid provider outcomes")
        if number(p, "cpu_samples") != number(p, "samples"):
            raise ValueError("Provider CPU coverage mismatch")
    provider_cpu = sum(number(p, "sampled_cpu_ns") for p in providers)
    if provider_cpu != number(h, "sampled_provider_cpu_ns"):
        raise ValueError("Provider CPU accounting mismatch")
    if require_details:
        if not families or not queries:
            raise ValueError("Missing family/query detail")
        checks = {"calls": "calls", "repeats": "repeats", "hits": "hits",
                  "samples": "sampled_roots", "cpu_samples": "cpu_samples",
                  "sampled_cpu_ns": "sampled_cpu_ns", "sampled_wall_ns": "sampled_wall_ns"}
        for family_key, header_key in checks.items():
            if sum(number(f, family_key) for f in families) != number(h, header_key):
                raise ValueError(f"Family {family_key} accounting mismatch")
        if sum(number(f, "probes") for f in families) != sum(number(p, "calls") for p in providers):
            raise ValueError("Family/provider probe accounting mismatch")
        for f in families:
            if number(f, "first_cpu_ns") + number(f, "repeat_cpu_ns") != number(f, "sampled_cpu_ns"):
                raise ValueError("First/repeat CPU partition mismatch")
        for q in queries:
            if not 0 <= number(q, "hits") <= number(q, "calls") or number(q, "cpu_samples") != number(q, "samples"):
                raise ValueError("Invalid query detail")
    return {"valid": True, "owner": h, "providers": providers, "families": families, "queries": queries}


if __name__ == "__main__":
    print(json.dumps(validate(Path(sys.argv[1]).read_text(encoding="utf-8")), indent=2))
