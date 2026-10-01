# Sodium actual classifier owner verdict — 2026-10-01

Status: **ACTIVE BOUNDED VALIDATION**, base integration `19afd955` including the
user's two-control/two-candidate segment-first policy. #299/#300/#304 reviewed.
The candidate algorithm/guards are unchanged, including twelve ordered virtual
reads, NaN/sentinel behavior and exact class/version fallback.

Previous whole-bake clocks included other owners; standalone audit used a
synthetic coordinate corpus. This probe captures up to 4096 actual pinned-pack
eligible BakedQuad references plus the original Direction, sampling every 2048
eligible classifier invocations during the initial generation. Capture does
not copy vertices or call any extra getters. It is diagnostic startup overhead,
so no startup or full-reload performance conclusion is permitted from this run.
The real initial reload must finish before any replay, and capture then stops.

Replay calls the **actual transformed Sodium getQuadFlags method** via one
pre-adapted MethodHandle in both arms. Thus the current mixin guard, exact
version lookup (warmed), interface dispatch, cancellation and original stock
method are exercised; this is not a handwritten substitute for Sodium bytecode.
Capture injection has higher priority and a cheap disabled-capture guard in
both arms. No collection lock is taken during replay. This instrumentation is
never a production promotion.

Every selected real quad first passes original/candidate flag equality. Twenty
alternating unmeasured warmup blocks precede exactly C1/B1/B2/C2 observations.
Each has the same approximately eight million calls over the same reference
corpus; two CPU reads per complete block and elapsed wall separately. Checksums
must match, clocks must be valid and the actual candidate activation must be
present. References are cleared before automatic shutdown; no world is entered.
No framebuffer/GL/render work is timed as classifier CPU.

Both opposite-order contrasts must reduce classifier-block CPU for retention;
otherwise retire this candidate under the current policy. A retained segment
still requires an uninstrumented semantic/visual runtime gate before production.
The artificial repetition does not predict the real reload's seconds saved,
callback side effects of unknown mods or its cold JIT behavior. Source changes
are math only, so a whole-startup delta is not the primary gate.

`check_sodium_replay.py` rejects wrong process/order, missing/malformed clocks,
corpus changes, mismatch checksums, absent activation and extra resource reloads.
Hosted validation uses full console after process exit. No laptop or PC instance
was staged by this probe. No new performance result until runtime completes.
