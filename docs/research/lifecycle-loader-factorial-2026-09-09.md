# Exact-pack lifecycle / loader factorial — 2026-09-09

Status: **ACTIVE DIAGNOSTIC / NO PRODUCTION CLAIM**

Scope is intentionally limited to lifecycle/mod-construction/loader scaling before the resource/model pipeline. ModelBakery/ModelManager attribution and the post-reload tail are excluded. TTMM is used only to prove that a variant reaches the same `main_menu` endpoint and has a valid resource contract.

## Evidence inherited from PR #199

Only contract-valid hosted exact-pack observations are usable. The valid baseline/full observations were roughly 15–17 s and 89–92 s respectively. The first valid four-complement matrix (run `34257305504`) reached the same main-menu endpoint with `resource_contract_valid=true`, `diagnostic_only=false` and zero BootOptim/Mixin errors; its complements retained 54–60 artifacts and reached 32.5–38.8 s. A matching full control in `34257904410` reached 88.905 s. The later valid bisections `34258425229` and `34258839298` showed non-additive behavior: removing either half retained more time than removing the combined block.

Earlier broad subset/partition timings are not performance evidence. NarratorLinux/`flite`, Iris/Sodium, Bits_n_Bobs/Create, missing Kotlin runtime, FancyMenu/MCEF and other closure failures made those variants contract-invalid. Their TTMM values remain excluded.

## Why another root bisection is not enough

The previous matrix proved a structural scaling gap but could not say whether it belongs to lifecycle/loader or to the model/reload work removed by the same mod complements. A useful next experiment therefore treats TTMM only as an endpoint gate and computes a factorial interaction on a pre-reload metric.

Campaign `lifecycle-loader-create-connector-factorial-v1` tests two dependency-closed families:

- **A — Create runtime closure:** complement root `create`, with the existing explicit `create=com/simibubi/create/` bytecode-provider edge so undeclared Create consumers are removed rather than left broken.
- **B — Connector/Fabric bridge:** complement roots `connector,forgified_fabric_api`, preserving the full non-JAR pack state and all surviving dependency closures.

The four workloads are `full`, `remove-A`, `remove-B`, and `remove-A+B`. Known runtime families remain coupled (`FancyMenu/MCEF`, `TFMG/we_companion`, `Observable/KotlinForForge`, `Iris/Sodium`, `Bits_n_Bobs/Create`); `analogaudio` remains explicitly excluded on hosted Linux. The materializer inherited from #199 copies all non-JAR pack state and `mods/` non-JAR entries, including the preseeded `mods/mcef-libraries/` native tree.

## Contract and interpretation

The campaign manifest declares:

- planner authority: PR #199 head `ee79a4ebdcb4b83ba1635cb85fcd22ef871e1bd4`, planner schema 1;
- exact fixture: `exact-pack-2026-09-02-v1`, asset SHA-256 `7f586ecd90497a4d4aa1d2024af2643dbd64691864edbad9eb2ed40551c55639`;
- expected source-mod fingerprint: `6e9b250390ee2cbeb5e4f0ad3363d5159a51481dbfe1412f815882fc5d4ab990`;
- campaign-contract fingerprint: `dc02c082909b34d7be9eea40a42ff30f0d6a9b1b9c86d059610aac6fd009c463`;
- measurement origin: fresh hosted exact-pack VM;
- start marker: process origin; endpoint: `main_menu`;
- causal metric for this campaign: `mod_entrypoint_ms` only, explicitly treated as a **coarse pre-reload loader/bootstrap boundary**, not as pure mod-construction time;
- minimum: three fresh-VM repetitions per cell.

The interaction term is

`I = median(full) - median(remove-A) - median(remove-B) + median(remove-A+B)`.

A materially positive `I` means the retained A+B workload has super-additive cost before the mod entrypoint boundary. It is only a trigger for deeper source/trace attribution. It is not an optimization saving, and neither ModelBakery nor any post-reload duration may be substituted into this calculation.

The analyzer `scripts/exact-pack/analyze_lifecycle_factorial.py` rejects invalid resource contracts, diagnostic-only endpoints, Mixin errors, missing dependencies, wrong source fingerprints, forbidden reload/model/TTMM metrics, and campaigns with fewer than three fresh-VM repetitions per cell.

## Decision gate

Investigate source/lifecycle internals of the family pair only if all twelve hosted cells are contract-valid and the positive interaction is larger than fresh-VM range noise in a coherent direction. If the interaction is small, mixed-sign under repetition, or any cell requires relaxing the resource/native/dependency contract, close this family pair as a matrix no-go and do not escalate to invasive loader changes.
