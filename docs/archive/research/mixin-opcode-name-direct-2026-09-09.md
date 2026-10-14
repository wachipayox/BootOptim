# Mixin 0.8.7 opcode-name direct lookup microcandidate — 2026-09-09

Status: **NO-GO / stock retained**. PR #213. Base authority: `agent/integration-current` @ `fa6df8bc8f74aae32338f521bf845a5730ac634b`.

## Scope

PR #211 attributed 39 JFR execution samples in the pre-Bootstrap Mixin slice to the reflective `org.spongepowered.asm.util.Bytecode.getOpcodeName(int)` path (`Opcodes.getDeclaredFields()` / `Field.getInt`). This is a microcandidate only; the evidence does not support a seconds-scale claim.

## Route selection

- A global Mixin upgrade is not isolated: the launcher/NeoForge stack owns the Mixin artifact and changing it would also change loader and other-mod Mixin behavior.
- Vendoring/forking `org.spongepowered.asm` from BootOptim would introduce split-package/module/classloader risk for a dependency BootOptim does not own.
- A Java agent or launcher/plugin wrapper would be disproportionately invasive for this microcandidate.
- The least-invasive experiment reused BootOptim's existing ModLauncher `ITransformationService`, with one opt-in `ITransformer<ClassNode>` target and an empty transformer list by default.

## Exact 0.8.7 semantics

Blindly copying current Mixin's `Printer.OPCODES` lookup is not equivalent to the 0.8.7 runtime used by NeoForge 21.1.x. Hosted exhaustive comparison against stock `Bytecode.getOpcodeName` found that stock returns decimal strings for values that `Printer.OPCODES` names but runtime `Opcodes` does not expose in the relevant reflective scan:

- `19`, `20`
- `26..45`
- `59..78`
- `196`

Stock returns `ICONST_3` for value `6`; it does **not** return `UNINITIALIZED_THIS`. Negative values return `UNKNOWN`, zero returns `"0"`, and other non-matching non-negative values return their decimal spelling.

The implementation preserved these results and added two independent fail-open guards before rewriting:

1. the public `getOpcodeName(int)` wrapper had to have the exact 0.8.7 bytecode shape (`ILOAD 0`, `"UNINITIALIZED_THIS"`, `ICONST_1`, call to the private stock helper, `ARETURN`);
2. a one-shot, non-cached scan of the actual runtime `Opcodes` fields reconstructed the stock naming table and had to match the direct function for the complete `Printer.OPCODES` range.

No result cache or cross-loader state was retained.

## Equivalence and package gates

The bootstrap suite compared stock `Bytecode.getOpcodeName` with the direct function for every integer in `[-4096, 4096]`, every declared integer value in runtime `Opcodes`, and `Integer.MIN_VALUE` / `Integer.MAX_VALUE`. It also verified the runtime ASM semantics gate, exact Mixin wrapper guard, meaningful fail-open mutation, and direct table coverage.

Head `8110c4b0c76ac61930c79dd9691a3638f50676ea` passed hosted Build/package and normal Startup Benchmark. Exact-pack workflow `34290411814` reached the canonical main-menu endpoint with zero BootOptim Mixin errors on the first candidate run.

## Hosted reachability result: no-go

Candidate iteration 1 was launched with `-Dboot_optim.mixinOpcodeNameDirect=true`. Its full uploaded console proves BootOptim's SERVICE loaded normally:

- `BOOTOPTIM_STARTUP phase=transformation_service_construct`
- `BOOTOPTIM_STARTUP phase=transformation_service_on_load`
- `BOOTOPTIM_STARTUP phase=transformation_service_initialize`

However the complete candidate artifact contains neither `BOOTOPTIM_MIXIN_OPCODE_NAME_DIRECT` nor `mixin_opcode_name_direct`. Therefore `org.spongepowered.asm.util.Bytecode` was never offered to this ordinary GAME-layer transformation-service transformer. This matches the static ModLauncher layering concern: Mixin is launcher/service infrastructure and is parent-loaded before the GAME `TransformingClassLoader` consumes ordinary service transformers.

Because the candidate never applied, subsequent control/candidate timings from this route are not an optimization A/B; they are stock-vs-stock hosted noise. The first candidate happened to report 91.647 s and one control 66.729 s to the same endpoint, but those independent hosted samples must not be interpreted as a regression or improvement.

## Decision

Close the ordinary `ITransformationService` route as **no-go** and retain stock behavior. Do not promote, do not run a laptop gate, and do not escalate to a Java agent, custom classloader, launch-plugin wrapper, vendored Mixin, or global Mixin upgrade for a 39-sample microcandidate without a separately justified safety case. The experimental code remains only in the closed PR for reproducibility.
