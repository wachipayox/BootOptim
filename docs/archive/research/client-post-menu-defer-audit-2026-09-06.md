# Client post-menu / first-world deferral audit — 2026-09-06

Status: **CLOSED / NO NEW SAFE BOOTOPTIM RUNTIME BOUNDARY**

Agent: 29

Authoritative base refreshed before work: `agent/integration-current` @ `145c10c2f8132b21e7b7be067c56513b394ccb5a`.

The task prompt named `8fdcce08b2a4a1a374b321fcbdd1499ebf34094c`, but the live integration ref had already advanced. Per `AGENTS.md`, integration is authoritative, so this audit branches from the live ref and does not treat an older supplied SHA as current.

## Question

Can client work in the exact 1.21.1 pack be moved beyond the first visibly usable startup UI, or to a pre-world boundary, without moving a pause onto first input/world entry, changing the first visible screen, changing gameplay, or violating reload/render-thread semantics?

This is deliberately a boundary/consumer audit, not a generic search for work that merely happens before title. A candidate requires all of:

1. a precise current producer;
2. a precise first consumer;
3. proof that the consumer is after the product endpoint;
4. a continuation/ownership model that cannot self-wait or deadlock on re-entry;
5. a failure path that falls back to stock behavior;
6. enough critical-path evidence to justify runtime instrumentation.

## Non-overlap / current integration state

The live integration tree contains the production model/blockstate/Decocraft/FancyMenu panorama mixins, but **does not contain PR #90's MCEF first-consumer implementation**. PR #90 remains an open draft. PR #129 independently reaches the same integration-state conclusion and blocks additional MCEF gameplay-state work until #90 or an equivalent owner-aware implementation is actually integrated.

This audit therefore does not recreate #90, does not cancel renderer listeners (#95/#102), does not bypass FancyMenu `preLoadAll` (#116), and does not revisit FancyMenu's initial-layout resource list, which has a separate controlled-fork lane and the open ownership/provenance audit in #125.

Relevant BootOptim history:

- #90 — MCEF first-consumer defer, own validated front: <https://github.com/wachipayox/BootOptim/pull/90>
- #95 / #102 — renderer first-consumer/world defer rejected after black/frozen title output: <https://github.com/wachipayox/BootOptim/pull/95>, <https://github.com/wachipayox/BootOptim/pull/102>
- #112 / #129 — MCEF pre-gameplay and owner/re-entry contract: <https://github.com/wachipayox/BootOptim/pull/112>, <https://github.com/wachipayox/BootOptim/pull/129>
- #116 / #125 — FancyMenu preload boundary and optional-resource ownership: <https://github.com/wachipayox/BootOptim/pull/116>, <https://github.com/wachipayox/BootOptim/pull/125>
- #122 / #128 — actual first-screen endpoint and AnalogAudio replacement: <https://github.com/wachipayox/BootOptim/pull/122>, <https://github.com/wachipayox/BootOptim/pull/128>
- #98 / #100 — mod-specific audit and Xaero task-attribution closure: <https://github.com/wachipayox/BootOptim/pull/98>, <https://github.com/wachipayox/BootOptim/pull/100>

## Public exact-pack evidence refreshed

The immutable exact-pack fixture remains `exact-pack-2026-09-02-v1`, SHA-256 `7f586ecd90497a4d4aa1d2024af2643dbd64691864edbad9eb2ed40551c55639`.

For independent log screening this audit downloaded public exact-pack artifact `9953845893` from run `33917611497`:

- run: <https://github.com/wachipayox/BootOptim/actions/runs/33917611497>
- artifact: <https://github.com/wachipayox/BootOptim/actions/runs/33917611497/artifacts/9953845893>
- artifact upload digest recorded by Actions: `sha256:c219ea458b7124543fcdb884aebbfb4bcd6d04a8b9a5c459f756b90147f6efc4`
- exact fixture reports 160 mod JARs;
- one-run smoke TTMM `77,799 ms`;
- mod-entrypoint `25,754 ms`;
- post-entrypoint `52,045 ms`;
- initial reload -> FancyMenu finish `36,404 ms`;
- MCEF init `929 ms`;
- panorama `3,662.953 ms`;
- block atlas `8192x8192x2`;
- BootOptim Mixin errors `0`.

Those are **single-run hosted wall observations**, not a control/candidate comparison and not evidence that any screened service owns that TTMM.

For the current FancyMenu ownership question, #125's later exact-pack smoke `33976030376` / artifact `9972386153` is stronger: stock-equivalent instrumentation measured `ResourcePreLoader` at `4015.712 ms` wall / `3293.221 ms` current-thread CPU, with `132` waits (`120` panorama, `10` slideshow, `2` ordinary). Those family totals are inclusive work and are not a recoverable-unselected-resource ceiling.

## Candidate / boundary table

| Screened area | Precise consumer / endpoint | Work that looks movable | Required invariants / no-self-wait contract | Evidence and risk | Decision |
| --- | --- | --- | --- | --- | --- |
| FancyMenu unselected random backgrounds | `ScreenCustomizationLayer.onInitOrResizeScreenPre` selects one `RandomLayoutContainer` member; selected `MenuBackground` renderer is the consumer | preload of backgrounds that will not be selected | selection must happen once with stock RNG/cache semantics; source ownership must be known; reload invalidation/failure/timeout ordering must remain stock; no later interactive first-load | #125 proves 22 enabled mutually-exclusive group members but a flat source registry with no source→layout provenance. Early pruning can change RNG, conditions and failure lifecycle | **NO-GO here / owned by #125 + controlled fork**. Reopen only with a FancyMenu-owned provenance token/API |
| Entity/player renderer, item/model/atlas infrastructure | exact active FancyMenu title layout renders 12 player-entity widgets and 6 item widgets | broad renderer/model/atlas rebuild sometimes described as “world-only” | every title consumer must see complete renderer/model state before first present; no cancelled reload listener | #125 proves direct title consumers; #95/#102 physically produced black/frozen output after cancelling dispatcher listeners | **REJECTED** |
| AnalogAudio missing-AnalogPlayer path | `ScreenEvent.Init.Post(TitleScreen)` synchronously replaces it with `LavaplayerWelcomeScreen`; first present of that actual screen is the first visible endpoint | filesystem checks / welcome-screen construction | must preserve exact first screen, 40-tick button-disable period, Escape behavior and user choice | #128 shows the path does not download/init Lavaplayer/OpenAL; suppressing/delaying it changes visible navigation semantics | **NO-GO**; no material hidden service exists in this path |
| Drippy loading overlay | Drippy/FancyMenu loading-overlay render before title; exact log initializes its text fonts during initial reload | loading-overlay font setup | overlay must look identical while it is already visible | exact public log: `[DRIPPY LOADING SCREEN] Initializing fonts for text rendering` before title | **NOT DEFERRABLE** because it is already observable |
| MCEF / WebDisplays | guarded CEF consumers (`MCEF.getClient/getApp`); pre-gameplay local/remote boundaries documented in #112 | native CEF initialization | one owner; same-thread re-entry must never wait on itself; non-owner waits bounded diagnostically; no terminal abort while initializer still runs; fail open; actual init stays client/render thread | #90 has favorable hosted 3x3 directions but is not integrated; #112/#129 identify callback-reentry/timeout hazards | **OUT OF SCOPE / own front**. Do not implement from this base |
| Xaero World Map 1.41.0 Stage 2 | world-map/player-tracker infrastructure and ultimately first map/world use | one synchronous deferred task | exact queued→executing ownership boundary; no shift to first playable frame or first map opening; world/map behavior unchanged | exact log/FML warning: `1.026 s` **inclusive wall** on Render thread. #100 could not obtain a supported exact task-start boundary; completion future is insufficient | **REJECTED until new supported FML task token/hook** |
| Jade 15.10.5 plugin bootstrap | in-world HUD/plugin lookup after world exists | Jade plugin discovery/bootstrap during resource reload | all plugins available before first relevant HUD query; reload/plugin state and data-provider registration identical; any force must have owner/re-entry state | exact public log loads 10 listed plugins from `20:46:18.380` to `.461`, about **81 ms observed wall on Worker-ResourceReload-2**; sum of logged per-plugin load durations is ~72.5 ms. Neither is TTMM-critical attribution | **REJECT economic/attribution premise**. Do not add a lifecycle defer for a sub-0.1 s observed worker lane |
| JEI 19.27.0.340 setup | first JEI inventory/recipe UI; GUI atlas is a reload consumer | config/plugin/UI setup | JEI config/plugin registry and resource reload must be complete before any JEI screen; no first-inventory hitch | exact log reports `Sending ConfigManager took 2.883 milliseconds`; later creates only a `256x256` JEI GUI atlas. No material startup lane identified | **NO CANDIDATE** |
| Fzzy Config keybinds | key binding/config consumers, including settings/input behavior | parsing `fzzy_config:keybinds` | values must exist before any keybinding/input consumer; config reload/update semantics preserved; no first-options/input stall | exact log reports `304 ms` on `modloading-worker-0`; #98 already marks it HOLD. Inclusive worker wall is not critical-path wall | **HOLD / no diagnostic** until a phase gate or repeated parse is demonstrated |
| BoccHUD/MiniHUD and other in-world overlays | first in-world HUD render/tick | overlay-specific setup | registrations/config/resources ready before first world HUD frame; cannot convert startup cost into a first-frame hitch | exact pack contains BoccHUD/MiniHUD but the refreshed public log gives no material timed setup owned by it | **NO CANDIDATE WITHOUT ATTRIBUTION** |
| Create Railways Navigator overlays | overlay render while route UI/world is active | overlay state | first route interaction and world frame unchanged | public log's notable event is `Removed all overlays` **after** the main-menu marker, so this cleanup is already post-endpoint and has no TTMM leverage | **ALREADY OFF CRITICAL ENDPOINT / ignore** |
| NeoForge version/update checks | background version-check results | network requests | cannot gate mod state needed by startup; no new user-visible update timing semantics | exact log runs these on separate `NeoForge Version Check` thread during reload; #98 found no TTMM-gate evidence | **NO DEFER CLAIM**; already asynchronous relative to render thread |
| Veil/Iris shader/framebuffer/post resources | renderer/post pipeline consumers; title can transit rendering stack | named shader/pipeline setup | exact consumer graph must prove absence from first presented startup UI; GL creation/commit remains render thread; listener order preserved | #125 records Veil's deliberate listener ordering; #114/#118 physical probe found only `155.418 ms` shader capability wall and `5.534 ms` Voxy save wall | **NO NEW LIFECYCLE CANDIDATE** |
| FancyMenu native-video reset / Melody media service | selected media background/resource handler | hypothetical media player/native state | selected title media must remain identical; OpenAL/GL/native affinity preserved; owner state on first use | #125 exact hosted reset: `backgroundsReset=30`, `stoppedPlayers=0`, `videoResourcesReleased=0`; refreshed log shows Melody loading but no material timed native/player startup owned by it | **NO WORK PAYLOAD DEMONSTRATED** |

## Why there is no opt-in runtime diagnostic PR from this audit

A diagnostic is useful only if it can discriminate a live candidate. Here the material-looking fronts already fail before that point:

- FancyMenu has material work but lacks the ownership/provenance boundary, and its resource-list work is already owned by #125/the controlled fork.
- MCEF has a valid first-consumer premise but is explicitly owned by #90 and blocked for further state-machine work by #129 on this integration base.
- renderer deferral has a physical correctness failure.
- Xaero has the largest remaining named mod-owned synchronous observation, but #100 proved BootOptim cannot currently obtain the exact supported task-start boundary needed to move or even precisely attribute it without fragile loader-private machinery.
- Jade/JEI/Fzzy and other overlay/config services have low or unproven critical-path ceilings; instrumenting lifecycle deferral first would optimize a count rather than the TTMM critical path.

Therefore **no opt-in behavior-changing or lifecycle-defer diagnostic is added**. This branch is documentation only. No exact-pack runtime run is requested for this branch because there is no executable mechanism to validate.

## Exact reopening conditions

Reopen this front only when at least one of the following becomes true:

1. **FancyMenu ownership API:** the controlled fork exposes a source→layout/random-group selection/preload token with stock-equivalent RNG, failure, invalidation and re-entry semantics. Then test that fork's mechanism; do not infer ownership from file names in BootOptim.
2. **Supported FML task lifecycle:** NeoForge/FML exposes a public owner/task execution-start token for deferred work. Then re-attribute Xaero's exact task before proposing any defer, and reject any mechanism that transfers its pause to world entry/map opening.
3. **New measured mod-owned critical path:** a stock-delegating profiler proves at least a material named client service lies on the ordered critical path to the first actually presented startup screen, and source inspection proves its first consumer is later. Counts/log windows alone do not qualify.
4. **MCEF integration changes:** #90 or an equivalent owner-aware first-consumer implementation is actually merged. Only then may #129's owner/re-entry/pre-gameplay state machine become an implementation task.
5. **Endpoint contract changes:** if the benchmark product KPI is redefined from first actually presented UI to the underlying navigable `TitleScreen`, first resolve AnalogAudio's blocking setup modal with an explicit product/user decision. Do not automate a choice and call it behavior-preserving.

Any reopened candidate must pass, in order:

- deterministic unit/state tests for owner, same-thread re-entry, exception/cancellation and timeout semantics where applicable;
- normal `build` and property-off Startup Benchmark;
- hosted exact-pack smoke proving the mechanism fired, resource-selection contract stayed valid, one effective reload completed, atlas/visual structural checks remained expected, and BootOptim Mixin errors stayed zero;
- only then hosted candidate/control A/B for TTMM and the enclosing critical-path interval;
- first real interaction plus first singleplayer world gate; add multiplayer and feature-specific gates (map/HUD/browser) when the deferred consumer can occur there;
- physical Windows only for native, GPU/visual/storage-sensitive behavior or a hosted-surviving small/noisy effect.

## Decision

**Close the generic “defer more client work after menu/world” front on current integration.** There is no new BootOptim-side boundary that is simultaneously material, after the real first-screen consumer, semantically safe, owner/re-entry safe, and measurable on the TTMM critical path.

The next concrete action is **not another defer experiment**. Continue the existing owned fronts (#90/#129 for MCEF when integration permits; controlled-FancyMenu provenance work outside this generic BootOptim lane). Reopen Agent 29's lane only with one of the exact conditions above.
