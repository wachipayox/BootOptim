# Mixin 0.8.7 opcode-name direct lookup microcandidate — 2026-09-09

Status: **EXPERIMENTAL / default-off**. PR #213. Base authority: `agent/integration-current` @ `fa6df8bc8f74aae32338f521bf845a5730ac634b`.

## Scope

PR #211 attributed 39 JFR execution samples in the pre-Bootstrap Mixin slice to the reflective `org.spongepowered.asm.util.Bytecode.getOpcodeName(int)` path (`Opcodes.getDeclaredFields()` / `Field.getInt`). This is a microcandidate only; the evidence does not support a seconds-scale claim.

## Route selection

- A global Mixin upgrade is not isolated: the launcher/NeoForge stack owns the Mixin artifact and changing it would also change loader and other-mod Mixin behavior.
- Vendoring/forking `org.spongepowered.asm` from BootOptim would introduce split-package/module/classloader risk for a dependency BootOptim does not own.
- A Java agent or launcher/plugin wrapper would be disproportionately invasive for this microcandidate.
- The experiment therefore reuses BootOptim's already-present ModLauncher `ITransformationService`, adds exactly one opt-in `ITransformer<ClassNode>` target, and leaves the default transformer list empty.

There is an explicit reachability risk: ModLauncher collects transformation-service transformers immediately before constructing the GAME `TransformingClassLoader`, while Mixin is launcher/service infrastructure. If `Bytecode` is resolved from a parent layer, the target will never be offered to this transformer. The hosted marker `BOOTOPTIM_MIXIN_OPCODE_NAME_DIRECT` is required evidence that the candidate actually applied; absence is a no-go for this route rather than a performance result.

## Exact 0.8.7 semantics

Blindly copying current Mixin's `Printer.OPCODES` lookup is not equivalent to the 0.8.7 runtime used by NeoForge 21.1.x. Hosted exhaustive comparison against stock `Bytecode.getOpcodeName` found that stock returns decimal strings for values that `Printer.OPCODES` names but runtime `Opcodes` does not expose in the relevant reflective scan:

- `19`, `20`
- `26..45`
- `59..78`
- `196`

Stock also returns `ICONST_3` for value `6`; it does **not** return `UNINITIALIZED_THIS`. Negative values return `UNKNOWN`, zero returns `"0"`, and other non-matching non-negative values return their decimal spelling.

The implementation preserves these results and adds two independent fail-open guards before rewriting:

1. the public `getOpcodeName(int)` wrapper must have the exact 0.8.7 bytecode shape (`ILOAD 0`, `"UNINITIALIZED_THIS"`, `ICONST_1`, call to the private stock helper, `ARETURN`);
2. a one-shot, non-cached scan of the actual runtime `Opcodes` fields reconstructs the stock naming table and must match the direct function for the complete `Printer.OPCODES` range.

The guard scan is not retained or reused as a global cache. The rewritten method reads ASM's existing `Printer.OPCODES` table and contains the compatibility exceptions inline.

## Equivalence tests

The bootstrap test suite compares stock `Bytecode.getOpcodeName` with the direct function for every integer in `[-4096, 4096]`, every declared integer value in runtime `Opcodes`, and `Integer.MIN_VALUE` / `Integer.MAX_VALUE`. It also verifies the runtime ASM semantics gate, the exact Mixin wrapper guard, a meaningful fail-open mutation, and that the table range used directly has no null holes.

## Promotion gate

No production promotion and no laptop run unless all of these pass on the same candidate head:

1. build and exhaustive tests;
2. packaged bootstrap validation;
3. normal startup/exact-pack smoke to the canonical menu endpoint, with the candidate marker proving the target was transformed;
4. same-origin, same-endpoint hosted A/B with repeated runs and a coherent direction large enough to distinguish from hosted noise.

If the target is unreachable through the ordinary transformation service, or if hosted A/B is small/noisy/inconsistent, close as no-go and keep stock behavior.
