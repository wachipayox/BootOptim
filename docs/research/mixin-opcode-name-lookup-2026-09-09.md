# Mixin 0.8.7 opcode-name lookup audit — 2026-09-09

Status: **NO-GO AS A BOOTOPTIM RUNTIME PATCH / SEMANTICS-PROVEN MICRO-CANDIDATE ONLY**

## Scope

PR #211's hosted JFR attribution found 39 Mixin execution samples under `org.spongepowered.asm.util.Bytecode.getOpcodeName(int)` through `Opcodes.getDeclaredFields()` / `Field.getInt`. Those are CPU samples, not a wall-time savings estimate. This audit evaluates only that reflective lookup and does not change Mixin selection, preparation, application, callbacks, classloading, ordering, threads, GL, or gameplay.

Authority for this branch is `agent/integration-current` at `fa6df8bc8f74aae32338f521bf845a5730ac634b`.

## Exact 0.8.7 semantics

Mixin 0.8.7 implements `getOpcodeName(int)` by scanning `Opcodes.getDeclaredFields()` starting at the field named `UNINITIALIZED_THIS`, considering only primitive `int` fields, and returning the first matching field name. If no match is found it returns:

- `UNKNOWN` for negative values;
- the decimal string for non-negative values.

There is one important edge case that makes a blind copy of the newer Fabric direct-table implementation non-equivalent: stock 0.8.7 does not enter the reflective scan for opcode `0`, so it returns `"0"`, not `"NOP"`.

The test-only direct candidate therefore uses `Printer.OPCODES` for positive in-range values, preserves `0 -> "0"`, preserves `negative -> "UNKNOWN"`, and preserves decimal fallback for positive values outside the table or null table entries.

`MixinOpcodeNameSemanticsTest` compares that candidate directly with the resolved stock `Bytecode.getOpcodeName` across the complete opcode table plus a broad invalid boundary domain, integer extrema, and every primitive-int value reachable by the exact legacy reflective scan. The latter closes the finite collision/first-name domain: values not present in those reflected fields necessarily fall through to the documented negative/decimal fallback.

## Dependency ownership

BootOptim targets NeoForge `21.1.248`. NeoForge 1.21.1 declares Fabric Sponge Mixin as a launcher `libraries(...)` dependency (`0.15.2+mixin.0.8.7` on the maintained 1.21.1 branch), rather than a JarJar dependency owned by BootOptim. Replacing it from the regular mod would therefore introduce a second provider or require launcher-level dependency replacement.

That makes a direct BootOptim fork inappropriate. A whole-Mixin upgrade would also change substantially more than this one lookup and could not attribute any startup delta to the opcode-name path alone.

## Why an ordinary BootOptim transformer cannot patch Bytecode

Pinned ModLauncher `11.0.5+main.901c6ea8` builds the `SERVICE` module layer, loads all `ITransformationService` providers there, then gathers their transformers. Only afterwards does it build the `GAME` layer using `TransformingClassLoader` and the gathered transform store.

Mixin itself and BootOptim's `EarlyStartupProbeService` are therefore peers in `SERVICE`. `org.spongepowered.asm.util.Bytecode` belongs to the already-resolved Mixin library in that parent layer. Returning an `ITransformer` targeting that class from BootOptim does not make the SERVICE-layer class pass through the GAME-layer transformer pipeline.

The following alternatives were rejected:

- **JarJar/shadow copy of Mixin or Bytecode:** creates duplicate package/module ownership or launcher-resolution ambiguity; no fail-open stock fallback.
- **BootOptim Mixin targeting Mixin:** same parent-layer problem; Mixin's own classes are not ordinary GAME targets.
- **Launch-plugin wrapper:** still acts on classes loaded through the transforming GAME loader and adds the invasive launch-plugin coupling already ruled out for this front.
- **Self-attach / runtime redefinition:** requires JVM attach/instrumentation capabilities, changes the launcher/JDK contract, and has complexity/overhead disproportionate to a microcandidate.
- **Whole Mixin launcher upgrade/fork:** launcher-owned dependency and much broader semantic surface than the isolated lookup.

## Decision

The algorithmic replacement is verifiably equivalent for the resolved 0.8.7/ASM contract when the opcode-0 exception is retained, but there is no sufficiently local, fail-open delivery mechanism available to the BootOptim distributable under the current ModLauncher/NeoForge classloader architecture.

Therefore this branch intentionally contains **no runtime optimization toggle and no A/B candidate**. It records the semantics proof and the delivery no-go rather than forcing a classloading intervention whose maintenance/safety cost exceeds the expected micro-scale gain.

No performance claim is made from the 39 JFR samples. No laptop run is justified.

## Validation gates

- Unit test: exact stock-vs-direct semantic equivalence, including invalid values and reflection-order collisions.
- Normal build/package/startup CI must remain green.
- Hosted exact-pack smoke is requested only to prove the research/test branch leaves the packaged startup path unchanged; it is not a performance comparison.
- No A/B is run because no runtime candidate exists.
- No physical laptop run until a future supported delivery mechanism first shows coherent hosted signal.

## Reopen only if

1. NeoForge/ModLauncher exposes a supported, versioned hook for replacing or instrumenting SERVICE-layer library classes with a stock fallback; or
2. BootOptim gains explicit control of the launcher Mixin artifact; or
3. the pack moves to a Mixin artifact that already contains an equivalent direct lookup, in which case the correct action is to verify the upgrade rather than carry a BootOptim patch.

Relevant evidence: PR #211 and `docs/research/mixin-pipeline.md`.
