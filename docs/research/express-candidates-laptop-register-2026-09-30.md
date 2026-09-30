# Persistent register: express candidates and future low-end validation

User explicitly requested this register on 2026-09-30 to prevent candidates
being lost during conversation compaction. **Read before the next laptop
campaign.** The laptop is not authorized for use now. None of these
default-off experiments is currently integrated production or a proven
physical F3+T win. Preserve prior negative/inconclusive evidence.

| Candidate | Source / PR | Exact switch | Current evidence | Required future low-end gate |
| --- | --- | --- | --- | --- |
| Decocraft corner V2 | [#296](https://github.com/wachipayox/BootOptim/pull/296), `codex/manual-reload-decocraft-v2-20260929` | `boot_optim.experimentalDecocraftCornerRotationReuseV2=true/false` | Semantic aggregates pass; hosted repeat paired medians +280/+635 ms, no consistent win. Historical laptop post-entrypoint -2.325 s direction is inconclusive. | Explicitly include V2 on/off with matched startup and repeat origins, visual geometry equivalence. |
| FerriteCore empty quad-table capacity | [#298](https://github.com/wachipayox/BootOptim/pull/298), `codex/ferritecore-quad-capacity-20260930` | `boot_optim.ferriteCoreQuadCapacity=true/false` | 420,000 canonical insertion checks pass; hosted repeat medians +136/+502 ms, no consistent win. | MUST revisit capacity on/off; record retained heap, rehash/GC cost and reload duration. User specifically requested not forgetting this option. |
| Sodium axis quad classifier | [#299](https://github.com/wachipayox/BootOptim/pull/299), diagnostic [#300](https://github.com/wachipayox/BootOptim/pull/300) | `boot_optim.sodiumAxisQuadFlags=true/false` | 2,410,296 binary equivalence checks; healthy exact-pack. Repeat bake paired median -253/-208 ms; whole reload order-confounded. | Matched physical repeat timing and in-world quad/lighting appearance; do not attribute 1.18 s hosted median to classifier. |
| Generated-item layer expansion delta | `codex/generated-layer-delta-20260930`, [research](generated-item-layer-delta-2026-09-30.md) | `boot_optim.generatedItemLayerDeltaHoist=true/false` | Compile/package pass; identical pure float expression hoisted. Enabled exact-pack smoke 36721846567 passes, pack order/atlas intact, zero BootOptim Mixin errors; no measured saving. | Enable parent direct baker; compare matched reloads and Trimmable Tools item edges. Tiny/noisy effect: report inconclusive if appropriate. |
| Pixel-query reuse | [Audit](generated-item-pixel-query-followup-2026-09-30.md), subagent commit `6c2feba0` | None; express NO-GO | 5,562 ordered-topology cases match, but queries change (12 to 4 in solid 2x2). Injected callbacks can observe change; no simple final-bytecode purity guard. | NOT in laptop candidate JAR. Reopen only with trustworthy final-method guard or an explicitly accepted exact-pack callback contract. |
| UnionFS path normalization | Source audit copied to #300, commit `0f020ddd` | None | 74,912 offline cases match; MC-BOOTSTRAP access makes direct BootOptim implementation intrusive. | Not in laptop candidate JAR. Reopen only for an independently justified source/loader replacement. |

## Staging and recovery contract

These candidates reside in different branches; **there is no combined JAR**.
Before a future campaign fetch integration, re-read the PR bodies/results and
verify actual sources and flags. Build an isolated candidate branch containing
only intended optimization code, excluding repeat automation, JFR/per-quad
profilers and diagnostic comparators from their historical branches. Record
source SHAs, packaged bootstrap hash, enabled packs, JVM and each effective
flag. Test one feature at a time before combinations so effects remain
attributable. Disabled flags in a JAR that does not include their code do not
constitute a control/candidate experiment.

Follow AGENTS.md: stop Prism before instance edits, reject stale JVMs, exactly
one BootOptim JAR, same process/BootOptim origin and menu/reload endpoint.
Keep launcher setup, startup, bake/listener inclusive times, complete manual
reload and final freeze duration separate. Never sum overlapping listeners.
Also verify repeated F3+T, pack changes, menu/options/player rendering and first
world entry. Record every rejection/promotion and update this table with its
PR, flag, final disposition and missing gates. No experimental option may be
silently forgotten because a hosted delta was small.
