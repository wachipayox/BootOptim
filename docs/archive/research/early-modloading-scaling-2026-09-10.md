# Early modloading scaling matrix — 2026-09-10

Status: **HOSTED DIAGNOSTIC / NO OPTIMIZATION / NO LAPTOP EQUIVALENCE**

## Scope

Agent 107 extends the closure-safe exact-pack scaling work from PR #199 with the already-profiled structured boot trace edge from PR #205. The question is limited to early modloading: root discovery, dependency discovery, the literal BootOptim transformation-service callback -> Minecraft Bootstrap transition, and the existing mod-entrypoint endpoint. ModelManager and resource-reload time are not used to assign early-modloading cost.

This branch is intentionally stacked diagnostic work. Its runtime authority is `agent/integration-current` at `fa6df8bc8f74aae32338f521bf845a5730ac634b`, plus the open diagnostic dependencies #199 and #205. It must be rebased after those dependencies are resolved and is not production optimization code.

## Measurement contract

Hosted run `34491728782` used the pinned exact-pack fixture and the same process origin / `main_menu` endpoint for `full` and four balanced complements. Every admitted point has:

- `resource_contract_valid=true` and `diagnostic_only=false`;
- source-pack fingerprint `6e9b250390ee2cbeb5e4f0ad3363d5159a51481dbfe1412f815882fc5d4ab990`;
- structured trace schema `bootoptim.boottrace` v1;
- `measurement_origin=hosted_exact_pack`, endpoint `main_menu`;
- no dropped trace events, flush/development-sink failures, or trace error events;
- zero BootOptim Mixin errors.

The new `scripts/exact-pack/enrich_early_modloading.py` fails closed for scaling points that violate those conditions. It requires unique balanced root/dependency task pairs and a unique `modlauncher_transformers_to_minecraft_bootstrap` phase pair before attaching `early_modloading` metrics to `result.json`.

`transform_to_bootstrap_ms` is the inclusive monotonic interval from `EarlyStartupProbeService.transformers()` to Minecraft Bootstrap entry. It can contain service-transformer registration, launch-plugin/target work, GAME layer creation, classloading/transformation/Mixin work and other work. It is not exclusive transformer CPU and must not be added to child task walls as a saving.

## Results

| variant | artifacts | root discovery ms | dependency discovery ms | dep -> transform gap ms | transform -> Bootstrap ms | Minecraft Bootstrap ms | FML gather/init ms | mod entrypoint ms | main menu ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| complement-1 | 52 | 205.248 | 543.842 | 271.422 | 4,250.459 | 3,299.537 | 793.618 | 12,237 | 27,767 |
| complement-2 | 56 | 213.788 | 533.396 | 278.005 | 3,763.157 | 3,819.903 | 928.330 | 11,626 | 31,931 |
| complement-3 | 54 | 245.188 | 529.266 | 1,564.343 | 4,679.731 | 4,229.060 | 651.884 | 16,931 | 32,474 |
| complement-4 | 59 | 255.301 | 230.065 | 1,349.896 | 5,034.662 | 3,144.438 | 1,245.702 | 15,373 | 38,939 |
| full | 160 | 484.832 | 7,320.187 | 1,331.041 | 8,469.389 | 4,712.418 | 5,460.758 | 29,990 | 91,968 |

These are one fresh hosted process per variant. The table establishes structural scale, not per-root or per-mod savings. Balanced complements remove interacting dependency-closed sets, and the exact excluded remainder differs by variant; subtracting a complement from `full` is therefore not a causal budget for its directly omitted roots.

## Interpretation

The strongest early nonlinear signal is **dependency discovery**. Full is 7.320 s while all four valid complements are 0.230–0.544 s, despite the complements retaining 52–59 artifacts. This is materially stronger scaling than root discovery (0.485 s full vs 0.205–0.255 s complements) and is consistent with a workload dominated by artifact metadata/dependency traversal and JAR/filesystem work rather than a fixed loader overhead.

`fml_gather_and_initialize_mods` is the second strong early signal: 5.461 s full versus 0.652–1.246 s in the complements. That points at a separate classloading/construction/callback family rather than explaining the discovery result away. The pre-Bootstrap transition also scales materially, 8.469 s full versus 3.763–5.035 s, but its inclusive scope is broader than transformation alone. Minecraft Bootstrap itself changes much less, 4.712 s full versus 3.144–4.229 s.

The full mod-entrypoint endpoint is 29.990 s versus 11.626–16.931 s for the complements, confirming that a substantial part of the hosted full-pack gap exists before ModelManager/resource reload. No resource-reload phase is used to apportion that gap.

## Priority frontier

The next high-information experiments should remain closure-safe and target **families**, not individual filenames selected from raw timing subtraction:

1. Metadata/JAR/dependency-heavy hubs first, because dependency discovery shows the largest nonlinear scaling. Candidate families visible in the valid complement closures include the Create/addon graph and Connector/Forgified-Fabric API graph, both of which carry broad dependency or embedded/co-located metadata relationships in this pack. Construct narrower complement groups around these families and preserve the explicit runtime-symbol/provider rules established by #199.
2. Classloading/transformation-heavy families second, because the pre-Bootstrap transition remains 8.469 s full and `fml_gather_and_initialize_mods` grows to 5.461 s. Candidate follow-up families include the broad Mixin/render stack (Iris/Sodium and compatibility partners) and other closure-heavy transformation ecosystems, but the current matrix does **not** assign their time individually.
3. Do not resume arbitrary root-only bisection. #199 already showed non-additive interaction under bisection; phase-specific closure complements are now the higher-information design.

These priorities are plausible for an old CPU/HDD because they exercise metadata/JAR I/O, dependency parsing/traversal, classloading and transformation. That is a workload hypothesis only. Hosted Linux software rendering is not the laptop, and there is **sin evidencia física** for equivalence or savings.

## Decision

Keep the new enrichment/tooling as diagnostic infrastructure and retain the result as evidence that early modloading contains multiple independently scaling fronts: dependency discovery first, FML gather/init second, and the inclusive pre-Bootstrap transition third. Do not implement an optimization from these aggregate data. The next agent should create narrower versioned closure-safe family variants and require the same fingerprint/origin/endpoint/resource/trace gates before making a mod-specific claim.
