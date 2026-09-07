# BlockModel material-resolution list pool — 2026-09-07

## Hypothesis

Vanilla `BlockModel#getMaterial` allocates a new `ArrayList` on every face
material lookup, before it knows whether the first texture-map lookup returns a
direct `Material`. The exact-pack profile counted about 2.2 million lookups,
and earlier selective cache profiling found roughly 90% direct-local hits. The
rejected material caches added map/cache overhead and did not produce a win;
this experiment changes the premise by retaining vanilla lookup and cycle
semantics while reusing only the temporary reference-chain list per thread.

## Safety boundary

The pool is opt-in with `-Dboot_optim.materialResolutionListPool=true` and
fail-open when disabled or when the redirected call is reached without the
method entry hook. A list is borrowed and cleared for one `getMaterial` call,
never shared across threads, models or reloads, and returned at the method
return. The concrete return type remains `ArrayList`, so the vanilla list
operations (`contains`, `add`, and cycle-log iteration) are unchanged. No
`Material`, sprite, model or resource state is retained.

This is an experiment only. It needs build/startup validation and a three-run
hosted exact-pack A/B before any physical gate or promotion decision.
