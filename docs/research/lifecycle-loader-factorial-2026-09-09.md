# Exact-pack lifecycle / loader factorial — 2026-09-09

Status: **CLOSED MATRIX NO-GO / DIAGNOSTIC TOOLING RETAINED**

Scope is intentionally limited to lifecycle/mod-construction/loader scaling before the resource/model pipeline. ModelBakery/ModelManager attribution and the post-reload tail are excluded. TTMM is used only to prove that a variant reaches the same `main_menu` endpoint and has a valid resource contract; no TTMM value is used below as causal evidence.

## Evidence inherited from PR #199

Only contract-valid hosted exact-pack observations are usable. The valid baseline/full observations were roughly 15–17 s and 89–92 s respectively. The first valid four-complement matrix (run `34257305504`) reached the same main-menu endpoint with `resource_contract_valid=true`, `diagnostic_only=false` and zero BootOptim/Mixin errors; its complements retained 54–60 artifacts and reached 32.5–38.8 s. A matching full control in `34257904410` reached 88.905 s. The later valid bisections `34258425229` and `34258839298` showed non-additive behavior: removing either half retained more time than removing the combined block.

Earlier broad subset/partition timings are not performance evidence. NarratorLinux/`flite`, Iris/Sodium, Bits_n_Bobs/Create, missing Kotlin runtime, FancyMenu/MCEF and other closure failures made those variants contract-invalid. Their TTMM values remain excluded.

## Hardened campaign contract

Campaign `lifecycle-loader-create-connector-factorial-v1` was designed around two dependency-closed families:

- **A — Create runtime closure:** complement root `create`, with the explicit `create=com/simibubi/create/` bytecode-provider edge so undeclared Create consumers are removed rather than left broken.
- **B — Connector/Fabric bridge:** complement roots `connector,forgified_fabric_api`, preserving the full non-JAR pack state and all surviving dependency closures.

Known runtime families stay coupled (`FancyMenu/MCEF`, `TFMG/we_companion`, `Observable/KotlinForForge`, `Iris/Sodium`, `Bits_n_Bobs/Create`). `analogaudio` is excluded in every cell because the hosted Linux environment is not a valid AnalogAudio control. The control is therefore a **full-like complement**, not planner `full`; this avoids the first attempted dispatch's control mismatch. The materializer copies all non-JAR pack state and `mods/` non-JAR entries, including the preseeded `mods/mcef-libraries/` native tree.

The campaign declares:

- planner authority: PR #199 head `ee79a4ebdcb4b83ba1635cb85fcd22ef871e1bd4`, planner schema 1;
- exact fixture: `exact-pack-2026-09-02-v1`, asset SHA-256 `7f586ecd90497a4d4aa1d2024af2643dbd64691864edbad9eb2ed40551c55639`;
- expected source-mod fingerprint: `6e9b250390ee2cbeb5e4f0ad3363d5159a51481dbfe1412f815882fc5d4ab990`;
- campaign-contract fingerprint: `1d528c0ed4c6d585fd4cd023b7b0f1b7f78e5d41ce04d826949d47816edf717d`;
- measurement origin: fresh hosted exact-pack VM;
- start marker: process origin; endpoint: `main_menu`;
- causal metric: `mod_entrypoint_ms` only, a **coarse pre-reload loader/bootstrap boundary**, not pure mod-construction time;
- minimum: three fresh hosted VMs per cell.

Because scaling mode in the inherited workflow emits one job per variant ID regardless of `exact-pack-repetitions`, the hardened run uses three alias IDs per cell. The analyzer requires the three aliases in each cell to materialize byte-for-byte identical selected-artifact sets before using their timings.

## Hosted exact-pack run 34293964626

Run `34293964626` completed successfully. All twelve jobs reached the main-menu endpoint, had `resource_contract_valid=true`, `diagnostic_only=false`, zero BootOptim/Mixin errors, the exact source fingerprint above, no final missing dependencies, and identical resource-selection state. Each alias within a cell materialized the same selected-artifact and selected-mod-ID sets.

Only the in-scope pre-reload metric is summarized:

| Cell | Selected artifacts | Selected mod IDs | `mod_entrypoint_ms` fresh VMs | Median | Range |
| --- | ---: | ---: | --- | ---: | ---: |
| full-like control | 155 | 224 | 31,591 / 29,910 / 21,005 | 29,910 | 10,586 |
| remove A: Create closure | 110 | 171 | 25,445 / 25,393 / 26,740 | 25,445 | 1,347 |
| remove B: Connector/Fabric closure | 93 | 100 | 18,468 / 18,519 / 19,228 | 18,519 | 760 |
| remove A+B | 94 | 101 | 18,974 / 18,397 / 18,861 | 18,861 | 577 |

The control variance is materially larger than the nominal interaction scale. Hosted runner snapshots show the same exposed 4 CPUs, 15 GiB RAM class and Azure kernel across these cells, so the 10.586 s control range cannot be justified away as a declared hardware-class change. It remains fresh-VM variance and bounds interpretation.

## Structural factorial failure

The run is runtime/resource-contract-valid, but **the intended 2x2 interaction is not structurally identifiable**.

The materialized selected sets show:

1. `remove-B` is a strict subset of `remove-A`: removing Connector/Forgified-Fabric removes 62 of the control's 155 selected artifacts, and those 62 include **all 45 artifacts removed by the Create factor**. In set terms, `removed(A) ⊂ removed(B)`. The B cell therefore has no state in which Connector/Fabric is absent while the Create closure remains present; the two factors are not independently crossed.
2. `remove-A+B` selects 94 artifacts while `remove-B` selects 93. The extra artifact is `CreateFood -20- {v2.5.0} [1.21.1] [SYNC].jar`. Removing an additional factor must not re-add work relative to a single-factor complement. This violates monotonic closure behavior for a factorial comparison.

If one ignores those structural failures, the median formula would numerically produce `29,910 - 25,445 - 18,519 + 18,861 = +4,807 ms`. **That number is not an interaction result and must not be used for attribution or optimization decisions.** It is also smaller than the 10.586 s full-like control range.

`analyze_lifecycle_factorial.py` is hardened to reject both conditions before emitting any interaction: `remove-A+B` may not re-add artifacts relative to either single-factor cell, fresh-VM aliases must materialize identical sets, and neither factor's removal closure may completely subsume the other's.

## Decision

**Close Create × Connector/Forgified-Fabric as a factorial matrix no-go.** Do not launch an invasive loader optimization from the nominal +4.807 s value and do not rerun this same pair with more repetitions: dependency topology prevents the independent crossing that the statistic requires.

The concrete next family worth source/lifecycle investigation is the **Connector/Forgified-Fabric dependent closure as one composite family**, not Connector in isolation and not a claimed Create interaction. Its contract-valid `remove-B` runs are tightly grouped at 18.468–19.228 s while the full-like control is 21.005–31.591 s; even the slowest remove-B run is 1.777 s before the fastest control at the coarse `mod_entrypoint` boundary. The median separation is 11.391 s, but that is only a screening delta because the closure removes 62 artifacts including the entire Create closure and the control VM range is large.

Therefore the next acceptable step is **in-situ full-pack tracing/source attribution inside that composite loader/lifecycle family**, preserving every mod and resource, to determine whether time belongs to Connector/Fabric bridge initialization, dependent mod construction, or another pre-reload boundary. Create-alone removal is not selected from this campaign because its 25.393–26.740 s band lies inside the full-like control's 21.005–31.591 s envelope. No production promotion, laptop gate, ModelBakery attribution, or post-reload claim follows from this matrix.
