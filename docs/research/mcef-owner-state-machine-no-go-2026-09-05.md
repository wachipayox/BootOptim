# MCEF owner-aware gameplay state machine — 2026-09-05

Status: **SOURCE-LEVEL NO-GO FOR IMPLEMENTATION ON CURRENT INTEGRATION**

Baseline: `agent/integration-current` @ `145c10c2f8132b21e7b7be067c56513b394ccb5a`.

Scope: continuation of PR #112. This audit does not overlap CEF with resource reload, move JCEF/native/GL work to a worker, change the already validated title/menu experiment, or claim that a title-screen smoke proves gameplay.

## Blocking integration fact

The requested baseline does **not** contain the MCEF first-consumer production candidate. At the exact integration SHA there is no `dev/wachipayox/bootoptim/compat/client` package, no `McefFirstConsumerDefer`, no MCEF/FancyMenu first-consumer mixins in the client mixin tree, and no MCEF entries in `boot_optim.mixins.json`. The two requested first-consumer documents are also absent from integration.

Those files exist on still-open draft PR #90 (`agent/promote-mcef-first-consumer-defer` @ `e348ef820520733eca3f8e64d0cd01013e570f15`), whose base is `agent/integration-current`. PR #112 explicitly audits that PR as not yet gameplay-complete.

Therefore an implementation from current integration would necessarily copy/cherry-pick or independently recreate #90's unmerged coordinator and mixins before changing their semantics. That would overlap the open promotion branch and violate the requested isolated continuation. The safe result for this branch is a source-level no-go, with the exact state contract and tests to apply once the first-consumer implementation actually becomes part of the authoritative integration baseline.

## Exact source facts

### MCEF `2.1.6-1.21.1`

Upstream commit: `CinemaMod/mcef@4cecd7b1f009280694a42c255398a3e3d2ca417c`.

`MCEF.initialize()` is synchronous on its caller. On success it first creates/publishes `app` and `client`, then runs every `scheduleForInit` listener synchronously, clears the list, performs scheme/shutdown registration, and only then returns `true`. `isInitialized()` is exactly `client != null`; `getApp()` and `getClient()` assert initialized state.

This ordering makes owner reentry concrete: a callback can call `MCEF.getApp()`/`getClient()` after `client` was published but before the outer `MCEF.initialize()` returned.

### WebDisplays exact fork

Exact source audited by #90/#112: `brother-bill/webdisplays-mc@df820aa136b64368a043c2c1e1ef2d0292d233eb` (`2.5.0-1.21.1`).

`SharedProxy.init()` registers `MCEF.scheduleForInit(cef -> onCefInit())`. `ClientProxy.onCefInit()` synchronously calls `MCEF.getApp()` and `MCEF.getClient()` to install the WebDisplays scheme, display handler and message router. `WDBrowser.createBrowser()` constructs its browser from `MCEF.getClient()` and calls `createImmediately()`.

Thus WebDisplays supplies both the exact callback-reentry case and a real in-world first-consumer case.

### FancyMenu exact pack path

PR #90's exact-pack audit uses FancyMenu `3.9.0-wedit`. Its high-level MCEF readiness bridge can short-circuit before direct MCEF getters, which is why #90 added FancyMenu-specific first-consumer guards. Hosted validation reached an active `video_mcef` title consumer and real MCEF initialization, but that smoke does not prove local-world or remote-connect behavior.

## Why PR #90's current coordinator is unsafe for gameplay promotion

PR #90 uses `FORCING_BY_CONSUMER` plus one shared `CompletableFuture<Boolean>` and a fixed 30 s wait.

The deterministic reentry cycle is:

```text
first consumer
  -> state FORCING_BY_CONSUMER
  -> MCEF.initialize()
     -> app/client published; isInitialized() == true
     -> WebDisplays onCefInit() synchronously
        -> MCEF.getApp()/getClient()
           -> BootOptim beforeConsumer()
              -> sees FORCING_BY_CONSUMER
              -> same-thread escape is skipped because isInitialized() == true
              -> waits on completion future
  -> outer initialize cannot return and complete the future until callback returns
```

That is a self-wait. Independently, a 30 s waiter timeout cannot be promoted to global `ABORTED` while the real initializer may still be executing: #78 already observed a >30 s native interval under a rejected contention setup, and a timeout is not evidence of initializer termination.

## Required owner-aware state contract

The minimum terminal/nonterminal model is:

```text
UNINITIALIZED
  -> FORCING_BY_CONSUMER(owner)
  -> INITIALIZING(owner)
  -> READY
                   -> FAILED(cause)

UNINITIALIZED -> ABORTED(reason)       // legal before ownership only
FORCING_BY_CONSUMER -> ABORTED(reason) // legal only before real initializer entry
INITIALIZING + cancel/timeout           // request/waiter outcome only; NOT terminal
```

`ABORTED` means BootOptim no longer owns an initialization attempt and stock/fail-open behavior may continue. It must never mean “a waiter stopped waiting” while CEF is still running.

### Owner gate

The transition that wins initialization records `ownerThread` and a unique attempt/generation. Only that owner may enter the real synchronous initializer for the attempt. `INITIALIZING` is published immediately before invoking the real `MCEF.initialize()`.

A world-entry force on the Minecraft client/render thread is itself the owner if no earlier real consumer already owns the attempt. Native/thread affinity remains unchanged.

### Callback-reentry gate

If `Thread.currentThread() == ownerThread` while state is `FORCING_BY_CONSUMER` or `INITIALIZING`, a guarded getter must **never wait**.

During `INITIALIZING`, if MCEF has already published its client/app, the reentrant guard passes through to the stock getter and allows the synchronous callback to finish. Before publication, owner reentry is an invariant violation and must not recursively invoke the initializer; it should fail deterministically/log rather than wait on itself.

No callback is manually dispatched by BootOptim and no upstream initialized flag is forged.

### Second-consumer gate

A non-owner consumer that observes `FORCING_BY_CONSUMER`/`INITIALIZING` may wait for the attempt's completion signal. It must not invoke the initializer concurrently.

A bounded diagnostic timeout is allowed only as a return/exception outcome for that waiter. Timeout must not change global state to `FAILED` or `ABORTED`, complete the owner completion signal falsely, or permit a direct MCEF getter to proceed as though ready.

### Failure gate

Only the owner completing the real initializer may publish the attempt terminal state:

- return `true` and stock readiness visible -> `READY`;
- return `false` after MCEF's own failure callback/shutdown path -> `FAILED` with a stable failure descriptor;
- thrown exception/error -> `FAILED(cause)` after the real call exits.

Later consumers see the same terminal result; they do not retry implicitly within the same attempt.

### Cancellation gate

Cancellation is state-sensitive:

- before an owner claims the attempt: `UNINITIALIZED -> ABORTED` is legal;
- after claim but before the owner enters the real initializer: owner may acknowledge cancellation and transition to `ABORTED`;
- once state is `INITIALIZING`, cancellation is only `cancelRequested=true`. The synchronous native call cannot be safely aborted by BootOptim. Terminal state is published only after the real initializer returns/throws.

This prevents a stale async/native initializer from continuing behind an `ABORTED` state.

### Long-timeout gate

A test must keep the owner initializer blocked for longer than the old 30 s threshold while a second consumer times out. Expected result:

- second consumer gets a waiter timeout only;
- global state remains `INITIALIZING` with the same owner/attempt;
- when owner is released and initializer succeeds, state becomes `READY`;
- no second initializer call occurs.

The production design should avoid a mandatory finite timeout on the client/render-thread owner. A timeout is diagnostic for non-owner waiters, not lifecycle authority.

## Gameplay boundaries from PR #112

### Local world

The source-safe boundary remains `Minecraft.doWorldLoad(...)` before `MinecraftServer.spin(...)`. World/resource data has been prepared and the integrated server has not started. If a policy requires MCEF before gameplay, the force is synchronous on the existing Minecraft client thread and the entire native cost is charged to world entry. It must be a no-op if MCEF is already `READY`.

This hook must not be added to current integration until the owner-aware first-consumer coordinator exists there. It also must not make CEF unconditional merely because the user opened a world unless that is the chosen compatibility policy; #112's purpose is preventing an in-world first-use hitch without recreating reload overlap.

### Remote server

The source-safe boundary remains the final portion of `ConnectScreen.startConnecting(...)`: after stock disconnect/preparation and after `minecraft.setScreen(connectscreen)`, but immediately before the private `connect(...)` call creates the connector thread. This keeps DNS/TCP/login timeouts from running during the synchronous native pause.

Again, the trigger is synchronous on Minecraft's client thread, retains the real MCEF initializer, and is a no-op for `READY`.

## Deterministic test matrix once #90 is integrated

The state core should be extracted behind injected collaborators (`initializer`, readiness probe, current-thread identity, waiter clock/signal) so hosted tests do not need native CEF. No new test dependency is required: current integration has no test source tree/JUnit dependency, and `AGENTS.md` forbids unapproved dependency changes. A dependency-free Java harness or existing project-supported test mechanism should be used unless the coordinator approves a test dependency.

Required cases:

1. **owner success** — one initializer call; `UNINITIALIZED -> FORCING -> INITIALIZING -> READY`.
2. **owner callback reentry after publication** — reentrant guard on same thread does not block or recurse; outer initializer completes.
3. **owner reentry before publication** — deterministic invariant failure/no recursion/no wait.
4. **second consumer** — waits behind owner, observes same READY; initializer count remains one.
5. **initializer false** — terminal FAILED after real call exits; later consumers observe same failure.
6. **initializer throws** — terminal FAILED(cause); second consumer released; no hidden retry.
7. **cancel before claim** — ABORTED and initializer count zero.
8. **cancel after claim/before enter** — ABORTED only if owner acknowledges before real call.
9. **cancel during INITIALIZING** — no terminal transition until real call exits.
10. **long initialization / waiter timeout** — waiter timeout leaves global INITIALIZING unchanged; eventual owner success -> READY.
11. **local hook** — force executes before server spin continuation and only once/no-op when READY.
12. **remote hook** — screen is established and force completes before connector-thread continuation; no-op when READY.
13. **FancyMenu real title consumer** — retained regression gate so gameplay hardening does not break the already validated title path.
14. **WebDisplays callback shape** — exact fixture proves registered callback can reenter getters without waiting.

## Hosted versus Windows gates

Hosted CI can prove:

- compile/build and optional-target mixin application;
- deterministic owner/reentry/second-consumer/failure/cancel/timeout state semantics;
- exact-pack fixture contains the expected MCEF `2.1.6-1.21.1`, FancyMenu `3.9.0-wedit` and exact WebDisplays fork/path;
- an exact-pack local-world functional smoke if the hosted fixture has a deterministic world-entry driver;
- a remote-connect ordering smoke only if CI provides a deterministic local test server/fixture; otherwise it remains unproven rather than being inferred from title success.

Windows/native is still required for:

- real JCEF/CEF thread/native/GL affinity and callback timing;
- native process/resource lifetime and shutdown;
- visible loading/connect-screen behavior during the synchronous pause;
- real in-world WebDisplays browser creation/rendering;
- functional local-world entry and remote connection with the exact pack if hosted cannot exercise those paths.

No laptop request is justified before the owner-aware coordinator is actually on the tested branch, hosted deterministic state tests pass, and hosted functional world/connect smoke is completed wherever the fixture supports it.

## Decision

**Do not implement gameplay hooks or a replacement coordinator from integration SHA `145c10c...`.** The prerequisite first-consumer code is not in the authoritative tree and PR #90 is still a separate open draft. Reimplementing it here would violate the non-overlap constraint and make review/CI evidence ambiguous.

The next implementation branch should start only after either:

1. PR #90 (or an equivalent first-consumer implementation) is merged into `agent/integration-current`, at which point this state contract can replace its unsafe future/30 s semantics and the local/remote hooks can be tested in isolation; or
2. the coordinator explicitly decides that Agent 24 should supersede PR #90, in which case that is a real ownership decision and the branch must intentionally absorb/replace #90 rather than pretending to be a non-overlapping continuation.
