# PathPackResources lexical prefix reuse candidate — 2026-10-03

Status: **opt-in candidate; hosted A/B pending, physical laptop gate pending**.

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

## Required decision gates

- Build, startup and exact-pack A/B must pass with `-Dboot_optim.pathPrefixReuse=true` and `false`.
- The A/B must prove the same resource selection, atlas shape, menu and no new mixin errors.
- Physical measurement must compare only the post-menu replay CPU owner cost using the frozen diagnostic jar and actual laptop JVM, not compare its startup marker with the normal control.
- Integrate only if the physical CPU budget justifies the per-call branch/volatile state and exact-pack behavior is equivalent. Otherwise remove runtime code and archive the measured limitation here and in the research index.
