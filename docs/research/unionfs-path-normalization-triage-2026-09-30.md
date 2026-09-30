# UnionFS immutable path conversion triage — 2026-09-30

Status: **LIMITED / NO BOOTOPTIM RUNTIME PATCH**. A pure source reduction is plausible, but no game performance benefit is established and its bootstrap-layer deployment is disproportionate for the bounded small/easy lane. Do not introduce a normal UnionFS mixin or a filesystem-result cache on this evidence.

## Authority, history and exact source

Integration refreshed before investigation: `agent/integration-current` at `a0b8fdc05dd97267698ebbce1f561ac4895d3b74`. Isolated branch `codex/unionfs-paths-20260930`; no other worktree, user's instance or laptop changed. Read operating guide, root README, research/production indexes, exact-pack contract, current source tree, Palladium triage and the input-open ledger. Checked open/closed overlapping PRs and read bodies/comments for #71, #264, #283 and #285. No UnionFS optimization is integrated, and no overlapping active UnionFS source candidate was found.

Exact dependency: SecureJarHandler **3.0.8**, binary SHA-256 `945c63d6deafc821616b0380c23867d9b8c2852438f8a6df72732ab933fc587d`. Gradle's matching sources JAR agrees byte-for-text with the existing local `UnionPath.java` / `UnionFileSystem.java`; `javap` independently confirms the target method sequence. This is stronger provenance than guessing current upstream source.

Prior physical JFR attribution in [Palladium identifier triage](palladium-identifiers-triage-2026-09-30.md) found 14 and 17 `String.join` leaf samples belonging to UnionPath, compared with 26 and 27 aggregate join leaves. The common chain is `String.join -> UnionPath.toString -> UnionFileSystem.toRealPath -> findFirstFiltered`. In the second recording eleven are on the constructor lane at request-relative 2.904–8.889 seconds. These are **samples, not milliseconds or exact call counts**. The recordings do not establish how many same-path conversions are repeated or an exclusive critical-path budget.

## Confirmed redundant conversion

For every base visited by `findFirstFiltered`, `readAttributesIfExists` or `newDirStream`, the private `toRealPath(basePath, path)` currently does:

```java
var embeddedpath = path.isAbsolute() ? this.root.relativize(path) : path;
var resolvepath = embeddedpath.normalize().toString();
// Original embedded-FS lookup and basePath.resolve/getPath follow.
```

For an absolute UnionPath, `root.relativize` creates a relative object/parts array. Its normalization then creates another object/parts array. The next lookup constructs a fresh relative object, so UnionPath's already-existing normalization memo is not reused. Relative paths already use that memo and have much less scope for this reduction.

A source-only alternative can instead normalize the original immutable path, render it, then remove its absolute leading slash:

```java
var resolvepath = path.normalize().toString();
if (path.isAbsolute()) resolvepath = resolvepath.substring(1);
```

This preserves UnionPath's actual `..` rules, including its unusual handling above root. Do not substitute the host filesystem's normalization or change root/relative semantics. It leaves filesystem selection, existence/attributes, filter invocation, base order, lazy stream opening and errors in the original methods. No file, inode, existence, attributes or winning-pack result is retained. It still performs join/rendering, plus an absolute substring; it is not elimination of path work.

`pathParts` is private/final and not exposed through the public Path API. The string-varargs constructor creates its own split array. Package-private array-retaining constructors are used by the inspected class/UnionFS with newly owned arrays; no supported mutation path was found. Reusing normalization does populate the existing memo on the original retained path, extending the lifetime of one normalized object/array versus temporary-only stock absolute conversions. That memory tradeoff must be checked in a real deployment.

## Offline premise checks, not runtime validation

Retained standalone audit: [`tools/research/UnionPathNormalizationAudit.java`](../../tools/research/UnionPathNormalizationAudit.java). It compiles/runs against the exact SJH JAR with Java 21 and `--add-opens=java.base/java.lang.invoke=ALL-UNNAMED`. It never launches Minecraft and has no runtime hook.

The exhaustive token corpus plus explicit cases covers empty/absolute/relative roots, repeated and trailing separators, `.`/`..`, spaces, Unicode, backslash conversion, Windows-looking strings and repeated use of the same object. Result: **74,912 exact resolution-string matches, zero mismatches**, with original rendered paths unchanged. This is a pure path-string equivalence check, not a complete filesystem/provider/filter/pack validation.

Optional `--cost-probe` uses 256 retained absolute resource-like paths and 500,000 conversions per round after warmup. Stock isolated conversion allocated approximately **408 bytes/call**; normalize-original/strip settled near **309 bytes/call**. Thread CPU readings were quantized in **15.625 ms** increments and JIT effects were visible; do not turn these synthetic readings into a game-time estimate. The probe excludes map lookup, actual FS resolution, filtering, file opening/reading and the cold cost of retaining normalized paths. Its observation only supports a small pure allocation reduction, not the 17–19 second F3+T problem being solved here.

## Deployment gate and disposition

[PR #71](https://github.com/wachipayox/BootOptim/pull/71) already demonstrated that UnionFileSystem belongs to **MC-BOOTSTRAP**, outside the normal client mixin target layer. BootOptim's present SERVICE transformation service cannot redefine the already-owned bootstrap classes through an ordinary game transformer. Do not repeat that failed hook. A late PathPackResources wrapper that substitutes relative paths is weaker than the source change: it can change original-path exception text or callback input, and it still cannot remove the internal joins generically.

A pinned SecureJarHandler source replacement, an upstream patch, or a separately justified early bootstrap deployment could exercise the pure alternative. That is not a small mod-local patch. Introducing bootstrap replacement/instrumentation solely for this unquantified cost is **NO-GO now**. The source idea itself remains technically plausible rather than rejected for semantics.

No game run, new recording, bootstrap build, PR or hosted dispatch was needed: no runtime candidate was created. No performance saving is claimed. Root Sodium work can continue without waiting on this lane.

Reopen only if an already-authorized SJH replacement makes deployment cheap, or exclusive current-pack counts/allocation/CPU show material absolute-path churn. Then validate directory and ZIP bases, multiple-base priority, denied filters, dynamic file changes, missing-file errors, close/lifetime and full exact-pack resource contracts. Do not replace this premise with blanket path/FS caches, read caps (#283), archive batching (#285), empty-prefix logging changes (#264), or claims inferred from sample counts.
