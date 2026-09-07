# Strict-vanilla immutable model preparation plan — 2026-09-07

Status: **ARCHITECTURE CANDIDATE / PROTOTYPE GATED / NO PRODUCTION CHANGE**

Base authority: `agent/integration-current` @ `2bccf5f4fa221c78e286d052beb78636fa4c317b`.

This note is Agent 44's source-level closure for the current physical ModelManager bottleneck. It deliberately does **not** reopen top-level identity reuse, recursive baked-model caching, material memoization, eager bake parallelism, worker-count tuning or cull-direction caching. It also does not persist `BakedModel`, `TextureAtlasSprite`, GL state, a mutable `ModelBakery`, or a model registry.

## Physical evidence and accounting

Corrected laptop sample `p0.2-variance-harness-20260906b`:

- menu opening `350,330 ms`;
- ModelManager final future `150,078.409 ms`;
- ModelBakery construction `65,459.832 ms`;
- `loadModels` `46,874.146 ms`, containing `bakeModels` `41,152.538 ms`;
- process CPU at menu about `1,053,438 ms` on four effective processors;
- aggregate GC `15,421 ms`.

Second comparable diagnostic sample:

- menu opening / presented `355,582 / 361,195 ms`;
- all preparations `123,797.845 ms`;
- ModelManager final future `130,955.212 ms`;
- ModelBakery `55,770.392 ms`;
- `loadModels` `38,926.009 ms`, containing `bakeModels` `32,736.444 ms`;
- process CPU about `990,031 ms`;
- GC `15,311 ms`;
- available memory near title `775.551 MiB`.

The scopes above are not additive. In particular `bakeModels` is nested inside `loadModels`. The first process averaged about three CPU cores over menu wall; the second about 2.78. Both reproduce a CPU-dense ModelManager/model branch, while the second diagnostic's large working set means its absolute wall is not a clean production timing comparison.

The only honest physical ceiling currently established for a constructor-side structural plan is the enclosing ModelBakery wall itself: roughly `55.8–65.5 s` in these two samples. That is an **upper bound on the containing phase, not removable work**. Historical exact-pack attribution found parent resolution and item/dependency registration much smaller, so no claim is made that most of those 55–65 seconds are persistable structure. For bake-side work, a plan that still calls current-generation `FaceBakery` cannot claim the whole `32.7–41.2 s` bake as savings.

## Source compatibility boundary

NeoForge 1.21.1 changes `BlockModel` parsing to use `ExtendedBlockModelDeserializer`. A root `loader` can replace vanilla geometry, while `transform`, `render_type` and `visibility` populate NeoForge baking context. `BlockModel#bake` delegates to `UnbakedGeometryHelper`: custom geometry is authoritative when present; generated-item and block-entity markers have separate paths; only the remaining ordinary path becomes `ElementsModel`.

`ElementsModel` binds each face to the **current** `TextureAtlasSprite`, calls stock `BlockModel.bakeFace`/`FaceBakery`, and routes quads according to current model state/cull direction. `SimpleUnbakedGeometry` also obtains the current particle sprite and current render-type group and constructs a fresh model builder.

NeoForge model events are generation/publication boundaries:

- `ModelEvent.ModifyBakingResult` receives the complete mutable baked registry, current texture getter and ModelBakery on a worker thread before BlockModelShaper caching;
- `ModelEvent.BakingCompleted` fires after ModelManager has installed the new registry and model-group state;
- `RegisterAdditional` can inject side-loaded model roots that still require normal dependency loading.

Therefore a safe cache may reuse only **pre-sprite, pre-baked structural input**. Every reload must still create fresh runtime baked models, use current sprites/material atlas state and fire both events exactly once in their stock order.

## Candidate: Structural Preparation IR

Version 1 should target only ordinary vanilla-element `BlockModel` geometry. It must explicitly exclude:

- effective custom geometry or any root `loader` ownership;
- generated-item and block-entity special roots (generated item is already optimized separately in current integration);
- any model whose parent closure leaves the supported `BlockModel` domain;
- malformed/unknown data, unresolved dependencies or parent cycles;
- any representation field not encoded by the current schema.

The immutable IR is not a `BlockModel` and not a baked-model cache. A plan entry contains only canonical structural values such as:

- model resource location and source provenance;
- resolved parent-closure dependency IDs/digests;
- effective vanilla/NeoForge scalar render metadata that the plan claims to preserve;
- ordered element records: raw IEEE-754 float bits for `from`/`to`, optional element rotation, shade and encoded supported face data;
- ordered face instructions: source direction, optional cull direction, tint index, texture/material identifier, raw UV float bits and UV rotation;
- no sprite identity, no atlas object, no GL object, no `BakedQuad`, no `BakedModel`, no mutable registry and no callback result.

At runtime a hit is lowered through the current generation's material/sprite getter and the stock/NeoForge `FaceBakery`; metadata and model construction stay generation-local. The existing ModelBaker recursive cache remains authoritative above this path.

### Strict first-domain recommendation

For the first verifier, narrow further to models with ordinary `ElementsModel` geometry and no non-default NeoForge extended geometry semantics. Specifically reject effective custom geometry and non-identity root transform. Prefer also rejecting a non-null render-type hint and any unsupported extra face data until the verifier has explicit canonical comparison support. Overrides should initially stay on the stock metadata path; if an implementation cannot do that without changing dependency discovery, require empty overrides in v1.

This intentionally sacrifices hit rate for proof quality. Expansion happens only after zero-mismatch evidence.

## New hypotheses

### H1 — compact lowering can matter on weak hardware even when memoization did not

PR #59 proved that adding a map around material lookup is the wrong mechanism: 90–96% apparent hits did not improve the affected timings. A structural plan changes the premise by eliminating repeated parent/texture-chain walking, temporary element/face traversal structures and associated allocation rather than replacing them with another lookup. The laptop's ~2.8–3.0 average utilized cores, ~15.3–15.4 s aggregate GC and low available-memory second sample make allocation/memory-bandwidth amplification plausible, but not yet proven phase-locally.

### H2 — content-addressed parent-closure IR may be more valuable than whole-registry persistence

Persist each eligible resolved model structure by exact source/dependency content rather than serializing one giant ModelBakery. Unchanged parent DAGs can be reused even when unrelated packs/models change. This is different from runtime object-identity caching: the key is immutable content/provenance and the output is pre-bake structure.

### H3 — reload-local snapshot is the necessary falsification step

Before disk persistence, build the same immutable IR after stock parsing/parent resolution and use it only within the current reload. This isolates representation/lowering savings from fingerprint I/O and stale-cache risk. If reload-local IR cannot move ModelManager preparation or the directly affected element-bake work, persistent IR is a no-go regardless of cache hit rate.

### H4 — later parse/parent reuse can be layered only after H3 wins

A second stage may deserialize a previously verified IR instead of recreating the Gson `BlockModel` object graph and resolving unchanged parent chains. Exact byte/content keys preserve parse-error behavior: changed bytes always miss and take the stock parser, so a newly malformed JSON can never be hidden by an old valid plan.

## Exact fingerprint and invalidation

Use a content-addressed design with a conservative two-level identity.

### Environment/ABI key

The cache namespace includes:

- magic string;
- `schema_version` and independent `encoding_version`;
- Minecraft `1.21.1`;
- exact NeoForge version/build;
- BootOptim preparation-plan ABI/build identifier;
- exact model transformation environment identifier. A safe implementation must account for mods/coremods that can alter model parsing/baking semantics; a plain Minecraft version key is insufficient.

### Resource-pack fingerprint

Resource order is semantic. The authoritative fingerprint is SHA-256 over the selected client resource-pack sequence and the exact contributing resource content/provenance in resolution order.

Recommended representation:

1. ordered selected pack identities, preserving priority order;
2. for immutable ZIP/JAR packs, a cryptographic digest of the container or a previously cryptographically verified content manifest;
3. for directory packs, a Merkle digest of every relevant contributing model/blockstate resource, never only mtime/size;
4. per eligible model entry, the exact winning model JSON byte digest plus the ordered parent-closure digests;
5. where a resource type uses a stack/merge across packs, include **all** contributing entries in effective order, not only the top winner.

File metadata may be used only as a lookup hint to avoid unnecessary hashing; it must not be the semantic authority for a cache hit. If obtaining an exact digest costs too much on the laptop, that is a performance reason to reject persistence, not a reason to weaken correctness.

A practical optimization is digest-on-read: while stock/resource loading already reads bytes, compute SHA-256 and build the next content manifest. A future startup can use immutable container digests/content manifests, while changed/uncertain directory packs conservatively miss.

### Invalidation

Any mismatch of ABI, schema, resource order, contributing resource digest, parent closure, transformation environment or safe-domain classifier causes a miss. A changed parent invalidates all descendants through the closure digest. Manual reload starts a new generation and never reuses generation-local runtime objects even when the structural entry hits.

## Generation and atomic publication

Maintain an in-memory monotonically increasing reload generation. A loaded disk snapshot is immutable and belongs to no live atlas/model generation; only freshly reconstructed runtime objects do.

Publication protocol:

1. current reload reads an immutable old snapshot or misses;
2. stock/candidate reload completes successfully and verification has no mismatches;
3. build a new immutable content-addressed payload and manifest off the hot publication path;
4. write to a same-filesystem temporary/generation-named file, checksum it and flush it;
5. atomically replace a small manifest/pointer (`ATOMIC_MOVE` where supported). On Windows, prefer immutable generation files plus a replaceable manifest rather than in-place mutation of an open payload;
6. only the next reload/process may consume the newly published snapshot.

A crash before manifest replacement leaves the previous snapshot authoritative. Orphan temporary/generation files are cleanup-only, never startup blockers.

## Fail-open rules

Any of the following falls back to stock for the affected entry/reload without aborting startup: bad magic/version, checksum failure, truncated file, resource/ABI mismatch, unknown field, unsupported geometry/custom data, dependency disagreement, parent-cycle disagreement, material mismatch, verifier mismatch or runtime reconstruction failure.

On a verifier mismatch, return the stock model, poison that plan key for the process, and do not publish it. Logging must be rate-limited. No global synchronized cache lock is allowed on the model hot path.

## Equivalence strategy

Do not invoke NeoForge callbacks twice. Verification happens **before** `ModifyBakingResult`:

1. bake the eligible entry through the stock path;
2. bake the candidate from the IR with current sprites and stock `FaceBakery`;
3. compare canonical pre-callback output;
4. while verification is enabled, always return stock even when equal;
5. after the complete stock registry is assembled, let NeoForge fire `ModifyBakingResult` once and later `BakingCompleted` once exactly as today.

Canonical comparison should cover, at minimum:

- runtime baked-model class/metadata expected for the strict domain;
- particle sprite identity;
- AO/block-light/gui3d flags, transforms, render types and overrides semantics;
- for unculled and every culled direction: quad count and order, every raw vertex int, tint, direction, shade/light-emission metadata and sprite identity;
- complete top-level key set/order/aliasing invariants relevant to ModelBakery's existing cache.

The candidate must not alter top-level sharing or ModelBaker cache keys. It replaces only the structural work below the same cache-miss point.

Custom loaders and `RegisterAdditional` models continue through stock discovery/bake. A plan entry is eligible only after the implementation can prove it belongs to the strict ordinary `BlockModel` domain.

## Alternatives ranked by fragility

1. **Reload-local immutable structural snapshot — preferred first prototype.** No disk invalidation problem; directly falsifies whether representation is valuable.
2. **Content-addressed parent/JSON IR — preferred persistence form if #1 wins.** Per-model/closure reuse avoids invalidating a monolithic registry for unrelated changes.
3. **Parse-only binary snapshot.** Safer than baked persistence, but useful only if fresh exclusive profiling proves JSON/object-graph construction is material on the physical machine.
4. **Deeper NeoForge backport/API.** Newer Minecraft separates discovery/dependency resolution from baking more explicitly (`ModelDiscovery`, resolved dependencies, later `BakingResult`). A maintained 1.21.1 NeoForge fork could expose an immutable vanilla preparation boundary instead of relying on BootOptim mixins. This is architecturally cleaner but has a larger maintenance/compatibility surface and should follow, not precede, a proven removable ceiling.
5. **Whole ModelBakery/BakedModel serialization — reject.** It crosses sprite generation, custom loader and event boundaries.

## Prototype / CI gates

### Stage A — default-off shadow verifier

Property: `-Dboot_optim.modelPreparationPlan=verify`.

- construct reload-local IR only for the strict domain;
- collect eligibility by **measured stock time**, not call count;
- candidate-bake and canonical-compare every eligible model;
- always return stock;
- record plan-build, stock structural, candidate structural, FaceBakery, allocation/GC and ModelManager barrier timings separately.

### Stage B — reload-local A/B

Property: `-Dboot_optim.modelPreparationPlan=reload-local`.

Only after zero mismatches. Hosted exact-pack same-branch A/B first. No persistence yet.

### Stage C — persistent warm-cache A/B

Property: `-Dboot_optim.modelPreparationPlan=persistent`.

CI must exercise two launches in the same exact-pack VM: an authoritative cache-build launch followed by a measured warm-cache launch. Control must receive an equivalent first launch so OS/JIT/resource page-cache effects are not confused with IR persistence. Repeat with alternating order/fresh VMs if the existing paired harness supports it.

Additional tests:

- codec determinism and malformed/truncated/future-schema files;
- pack-order changes;
- one model-byte change;
- one parent change invalidating descendants;
- custom loader / root transform / render-type cases remaining ineligible;
- current-generation sprite identity after manual reload;
- `RegisterAdditional` model still loaded;
- one `ModifyBakingResult` and one `BakingCompleted` dispatch in stock order;
- cache corruption reaches menu through stock fallback.

### Physical gate

If hosted semantics pass and the affected metric moves coherently, use the low-noise laptop harness. Compare matched control/candidate runs with the same start/end markers and JVM/resource state. Report menu opening and presented separately, all-preparations/ModelManager barrier/final future, ModelBakery, `loadModels`, nested `bakeModels`, phase-local CPU, GC and memory. Never sum overlapping scopes.

## Explicit no-go criteria

Stop the architecture before persistent production if **any** of these is true:

- one canonical semantic mismatch in the strict domain;
- callback count/order or complete registry key-set semantics change;
- any custom-loader/additional-model path is accidentally captured;
- stale current-generation sprite/model state can be observed after a reload;
- corrupted/stale cache can prevent stock fallback;
- reload-local IR does not produce a coherent direct timing improvement: persistence cannot rescue a representation whose only evidence is hit count;
- the measured strict-safe domain owns less than about `5 s` of physical removable structural wall on the ModelManager critical branch, or a hosted effect remains mixed-sign/noise-sized after the normal paired gate;
- exact fingerprint + deserialization overhead consumes roughly half or more of the stock structural work it replaces;
- a win exists only in an isolated microphase while ModelManager preparation and TTMM do not move.

A production promotion additionally requires zero verifier mismatches, exact-pack startup success, manual-reload generation safety, and a reproducible physical reduction in the actual ModelManager preparation gate/TTMM. A high cache hit ratio is never sufficient.

## Decision

**Proceed only to a default-off reload-local shadow verifier, not directly to persistent production code.** The persistent structural-plan architecture is semantically viable for a strict vanilla element domain because it can remain entirely before sprite binding and NeoForge's complete-map callbacks. It is materially different from the rejected caches. But the current data still do not prove that enough of the physical `55.8–65.5 s` ModelBakery phase is deterministic structural work rather than current-generation bake/model processing. Stage A must quantify that ceiling first.
