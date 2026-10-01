# Versioned custom-launcher architecture evaluation — 2026-09-13

Status: **RESEARCH COMPLETE / PROTOTYPE RECOMMENDED, NOT AUTHORIZED OR IMPLEMENTED**

Agent 148 evaluated whether a launcher owned by the exact BootOptim modpack could create startup opportunities that are awkward or impossible to make robust through Prism alone. This document is design and measurement research only. It does not add a launcher, change Java, modify the OS, stage a user cache, generate an AppCDS archive, or claim any time saving.

Base authority: `agent/integration-current@b3f0c5f6462a359483883741ac16d2f154868900` (refreshed and still current on 2026-09-13).

## Required context and one missing document

Read/verified before this evaluation:

- `AGENTS.md` and `README.md` at the authority SHA;
- `docs/research/README.md` and `docs/research/exact-pack-ci.md`;
- PR #260 (packaged AppCDS identity/fail-open design);
- PR #266 (production-JAR hosted AppCDS functional harness);
- PR #280 (pre-generated Prism-relocatable `.jsa` NO-GO).

The requested `docs/research/boot-pipeline-program-2026-09-08.md` is not present in the public tree at the authority SHA. This is not a new discovery: PR #200 also recorded that the assignment-requested file was absent from refreshed public integration and repository search. Its contents are therefore not inferred or reconstructed here.

## Executive decision

A custom launcher is **worth a narrow prototype**, but only to answer one high-value unresolved question: does a **locally generated, per-install AppCDS archive** materially reduce `Minecraft process start -> usable main menu` on the exact pack while remaining fail-open and preserving the ModLauncher/Mixin/FML surface?

The case for a prototype is evidence-based but not a performance claim:

- #266 proved same-tuple functional feasibility: a fresh process consumed a temporary dynamic archive and recorded **4,641 application/custom-runtime shared-class hits**, while preserving exact resource selection (14/14, in order) and `reload_count=1`.
- #266 did **not** run an A/B TTMM experiment, so there is no seconds-saved claim.
- #280 proved a pre-generated archive cannot be treated as a relocatable Prism artifact because the production class path contains installation-specific absolute paths and AppCDS binds archive creation/use to class-path identity.
- A launcher that owns the pre-JVM lifecycle can instead generate the archive **locally under the user's actual Java/OS/classpath/pack tuple**, then consume it only on later launches after an exact identity match.

Most other launcher ideas in scope either improve reproducibility/support or merely move work before process start. They should not be sold as BootOptim TTMM wins.

## Measurement vocabulary

Every launcher experiment must report separate buckets. Never collapse them into one number:

1. **launcher/setup wall** — launcher entry to Java process creation, including verification, downloads, hashing, cache preparation and lock waits;
2. **process-start -> main-menu** — BootOptim's core optimization metric, measured with the same endpoint in control and candidate;
3. **process-start -> main-menu-presented** when that later marker is available, reported separately rather than substituted silently;
4. **first-run/training cost** — any archive generation/training work, reported as provisioning cost and excluded from steady-state TTMM savings;
5. **subsequent-run benefit** — only launches that consume an already-generated archive may be used for AppCDS steady-state A/B;
6. **cold versus warm state** — fresh VM/page-cache surrogate and same-VM warm-page-cache measurements are different populations;
7. **outside-process work** — a shorter JVM startup caused by reading/hashing/prefetching before Java starts is a shifted cost unless launcher-entry -> menu also improves.

A launcher may improve user-visible click-to-menu while not improving process-start -> menu, or vice versa. Both can be useful, but they are different claims.

## Mechanism classification

| Mechanism | Classification | Expected effect on process-start -> menu | What must be measured before promotion |
|---|---|---|---|
| Local per-install AppCDS, consumed only on exact tuple match | **1 — probable process-start candidate** | Plausible positive effect because #266 proved 4,641 application/custom-runtime shared-class hits; magnitude unknown without A/B | Stock-vs-CDS fresh-process A/B, exact same endpoint, mechanism hit proof, first-run vs subsequent-run split, physical Windows/laptop gate |
| Deterministic JVM/classpath argfile owned by launcher | **1 as enabler; near-zero direct win expected** | Mainly removes launch-command variance and makes AppCDS identity reproducible; by itself should not be credited with TTMM savings | Byte-for-byte launch topology comparison and no semantic change; measure only if command representation itself is changed |
| Prevalidation of pack/JARs before Java | **2 — shifted work/support feature** | Can make the Java process start from a known-good state but hashing is outside the process; no intrinsic TTMM win | Launcher-entry -> menu and prelaunch hashing wall; incremental versus full verification cost |
| Download/repair of missing libraries/assets/modpack files before Java | **2 — shifted work**, except when it prevents an in-process downloader from doing the same work | Usually moves network/extraction out of the JVM startup interval; improves reliability and possibly click-to-menu, not automatically BootOptim TTMM | Separate download/repair wall, process TTMM after staging, and evidence that an in-process downloader was actually removed from the measured interval |
| Staging immutable producer-supported artifacts (for example pinned runtime libraries) | **2 — shifted work**, potentially removes process work when stock would provision them during startup | May reduce process TTMM on cache-miss launches, but the cost has merely been moved to launcher provisioning unless overlapped with unavoidable setup | Miss/hit A/B plus launcher-entry -> menu; exact producer/version/hash contract |
| Synthesizing BootOptim scan/model/resource caches in launcher | **3 for now — too fragile / not authorized** | Could theoretically remove process work, but duplicates internal cache semantics and invalidation logic | Reopen only with an explicit offline cache-builder contract owned by the producer and exact equivalence/invalidation tests |
| OS page-cache/JAR warm-up (read files before Java) | **2 — shifted work / diagnostic only** | May shorten process TTMM by making I/O warm; does not remove work and is highly hardware/page-cache dependent | Report launcher warm-up wall and total click-to-menu; cold and warm populations separately; laptop gate mandatory |
| General JVM "warm-up" by launching throwaway Java/Minecraft processes | **3 — not acceptable as an optimization** | Hides work in a prior process and may pollute OS/JIT/cache state | Do not promote; only use deliberately as a diagnostic state when labelled warm |
| Control of the user's already-selected JVM path and exact JVM args | **1 as reproducibility/AppCDS enabler** | No direct saving assumed; makes tuple fingerprinting and safe archive reuse possible | Exact Java identity and command capture; stock equivalence |
| Optional bundled JVM | **separate variant, not assumed** | Could improve reproducibility and perhaps performance, but changes vendor/build/runtime and distribution/support surface | Separate compatibility + license/distribution review + A/B. Must not be required for the local-AppCDS prototype |
| Global Java/OS modification, replacing user's Java installation, registry/system tuning | **3 — prohibited/too invasive** | Outside project safety requirement | Do not implement |

Classification 1 means a mechanism can plausibly reduce the measured Minecraft-process interval. It does **not** mean that a saving has been measured.

## What a custom launcher actually adds over Prism

Prism already supports per-instance Java selection/JVM arguments and pre-launch custom commands. Its custom-command environment exposes the selected Java executable and absolute instance paths. Therefore several ideas are technically scriptable around Prism.

The custom-launcher advantage is not magical access to a faster JVM. It is **ownership of the complete launch transaction**:

- one versioned component can resolve the instance manifest, verify it, select the Java executable, construct the final ordered arguments/class path and spawn Java;
- AppCDS generation/consumption can be a first-class state machine rather than a fragile interaction between `instance.cfg`, a custom pre-launch script and Prism's separately constructed final class path;
- the launcher can atomically own per-instance cache metadata and locking before any JVM exists;
- it can record launcher-start, process-start and child endpoint timestamps in one measurement record;
- it can fail open to the exact stock launch command if any optimization-side precondition fails;
- it avoids relying on current Prism custom-command quoting/path behavior for correctness-sensitive archive activation.

This distinction is primarily **control and supportability**. Prevalidation, downloads, Java selection and custom commands are not categorically impossible in Prism; a dedicated launcher makes their exact ordering, versioning and failure semantics enforceable as part of the pack.

## Minimal launcher architecture for an evaluation prototype

The prototype, if separately authorized, should be deliberately smaller than a general Minecraft launcher. It should not implement accounts, a mod browser, a full updater UI, or a bundled JVM in v0.

### 1. Versioned instance manifest

A signed/authenticated or distribution-hash-covered manifest describes the exact pack version and immutable launch inputs. At minimum record:

- manifest/schema version;
- Minecraft/NeoForge/launcher bootstrap versions;
- ordered production Java class path and launch/module arguments;
- expected hashes/sizes for launcher/game/library JARs;
- all top-level mod JAR hashes and BootOptim bootstrap hash;
- enabled launch-affecting configuration identity needed by the exact pack;
- resource-pack selection fingerprint for experiment comparability even though resources are not themselves AppCDS class bytes;
- allowed Java major/vendor policy for this pack.

Downloads may repair only files declared by the trusted manifest. User-owned saves, options and unrelated mutable files are outside automatic repair unless explicitly versioned.

### 2. Java resolver

Default variant: use the user's configured Java executable; do not install or alter Java globally.

Record a strong Java tuple including:

- OS and architecture;
- normalized Java executable path;
- SHA-256 of the Java executable;
- SHA-256 of the runtime `release` metadata when present;
- reported vendor, runtime version and full build string;
- relevant CDS/JVM flags;
- absence of `-javaagent`, `-agentlib`, `-agentpath` and unsupported archive-generation escape hatches.

A bundled JVM is a different product variant and must not be smuggled into the prototype. If explored later, it needs an independent redistribution/license/security-update and performance decision.

### 3. Deterministic launch builder

Construct one canonical argument vector and optionally materialize it as an `@argfile` for Windows command-line length and quoting stability.

Rules:

- preserve exact argument ordering and duplicate arguments where the stock launch requires them;
- preserve class-path order;
- do not normalize HashMap-derived or callback order from the game/mod runtime;
- do not reorder module/transform/service inputs to make fingerprints prettier;
- quote/escape according to Java argfile rules, not shell rules;
- record a redacted command digest plus a separate ordered path/hash manifest;
- secrets/tokens never enter diagnostic logs.

For an initial prototype, keep the production class-path representation stable between generation and consumption rather than trying to invent a relocation scheme. Instance movement should invalidate the local archive and regenerate it.

### 4. Preflight / verification

The launcher verifies enough state to know whether it can safely use the local optimization cache.

Security identity for AppCDS must ultimately be cryptographic. A metadata sidecar may accelerate repeated checks only after a full strong-hash pass and only with explicit invalidation. Never treat size/mtime alone as the security identity.

Preflight failure must not block a valid stock launch merely because the optimization cache is unavailable. Distinguish:

- **required pack corruption/missing required launch file** -> normal launcher repair/error path;
- **optimization identity mismatch/cache corruption** -> delete/ignore optimization metadata and launch stock;
- **telemetry/logging failure** -> launch stock.

### 5. Local AppCDS cache state machine

Use a per-instance cache directory, e.g. `.bootoptim-launcher/appcds/`, owned by the launcher and never by the Java installation.

Tuple key should bind at least:

- Java tuple above;
- OS/arch;
- exact ordered final class-path strings plus hashes;
- launch/bootstrap/library/mod hashes;
- transform-affecting JVM arguments and agent absence;
- pack manifest identity.

States:

- `ABSENT`: no archive for this tuple. Launch stock-compatible **generation run** with explicit `-XX:ArchiveClassesAtExit=<staging-file>` if generation has been authorized for this run. This run is not a performance candidate.
- `GENERATING`: exclusive per-tuple lock held. A concurrent launcher must not wait indefinitely or share a half-written archive; it launches stock without CDS or reports that optimization setup is already in progress.
- `READY`: completed archive plus tuple manifest. Candidate launch adds `-Xshare:auto` and `-XX:SharedArchiveFile=<local archive>` only after identity match.
- `STALE`: tuple mismatch, Java update, moved class path, changed mod/library/launch arg, invalid/missing archive. Do not pass the archive; schedule a new generation opportunity.
- `FAILED`: generation/validation failure. Record bounded diagnostic state and launch stock on later runs unless/until retried under policy.

`-Xshare:auto` is mandatory for consumption; `-Xshare:on` is diagnostic-only because Oracle documents it as unsuitable for production fallback.

Do not use `-XX:+AutoCreateSharedArchive` in v0. Explicit launcher-owned generation provides clearer cache ownership, staging, invalidation and failure semantics.

### 6. First-run UX

Local AppCDS is inherently a subsequent-run optimization if generated from the real client route.

Recommended UX contract:

- first exact-tuple launch: "startup optimization will be prepared for later launches"; do not promise this launch is faster;
- game reaches the same normal menu; archive is finalized only when the JVM exits successfully enough for HotSpot to write it;
- launcher checks that the staging file exists, is non-empty and belongs to the current generation attempt, then atomically promotes metadata/archive to `READY`;
- crash/forced kill/no archive -> remain `ABSENT/FAILED`; next launch is stock, not broken;
- later exact-tuple launch: silently attempt CDS through `-Xshare:auto`; mismatch falls back to stock.

A `jcmd VM.cds dynamic_dump` at menu could theoretically avoid waiting until process exit, but it adds attach permissions, `jcmd` availability, pause/coordination and support complexity. It should not be in the first prototype.

### 7. Locking and concurrency

Use one OS-level exclusive lock per tuple/generation. Requirements:

- lock acquisition is bounded/nonblocking for ordinary play;
- archive consumption never observes a staging filename;
- generation writes a unique temp archive and metadata file;
- promotion uses atomic rename within the same filesystem;
- stale lock files are not authoritative; OS lock ownership/process liveness decides active generation;
- cache eviction skips locked/in-use tuples;
- two concurrently launched pack instances may both run stock, but only one may generate/promote a given tuple.

### 8. Size and eviction

#266's Linux production-JAR archive was **286,916,608 bytes**. Treat that as one concrete size observation, not a Windows estimate.

A reasonable prototype policy is an instance-local budget rather than unlimited tuple accumulation, for example:

- keep the current valid tuple plus at most one recent previous tuple;
- soft budget around 1 GiB per instance, configurable;
- LRU eviction only for non-current, unlocked archives;
- Java/pack updates mark old tuples stale and eligible for eviction;
- report cache bytes in diagnostics.

The exact production default should be selected only after Windows archive sizes are observed.

## Prevalidation, download and allowed cache staging

A versioned launcher can improve reliability substantially, but these mechanisms must not be misreported as process TTMM savings.

### Safe/preferred staging lane

Prefer immutable, producer-supported artifacts with explicit version/hash identity, such as:

- Minecraft/NeoForge libraries and launcher artifacts;
- modpack JARs declared by the pack manifest;
- producer-supported runtime binaries that would otherwise be downloaded/extracted during startup, when licensing/distribution permits;
- exact pack files required before process creation.

For each artifact: download to a temp file, verify strong hash/signature, then atomically rename. Never execute or put an unverified partial file on the launch path.

### Explicitly excluded from initial launcher staging

- browser/history caches such as mutable MCEF content cache;
- synthesized model/resource caches;
- hand-built BootOptim metadata-scan cache entries;
- transformed-class caches;
- Mixin outputs;
- any live Java/mod object serialization.

Those require their own producer-owned invalidation/equivalence contract. A launcher must not reverse-engineer a mutable internal cache merely to move its cost outside the JVM.

## Warm-up policy

"Warm-up" must be narrowly defined.

Acceptable diagnostic experiment: read a fixed list of immutable JARs immediately before Java to study storage/page-cache sensitivity. This is safe in the sense that it does not alter game files, but it is **category 2**: the launcher performs the reads that the JVM would otherwise trigger later. Report both the warm-up wall and total launcher-entry -> menu.

Do not ship by default unless total user-visible launch improves on the target machine. On HDD hardware, aggressive pre-reading can also contend with verification/downloads and make things worse.

Never call a throwaway Minecraft/JVM training process a TTMM optimization. A previous process can warm the OS and JDK but that is simply prior work.

## Measurement program

### A. Hosted Linux mechanism gate

Extend the production-JAR harness concept from #266, but do not pretend it is Prism/Windows equivalence.

1. Generate a local archive on one exact Linux/JDK/classpath tuple. Generation run is labelled training and excluded from savings.
2. Fresh process, stock control: same Java and normal default CDS behavior, **without** the dynamic archive.
3. Fresh process, candidate: same command plus the exact local dynamic archive under `-Xshare:auto`.
4. Use at least three repetitions per variant initially; fresh hosted VM matrix estimates cold-run directionality.
5. Separately use same-VM paired alternating order to diagnose page-cache/runner variance. Do not compare the second process to a cold absolute baseline.
6. Primary endpoint: same BootOptim `main_menu` (and separately `main_menu_presented` if reliably available) for both variants.
7. Preserve exact resource selection, reload count and BootOptim/Mixin failure gates.

Control must **not** use `-Xshare:off`, because that would disable the JDK's ordinary base CDS and exaggerate the candidate difference. The fair control is the stock Java launch without the pack-specific dynamic archive.

### B. Mechanism proof separate from timed A/B

Use bounded diagnostic launches with CDS/class-load logging to count:

- dynamic archive mapped/accepted;
- total shared-class loads;
- application/custom-loader shared hits, including representative `cpw.mods`, `net.neoforged`, Mixin/Minecraft/BootOptim classes where applicable;
- archive rejection reason on deliberate stale identity.

Avoid high-volume `class+load=info` logging in the primary timing population if its overhead is material. Mechanism proof and performance sampling may be separate runs as long as they use the same tuple.

### C. First-run versus subsequent-run accounting

Report a table with at least:

- launcher preflight wall;
- Java process-start -> menu for generation run;
- process exit -> archive-ready finalization wall;
- archive size;
- next-launch launcher preflight wall;
- next-launch process-start -> menu control/candidate;
- launcher-entry -> menu control/candidate.

Break-even in launches may be computed only after A/B data exists. Do not assume one.

### D. Windows/local harness before laptop

Once hosted proves a coherent mechanism/delta, run the same launch harness on Windows using the target Java build and exact production command topology. It can be a developer/agent-controlled local harness on ordinary Windows hardware; it still is not the historical laptop.

Required Windows-specific gates:

- `@argfile` quoting with spaces, Unicode and long paths;
- archive path and class-path identity under drive letters and mixed separators;
- file locks/atomic rename/AV scanning behavior;
- crash during generation;
- concurrent launcher starts;
- Java update and modpack update invalidation;
- moved instance invalidates/regenerates cleanly;
- `-Xshare:auto` rejection reaches menu stock.

### E. Physical laptop gate

A physical laptop test remains mandatory before promotion because archive mapping, disk/page-cache behavior and startup variance are hardware/OS sensitive.

Only request it after hosted and ordinary-Windows runs show a coherent effect. Use alternating/repeated stock vs CDS launches, same endpoint and same pack/JVM state. Record cold/warm intent explicitly; do not ask the laptop to arbitrate an unproven premise repeatedly.

## Security model

A custom launcher becomes part of the pack's supply-chain trust boundary. Minimum requirements for any prototype that can download or execute files:

- authenticated release/manifest provenance; strong hashes for all launch artifacts;
- HTTPS with normal certificate validation; no arbitrary mirror/script execution from mutable pack data;
- temp download + hash/signature check + atomic promote;
- ZIP/archive extraction must reject path traversal, absolute entries and unsafe symlink/hardlink escapes;
- least-privilege per-user/per-instance directories; no administrator rights required;
- do not write into the user's Java installation or system directories;
- protect launcher cache from cross-instance confusion; tuple includes instance identity/manifest;
- redact access tokens, usernames and authentication material from command diagnostics;
- bounded logs and explicit cache deletion UX;
- local `.jsa` is untrusted unless it was generated/promoted by the launcher for the current tuple; hash it in metadata and ignore corruption;
- an optimization failure never justifies bypassing required pack integrity checks.

If authentication/account management is ever added, that is a separate security project. The startup prototype should preferably launch an already-authorized/test instance through a narrowly scoped harness rather than become an account-token store.

## Support/update risks

### Pack updates

Any launch-affecting JAR/argument change invalidates the AppCDS tuple. Minor config/resource-only changes may be semantically safe for CDS, but the exact-pack prototype should prefer conservative invalidation until proven otherwise.

### Java updates

Archive is JDK-build-specific. Vendor/version/build or Java executable change => immediate archive miss and stock launch; regenerate later.

### Launcher updates

Version the manifest schema, argument builder and cache schema independently. A launcher update that changes command representation must invalidate archives even if the pack bytes are unchanged.

### Crashes

Generation writes staging only. A crash must never replace a known-good archive or leave future launches requiring CDS. Consumption always uses `-Xshare:auto`.

### Windows paths

Do not invoke pack paths through an extra shell when direct process APIs are available. Java `@argfile` encoding/escaping must be tested with spaces, parentheses, `&`, Unicode and long instance roots. Prism's current custom-command surface is useful but is not the desired correctness boundary for a dedicated launcher.

### Modpack compatibility

The launcher must preserve the exact production NeoForge/ModLauncher/JarJar/Connector topology. It may not cache transformed classes, change callback order, alter class-loader/TCCL behavior, reorder service discovery or swallow failures. AppCDS is acceptable only if those layers still execute normally and HotSpot reuses compatible archived metadata underneath them.

## Optional bundled JVM variant

Do not assume a bundled runtime is allowed or desirable.

Possible upside:

- stable Java tuple makes AppCDS invalidation less frequent;
- simpler support matrix;
- launcher can test exactly one known runtime.

Costs/risks:

- runtime download/storage/update burden;
- redistribution/license obligations depend on the chosen distribution;
- security patch responsibility shifts to the pack launcher;
- changing from the user's Oracle 25.0.4 baseline to another vendor/build is itself a behavior/performance change;
- native/JCEF and other compatibility must be revalidated.

Therefore bundled Java is **not required** for the recommended AppCDS prototype. First measure with the user's selected Java and exact fingerprinting.

## Prototype scope recommendation

If the user later authorizes implementation, v0 should contain only:

1. read-only exact instance manifest;
2. selected-Java fingerprint;
3. deterministic final argument/class-path capture;
4. per-tuple AppCDS state/lock/cache;
5. explicit first-run `ArchiveClassesAtExit` generation to a staging path;
6. subsequent exact-match `-Xshare:auto` consumption;
7. fail-open stock launch on every optimization-side error;
8. structured launcher/process/menu timing output.

Explicitly **defer** updater UI, account management, bundled Java, page-cache warm-up, speculative cache synthesis and broad download management. Those would make the first A/B harder to interpret.

## Go/no-go decision

**GO for a measurement prototype, NO-GO for productization today.**

Why prototype:

- same-tuple AppCDS is already proven functionally against the production-JAR NeoForge path (#266);
- pre-generated distribution is closed by relocation (#280), but local generation removes that contradiction;
- a custom launcher gives clean ownership of pre-JVM tuple identity, generation, locking and fallback;
- the remaining central unknown is now measurable: actual TTMM effect.

Why not productize:

- there is still no AppCDS A/B TTMM evidence;
- the 4,641 hits prove mechanism use, not wall-clock value;
- Windows/physical storage behavior is unresolved;
- launcher security/update/support cost is substantial;
- most non-AppCDS launcher features only shift work before Java and should be justified on click-to-menu/reliability, not BootOptim process TTMM.

Prototype promotion criterion: only continue toward a real launcher if local AppCDS shows a repeatable, practically meaningful reduction in the exact process-start -> usable-menu metric **and** launcher-entry -> menu remains favorable after verification/gating cost, with stock-compatible fallback and unchanged behavior gates.

## Public references

- BootOptim PR #200: https://github.com/wachipayox/BootOptim/pull/200
- BootOptim PR #260: https://github.com/wachipayox/BootOptim/pull/260
- BootOptim PR #266: https://github.com/wachipayox/BootOptim/pull/266
- BootOptim PR #280: https://github.com/wachipayox/BootOptim/pull/280
- Oracle JDK 25 Class Data Sharing guide: https://docs.oracle.com/en/java/javase/25/vm/class-data-sharing.html
- Oracle Java tool/AppCDS restrictions (JDK 21): https://docs.oracle.com/en/java/javase/21/docs/specs/man/java.html
- Prism custom commands: https://prismlauncher.org/wiki/help-pages/custom-commands/
- Prism Java settings: https://prismlauncher.org/wiki/help-pages/java-settings/
- Prism Launcher source: https://github.com/PrismLauncher/PrismLauncher
