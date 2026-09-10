#!/usr/bin/env python3
import argparse
import hashlib
import json
import re
from pathlib import Path

SHA256 = re.compile(r"^[0-9a-f]{64}$")
DEP_RE = re.compile(r"phase=dependency_discovery_end\b.*?elapsed_ms=([0-9]+(?:\.[0-9]+)?)")


def sha256_file(path: Path):
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def enrich_parent_digest(row, trace_path: Path):
    current = row.get("parent_sha256")
    if SHA256.fullmatch(current or ""):
        return current
    parent_text = row.get("parent_path")
    if not parent_text:
        raise SystemExit(f"missing parent path/strong identity in {trace_path}: {row}")
    try:
        parent = Path(parent_text)
    except (TypeError, ValueError) as exc:
        raise SystemExit(f"invalid physical parent path in {trace_path}: {parent_text}: {exc}")
    if not parent.is_file():
        raise SystemExit(f"cannot resolve physical parent strong identity after process exit in {trace_path}: {parent}")
    current = sha256_file(parent)
    row["parent_sha256"] = current
    row["parent_sha256_source"] = "post_process_physical_file"
    return current


def load_trace(path: Path):
    rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    headers = [r for r in rows if r.get("kind") == "profile_header"]
    disabled = [r for r in rows if r.get("kind") == "profile_disabled"]
    if len(headers) != 1 or disabled:
        raise SystemExit(f"invalid profile header/disabled state in {path}: headers={len(headers)} disabled={len(disabled)}")
    detail = headers[0].get("detail") or ""
    if "expected_fml=4.0.43" not in detail or "module_name=fml_loader" not in detail or "module_fml=4.0.43" not in detail:
        raise SystemExit(f"unexpected FML identity in {path}: {detail}")
    scans = [r for r in rows if r.get("kind") == "scan"]
    extracts = [r for r in rows if r.get("kind") == "extract_sha256"]
    loads = [r for r in rows if r.get("kind") == "load"]
    if not scans or not extracts or not loads:
        raise SystemExit(f"missing scan/extract/load events in {path}: {len(scans)}/{len(extracts)}/{len(loads)}")
    for row in extracts:
        if row.get("detail"):
            raise SystemExit(f"extract failure in {path}: {row}")
        enrich_parent_digest(row, path)
        if not SHA256.fullmatch(row.get("parent_sha256") or ""):
            raise SystemExit(f"missing strong parent provenance in {path}: {row}")
        if not SHA256.fullmatch(row.get("child_sha256") or ""):
            raise SystemExit(f"missing child digest in {path}: {row}")
        if not row.get("relative_path") or row.get("bytes", -1) < 0 or row.get("output_preexisting") is None:
            raise SystemExit(f"incomplete extraction record in {path}: {row}")
    return rows, scans, loads, extracts


def union_ns(rows):
    if not rows:
        return 0
    intervals = sorted((int(r["start_ns"]), int(r["end_ns"])) for r in rows)
    total = 0
    start, end = intervals[0]
    for a, b in intervals[1:]:
        if a <= end:
            end = max(end, b)
        else:
            total += end - start
            start, end = a, b
    return total + end - start


def dependency_ms(path: Path):
    text = path.read_text(encoding="utf-8", errors="replace")
    matches = DEP_RE.findall(text)
    if len(matches) != 1:
        raise SystemExit(f"expected one dependency_discovery_end elapsed marker in {path}, got {len(matches)}")
    return float(matches[0])


def summarize(label, trace_path, startup_path):
    _, scans, loads, extracts = load_trace(trace_path)
    dep_ms = dependency_ms(startup_path)
    scan_ns = union_ns(scans)
    load_ns = union_ns(loads)
    extract_ns = union_ns(extracts)
    total_bytes = sum(int(r["bytes"]) for r in extracts)
    existing = [r for r in extracts if r["output_preexisting"] is True]
    cold = [r for r in extracts if r["output_preexisting"] is False]
    unique_provenance = {(r["parent_sha256"], r["relative_path"], r["child_sha256"]) for r in extracts}
    post_process_parent_hashes = sum(1 for r in extracts if r.get("parent_sha256_source") == "post_process_physical_file")
    return {
        "label": label,
        "dependency_discovery_ms": dep_ms,
        "jarjar_scan_ms": scan_ns / 1e6,
        "jarjar_share_of_dependency_pct": (scan_ns / 1e6) / dep_ms * 100.0,
        "load_calls": len(loads),
        "load_union_ms": load_ns / 1e6,
        "extract_count": len(extracts),
        "extract_union_ms": extract_ns / 1e6,
        "extract_share_of_dependency_pct": (extract_ns / 1e6) / dep_ms * 100.0,
        "extract_share_of_jarjar_pct": (extract_ns / scan_ns * 100.0) if scan_ns else 0.0,
        "extract_bytes": total_bytes,
        "existing_count": len(existing),
        "existing_bytes": sum(int(r["bytes"]) for r in existing),
        "existing_extract_ms": union_ns(existing) / 1e6,
        "cold_count": len(cold),
        "cold_bytes": sum(int(r["bytes"]) for r in cold),
        "cold_extract_ms": union_ns(cold) / 1e6,
        "provenance_keys": len(unique_provenance),
        "post_process_parent_hashes": post_process_parent_hashes,
        "provenance": [list(v) for v in sorted(unique_provenance)],
        "top_extracts": sorted(
            ({
                "ms": int(r["duration_ns"]) / 1e6,
                "bytes": int(r["bytes"]),
                "preexisting": bool(r["output_preexisting"]),
                "parent_sha256": r["parent_sha256"],
                "relative_path": r["relative_path"],
                "child_sha256": r["child_sha256"],
            } for r in extracts),
            key=lambda x: x["ms"], reverse=True)[:12],
    }


def fmt_mib(value):
    return value / (1024 * 1024)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--cold-trace", type=Path, required=True)
    p.add_argument("--cold-startup", type=Path, required=True)
    p.add_argument("--warm-trace", type=Path, required=True)
    p.add_argument("--warm-startup", type=Path, required=True)
    p.add_argument("--json-output", type=Path, required=True)
    p.add_argument("--markdown-output", type=Path, required=True)
    args = p.parse_args()

    cold = summarize("cold", args.cold_trace, args.cold_startup)
    warm = summarize("warm-existing", args.warm_trace, args.warm_startup)
    if warm["cold_count"] != 0:
        raise SystemExit(f"warm run unexpectedly created cold content-addressed outputs: {warm['cold_count']}")
    if cold["provenance"] != warm["provenance"]:
        raise SystemExit("cold/warm strong-provenance topology differs")
    if cold["extract_count"] != warm["extract_count"]:
        raise SystemExit("cold/warm extraction count differs")

    result = {"schema": 2, "cold": cold, "warm": warm}
    args.json_output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")

    lines = [
        "# JarInJar copy+SHA diagnostic",
        "",
        "| profile | dependency wall ms | JarJar scan ms | scan share | extract count | extract MiB | copy+SHA ms | copy share dep/JarJar | existing/cold |",
        "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |",
    ]
    for row in (cold, warm):
        lines.append(
            f"| {row['label']} | {row['dependency_discovery_ms']:.3f} | {row['jarjar_scan_ms']:.3f} | "
            f"{row['jarjar_share_of_dependency_pct']:.2f}% | {row['extract_count']} | {fmt_mib(row['extract_bytes']):.2f} | "
            f"{row['extract_union_ms']:.3f} | {row['extract_share_of_dependency_pct']:.2f}% / "
            f"{row['extract_share_of_jarjar_pct']:.2f}% | {row['existing_count']}/{row['cold_count']} |"
        )
    lines += [
        "",
        "| profile/class | count | MiB | copy+SHA union ms |",
        "| --- | ---: | ---: | ---: |",
    ]
    for row in (cold, warm):
        lines.append(f"| {row['label']} / preexisting | {row['existing_count']} | {fmt_mib(row['existing_bytes']):.2f} | {row['existing_extract_ms']:.3f} |")
        lines.append(f"| {row['label']} / first-materialization | {row['cold_count']} | {fmt_mib(row['cold_bytes']):.2f} | {row['cold_extract_ms']:.3f} |")
    lines += ["", "Warm top copy+SHA intervals (not savings; stock still executes them on existing outputs):", ""]
    for row in warm["top_extracts"][:8]:
        lines.append(
            f"- {row['ms']:.3f} ms, {fmt_mib(row['bytes']):.2f} MiB, parent `{row['parent_sha256'][:12]}…`, "
            f"entry `{row['relative_path']}`"
        )
    lines += [
        "",
        "`jarjar_scan_ms` is the stock locator callback wall nested in dependency discovery. `copy+SHA ms` is the union of exact",
        "`extractEmbeddedJarFile` intervals; it is not added to `jarjar_scan_ms`. The warm run is a second fresh Minecraft JVM on",
        "the same hosted VM with the first run's stock `.cache/jij` restored before Java starts; this is a warm-residual diagnostic, not an A/B.",
        "Cold `output_preexisting=true` rows can be intra-launch content-addressed aliases; warm rows must all be preexisting.",
        "Strong SHA-256 for physical parents is computed only here, after both Minecraft processes exit; nested-parent digests come from stock child SHA-256.",
        "No full-parent hashing or trace-file I/O is added to the timed JarJar/discovery interval.",
    ]
    args.markdown_output.write_text("\n".join(lines) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
