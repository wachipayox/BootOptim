# AccessTransformers 10.0.1 incremental-equivalence contract — 2026-09-13

Status: **TEST CONTRACT / GO FOR FUTURE ISOLATED CANDIDATE, NO RUNTIME OPTIMIZATION**

This entry defines the correctness contract for a future source-level correction to the per-file AccessTransformers transaction. It deliberately does **not** implement a BootOptim bridge, replace the runtime engine, change FML/ModLauncher, or claim startup savings.

## Authority and pins

- BootOptim integration base: `agent/integration-current` @ `b3f0c5f6462a359483883741ac16d2f154868900`.
- FML runtime/source boundary from PR #275: `fml_loader@4.0.43`, source `neoforged/FancyModLoader@15c77cf658f360c171668a8700d02c30ad0cd965`.
- Runtime dependency under test: Maven coordinate `net.neoforged:accesstransformers:10.0.1`.
- Public AccessTransformers repository: `neoforged/AccessTransformers`, MIT licensed.
- The relevant 10.x implementation is the pre-11 ANTLR implementation. `AccessTransformerList.java` has blob `30706bda44951b044a90fc2c1d00a55ccd98abd2` both at `139da711070c67f7e62cc20ea43507aa216cc8c6` and at the last pre-parser-refactor commit `e3f429bf3ac9d2a25cdbb8c73f14c2fb15af5089`. The 11.0 transition begins at `ef6c7ed15005cef7c51a6a873359c286ca64e8eb` and changes parser/API structure, so it is not used as a semantic oracle for 10.0.1.

The isolated test project resolves the published 10.0.1 artifact and fails if `AccessTransformerEngine` does not report an implementation version beginning with `10.0.1+`. It does not vendor or replace the distributed JAR.

## Stock transaction that must be preserved

For every file, stock 10.x `AccessTransformerList.loadAT`:

1. parses the complete file using the stock ANTLR lexer/parser and `AccessTransformVisitor`;
2. copies the complete currently published `HashMap<Target<?>, AccessTransformer>`;
3. merges the file's rules into the copy in parser encounter order;
4. scans the complete copied map for invalid final-state conflicts;
5. on conflict, logs every invalid transform in the copied-map iteration order and throws `IllegalArgumentException("Invalid AT final conflicts")` without publishing the copy;
6. on success, clears and republishes the full map and rebuilds the targeted-class set.

`AccessTransformer.mergeStates` is itself observable: modifier state is merged by minimum enum ordinal, finality by bitwise-OR of `FinalState` ordinals, and each merge creates a new transformer whose origins begin with `<current-file>:merge:0`, followed by the existing origins and then the incoming origins.

## Existing upstream coverage

The 10.x upstream tests cover useful components but not this transaction contract:

- `AccessTransformerLoadTest` loads ordinary AT resources and compares sorted `AccessTransformer.toString()` output.
- `ATRulesTest` checks visibility/finality application to access flags.
- `BatATParseTest` checks lexer tokenization of a bad fixture.
- parser/base/transform tests cover parsing and transformation pieces.

They do not jointly assert multi-file publication after every file, exact ordered origins, rollback after a conflicting file, ordered conflict diagnostics, parser failure publication state, or differential transformed class output for an incremental implementation.

## Isolated differential harness

`at-incremental-contract/` is a test-only Gradle project. `IncrementalContractTest` executes stock 10.0.1 alongside a deliberately simple reference model. The reference is not proposed production code: on successful files it keeps an overlay only for targets touched by the current file; on a conflict it recreates the stock `HashMap` copy solely to reproduce the current exceptional diagnostic path.

The fixtures and oracles are:

1. **Visibility/finality merge across files** — base protected rules followed by public `+f`/`-f` rules. After every file, compare canonical target identity, modifier, finality, ordered origins, and target-set membership.
2. **Repeated rules** — a third file repeats field/method rules. The field oracle fixes the exact origin sequence `03-repeat.cfg:merge:0`, `02-merge.cfg:merge:0`, `01-base.cfg:2`, `02-merge.cfg:2`, `03-repeat.cfg:1` together with `PUBLIC` / `REMOVEFINAL`.
3. **Conflicting finality** — a valid `+f` file is followed by a `-f` file for two targets. Compare exception class/message, ordered formatted ERROR messages, unchanged published state, and unchanged target membership.
4. **Malformed parser input** — after a valid publication, parse `public example.Target broken(`. Compare failing file boundary, throwable class/message and the pinned parser's line/column stderr fingerprint, then verify no publication occurred.
5. **Transformation result** — transform the same synthetic ASM class with stock and the reference state and compare generated class bytes. The class includes a private method and an `INVOKESPECIAL` caller, so the engine's private-method access change and corresponding `INVOKEVIRTUAL` rewrite are exercised rather than comparing only the map.

## Observables that are not stable universal oracles

Some implementation details are observable in one run but cannot safely be normalized into cross-version/cross-JDK contracts:

- `Set<Type>` iteration order is unspecified; compare membership, not iteration order.
- Multiple conflict diagnostics currently inherit `HashMap` iteration order. A future implementation cannot choose a sorted or insertion order and call it equivalent. Under the pinned runtime it must reproduce stock order, or take/fall back to the stock exceptional path. The harness therefore reconstructs the stock `HashMap` on conflicts.
- Logger prefixes, timestamps, thread labels, backend formatting and stack traces are infrastructure-dependent. The stable oracle is ordered formatted AXFORM ERROR message content under the pinned backend, plus throwable class/message and transaction state.
- The 10.x parser error listener throws `RuntimeException("")`; ANTLR's default listener emits line/column text to stderr. The harness pins that parser/JVM dependency path, but a future upstream parser version would require a new oracle rather than reusing this one.
- Object identity, identity hash codes, allocation order, JIT timing and raw `HashMap` internal bucket layout are not semantic equality criteria.

## Evidence boundary

PR #274 measured 97 per-file AT calls at 272.654 ms and 347.437 ms summed child wall in two heavily instrumented hosted launches; those are diagnostic method timings, not savings. PR #275's lower-perturbation hosted exact-pack run measured `addAccessTransformers` at **233.611 ms wall / 232.572 ms thread CPU**, within `stage2Validation` **286.449 ms wall / 282.121 ms CPU**. The same run reached the main menu in **71,308 ms**, with 14/14 resources in order, reload count 1 and zero BootOptim Mixin errors. None of these numbers establishes the benefit of an incremental correction.

No performance A/B is part of this contract PR. Exact-pack runtime CI would not exercise the isolated reference implementation. A future source-level candidate must first pass this differential contract, then run the standard exact-pack gates and an end-to-end candidate/control A/B before any TTMM claim.

## Decision

**GO** to use this contract as the entry gate for a future isolated AccessTransformers 10.0.1 incremental candidate/upstream correction. The public MIT source and published artifact make the behavior reproducible enough to test.

**NO-GO** for any BootOptim/runtime promotion from this work. There is no replacement engine, no packaged JAR change, no end-to-end A/B and therefore no demonstrated startup saving. A candidate that cannot preserve parser failure boundary, conflict diagnostic order, exact ordered origins, transaction rollback and transformed class output must fall back to the stock per-file transaction or be rejected.
