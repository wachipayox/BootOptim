# PathPackResources lexical prefix reuse candidate — 2026-10-03

Status: **opt-in candidate; hosted A/B completed, physical laptop gate pending**.

## Evidence and premise

Diagnostic PR #330 on integration `7138fcb3` observed 185 exact UnionPath roots
and 985,854 `PathPackResources.getResource` calls in the hosted exact pack.
The post-menu C/B/B/C replay measured 54.56/9.01/8.998/46.11 ms of current-thread
CPU for reconstructing versus reusing the first `assets`/`data` prefix. The
equivalence marker was true. This is a lexical-operation ceiling, not measured
time-to-menu savings; profiling overhead makes startup time from that build
invalid for performance comparison. See PR #330 and its diagnostic entry.

The candidate caches only the first immutable `assets` or `data` `Path` on the
owning `PathPackResources` instance. It calls the stock operation on first use
and for every unknown provider, subclass, directory, disabled property, or
other fallback. It does not cache namespace/suffix paths, existence, filters,
resource results, streams or bytes. The default is off until promotion. Reloads
keep the same root for a pack instance; a replacement pack gets its own fields.

## Pack compatibility inspection

The local `nooptim` profile has 161 mod JARs. Classfile inspection found
ModernFix 5.27.14's `PathPackResourcesMixin`, FancyMenu's root accessor, and
CreativeCore's root accessor. The ModernFix mixin only adds its own resource
cache lifecycle and `PackResourcesCacheEngine`; it does not wrap the
`getResource` prefix call. FancyMenu/CreativeCore only expose the root. A scan
found no third-party class containing both a `PathPackResources` reference and
the `Path.resolve(String)` invocation descriptor; this is useful profile
evidence, not proof about arbitrary packs or transformations created at runtime.

Because a cache hit skips the original `Path.resolve(String)` wrapper chain,
the candidate remains opt-in until exact-pack behavior and the physical CPU
budget are validated. Unknown provider subclasses retain stock behavior. The
small hosted owner budget alone does not justify default-on retention.

## Hosted candidate gate

Local packaged build and hosted build/startup passed. Exact-pack A/B #331 ran
three controls and three candidates. All six reached the same main-menu
endpoint, selected the expected resource packs, used identical options SHA,
created the `8192x8192x2` block atlas and recorded zero BootOptim mixin errors.

Raw time-to-menu values in seconds were candidate `50.778, 89.424, 80.862`
and control `92.163, 86.870, 88.489`. The apparent median delta is `-7.627 s`,
but it is not attributable: candidate 1 was a major outlier before the target
work (`mod_entrypoint=17.729 s`, `post-mod=33.048 s`, versus candidate 2/3 at
26.878–31.037 s and 53.984–58.387 s). Candidate 2 was slower than control 2;
candidate 3 was faster than control 3. The source-specific hosted replay only
budgets 37–46 ms, so the multi-second median movement is runner/phase variance,
not evidence that this prefix change saved seconds. The end-to-end result is
**inconclusive**.

Exact-pack behavior checks passed, but a default-on decision still needs a
hardware-sensitive owner-budget measurement. The earlier single-startup
laptop diagnostic for #330 could not be retrieved because that laptop is
offline. Do not compare its diagnostic startup marker against these normal
candidate/control runs; once the laptop is available, retrieve the diagnostic
replay and make a binary retain/remove decision from its per-owner CPU budget.

## Required decision gates

- Build, startup and exact-pack A/B passed with `-Dboot_optim.pathPrefixReuse=true` and `false`; TTMM attribution is inconclusive.
- The A/B must prove the same resource selection, atlas shape, menu and no new mixin errors.
- Physical measurement must compare only the post-menu replay CPU owner cost using the frozen diagnostic jar and actual laptop JVM, not compare its startup marker with the normal control.
- Integrate only if the physical CPU budget justifies the per-call branch/volatile state and exact-pack behavior is equivalent. Otherwise remove runtime code and archive the measured limitation here and in the research index.
