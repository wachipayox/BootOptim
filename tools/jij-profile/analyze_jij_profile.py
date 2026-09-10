#!/usr/bin/env python3
import argparse
import hashlib
import io
import json
import re
import zipfile
from pathlib import Path
from urllib.parse import unquote

DEP_RE = re.compile(r"phase=dependency_discovery_end\b.*?elapsed_ms=([0-9]+(?:\.[0-9]+)?)")


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


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


def dependency_ms(path: Path) -> float:
    matches = DEP_RE.findall(path.read_text(encoding="utf-8", errors="replace"))
    if len(matches) != 1:
        raise SystemExit(f"expected one dependency_discovery_end marker in {path}, got {len(matches)}")
    return float(matches[0])


def read_entry(parent_payload: bytes, relative_path: str) -> bytes:
    name = relative_path.replace("\\", "/").lstrip("/")
    with zipfile.ZipFile(io.BytesIO(parent_payload), "r") as archive:
        return archive.read(name)


def physical_path_from_runtime_path(runtime_path: str):
    """Resolve only paths that are backed by a physical root JAR.

    FML/SecureJar can expose a root mod through a synthetic `union:union:/...jar%23NNN!/`
    path. The `%23NNN` suffix is a runtime filesystem instance discriminator, not part of
    the physical filename. Nested `jij:` paths are deliberately *not* decoded here: those
    must resolve through a previously captured parent payload so provenance follows the
    actual embedded-JAR chain.
    """
    try:
        direct = Path(runtime_path)
    except (TypeError, ValueError):
        direct = None
    if direct is not None and direct.is_file():
        return direct

    if not runtime_path.startswith("union:"):
        return None

    decoded = unquote(runtime_path)
    while decoded.startswith("union:"):
        decoded = decoded[len("union:"):]
    archive_part = decoded.split("!/", 1)[0]
    archive_part = re.sub(r"#\d+$", "", archive_part)
    try:
        candidate = Path(archive_part)
    except (TypeError, ValueError):
        return None
    return candidate if candidate.is_file() else None


def resolve_load_provenance(loads, trace_path: Path):
    payload_by_runtime_path = {}
    physical_payload_cache = {}
    resolved = []

    for row in sorted(loads, key=lambda r: (int(r["start_ns"]), int(r.get("seq", 0)))):
        parent_path = row.get("parent_path")
        relative_path = row.get("relative_path")
        if not parent_path or not relative_path:
            raise SystemExit(f"missing parent path/relative path in {trace_path}: {row}")

        parent_payload = None
        parent_source = None
        if parent_path in payload_by_runtime_path:
            parent_payload = payload_by_runtime_path[parent_path]
            parent_source = "post_process_parent_chain"
        else:
            physical = physical_path_from_runtime_path(parent_path)
            if physical is not None:
                cache_key = str(physical)
                parent_payload = physical_payload_cache.get(cache_key)
                if parent_payload is None:
                    parent_payload = physical.read_bytes()
                    physical_payload_cache[cache_key] = parent_payload
                parent_source = "post_process_physical_file"

        if parent_payload is None:
            raise SystemExit(f"cannot resolve strong parent identity for {parent_path} in {trace_path}")

        try:
            child_payload = read_entry(parent_payload, relative_path)
        except Exception as exc:
            raise SystemExit(f"cannot read embedded entry {relative_path} from {parent_path}: {exc}")

        parent_sha = sha256_bytes(parent_payload)
        child_sha = sha256_bytes(child_payload)
        child_path = row.get("child_path")
        if child_path:
            payload_by_runtime_path[child_path] = child_payload

        enriched = dict(row)
        enriched.update({
            "parent_sha256": parent_sha,
            "parent_sha256_source": parent_source,
            "child_sha256": child_sha,
            "bytes": len(child_payload),
        })
        resolved.append(enriched)
    return resolved


def load_trace(path: Path):
    rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    headers = [r for r in rows if r.get("kind") == "profile_header"]
    disabled = [r for r in rows if r.get("kind") == "profile_disabled"]
    if not headers or disabled:
        raise SystemExit(f"invalid profile header/disabled state in {path}: headers={len(headers)} disabled={len(disabled)}")
    for header in headers:
        detail = header.get("detail") or ""
        required = ("expected_fml=4.0.43", "module_name=fml_loader", "module_fml=4.0.43", "implementation=jij_filesystem")
        if not all(token in detail for token in required):
            raise SystemExit(f"unexpected FML identity/implementation in {path}: {detail}")

    scans = [r for r in rows if r.get("kind") == "scan"]
    loads = [r for r in rows if r.get("kind") == "load_jij_filesystem"]
    extracts = [r for r in rows if r.get("kind") == "extract_sha256"]
    if not scans or not loads:
        raise SystemExit(f"missing scan/load events in {path}: scans={len(scans)} loads={len(loads)}")
    if extracts:
        raise SystemExit(f"FML 4.0.43 unexpectedly exposed copy+SHA extraction events in {path}")
    failures = [r for r in scans + loads if r.get("detail")]
    if failures:
        raise SystemExit(f"stock JarJar callback failures observed in {path}: {failures[:3]}")
    return scans, resolve_load_provenance(loads, path)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--trace", type=Path, required=True)
    p.add_argument("--startup", type=Path, required=True)
    p.add_argument("--json-output", type=Path, required=True)
    p.add_argument("--markdown-output", type=Path, required=True)
    args = p.parse_args()

    scans, loads = load_trace(args.trace)
    dep_ms = dependency_ms(args.startup)
    scan_ns = union_ns(scans)
    load_ns = union_ns(loads)
    total_bytes = sum(int(r["bytes"]) for r in loads)
    provenance = sorted({(r["parent_sha256"], r["relative_path"], r["child_sha256"]) for r in loads})
    result = {
        "schema": 3,
        "fml_version": "4.0.43",
        "implementation": "jij_filesystem",
        "content_addressed_cache": False,
        "cache_existing_vs_cold": "not_applicable_no_content_addressed_output",
        "dependency_discovery_ms": dep_ms,
        "jarjar_scan_calls": len(scans),
        "jarjar_scan_ms": scan_ns / 1e6,
        "jarjar_share_of_dependency_pct": (scan_ns / 1e6) / dep_ms * 100.0,
        "jij_load_calls": len(loads),
        "jij_load_union_ms": load_ns / 1e6,
        "jij_load_share_of_dependency_pct": (load_ns / 1e6) / dep_ms * 100.0,
        "jij_load_share_of_jarjar_pct": (load_ns / scan_ns * 100.0) if scan_ns else 0.0,
        "embedded_bytes": total_bytes,
        "embedded_mib": total_bytes / (1024 * 1024),
        "provenance_keys": len(provenance),
        "provenance": [list(v) for v in provenance],
        "top_loads": sorted(({
            "ms": int(r["duration_ns"]) / 1e6,
            "bytes": int(r["bytes"]),
            "parent_sha256": r["parent_sha256"],
            "relative_path": r["relative_path"],
            "child_sha256": r["child_sha256"],
        } for r in loads), key=lambda x: x["ms"], reverse=True)[:12],
    }
    args.json_output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")

    lines = [
        "# FML 4.0.43 Jar-in-Jar diagnostic",
        "",
        "The exact-pack runtime does not implement a content-addressed copy+SHA JarJar cache. It opens selected embedded JARs through the stock `jij:` filesystem and creates fresh `JarContents`/reader state.",
        "",
        "| dependency wall ms | JarJar scan ms | scan share | JiJ loads | embedded MiB | load wall ms | load share dep/JarJar | cache-existing vs cold |",
        "| ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |",
        f"| {dep_ms:.3f} | {scan_ns / 1e6:.3f} | {result['jarjar_share_of_dependency_pct']:.2f}% | {len(loads)} | {result['embedded_mib']:.2f} | {load_ns / 1e6:.3f} | {result['jij_load_share_of_dependency_pct']:.2f}% / {result['jij_load_share_of_jarjar_pct']:.2f}% | N/A: no content-addressed output |",
        "",
        "Top stock `loadModFileFrom` intervals:",
        "",
    ]
    for row in result["top_loads"][:8]:
        lines.append(f"- {row['ms']:.3f} ms, {row['bytes'] / (1024 * 1024):.2f} MiB, parent `{row['parent_sha256'][:12]}…`, entry `{row['relative_path']}`")
    lines += [
        "",
        "`jarjar_scan_ms` is inclusive locator wall nested inside dependency discovery; `jij_load_union_ms` is a nested subinterval and is not added to it.",
        "Strong parent SHA-256, embedded byte counts and child SHA-256 are resolved only after the Minecraft process exits from recorded parent path + relative path (recursing through nested JiJ payloads when needed).",
        "Synthetic root `union:` paths are normalized post-process to the encoded physical JAR only; nested `jij:` parents still resolve through the captured embedded-payload chain.",
        "The timed JVM retains only strings/primitives and never persists or reuses live `JarContents`, `IModFile`, `FileSystem`, readers or callbacks.",
    ]
    args.markdown_output.write_text("\n".join(lines) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
