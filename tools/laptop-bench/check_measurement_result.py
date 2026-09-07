#!/usr/bin/env python3
"""Offline validity gate for BootOptim startup measurements.

This checker does not read a running Minecraft log and does not profile Java.
It consumes a completed, normalized measurement JSON sidecar and classifies the
run (or a two-run comparison) as valid, inconclusive, or invalid before timing
values are aggregated.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

SCHEMA_VERSION = 1
ORIGINS = {"physical_laptop", "hosted_exact_pack", "fast_pc", "diagnostic_harness"}
ENDPOINTS = {"main_menu", "main_menu_presented"}
CLOCK_ORIGINS = {"process", "bootoptim"}
PURPOSES = {"absolute_startup", "phase_attribution", "candidate_control", "paired_diagnostic"}
CACHE_STATES = {
    "fresh_vm",
    "cold_verified",
    "warm_verified",
    "paired_shared",
    "session_uncontrolled",
    "unknown",
}

# Screening thresholds, not causal attribution thresholds. They are deliberately
# far below the 40-97% iowait contamination observed in the PR #154 campaign.
HOST_IOWAIT_P95_INCONCLUSIVE_PCT = 20.0
HOST_STOLEN_MAX_INCONCLUSIVE_PCT = 5.0
JVM_CLOCK_TOLERANCE_MS = 5_000
EARLY_PROBE_MAX_UPTIME_MS = 60_000


def _get(doc: dict[str, Any], path: str, default=None):
    value: Any = doc
    for part in path.split("."):
        if not isinstance(value, dict) or part not in value:
            return default
        value = value[part]
    return value


def _append_missing(doc, path, reasons):
    if _get(doc, path) is None:
        reasons.append(f"missing:{path}")
        return True
    return False


def _selected_endpoint_ms(doc):
    endpoint = doc.get("endpoint")
    if endpoint == "main_menu":
        return _get(doc, "metrics.main_menu_ms")
    if endpoint == "main_menu_presented":
        return _get(doc, "metrics.main_menu_presented_ms")
    return None


def classify_run(doc: dict[str, Any]) -> dict[str, Any]:
    invalid: list[str] = []
    inconclusive: list[str] = []
    warnings: list[str] = []

    if doc.get("schema_version") != SCHEMA_VERSION:
        invalid.append(f"schema_version_must_be_{SCHEMA_VERSION}")

    for field in ("run_id", "purpose", "origin", "endpoint"):
        _append_missing(doc, field, invalid)

    purpose = doc.get("purpose")
    origin = doc.get("origin")
    endpoint = doc.get("endpoint")
    if purpose is not None and purpose not in PURPOSES:
        invalid.append(f"unsupported_purpose:{purpose}")
    if origin is not None and origin not in ORIGINS:
        invalid.append(f"unsupported_origin:{origin}")
    if endpoint is not None and endpoint not in ENDPOINTS:
        invalid.append(f"unsupported_endpoint:{endpoint}")

    clock_origin = _get(doc, "clock.origin")
    if clock_origin is None:
        invalid.append("missing:clock.origin")
    elif clock_origin not in CLOCK_ORIGINS:
        invalid.append(f"unsupported_clock_origin:{clock_origin}")

    if _get(doc, "clock.process_start_verified") is not True:
        invalid.append("process_start_not_verified")

    first_probe_uptime = _get(doc, "clock.first_probe_uptime_ms")
    first_probe_wall = _get(doc, "clock.first_probe_wall_epoch_ms")
    jvm_start_wall = _get(doc, "clock.jvm_start_epoch_ms")
    if first_probe_uptime is not None:
        if first_probe_uptime < 0:
            invalid.append("negative_first_probe_uptime")
        elif first_probe_uptime > EARLY_PROBE_MAX_UPTIME_MS:
            invalid.append(f"first_probe_uptime_exceeds_{EARLY_PROBE_MAX_UPTIME_MS}ms")
    if None not in (first_probe_uptime, first_probe_wall, jvm_start_wall):
        if abs((first_probe_wall - jvm_start_wall) - first_probe_uptime) > JVM_CLOCK_TOLERANCE_MS:
            invalid.append("jvm_start_wall_uptime_inconsistent")

    stale_java = _get(doc, "identity.stale_java_detected")
    surviving_java = _get(doc, "identity.surviving_previous_java")
    if stale_java is True:
        invalid.append("stale_java_detected")
    elif stale_java is None:
        inconclusive.append("stale_java_not_checked")
    if surviving_java is True:
        invalid.append("surviving_previous_java")
    elif surviving_java is None:
        inconclusive.append("previous_java_survivors_not_checked")

    java_pid = _get(doc, "identity.java_pid")
    observed_pid = _get(doc, "identity.observed_java_pid")
    if java_pid is None or observed_pid is None:
        inconclusive.append("java_pid_identity_incomplete")
    elif java_pid != observed_pid:
        invalid.append("java_pid_mismatch")

    if origin in {"physical_laptop", "diagnostic_harness"}:
        jar_count = _get(doc, "identity.bootoptim_jar_count")
        if jar_count is None:
            invalid.append("missing:identity.bootoptim_jar_count")
        elif jar_count != 1:
            invalid.append(f"bootoptim_jar_count:{jar_count}")
        if _get(doc, "identity.effective_jvm_args_verified") is not True:
            invalid.append("effective_jvm_args_not_verified")
        if _get(doc, "identity.instance_cfg_verified") is not True:
            invalid.append("instance_cfg_not_verified")

    selection = _get(doc, "state.resource_selection_valid")
    if selection is False:
        invalid.append("resource_selection_invalid")
    elif selection is None:
        inconclusive.append("resource_selection_not_checked")

    cache_state = _get(doc, "state.cache_state")
    if cache_state is None:
        inconclusive.append("cache_state_missing")
    elif cache_state not in CACHE_STATES:
        invalid.append(f"unsupported_cache_state:{cache_state}")
    elif cache_state == "unknown" and purpose in {"candidate_control", "paired_diagnostic"}:
        inconclusive.append("cache_state_unknown_for_comparison")

    markers = doc.get("markers")
    if not isinstance(markers, dict):
        invalid.append("missing:markers")
    else:
        main_count = markers.get("main_menu")
        if main_count != 1:
            invalid.append(f"main_menu_marker_count:{main_count}")
        if endpoint == "main_menu_presented":
            presented_count = markers.get("main_menu_presented")
            if presented_count != 1:
                invalid.append(f"main_menu_presented_marker_count:{presented_count}")
        reload_count = markers.get("initial_resource_reload")
        if reload_count != 1:
            invalid.append(f"initial_resource_reload_marker_count:{reload_count}")

    main_menu_ms = _get(doc, "metrics.main_menu_ms")
    presented_ms = _get(doc, "metrics.main_menu_presented_ms")
    selected_ms = _selected_endpoint_ms(doc)
    if selected_ms is None:
        invalid.append("selected_endpoint_metric_missing")
    elif not isinstance(selected_ms, (int, float)) or selected_ms <= 0:
        invalid.append("selected_endpoint_metric_nonpositive")
    if main_menu_ms is not None and presented_ms is not None and presented_ms < main_menu_ms:
        invalid.append("main_menu_presented_precedes_main_menu")

    process_cpu_ms = _get(doc, "metrics.process_cpu_ms")
    gc_time_ms = _get(doc, "metrics.gc_time_ms")
    available_memory_mib = _get(doc, "metrics.available_memory_mib")
    if process_cpu_ms is not None and process_cpu_ms < 0:
        invalid.append("negative_process_cpu")
    if gc_time_ms is not None and gc_time_ms < 0:
        invalid.append("negative_gc_time")
    if available_memory_mib is not None and available_memory_mib < 0:
        invalid.append("negative_available_memory")

    profile = _get(doc, "instrumentation.profile")
    perturbation = _get(doc, "instrumentation.observer_perturbation_known")
    if profile is None:
        inconclusive.append("instrumentation_profile_missing")
    if perturbation is True:
        if purpose == "phase_attribution":
            warnings.append("observer_perturbation_known:absolute_wall_not_production_comparable")
        else:
            inconclusive.append("observer_perturbation_known")
    elif perturbation is None:
        inconclusive.append("observer_perturbation_not_classified")

    # Host-pressure counters are contamination indicators. They never assign
    # cause to the JVM or to storage; they only gate a causal A/B interpretation.
    io_wait_p95 = _get(doc, "host_pressure.io_wait_p95_pct")
    blocked_max = _get(doc, "host_pressure.blocked_processes_max")
    swap_out_max = _get(doc, "host_pressure.swap_out_kib_s_max")
    stolen_max = _get(doc, "host_pressure.stolen_cpu_max_pct")
    if isinstance(io_wait_p95, (int, float)) and io_wait_p95 >= HOST_IOWAIT_P95_INCONCLUSIVE_PCT:
        inconclusive.append(f"host_iowait_p95_ge_{HOST_IOWAIT_P95_INCONCLUSIVE_PCT:g}pct")
    if isinstance(swap_out_max, (int, float)) and swap_out_max > 0:
        inconclusive.append("host_swap_out_observed")
    if (isinstance(blocked_max, (int, float)) and blocked_max > 0 and
            isinstance(io_wait_p95, (int, float)) and io_wait_p95 >= 10.0):
        inconclusive.append("blocked_io_with_material_iowait")
    if isinstance(stolen_max, (int, float)) and stolen_max >= HOST_STOLEN_MAX_INCONCLUSIVE_PCT:
        inconclusive.append(f"host_stolen_cpu_ge_{HOST_STOLEN_MAX_INCONCLUSIVE_PCT:g}pct")

    # Descriptive telemetry may be absent without invalidating a clean timing.
    # Missing data limits attribution and is surfaced as a warning, not as a
    # fabricated disk/GC/CPU conclusion.
    for path in ("metrics.process_cpu_ms", "metrics.gc_time_ms", "metrics.heap_used_mib", "metrics.available_memory_mib"):
        if _get(doc, path) is None:
            warnings.append(f"attribution_metric_missing:{path}")

    # Deduplicate while preserving first-seen order.
    invalid = list(dict.fromkeys(invalid))
    inconclusive = list(dict.fromkeys(inconclusive))
    warnings = list(dict.fromkeys(warnings))
    status = "invalid" if invalid else ("inconclusive" if inconclusive else "valid")
    return {
        "run_id": doc.get("run_id"),
        "status": status,
        "eligible_for_aggregation": status == "valid",
        "selected_endpoint_ms": selected_ms,
        "invalid_reasons": invalid,
        "inconclusive_reasons": inconclusive,
        "warnings": warnings,
    }


def compare_runs(control: dict[str, Any], candidate: dict[str, Any]) -> dict[str, Any]:
    control_gate = classify_run(control)
    candidate_gate = classify_run(candidate)
    invalid: list[str] = []
    inconclusive: list[str] = []
    warnings: list[str] = []

    if control_gate["status"] == "invalid":
        invalid.append("control_run_invalid")
    if candidate_gate["status"] == "invalid":
        invalid.append("candidate_run_invalid")

    hard_match_fields = (
        "origin",
        "endpoint",
        "clock.origin",
        "state.pack_fingerprint",
        "state.config_fingerprint",
        "state.jvm_fingerprint",
        "instrumentation.profile",
    )
    for path in hard_match_fields:
        left = _get(control, path)
        right = _get(candidate, path)
        if left is None or right is None:
            inconclusive.append(f"comparison_field_missing:{path}")
        elif left != right:
            invalid.append(f"comparison_mismatch:{path}")

    control_cache = _get(control, "state.cache_state")
    candidate_cache = _get(candidate, "state.cache_state")
    paired_same_vm = bool(_get(control, "pair.same_vm")) and bool(_get(candidate, "pair.same_vm"))
    if not paired_same_vm:
        if control_cache is None or candidate_cache is None:
            inconclusive.append("comparison_cache_state_missing")
        elif control_cache != candidate_cache:
            invalid.append("comparison_cache_state_mismatch")

    if paired_same_vm:
        control_pair = _get(control, "pair.id")
        candidate_pair = _get(candidate, "pair.id")
        if control_pair is None or candidate_pair is None or control_pair != candidate_pair:
            invalid.append("same_vm_pair_id_mismatch")
        order = _get(control, "pair.order") or _get(candidate, "pair.order")
        if order not in {"control->candidate", "candidate->control"}:
            invalid.append("same_vm_pair_order_missing_or_invalid")
        if control_cache != "paired_shared" or candidate_cache != "paired_shared":
            inconclusive.append("same_vm_pair_not_labelled_paired_shared")

    if control_gate["status"] == "inconclusive":
        inconclusive.append("control_run_inconclusive")
    if candidate_gate["status"] == "inconclusive":
        inconclusive.append("candidate_run_inconclusive")

    control_ms = _selected_endpoint_ms(control)
    candidate_ms = _selected_endpoint_ms(candidate)
    delta_ms = None
    delta_pct = None
    if isinstance(control_ms, (int, float)) and isinstance(candidate_ms, (int, float)):
        delta_ms = candidate_ms - control_ms
        if control_ms:
            delta_pct = delta_ms / control_ms * 100.0

    invalid = list(dict.fromkeys(invalid))
    inconclusive = list(dict.fromkeys(inconclusive))
    warnings = list(dict.fromkeys(warnings))
    status = "invalid" if invalid else ("inconclusive" if inconclusive else "valid")
    return {
        "status": status,
        "control": control_gate,
        "candidate": candidate_gate,
        "candidate_minus_control_ms": delta_ms,
        "candidate_minus_control_pct": delta_pct,
        "invalid_reasons": invalid,
        "inconclusive_reasons": inconclusive,
        "warnings": warnings,
        "semantics": "valid means comparable enough to enter aggregation; it does not by itself validate an optimization",
    }


def _read(path: Path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)

    single = sub.add_parser("single", help="classify one completed measurement sidecar")
    single.add_argument("result", type=Path)

    compare = sub.add_parser("compare", help="gate one completed control/candidate comparison")
    compare.add_argument("control", type=Path)
    compare.add_argument("candidate", type=Path)

    args = parser.parse_args()
    if args.command == "single":
        output = classify_run(_read(args.result))
    else:
        output = compare_runs(_read(args.control), _read(args.candidate))
    print(json.dumps(output, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
