# Sodium quad classification by face axis — 2026-09-30

Status: experimental, default off; no physical performance claim.

## Authority and scope

Fresh integration base: `a0b8fdc05dd97267698ebbce1f561ac4895d3b74`.
This follows the manual-reload shortlist and the user's request to investigate
one stronger direct-operation reduction alongside bounded smaller audits.
The candidate does not change executor scheduling, resource publication,
atlases, model geometry, callbacks in BakedQuad construction, or generation
lifetime. It changes the pure Sodium flag calculation for exact BakedQuad
instances only, with pinned Sodium version `0.8.12-beta.1+mc1.21.1`.

The current physical diagnostic bake interval is 5.122–5.254 s. This is the
entire bake, **not** this classifier's exclusive cost or a predicted saving.
ModelQuadFlags appears in worker samples both in ordinary/generated baking
and in retained Decocraft quarter-turn derivation. #296 corner reuse and #298
FerriteCore table capacity are separate experiments with no coherent hosted
repeat-reload speedup; neither is enabled here. Existing quarter-turn reuse,
generated-item direct baking and all integration optimizations remain intact.

## Mechanism

The exact Sodium classifier reads X/Y/Z for each of four vertices, reduces
minimum/maximum for all three axes (24 min/max operations), then computes
partial, parallel and aligned bits from those bounds.

Only two perpendicular axes need extents. On the face axis, equality of all
four coordinates is equivalent to equal reduced bounds **only if the plane
lies within [-32,32]**, because Sodium initializes minima/maxima with those
sentinels. The candidate retains all twelve virtual getter calls in exactly
the original order, computes tangent bounds only, and replaces face-axis
min/max with four-coordinate equality plus the sentinel-range guard.
Pure tangent comparisons may short-circuit; getter calls do not.

NaN is a meaningful trap: partial must use the direct stock >= / <= OR
comparisons. Negating a hypothetical full-coverage AND changes NaN results.
The independent real-bytecode checker caught this during development and the
prototype was corrected before any game test.

## Optional integration

An optional interface Mixin adds QuadCoordinateView as a superinterface of
Sodium ModelQuadView, whose existing three getters already match. The static
optional flag hook accepts Object with @Coerce, avoiding a Sodium compile or
runtime dependency. Disabled property, null input/face, unknown view/class,
missing bridge or unsupported Sodium version delegates to stock. Version
resolution is lazy and performed once, not once per quad. Normal computation
in this Sodium build is already lazy and is **not** an alleged saving here.

Flag: `-Dboot_optim.sodiumAxisQuadFlags=true` (default false).
Marker on first eligible enabled call:
`BOOTOPTIM_SODIUM_AXIS_QUAD_FLAGS status=active version=...`.
The marker proves eligibility, not saved time or complete pack equivalence.

## Validation and remaining gates

Initial local packaged bootstrap build passed in 31 s. An independent
JVM/ASM linkage fixture using the real Sodium interface passed added-parent
interface casting, getter dispatch and cancellation/fallback behavior. Actual
Mixin 0.8.7 bytecode supports interface merging and Object coercion. These
are not substitutes for a real Mixin runtime/startup gate.

The exact installed Sodium bytecode is the semantic comparison authority;
its artifact is extracted read-only to a temporary analysis directory.
The independent exact-binary harness passed 2,410,296 comparisons with zero
mismatches, the twelve-read order check and all 72 getter-exception cases.
The root agent reproduced these results against the final helper source.
Three warmed CPU microbenchmark JVMs gave mixed results: stock/candidate
medians per 8.192 million calls were 382.813/281.250, 289.063/312.500 and
273.438/250.000 ms. This is **inconclusive performance evidence** and must not
be converted into saved physical reload time. The final packaged build passed
after the null fallback and unique-field hardening.

The next gate is enabled hosted exact-pack smoke for actual Mixin application,
first eligible activation and completed startup. If it passes, a comparable
critical-phase/end-to-end comparison is still needed before any promotion.
Only comparable end-to-end/critical-phase evidence can promote the candidate.
GPU/AO/culling visual validation remains a later physical gate if justified.
No laptop access or instance JAR replacement is authorized by this experiment.

The smaller parallel audits found no justified generic Palladium variant
cache or dispatch identifier cache. Caller attribution shows most String.join
leaf samples belong to UnionPath, not the Palladium getter; exposed mutable
arrays and custom property serializers rule out blanket reuse. Separately,
ModernFix already caches multipart selector predicates; historical constructor
cost was only 35.824 ms. None of these findings proves every possible
algorithmic multipart/resource-path improvement exhausted.
