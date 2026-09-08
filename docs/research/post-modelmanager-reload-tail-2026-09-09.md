# Post-ModelManager first-reload tail — 2026-09-09

Status: **ACTIVE DIAGNOSTIC / stacked on #214**

## Question

PR #214's hosted exact-pack profile `34290311738` established ModelManager as the first-reload preparation gate and measured about 10.131 s from the completion of the ModelManager listener future to stock `SimpleReloadInstance.allDone`. The later ~1.099 s `allDone -> main_menu` interval is explicitly outside this investigation.

This follow-up attributes only that post-ModelManager reload tail. It does not reopen ModelBakery caches/parallelism and does not treat `ProfiledReloadInstance` listener durations as additive.

## Historical boundary

PR #47 established the required semantics: listener preparation is concurrent, apply turns are globally gated and ordered, and inclusive listener durations overlap. PR #184 later measured a 72-listener exact-pack reload and showed that the external serialized tail contains MoreCulling callbacks, renderer rebuilds, NeoForge client reload work, shader/Flywheel/Veil work and a final FancyMenu-correlated listener. #189/#193 subsequently resolved the two anonymous Minecraft lambdas to MoreCulling mutable-cache callbacks. Those historical timings are context, not current-profile measurements.

## Probe design

The diagnostic extends #214's existing `SimpleReloadInstance.StateFactory.create(...)` redirect only after the first `ModelManager` entry in the stock listener list. For each later listener it records:

1. listener index and runtime class identity;
2. an **inclusive phase** from stock state creation to the listener future completion;
3. a preparation phase ending when the listener reaches its existing `PreparationBarrier`;
4. a `barrier_wait` at that point and `barrier_open` when the exact future returned by the stock barrier completes (ordered apply turn available);
5. listener-future completion.

The ordered apply-turn interval and completion timestamps are the causal evidence. Inclusive listener phases are retained only as orientation and must never be summed into recoverable wall time.

No cross-thread `task` is invented. Existing #214 lexical tasks remain same-thread scopes. The tail listener work is represented as async `phase`/`barrier` events because its futures can span threads.

## Semantic contract

- trace mode remains default-off and bridge calls fail open;
- the wrapper calls each original `PreparationBarrier.wait(value)` exactly once;
- it returns the **same original future** from that barrier;
- `StateFactory.create` receives the original prepare/apply executors unchanged and its returned listener future is observed, not replaced;
- listener order, callbacks, futures, threads, GL/render ownership and gameplay are unchanged;
- only first-reload listeners occurring after ModelManager are instrumented;
- no ModelBakery, atlas, renderer, shader, MoreCulling or FancyMenu behavior is modified.

`tools/laptop-bench/test_reload_tail_probe_contract.py` statically guards the one-call/same-future/no-executor-wrapper source contract in the hosted Build workflow. Gradle build/startup remain required runtime/package gates.

## Hosted gate

Run one exact-pack **profile smoke**, never A/B and never laptop, with the same origin/endpoint contract as #214:

- `boot_optim.bootTrace.mode=profile`
- `measurement_origin=hosted_exact_pack`
- `endpoint=main_menu`

Acceptance requires one valid contiguous schema-v1 JSONL stream, no dropped events, no Mixin failures, one first reload, balanced phase/barrier pairs, and menu reachability. Report separately:

- ModelManager future complete -> each later ordered apply-turn open;
- apply-turn open -> same listener future complete (causal serialized/post-turn segment);
- ModelManager future complete -> `allDone` tail;
- task critical path from #214, unchanged in meaning;
- inclusive listener phase values as non-additive diagnostics only.

The final decision is diagnostic/no-go unless the trace proves a specific false dependency or detached immutable prepare/original-thread commit boundary suitable for a separate future redesign. This PR itself contains no optimization.
