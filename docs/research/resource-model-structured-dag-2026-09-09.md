# Resource reload / ModelManager structured DAG — 2026-09-09

Status: **PROFILED DIAGNOSTIC / NO OPTIMIZATION**

Authority at assignment start: `agent/integration-current` @ `fa6df8bc8f74aae32338f521bf845a5730ac634b`.

This branch is intentionally stacked on PR #212 (`agent77-2/regular-trace-bridge-20260909` @ `9c1d6f3d7b25aeed099eee4b78c1818d94625b8d`), transitively on #202/#201/#200. It must be rebased after that diagnostic stack is resolved. The regular mod imports only `RegularBootTraceBridge`; `StructuredBootTrace`, schema, sequence and JSONL ownership remain exclusively in bootstrap SERVICE.

## Question

Build the first resource/model portion of the startup DAG without changing any reload scheduling, listener order, callback order, thread, GL/render operation, future or gameplay behavior. The output is an attribution map for a later redesign, not a performance candidate.

## Historical constraints

- PR #47 proved `ProfiledReloadInstance` listener timings are inclusive and overlapping. ModelManager preparation can report longer than the whole reload; listener durations therefore must not be summed.
- PR #138/#141 retained only low-cardinality aggregate ModelManager/atlas boundaries. Physical results varied materially and did not turn nested phase totals into recoverable TTMM.
- PR #132 records the source dependency graph: block models, block states and atlas preparation are already launched concurrently; `ModelBakery` consumes block model/state results; `loadModels` waits for bakery plus atlas readiness; apply is globally gated and ordered.
- PR #14's generic eager bake parallelism improved an isolated phase but regressed TTMM. PR #36 removed many repeated bakes for only a small isolated bake reduction and no end-to-end win.
- `ModelManager.apply` uploads prepared atlases, publishes model maps/cache and crosses NeoForge model callbacks. Its GL work remains render-thread-bound and its publication/callback semantics are not moved.

## Producer contract

The regular GAME layer uses only `dev.wachipayox.bootoptim.profiling.RegularBootTraceBridge` from #212. There is no regular-mod trace-core dependency or `StructuredBootTrace` import.

Only the first resource reload generation is detailed. Later reloads retain stock behavior and are not added to the startup DAG.

### Reload and barriers

- `phase_begin/end resource_reload`: first `SimpleReloadInstance` generation through stock `allDone` completion.
- `barrier_wait/open reload_global_preparation`: constructor-established wait through stock `allPreparations` completion.
- only the ModelManager listener receives a diagnostic barrier proxy. The proxy calls the original barrier exactly once, returns its original future unchanged, and only observes that future.
- `phase_begin/end model_manager_preparation`: `ModelManager.reload` entry to its arrival at the stock preparation barrier.
- `barrier_wait/open model_manager_apply_turn`: ModelManager barrier arrival to completion of the original ordered-turn future.
- `phase_begin/end model_manager_reload`: entry to completion of the original listener future.

No listener-inclusive duration is emitted as a task and no listener totals are summed.

### Model preparation

Cross-thread preparation uses `phase_begin/end`, not a task whose begin/end would cross threads:

- `model_manager_block_models_preparation`
- `model_manager_block_states_preparation`
- `model_manager_atlas_preparation` (one aggregate end when the existing `AtlasSet.scheduleLoad` future map completes)

Their synchronous scheduling bodies are separate lexical tasks:

- `model_manager_block_models_schedule`
- `model_manager_block_states_schedule`
- `model_manager_atlas_schedule`

`ModelBakery` construction is the lexical `model_bakery_prepare` task. `model_manager_load_models` is a lexical task and contains the nested lexical `model_bakery_bake` task on the same thread. This preserves the trace analyzer's same-thread nesting rule instead of inventing a cross-thread task.

### Commit and endpoint

`ModelManager.apply` is `model_manager_apply_commit`, with paired `commit_begin/end model_manager_commit`. The task depends on the completed `model_manager_load_models` lexical task, a real predecessor. No dependency is fabricated from asynchronous phase duration to a scheduling task.

The first accepted `TitleScreen` opening emits a coarse `mod_callback` event `main_menu`, matching the configured trace endpoint. The existing legacy startup-profiler stop behavior remains conditional on its original property.

## Interpretation

Keep four buckets separate:

1. **task wall** — lexical same-thread bodies such as bakery construction, bake and apply commit;
2. **phase wall** — asynchronous aggregate future lifetime; phases can overlap and nest;
3. **barrier wall** — actual semantic waits/turn availability (`allPreparations`, ModelManager ordered apply turn);
4. **inclusive listener wall** — historical/diagnostic only and never added to task/phase/barrier totals.

The generic analyzer's `critical_path_wall_ms` is intentionally task-only because its dependency graph consists of task IDs. It is not the full reload critical path when asynchronous preparation is represented correctly as phases/barriers. Full interpretation must compare the winning lexical task chain with phase completion order and the two barriers. Overlap is never summed.

## Safety

Default-off via `boot_optim.bootTrace.mode=off`; bridge failures are fail-open per #212. `boot_optim.mixins.json` remains `required=false` / `defaultRequire=0`.

No executor is wrapped, replaced or sized. No command is resubmitted. No resource open/parse event is emitted. No per-model/per-sprite/per-listener timing is added. No future is replaced or combined. No GL/render work moves threads. No callbacks, listener order, resource/model data or gameplay behavior are changed.

## Hosted gate

One exact-pack **profile smoke**, not A/B:

```text
-Dboot_optim.bootTrace.mode=profile
-Dboot_optim.bootTrace.origin=hosted_exact_pack
-Dboot_optim.bootTrace.endpoint=main_menu
```

Required evidence:

- build/package/startup pass;
- regular nested mod still contains zero `dev/wachipayox/bootoptim/trace/` classes and bridge identity test from #212 stays green;
- exact-pack reaches menu with zero BootOptim Mixin errors;
- one `logs/bootoptim-trace.jsonl` with one header, one contiguous JVM event sequence, one summary and zero dropped events;
- first reload generation contains the coarse reload, global preparation barrier, ModelManager preparation/apply-turn barrier, block-model/blockstate/atlas phases, bakery prepare/bake/load, commit and menu endpoint;
- analyzer accepts same-thread lexical nesting and task dependencies;
- report task critical path separately from asynchronous phase/barrier timing. No timing is an optimization claim.

No laptop run or optimization A/B is authorized by this diagnostic.
