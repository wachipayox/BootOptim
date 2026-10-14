# ModelBakery construction allocation attribution — 2026-10-14

Status: **PROFILED / NO-GO — allocation is not the lever for this phase.**

## Premise

`ModelBakery` construction is the most hardware-amplified block of the model pipeline (65.5 s on the physical laptop vs 11.7 s hosted, `modelmanager-physical-variance-2026-09-06.md`). Earlier work on it measured CPU per call or per owner (#14, #36, material chains, parent-cycle, pure-prepare/ordered-commit, the 2026-10-01 multipart union). Nobody had attributed **allocation by stack** inside the constructor. Hypothesis: a few allocation families inflate GC/memory bandwidth enough to explain part of the amplification.

## Measurement

Origin: hosted exact-pack smoke, run 38587747687 (PR #355), Oracle 25.0.4, workflow base JVM args, `-XX:StartFlightRecording=settings=profile` (allocation samples with stacks, 64 frames). Reached `main_menu` at 91.8 s with 0 mixin errors. JFR perturbs timing: these numbers are attribution only, not a time-to-menu result. Scope = samples whose stack contains `ModelBakery.<init>` (CPU-sample window 11 s) and, for comparison, `ModelBakery.bakeModels`.

| Scope | Sampled allocation | Share of startup allocation |
| --- | ---: | ---: |
| Whole startup | 56.6 GiB | 100 % |
| any `ModelManager` frame | 16.9 GiB | 29.8 % |
| `ModelBakery.<init>` | 7.8 GiB | 13.7 % |
| `ModelBakery.bakeModels` | 4.1 GiB | 7.2 % |

Inside `ModelBakery.<init>`:

- Largest single site: `MultiVariant.getDependencies` 1.34 GiB (17 %), 1.25 GiB of it reached through `MultiPart.getDependencies` (stream flat-map into a fresh set per call). It is also the top CPU leaf (8.5 % of the constructor's samples).
- FML `UnionFS` path handling (`UnionPath.<init>`, `getPathParts`, `byteChannel`, `fastPathExists`, `toRealPath`) ~24 % of allocation and ~12 % of CPU samples; resource lookups for models from mod JARs.
- CITResewn ~15 % of allocation; Mixin `CallbackInfoReturnable` 6 %.
- By type: `byte[]` 20 %, `char[]` 12 %, `Object[]` 9 %.

Inside `bakeModels`, the leaders are Decocraft geometry (including BootOptim's own quarter-turn rotation, 13 %), `FaceBakery`, and `Transformation.equals` as the top CPU leaf.

## Decision

1. **GC has a small ceiling.** Hosted GC is 3.3–4.6 s per startup (startup report `JVM` counters, `jit-compiler-pressure-2026-10-14.md`); physical laptop 15.4 s of 350 s (4.4 %). Removing the entire constructor's allocation would remove at most ~14 % of that, ~0.6 s hosted, before any real-world efficiency loss. Young-generation garbage of this kind is cheap for G1.
2. **The largest site is already a retired mechanism.** The multipart dependency union (`MultiPart.getDependencies` in `loadAll`) was measured physically on 2026-10-01: 147,906 calls, ~0.78 s of CPU, with a mixed-sign result. It was retired in `owner-candidate-final-decisions-2026-10-01.md`. The allocation view adds no new premise: 1.25 GiB of short-lived sets is ~2 % of startup allocation.
3. **UnionFS/resource lookups are already mined** (ZIP/path prefix, strict segment scan, resource-query budget, point-resource resolution plan NO-GO).

No runtime candidate is opened. The workflow now uploads `*.jfr` from exact-pack runs, so future attribution needs only a PR-body JVM arg.

## Reopening criteria

- A measurement showing GC (not allocation volume) on the critical path of `ModelBakery` on the physical laptop, e.g. a JFR with GC pauses concentrated inside the constructor window and a heap close to `-Xmx`.
- A different mechanism for `MultiPart.getDependencies` that removes the *calls* (not just the set allocation) with a stock-equivalent dependency order.

## How to repeat

PR body of any exact-pack PR:

```text
[exact-pack-ci]
exact-pack-mode: smoke
exact-pack-smoke-jvm-arg: -XX:StartFlightRecording=settings=profile,dumponexit=true,filename=startup.jfr
```

The file is written in the game directory (`run-pack-benchmark/startup.jfr`; `logs/` does not exist yet at JVM start). `jfr print --json` of a 13.6 MB recording expands to ~6 GB: filter with `--events` and stream if possible.
