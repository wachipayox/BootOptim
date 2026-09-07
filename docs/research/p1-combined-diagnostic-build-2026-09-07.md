# P1 combined low-noise diagnostic build — 2026-09-07

Status: **DIAGNOSTIC ONLY / DEFAULT OFF / PHYSICAL RUN GATED BEHIND P0 HARNESS CERTIFICATION**

Base authority: `agent/integration-current` @ `8f2c9c32771b6743417b3332030b467d5a4c6fc8` (includes transactional remote laptop tooling from PR #163).

Branch: `agent46/p1-diagnostic-build-20260907`.

## Decision

The integration base does not contain a distributable build that combines the low-noise physical variance snapshots with the detailed ModelManager/ModelBakery boundaries. PR #138 contains the earlier aggregate ModelManager boundary probe and PR #148 contains the reviewed successor that already folds those aggregate boundaries into one startup-variance observer. Porting both properties/probes would duplicate the same ModelManager scopes, so this branch deliberately ports only the validated #148 instrumentation head (`523a434dba87e00021f012da715039364599973a`) onto current integration.

The port is selective rather than a branch copy. Files changed after #148's base were checked against current integration. The runtime probe files themselves had not changed; the only overlapping production file is `boot_optim.mixins.json`, where this branch keeps the later CITResewn mixins already present in integration and appends only the seven diagnostic variance mixins. The hardened exact-pack runner from PR #164 is left untouched so completed logs continue to be read only after process exit.

## Activation contract

The combined runtime observer has one opt-in property:

```text
-Dboot_optim.profileStartupVariance=true
```

Absent or false, the diagnostic snapshotter is disabled. There is intentionally no second `profileResourceReloadBoundaries` switch in this build: the #148 variance probe already contains the #138-style ModelManager boundaries. Enabling two independent implementations would add duplicate rows and duplicate observer work around the same futures.

The existing benchmark auto-exit/startup logging properties are orthogonal and should remain whatever the certified P0 harness contract requires. The remote transaction from #163 must verify the effective JVM command line and exactly one packaged BootOptim wrapper before a physical run.

## Boundaries captured

The probe emits low-cardinality `BOOTOPTIM_VARIANCE` rows with explicit scope IDs. It observes only coarse lifecycle boundaries; it never instruments individual models, JSON documents, resources, sprites or bake calls.

Startup/process boundaries include:

- earliest ModLauncher transformation-service/process-age marker;
- root mod discovery;
- dependency discovery;
- vanilla `Bootstrap.bootStrap()` boundary aligned to the existing bootstrap state transition;
- BootOptim mod entrypoint;
- initial resource reload start/end and the global `allPreparations` barrier;
- FancyMenu `preLoadAll` entry/return as a coarse boundary;
- accepted title/main-menu opening;
- first `Window.updateDisplay()` completion after that opening (`main_menu_presented`).

Model/resource boundaries include:

- `block_models` future;
- `block_states` future;
- aggregate futures returned by `AtlasSet.scheduleLoad`;
- `model_bakery_init` (`ModelBakery` construction);
- `bake_models` (`ModelBakery.bakeModels`);
- `load_models` (`ModelManager.loadModels`), which contains `bake_models` and must not be added to it;
- final `model_manager_reload` future;
- resource-reload listener preparation barrier, ordered apply-turn and completion timestamps.

These scopes are inclusive and overlap by construction. They are correlation boundaries, not additive savings estimates. The offline parser rejects malformed/missing mandatory structure and duplicate initial reloads.

## JVM / CPU / GC / memory state

At each coarse variance boundary, the snapshot records:

- monotonic `System.nanoTime()`;
- wall epoch time only for identity/cross-checking;
- JVM start epoch and runtime uptime;
- whole-process CPU time;
- current-thread CPU time when supported and the owning thread id;
- cumulative GC collection count and collection elapsed time;
- heap used/committed/max;
- free/available physical memory reported by the JDK OS MXBean;
- phase deltas for process CPU, owner-thread CPU, GC count/time, heap used and available memory.

Interpretation limits remain unchanged: process CPU divided by wall is useful as average CPU-core equivalents, but `wall - process CPU` is not disk/GPU/descheduling attribution; GC MXBean time is accumulated collector elapsed time rather than GC CPU; available memory is a pressure snapshot rather than a hard-fault/page-cache counter.

Listener lifecycle attribution is intentionally cheaper than the full phase snapshots. While reload is critical, each listener records only monotonic time plus atomics at first preparation barrier, stock apply-turn future completion and listener future completion. MXBean snapshots/log formatting are not performed per listener. The listener table is buffered and formatted only after `main_menu_presented`, outside the TTMM endpoint.

## Perturbation / semantic contract

This build is diagnostic-only and makes no optimization or scheduling change:

- no ModelBakery parallelism or executor replacement;
- no cache or persistence;
- no model/resource identity map;
- no JSON retention;
- no stack retention;
- no per-model/per-resource timer or logger;
- no render/OpenGL work moved between threads;
- no selection/order/callback change;
- no resource/file wrapper;
- no sleep or polling loop;
- no permanent Java profiler inside the JAR.

The diagnostic mixins use `defaultRequire=0` and call only the disabled static gate when the property is absent. When enabled, observer work is proportional to the small fixed set of startup boundaries plus one constant-size listener lifecycle record per reload listener; it is not proportional to model count.

PR #148's hosted A/B gate already demonstrated that this instrumentation head did not grossly dominate startup: the candidate and control both reached the exact-pack menu with the expected atlas and zero BootOptim Mixin errors, and the candidate produced a complete parser-valid marker stream. That historical hosted delta is not an optimization claim or a precise observer-overhead estimate because the jobs ran on different hosted runners.

## Relationship to external JFR (#165)

JFR remains external. This branch does not package a `.jfc`, JFR agent, profiler library or recording logic in the wrapper.

PR #165 provides the external `-XX:StartFlightRecording=...` workflow/wrapper. Its hosted H0 plumbing worked twice, but broad model-marker stacks were only about 10–13% of post-entry execution/allocation samples. That result improves attribution plumbing; it does not authorize a production cache/IR.

If a later diagnostic needs both the P1 boundaries and JFR, the external #165 launcher can add `StartFlightRecording` to the JVM that also has `-Dboot_optim.profileStartupVariance=true`. Such a run must be labelled as a separate observer profile and must not be compared as a clean production absolute-time sample. The P1 boundaries then provide monotonic phase windows for offline JFR partitioning after JVM exit.

The first P1 physical variance run after P0 should normally use the low-noise boundary probe alone. Add external JFR only when a subsequent attribution question justifies its extra observer load.

## Physical use after P0 certification

One future fixed-setting physical run may use this build only after the transactional P0 harness is certified. The remote tooling from #163 remains the operational authority:

1. start from quiescent Prism / no stale target JVM;
2. stage exactly one packaged wrapper from `bootstrap/build/libs/` and verify its SHA-256;
3. replace the full instance JVM-argument value transactionally and require `-Dboot_optim.profileStartupVariance=true` exactly once;
4. launch through the verified interactive task/session;
5. bind the fresh Java PID + creation time + instance provenance and validate the effective command line;
6. once the Java identity is accepted, block on process completion without polling logs or resource files;
7. collect the frozen process console/logs only after Java exit;
8. run `tools/laptop-bench/variance_probe.py` offline and apply the normal resource-selection/measurement-validity gates;
9. restore the exact original `instance.cfg` and original BootOptim wrapper after Prism/Java are stopped, verify restoration hashes and retain exactly one packaged wrapper.

A forced post-present shutdown may still be phase-attribution evidence if the endpoint/rows are complete, but it is not a clean voluntary-exit benchmark. Stale JVM, wrong/duplicate JAR, missing/duplicate mandatory markers, wrong resource selection or wrong effective JVM arguments invalidate the run before timing interpretation.

## Validation gate for this branch

Required before calling the build ready:

- GitHub Build workflow: `python3 -m unittest discover -s tools/laptop-bench -p 'test_*.py' -v`, then `./gradlew build`;
- packaged-bootstrap validation from `bootstrap/build/libs/`, including early transformation-service entry and JarJar metadata;
- uploaded `bootoptim-<commit>` artifact, never the root `build/libs/` inner JAR;
- normal Startup workflow reaches menu;
- exact-pack smoke with `-Dboot_optim.profileStartupVariance=true` reaches menu with unchanged resource selection/atlas and zero BootOptim Mixin errors;
- wrapper bytes downloaded from the Build artifact are hashed with SHA-256 before any future physical staging.

## Decision rule

If all CI gates pass and the packaged wrapper SHA is recorded, disposition is **ready for one low-noise physical P1 measurement after P0**. A physical run is for variance attribution only; inclusive reductions/count differences do not constitute a TTMM optimization win.
