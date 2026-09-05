# MoreCulling reload-local translucency reuse — 2026-09-05

Status: **LIMITED / REJECTED — no production promotion**

Diagnostic precursor: PR #108. Candidate experiment: PR #119, closed without merge.

## Premise and safety boundary

MoreCulling 1.0.8's `SpriteUtils.doesHaveTranslucency(NativeImage, List, int, int, int, int)` is pure for `orMatch == null`: it checks the image format and exact integer pixel bounds. The candidate reused results only for identical `NativeImage` identity and bounds during one resource-reload generation. `originalImage` is separate from mipmaps and animated interpolation buffers; the cache was cleared at reload start and `ReloadInstance.done()`, default-off, fail-open, and did not touch model hooks or gameplay.

The semantic proof is useful but not sufficient for shipping. The diagnostic exact-pack run `33975337605` measured 602,308 scans, 450,293 exact repeats, 0 mismatches, and 395.731 ms direct repeat wall under a 4,096-key observation cap. That is a direct-work ceiling, not a TTMM promise.

## Candidate gates

PR #119 candidate head `8d84c239752b859667901d62f72fedea3786f708` passed build `33981523735`, startup `33981523739`, and smoke `33981523741`: exact resource selection (10 ZIPs in order), one reload, atlas `8192x8192x2`, menu, zero BootOptim Mixin errors, and marker `hits=451707 misses=150713 stores=4096 saturated=146617 failed_open=false`.

## End-to-end result

Two independent hosted exact-pack 3×3 campaigns both passed semantic gates but contradicted each other:

| Campaign | Candidate TTMM | Control TTMM | Candidate − control | Candidate reload→FancyMenu | Control reload→FancyMenu |
| --- | ---: | ---: | ---: | ---: | ---: |
| `33981861574` | 91.878 s | 76.873 s | **+15.005 s (+19.52%)** | 41.488 s | 35.846 s |
| `33981907944` | 91.738 s | 94.512 s | **−2.774 s (−2.94%)** | 42.711 s | 44.016 s |

All twelve runs retained the exact pack, atlas and zero Mixin errors. The candidate's global `synchronized` monitor on every lookup/store is an avoidable hot-path risk, but the duplicate campaign means the multi-second magnitude cannot be attributed to that lock alone. The stable conclusion is that the sub-second diagnostic ceiling did not become a reproducible TTMM or critical-path win, and the implementation risk is not justified.

## Decision

**Do not merge or request a laptop run.** PR #119 is closed without merge. Reopen only with a materially different lock-free/sharded design that preserves exact identity/bounds equivalence, strict reload invalidation, fail-open behavior, and concurrency/lifetime tests, followed by a new paired hosted A/B controlling MCEF/cache/order. Do not generalize this result to uncovered MoreCulling model hooks.
