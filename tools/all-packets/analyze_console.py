#!/usr/bin/env python3
import argparse
import json
import re
from pathlib import Path

KV = re.compile(r"([a-zA-Z_]+)=([^ ]+)")


def fields(line):
    return {k: v for k, v in KV.findall(line)}


def ns_to_ms(value):
    return int(value) / 1_000_000.0


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True)
    parser.add_argument("--json-output", required=True)
    parser.add_argument("--markdown-output", required=True)
    args = parser.parse_args()

    lines = Path(args.input).read_text(errors="replace").splitlines()
    markers = [line[line.index("BOOTOPTIM_ALLPACKETS_"):] for line in lines if "BOOTOPTIM_ALLPACKETS_" in line]
    if not markers:
        raise SystemExit("no AllPackets markers found")

    dep_starts = {}
    dep_intervals = []
    values = {}
    enum_durations = []
    register_packet_durations = []
    accepted = False
    throws = []

    for line in markers:
        f = fields(line)
        if " throw=" in line:
            throws.append(line)
        if line.startswith("BOOTOPTIM_ALLPACKETS_CREATE_CTOR_BEGIN"):
            accepted = f.get("accepted") == "true"
            values["create_version"] = f.get("observed_create")
        elif line.startswith("BOOTOPTIM_ALLPACKETS_CREATE_CTOR_END"):
            values["create_ctor_ms"] = ns_to_ms(f["dur_ns"])
        elif line.startswith("BOOTOPTIM_ALLPACKETS_CLINIT_BEGIN"):
            values["clinit_begin_ns"] = int(f["ns"])
            values["create_package_version"] = f.get("package_version")
        elif line.startswith("BOOTOPTIM_ALLPACKETS_CLINIT_END"):
            values["clinit_end_ns"] = int(f["ns"])
            values["clinit_ms"] = ns_to_ms(f["dur_ns"])
        elif line.startswith("BOOTOPTIM_ALLPACKETS_DEP_CLINIT_BEGIN"):
            dep_starts[f["class"]] = int(f["ns"])
        elif line.startswith("BOOTOPTIM_ALLPACKETS_DEP_CLINIT_END"):
            name = f["class"]
            start = dep_starts.pop(name, None)
            if start is None:
                raise SystemExit(f"missing dep clinit begin for {name}")
            end = int(f["ns"])
            dep_intervals.append({"class": name, "start": start, "end": end, "dur": end - start})
        elif line.startswith("BOOTOPTIM_ALLPACKETS_ENUM_CTOR"):
            enum_durations.append(int(f["dur_ns"]))
        elif line.startswith("BOOTOPTIM_ALLPACKETS_REGISTER_BEGIN"):
            values["register_begin_ns"] = int(f["ns"])
        elif line.startswith("BOOTOPTIM_ALLPACKETS_REGISTER_END"):
            values["register_ms"] = ns_to_ms(f["dur_ns"])
        elif line.startswith("BOOTOPTIM_ALLPACKETS_REGISTER_PACKET"):
            register_packet_durations.append(int(f["dur_ns"]))
        elif line.startswith("BOOTOPTIM_ALLPACKETS_REGISTER_ALL_BEGIN"):
            values["register_all_begin_ns"] = int(f["ns"])
        elif line.startswith("BOOTOPTIM_ALLPACKETS_REGISTER_ALL_END"):
            values["register_all_ms"] = ns_to_ms(f["dur_ns"])
        elif line.startswith("BOOTOPTIM_ALLPACKETS_NETWORK_HELPER_BEGIN"):
            values["catnip_package_version"] = f.get("catnip_package_version")
        elif line.startswith("BOOTOPTIM_ALLPACKETS_NETWORK_HELPER_END"):
            values["network_helper_ms"] = ns_to_ms(f["dur_ns"])

    required = ["clinit_ms", "register_ms", "register_all_ms", "network_helper_ms"]
    missing = [key for key in required if key not in values]
    if not accepted or missing or dep_starts or throws:
        raise SystemExit(f"profile rejected accepted={accepted} missing={missing} open_dep={list(dep_starts)} throws={len(throws)}")

    # Build containment tree for observed Create/Catnip class initializers. Exclusive time is
    # exclusive only with respect to other observed class initializers; uninstrumented JVM/MC work
    # remains in the owning interval and is never presented as removable savings.
    dep_intervals.sort(key=lambda x: (x["start"], -x["end"]))
    stack = []
    roots = []
    for item in dep_intervals:
        item["children"] = []
        while stack and item["start"] >= stack[-1]["end"]:
            stack.pop()
        if stack:
            if item["end"] > stack[-1]["end"]:
                raise SystemExit("non-nested class-init intervals")
            stack[-1]["children"].append(item)
        else:
            roots.append(item)
        stack.append(item)

    for item in dep_intervals:
        item["exclusive"] = item["dur"] - sum(child["dur"] for child in item["children"])

    enum_ctor_ns = sum(enum_durations)
    root_dep_ns = sum(item["dur"] for item in roots)
    clinit_ns = int(round(values["clinit_ms"] * 1_000_000))
    clinit_residual_ns = clinit_ns - root_dep_ns - enum_ctor_ns

    register_packet_ns = sum(register_packet_durations)
    register_all_ns = int(round(values["register_all_ms"] * 1_000_000))
    register_ns = int(round(values["register_ms"] * 1_000_000))
    register_residual_ns = register_ns - register_packet_ns - register_all_ns
    network_ns = int(round(values["network_helper_ms"] * 1_000_000))
    register_all_residual_ns = register_all_ns - network_ns

    top_exclusive = sorted(dep_intervals, key=lambda x: x["exclusive"], reverse=True)[:15]
    summary = {
        **values,
        "accepted": accepted,
        "marker_count": len(markers),
        "dep_clinit_count": len(dep_intervals),
        "enum_ctor_count": len(enum_durations),
        "enum_ctor_sum_ms": enum_ctor_ns / 1_000_000.0,
        "top_level_dep_clinit_sum_ms": root_dep_ns / 1_000_000.0,
        "clinit_residual_ms": clinit_residual_ns / 1_000_000.0,
        "register_packet_count": len(register_packet_durations),
        "register_packet_sum_ms": register_packet_ns / 1_000_000.0,
        "register_residual_ms": register_residual_ns / 1_000_000.0,
        "register_all_residual_ms": register_all_residual_ns / 1_000_000.0,
        "top_exclusive_class_inits": [
            {"class": x["class"], "inclusive_ms": x["dur"] / 1_000_000.0,
             "exclusive_observed_ms": x["exclusive"] / 1_000_000.0}
            for x in top_exclusive
        ],
    }
    Path(args.json_output).write_text(json.dumps(summary, indent=2) + "\n")

    total_ms = values["clinit_ms"] + values["register_ms"]
    md = [
        "# Create AllPackets diagnostic",
        "",
        f"- accepted Create version: `{values.get('create_version')}`",
        f"- AllPackets `<clinit>`: **{values['clinit_ms']:.3f} ms**",
        f"- AllPackets `register()`: **{values['register_ms']:.3f} ms**",
        f"- observed total `<clinit> + register`: **{total_ms:.3f} ms** (serial, non-overlapping)",
        f"- enum constructor bodies: {len(enum_durations)} calls / {enum_ctor_ns / 1_000_000.0:.3f} ms total",
        f"- observed top-level Create/Catnip dependency `<clinit>`: {root_dep_ns / 1_000_000.0:.3f} ms",
        f"- AllPackets `<clinit>` residual outside observed dep-clinit + enum ctors: {clinit_residual_ns / 1_000_000.0:.3f} ms",
        f"- Catnip `registerPacket`: {len(register_packet_durations)} calls / {register_packet_ns / 1_000_000.0:.3f} ms total",
        f"- Catnip `registerAllPackets`: {values['register_all_ms']:.3f} ms; nested NeoForge network helper: {values['network_helper_ms']:.3f} ms",
        f"- `register()` residual outside `registerPacket` + `registerAllPackets`: {register_residual_ns / 1_000_000.0:.3f} ms",
        "",
        "## Largest observed class initializers",
        "",
        "| class | inclusive ms | exclusive vs observed nested class-init ms |",
        "|---|---:|---:|",
    ]
    for item in top_exclusive:
        md.append(f"| `{item['class']}` | {item['dur'] / 1_000_000.0:.3f} | {item['exclusive'] / 1_000_000.0:.3f} |")
    Path(args.markdown_output).write_text("\n".join(md) + "\n")


if __name__ == "__main__":
    main()
