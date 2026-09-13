# Access Transformer 10.0.1 observable-order audit and shared-identity oracle

Date: 2026-09-13  
Agent: 145  
Status: diagnostic only; **no runtime candidate, no candidate JAR, no exact-pack A/B, no savings claim**.

## Scope and pins

Integration authority: `agent/integration-current@b3f0c5f6462a359483883741ac16d2f154868900`.

Read before this work: `AGENTS.md`, `README.md`, `docs/research/README.md`, PR #274, PR #275 and PR #277.

Exact public sources:

- FML 4.0.43 source pin used by the prior research: `neoforged/FancyModLoader@15c77cf658f360c171668a8700d02c30ad0cd965`.
- AccessTransformers 10.0.1: `neoforged/AccessTransformers@139da711070c67f7e62cc20ea43507aa216cc8c6`; `AccessTransformerList.java` blob `30706bda44951b044a90fc2c1d00a55ccd98abd2`.
- AccessTransformers is MIT; this branch fetches the exact public source in CI and does not redistribute an upstream binary.

Prior timing remains attribution only: #274 measured 266.609 ms hosted inside the 97 AT child calls in its cited run; #275 measured `addAccessTransformers` 233.611 ms wall / 232.572 ms CPU hosted and reported one laptop probe at 1,356.24 ms wall / 968.75 ms CPU. None is an A/B saving or menu-critical-path saving.

## Export/API boundary

`net.neoforged.accesstransformer` exports only `net.neoforged.accesstransformer.api`. The exported `AccessTransformerEngine` contract exposes load methods, `getTargets()` and `transform(...)`. Its Javadoc describes `getTargets()` as the classes targeted by loaded ATs; it does **not** promise iteration order, immutability, snapshot semantics or stable identity.

`AccessTransformerList`, `getAccessTransformers()`, `getTransformersForTarget()` and `containsClassTarget()` are in the non-exported parser package. They therefore are not JPMS API contract. That does not make their behavior irrelevant: `AccessTransformerEngineImpl` consumes them internally, and their HashMap encounter order can reach transformation order and diagnostics.

The ModLauncher adapter module exports `net.neoforged.accesstransformer.ml`; `AccessTransformerService` has a public final `engine`, calls `engine.getTargets().contains(classType)` from `handlesClass`, and calls `engine.transform(...)` from `processClassWithFlags`.

## Consumers and observables

| Surface | Consumer in exact source | Contract vs implementation | Observable that must be preserved for this pack path |
| --- | --- | --- | --- |
| raw `accessTransformers` `HashMap` | `AccessTransformerList.loadAT`, validation and grouping helpers | private implementation | per-file copy/merge/clear+putAll topology; conflict traversal/order; source for every downstream grouping |
| `getAccessTransformers()` | upstream tests/diagnostics; not called by FML 4.0.43 runtime path found in source | public Java method in non-exported package, not module API | returned grouping is a fresh mutable `HashMap`, but entry/list encounter order and shared AT/origin objects reflect raw map order; cannot be normalized in an equivalence claim |
| `getTransformersForTarget(Type)` | `AccessTransformerEngineImpl.transform` | internal non-exported method, but effective runtime consumer | outer/inner grouping order is derived from raw `HashMap`; CLASS group is applied with `forEach`, so order is transformation-observable; FIELD/METHOD lookup combines map membership with class-node member order |
| `getTargets()` | `AccessTransformerService.handlesClass`; `AccessTransformerEngineImpl.containsClassTarget` path; upstream CLI | exported API returns `Set<Type>` without order/mutability guarantee; exact implementation returns its current set directly | membership gates whether ModLauncher handles/transforms a class. Exact implementation also exposes identity/mutability accidentally: initial empty set is immutable, successful load replaces it with a mutable collected set, failed load keeps the previous object. A caller mutation can therefore affect later `contains` until next successful publication. Preserve this behavior rather than arguing it is undocumented. |
| `AccessTransformer.getOrigins()` | conflict diagnostic and `toString`; oracle | non-exported implementation object | ordered live `ArrayList`; `mergeStates` emits synthetic merge origin then old origins then new origins. Diagnostic arguments are order-sensitive. |
| `transform(...)` | ModLauncher `processClassWithFlags`; upstream CLI | exported engine API | class access, field/method access, private-method rewrite and output ASM bytes; must match stock, not only final map membership |

FML 4.0.43 itself obtains the public `AccessTransformerService.engine` during loader setup and `FMLLoader.addAccessTransformer` calls `loadATFromPath` for each AT path. No direct FML runtime consumer of `getAccessTransformers()` or `getTransformersForTarget()` was found; transformation consumption is through the ModLauncher plugin above. The upstream CLI is not the game-pack runtime, but independently confirms that the exported contract is used as `getTargets().contains(type)` followed by `transform`.

### Why Agent144's Aa/BB result did not prove the narrowed reference wrong

Agent144 correctly found that rebuilding an expected `HashMap` after the candidate had already parsed/published could produce `FIELD Aa, FIELD BB, CLASS` versus `FIELD BB, FIELD Aa, CLASS`. For collision/tree-bin cases, key identity/tie-breaking and insertion history are part of the concrete `HashMap` topology. Reconstructing the expected map from candidate-observed objects after publication is therefore not a valid oracle for attributing a topology difference to the candidate.

That does **not** justify ignoring the difference. It means the oracle must arrange the experiment before either lane publishes.

## In-process shared-identity oracle

Workflow: `.github/workflows/agent145-at-topology-oracle.yml`  
Runner: `scripts/agent145/run_at_topology_oracle.sh`  
Source generator: `scripts/agent145/apply_at_oracle.py`.

The runner fetches the exact MIT commit and checks the exact `AccessTransformerList.java` blob before any diagnostic source transformation. It then constructs two real `AccessTransformerList` instances inside the exact 10.0.1 source module.

For every file the exact parser runs **once**. The resulting `List<AccessTransformer>` and its exact `Target` object identities are passed to both real lists before either lane's next publication. Both lanes execute the same `new HashMap<>(state) -> merge -> clear -> putAll` publication and the same eager target-set rebuild. The only reference difference is healthy validation: stock scans all copied values; the reference probes the final state of targets touched by the parsed file. On any detected conflict it materializes the same stock full invalid traversal before logging/throwing.

This topology is intentionally narrower than Agent144: **lazy target-set rebuilding is not part of the reference**. The returned `Set` identity/mutability/replacement behavior remains stock.

The JUnit oracle checks:

1. after every successful file in a deterministic 32-class `Aa`/`BB` collision corpus: raw committed-map iteration, `getAccessTransformers()` outer/list iteration including origins, `getTargets()` iteration and membership, and every `getTransformersForTarget()` outer/inner iteration;
2. conflict publication rollback, exception message and the exact target/origin sequence used as conflict diagnostic arguments;
3. malformed-parser exception class/message/stderr fingerprint;
4. `getTargets()` initial immutability, escaped-set mutability, identity until publication, and replacement on the next successful file;
5. real `AccessTransformerEngineImpl.transform` results and final ASM class bytes for targeted sample classes.

Successful hosted run: https://github.com/wachipayox/BootOptim/actions/runs/34761063399 (`Agent145 AT topology oracle`, `BUILD SUCCESSFUL`, Java 17, exact upstream version resolved as 10.0.1). This is a contract/oracle result only, not performance evidence and not exact-pack physical equivalence.

## Decision

**GO to reopen only the narrowed touched-validation design for a later, separately reviewed implementation experiment.** The required in-process exact-topology oracle now exists and passes without reconstructing external expected maps. It demonstrates that the Agent144 order failure was an oracle/topology problem for this narrowed experiment, not evidence that touched-only validation itself necessarily changes collection order.

This is **not GO-to-ship**. There is no runtime candidate on this branch, no candidate artifact, no exact-pack smoke/A/B and no measured saving. The earlier 233.611/266.609 ms numbers remain inclusive/child attribution only.

The following remain NO-GO without new proof: lazy/deferred `getTargets()` publication, replacing or normalizing any `HashMap`, dedupe/cache/reordering, changing parser invocations, changing per-file failure/rollback boundaries, or any design whose oracle cannot feed the same parsed object graph to stock and reference before publication.

### Reopening criterion for any broader incremental AT change

A broader design may be reconsidered only if its differential test starts from the exact 10.0.1 source, shares parser-produced object identities before publication, compares state after every file (including live-set identity/mutability), captures failure/diagnostic order, and compares actual transforms/ASM bytes. If the design intrinsically requires different key objects or a different publication topology such that this comparison cannot be constructed, the front is NO-GO rather than 'probably equivalent'.
