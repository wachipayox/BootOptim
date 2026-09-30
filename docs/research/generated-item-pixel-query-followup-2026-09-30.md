# Generated-item pixel query reuse follow-up — 2026-09-30

Status: NO-GO for an express production patch; pure-mask algorithm validated, callback safety unresolved.

Integration refreshed: a0b8fdc05dd97267698ebbce1f561ac4895d3b74. Isolated branch: codex/pixel-query-reuse-followup-20260930. No laptop, Minecraft process or user instance used.

## Premise and scope

DirectGeneratedItemBaker.buildTopology reads each pixel and, for opaque pixels, up to four neighbors. A per-frame bitmap or rolling three-row buffer can reuse alpha queries while retaining frame/y/x/UP/DOWN/LEFT/RIGHT edge insertion order. This is not the rejected cross-model span cache in PR #57. Historical topology CPU is about 251 ms in later evidence, and old repeated-span removable work was 138.501 ms; neither is an end-to-end savings prediction.

Exact NeoForge 1.21.1 SpriteContents source in the local sourcesWithNeoForge archive confirms isTransparent computes frame offsets and reads originalImage alpha. This is pure in stock source. However exact SpriteContents runtime class does not rule out injection into the method; neither does a scan finding no current pack target prove support for unknown future mods. The integration source has no final transformed-bytecode purity verifier or mixin plugin offering one. Reading an archive class resource would prove pre-transform bytes only. A postApply plugin would not by itself certify transformations running afterwards.

## Reproducible semantic checks

Run `python scripts/pixel-query-audit/check.py`.

The checker compares complete ordered edge records (direction, anchor, min, max), exhaustive small masks and 5,000 random multi-frame masks. It also demonstrates the callback mismatch: a solid 2x2 sprite requires 12 isTransparent invocations in the current traversal but only 4 in bitmap traversal, in a different order. An injected counter can observe that change even when returned topology matches. A stateful injected alpha policy can also change geometry. These tests validate the mask algorithm, not transformed Minecraft runtime equivalence.

## Disposition and durable hardware queue

No production code, JVM flag, JAR or runtime gate was added. Consequently this proposal is NOT a forgotten disabled option awaiting laptop validation. Do not include it in the laptop candidate bundle. Parent's independent arithmetic-hoist change and Sodium PR #299/#300 remain separate candidates and must keep their own physical-validation rows.

Reopen only if a simple reliable final-bytecode guard or explicitly controlled SpriteContents implementation establishes purity after all transformations, or if the project deliberately accepts an exact-pack-only callback contract and records that policy. A broad loader/purity architecture for this modest CPU ceiling is not justified as an express change. If reopened, require ordered topology tests, current-generation lifetime, unknown sprite/provider fallback, enabled hosted startup/reload checks, physical visual validation and comparable timings before promotion. No GL work or scheduling change is involved.
