# MCEF first-consumer owner/reentry hardening — 2026-09-06

Status: **CANDIDATE / HOSTED VALIDATION REQUIRED**

PR #90 is now integrated, so its gameplay-safety follow-up can be evaluated on the authoritative tree. The existing coordinator used one `FORCING_BY_CONSUMER` state and a shared 30-second future. That left two correctness hazards:

- MCEF publishes its client/app before running synchronous `scheduleForInit` callbacks. A callback that called `getClient()` on the initializer thread could re-enter BootOptim and wait on the future that the outer `initialize()` call could only complete afterward (self-deadlock).
- A waiter timeout published `ABORTED` and completed the future while native initialization could still be running. A later consumer could then proceed against half-published state.

This candidate keeps the real MCEF lifecycle and thread affinity unchanged. It records the thread entering the real initializer, adds an `INITIALIZING` state, lets same-thread callback reentry pass through to stock getters once initialization has begun, and makes non-owner consumers wait for the real completion instead of converting a timeout into a false terminal state. Exceptional completion releases waiters; `Error` is rethrown after the state/future are made consistent.

## Validation

- `./gradlew test build` passes locally on the post-PR90 integration tree.
- No laptop run was performed; the original laptop instance remains restored and idle.
- The existing title/video-consumer and exact-pack evidence from PR #90 remains the compatibility baseline.

## Required next gate

Push this as a separate PR and run hosted build/startup/exact-pack smoke. Before promotion, add a deterministic coordinator test or equivalent harness for owner callback reentry, concurrent consumers, initializer failure, and a native interval longer than the old 30-second threshold. A Windows/native world-entry smoke is still required before adding explicit local-world or remote-connect prewarming hooks; this candidate does not add those hooks.
