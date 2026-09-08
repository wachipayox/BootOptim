# Root/FML discovery replay — Agent 55 — 2026-09-08

Status: **NO-GO for a production optimization; diagnostic evidence only. `sin evidencia física`.**

## Scope and authority

This investigation starts from the public integration authority `agent/integration-current` at exactly `fa6df8bc8f74aae32338f521bf845a5730ac634b`. It covers only the early ModLauncher/FML root/dependency-discovery surface before the client resource reload. It does not reopen the production mod-scan cache, dependency/JarJar persistence rejected by #134, ModernFix reload-pool experiments, or stale-JVM diagnostics.

The exact-pack fixture is the same public contract used by #183:

- release/tag: `exact-pack-2026-09-02-v1`;
- asset: `bootoptim-exact-pack.zip`;
- SHA-256: `7f586ecd90497a4d4aa1d2024af2643dbd64691864edbad9eb2ed40551c55639`;
- same BootOptim process-origin markers and `main_menu` endpoint as exact-pack CI.

#183's provenance/digest discipline is reused as a fixture/selection contract only. BlockModel replay semantics are not involved here.

## New premise tested

PR #145 identified a BootOptim-owned serial boundary that is distinct from #134: `DiscoveryStartLocator.locateWrapper()` only accepts a plain `file:` `CodeSource`. If SecureJarHandler exposes another scheme, the fallback enumerates `mods/` and opens each JAR with `ZipFile` to find BootOptim's wrapper marker before normal root discovery proceeds.

Source review confirmed that SecureJarHandler's module `CodeSource` is derived from its module reference URI, and its union filesystem exposes a physical `primaryPath`. Therefore a future direct live-origin lookup could, in principle, verify one physical wrapper instead of scanning every mod JAR, while falling back to the current path on any mismatch. This would not require a persistent JAR cache or suppress an FML locator/callback.

The premise was measured before any such runtime change.

## Diagnostic

Draft PR #185 adds default-off, fail-open, low-cardinality diagnostics under:

`-Dboot_optim.profileDiscoveryDetail=true`

It records:

- existing root/dependency wall boundaries;
- cumulative process CPU, current-thread CPU, loaded-class, JIT and GC snapshots at those boundaries;
- BootOptim wrapper lookup: `CodeSource` scheme, lookup mode, candidate-JAR count, actual `ZipFile` opens, found/miss and wall;
- nested-wrapper extraction outcome/wall when applicable.

No cache, locator reorder, skipped callback, executor change, module/classpath replacement, Java/OS change, or gameplay behavior is introduced.

## Exact-pack replay

Actions run: `34175121041` (three same-VM pairs, six fresh Minecraft JVMs). Both labels execute identical diagnostics; an unused tag is the only label difference. This run is a replay/noise diagnostic, **not** a candidate performance A/B.

All six JVMs reached the same `main_menu` endpoint with zero BootOptim Mixin failures.

Observed in every JVM:

- `code_source_scheme=union`;
- `mode=non_file_code_source_then_mods_scan`;
- `candidate_jars=160`;
- `jar_opens=160`;
- `found=false` in the ModDevGradle exact-pack environment.

The exact-pack development run injects BootOptim separately, so absence of a packaged BootOptim wrapper from the 160 fixture JARs is expected. The scan cost remains a useful upper representation of opening the exact pack's mod-JAR set; it is not proof that the packaged Windows launch resolves the wrapper identically.

### Per-JVM measurements

| pair | process position | wrapper scan ms | root wall ms | dependency wall ms | TTMM ms |
| --- | --- | ---: | ---: | ---: | ---: |
| 1 | first | 39.978 | 566.369 | 7253.970 | 89866 |
| 1 | second | 39.345 | 576.104 | 7197.552 | 91137 |
| 2 | first | 35.688 | 540.008 | 7510.614 | 90421 |
| 2 | second | 37.742 | 510.165 | 7599.972 | 89499 |
| 3 | first | 33.744 | 507.930 | 7218.074 | 84617 |
| 3 | second | 27.728 | 444.273 | 7316.334 | 84915 |

All-six medians:

- wrapper fallback: **36.715 ms wall**;
- root discovery: **525.087 ms wall**;
- root process CPU delta: **1795 ms**; current-thread CPU delta **486.035 ms**;
- dependency discovery: **7285.152 ms wall**;
- dependency process CPU delta: **20220 ms**; current-thread CPU delta **4506.431 ms**;
- TTMM: **89682.5 ms**;
- mod entrypoint marker: **29970 ms**.

The process-CPU/wall ratios are approximately 3.42 equivalent cores for root and 2.78 for dependency discovery under the hosted `ActiveProcessorCount=4` profile. That is evidence of substantial CPU/parallel work in the aggregate boundaries, not a low-CPU I/O stall classification.

First-process versus second-process medians were:

- wrapper: 35.688 -> 37.742 ms;
- root: 540.008 -> 510.165 ms;
- dependency: 7253.970 -> 7316.334 ms;
- TTMM: 89866 -> 89499 ms;
- mod entrypoint: 29858 -> 30082 ms.

The order alternates between pairs, and these labels run identical code. Those differences are noise/page-cache context, not speed evidence.

## Important warm-cache limitation

The existing paired exact-pack helper is not a valid warm mod-scan-cache replay for #134 because it invokes the Gradle run with `--rerun-tasks` for both processes. In these six JVMs the scan cache remained cold: about 239–240 `miss` rows, zero `hit` rows, and a scan window around 3.4–4.1 s. Therefore this run must **not** be cited as a warm residual measurement for dependency/JarJar discovery.

Fixing that harness is a separate measurement task: a future warm-discovery replay must preserve the prepared game directory and `.bootoptim` between fresh Minecraft JVMs without carrying over a JVM, while leaving the process-origin/main-menu measurement clock unchanged.

## Scale estimate for the old laptop

The exact-pack wrapper fallback is a real serial root-boundary cost, but its hosted median is only 36.715 ms. Applying the task's deliberately conservative old-hardware sensitivity factor of 20x gives approximately **734 ms**. Against the observed laptop root/FML interval of roughly 18–21 s, that is only about **3.5–4.1%** even under this aggressive scale assumption.

This is too small to justify a production candidate or a full TTMM A/B campaign. It also cannot explain the separate 28–34 s vanilla/ModernFix-visible Bootstrap interval.

No class/file count is treated as a menu improvement.

## I/O and page-fault classification

This low-cardinality replay measures wall and JVM/process CPU but does not provide process-specific page-fault or storage-I/O attribution. The paired host `vmstat` artifact is host-wide and is not sufficient to label the wrapper wall or dependency residual as disk/page-cache time. Consequently there is **no disk or page-fault performance claim** here.

The physical FilePackResources evidence in #140/#141 proves the laptop can be page-cache/storage sensitive elsewhere, but it does not make this early boundary disk-bound by inference.

## Decision

**NO-GO. Do not implement direct union-origin lookup, a persistent wrapper-location cache, another dependency/JarJar cache, or generic classpath/metadata persistence from this evidence.**

Why:

1. The new BootOptim-owned fallback is real and deterministic, but only ~36.7 ms hosted.
2. Even a 20x old-hardware scale estimate caps the likely opportunity below one second and below ~5% of the laptop root/FML interval.
3. The aggregate root/dependency boundaries show high process CPU, so the evidence does not point to a dominant serial storage wait that this wrapper lookup would solve.
4. The exact-pack paired helper rebuilt the prepared instance and therefore did not produce the warm scan-cache residual required to reopen #134.
5. A direct `union:` primary-path optimization would be fail-open and architecturally plausible, but the hosted exact-pack development lifecycle lacks the packaged wrapper needed to exercise its hit path. Shipping it from source plausibility plus JAR-open counts would violate the project's TTMM evidence rule.

## Reopening / one-pass physical protocol

Status remains **`sin evidencia física`** for this specific wrapper mechanism.

Only reopen the live-origin candidate after one normal packaged-laptop diagnostic (not an A/B series) using the same BootOptim startup/mod-entrypoint/menu markers and `-Dboot_optim.profileDiscoveryDetail=true`. Keep the normal Java/OS/pack selection and ensure no stale JVM survives preparation. Record:

- `BOOTOPTIM_DISCOVERY_SELF phase=wrapper_lookup`;
- root/dependency boundary snapshots;
- scan-cache hit/miss/fallback counts;
- process CPU and, if available without observer distortion, bounded OS file-I/O/page-fault counters for the Minecraft process.

Reopen direct live-origin resolution only if the packaged run proves the fallback is active **and** wrapper lookup is >=500 ms or >=10% of root-discovery wall. The candidate must verify the live physical primary path as BootOptim's wrapper, fail back to the current scan on every mismatch/error, preserve all FML callbacks/order, and be judged by root barrier plus TTMM rather than JAR-open count.

Separately, #134 may only be reopened after a true two-fresh-JVM warm-cache exact-pack replay that actually shows scan-cache hits and attributes the remaining dependency-locator wall. This PR does not supply that evidence.

## Evidence

- diagnostic PR: https://github.com/wachipayox/BootOptim/pull/185
- diagnostic head before this note: `f2e7742ec1a454b5f87829bd9605ed56965d69b4`
- Build run: https://github.com/wachipayox/BootOptim/actions/runs/34175121027
- Startup Benchmark run: https://github.com/wachipayox/BootOptim/actions/runs/34175121067
- exact-pack replay run: https://github.com/wachipayox/BootOptim/actions/runs/34175121041
- pair artifacts: `10037075515`, `10037075674`, `10037071791`
- summary artifact: `10037078916`
- #183 provenance reference: https://github.com/wachipayox/BootOptim/pull/183
