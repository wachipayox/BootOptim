# ModelManager low-retention JFR attribution gate — 2026-09-07

Status: **diagnostic-only proposal**. No BootOptim runtime code, mixin, cache,
executor or NeoForge behavior changes.

## Why this exists

The physical laptop places `ModelManager` around `130.955–150.078 s`, with
`ModelBakery` around `55.770–65.460 s` and `bakeModels` around `32.736–41.153 s`.
Those scopes are inclusive and do not provide phase-local CPU or allocation
ownership. A previous in-process profiler also demonstrated that retaining
model identities/stacks can exceed the 6 GiB heap and perturb the result.

This gate therefore uses Java Flight Recorder **outside BootOptim**. It samples
execution stacks and allocation stacks without adding per-model maps or logging
to the measured path. The recording is joined with the already-existing startup
boundaries only after Java exits.

## Recording contract

`modelmanager-attribution.jfc` enables only low-retention samples/events:

- `jdk.ExecutionSample` every 20 ms for CPU-stack attribution;
- `jdk.ObjectAllocationSample` every 10 ms for weighted allocation attribution;
- `jdk.CPULoad` every 100 ms;
- `jdk.GarbageCollection`, `jdk.GCPhasePause` and `jdk.Compilation`.

It does not record `ResourceLocation`, JSON, model identity or arbitrary object
graphs. Samples are attribution evidence, not exact accounting. The observer
must still be labelled in the result and never compared as a production
absolute-time baseline.

## Hosted first

Run one or more hosted exact-pack diagnostics before spending a laptop launch:

```text
python scripts/exact-pack/run_modelmanager_jfr.py \
  --variant modelmanager-jfr-control \
  --iteration 1 \
  --output diagnostics/modelmanager-jfr-control-1.jfr
```

The wrapper delegates startup/resource validation to `run_startup.py`; it does
not read logs while the process is alive. After exit, inspect the recording:

```text
jfr summary diagnostics/modelmanager-jfr-control-1.jfr
jfr print --events jdk.ExecutionSample diagnostics/modelmanager-jfr-control-1.jfr > diagnostics/execution.txt
jfr print --events jdk.ObjectAllocationSample diagnostics/modelmanager-jfr-control-1.jfr > diagnostics/allocation.txt
```

Use `bootoptim-startup.log` and the completed phase report to partition samples
by the real monotonic boundaries. Do not sum inclusive listener/future scopes.

### Hosted smoke result (2026-09-07)

The first successful hosted smoke completed with the exact-pack workflow run
`34103728083` (main menu `94,807 ms`, mod entrypoint `32,577 ms`). The wrapper
produced a 14.8 MiB recording lasting 98 s with 3,988 execution samples and
54,180 weighted allocation samples, so the observer path is operational and
the recording is usable for offline inspection. A textual stack scan found
374 execution-sample records mentioning `ModelManager`/`ModelBakery`/`BlockModel`
and 859 allocation-sample records mentioning those frames; these are only
coarse event counts, not exclusive CPU or byte percentages. FancyMenu's
preloader also appeared in 143 execution samples. Therefore this run validates
the diagnostic plumbing, but does **not** meet the production gate: it has not
yet established exclusive phase-local dominance or classified the samples
against the real ModelManager interval. Do not promote #160 or introduce a
model cache from this smoke alone. The initial two hosted attempts failed for
diagnostic lifecycle reasons (relative JFC path, then staging before the
benchmark directory existed); both were fixed before this successful run.

## Decision gate

Reopen a production candidate only if the diagnostic supports one of these
bounded outcomes:

1. **Vanilla structural path:** at least ~50% of exclusive sampled CPU or
   weighted allocation belongs to an eligible vanilla `ElementsModel` path and
   at least ~80% of that hot domain can be classified. Then prototype #160's
   reload-local shadow IR, still returning stock in verify mode.
2. **Custom loader:** one custom geometry loader dominates. Investigate that
   loader directly while preserving callbacks, exceptions and dependency order.
3. **Parent/dependency/lock path:** those frames account for at least ~10% of
   the bake interval (roughly 3–4 s physical). Only then reopen a DAG/parent
   design; #14/#36 and the 97.6% recursive cache hit rate remain prior evidence.
4. **JIT/allocation/host pressure:** no single model domain dominates. Keep the
   line in diagnosis and investigate allocation/polymorphism or Windows host
   traces rather than shipping a speculative model cache.

If the recording cannot classify the hot samples, or the wrapper's observer
effect is material, classify the run `INCONCLUSIVE`; do not infer a win from
`wall - process CPU`.

## Physical gate

No laptop JFR launch is justified until the hosted recording shows a clear,
actionable domain. If it does, use at most one representative physical run
first, with the same exact pack and the same endpoint contract, collecting the
recording only after the process exits. A production A/B requires a separate
low-noise pair; this diagnostic is not itself a candidate.
