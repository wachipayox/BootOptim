# MCEF gameplay-boundary audit — 2026-09-05

Status: **SOURCE-VALIDATED DESIGN / PR #90 NOT YET GAMEPLAY-COMPLETE**

Scope: audit only. This branch does **not** implement a candidate, rerun startup A/B, modify PR #90, overlap CEF with resource reload, move JCEF/GL work to workers, or defer renderer listeners.

Baseline integration: `agent/integration-current` @ `792d06ec008c5ebae3681dd94f7aeee2c8e5f2a2`.

Promotion under audit: PR #90, `agent/promote-mcef-first-consumer-defer` @ `e348ef820520733eca3f8e64d0cd01013e570f15`.

## Question

PR #90 removes the pre-title native CEF pause when no real MCEF consumer exists and forces the real initializer synchronously at the first guarded consumer. Hosted #88/#89/#90 evidence proves the first active FancyMenu MCEF video can force real CEF and receive the stock callbacks, but it does **not** prove that a Windows client which reaches the title without a consumer will avoid moving the native pause into the first in-world WebDisplays use.

The target here is therefore not lower TTMM. It is a lifecycle boundary that keeps PR #90's title deferral while guaranteeing that, before gameplay begins, CEF has either completed its real initialization or has failed through MCEF's normal failure path. The native cost remains visible and is charged to world-entry time.

## Evidence boundaries

Coordinator-provided physical evidence for this task records a current stock Windows CEF interval of about `21.852 s` (`17:09:56.560` -> `17:10:18.412`) on the 4-logical-processor / `-Xmx6144MiB` Java 25.0.4 laptop. Candidate 019 deferred MCEF but has strong unrelated FancyMenu variance, so its `376.479 s` TTMM versus control 018 `379.661 s` is **not** treated as a causal performance result here. Control 020 is owned by the coordinator and is not repeated by this research branch.

The older #78 exact-pack laptop experiment remains a hard negative boundary: starting real CEF after resource reload had begun made the native interval grow from `15.752 s` to `45.941 s` and regressed post-entrypoint wall by about `10.18 s`. This design therefore serializes world-entry preparation; it does not overlap native CEF with resource workers. See PR #78 and `docs/research/mcef-initial-reload-overlap-2026-09-02.md`.

Hosted exact-pack runs remain a Linux/Xvfb/llvmpipe semantic and direction-of-effect surrogate. `docs/research/exact-pack-ci.md` explicitly reserves native/UI conclusions for the real hardware gate. Since #103, any future hosted gate must retain the pinned exact fixture, all ten external resource packs and their order, and must reject fallback/reduced workloads.

## Exact source anchors

### MCEF 2.1.6-1.21.1

CinemaMod `mcef` branch `1.21.1` currently identifies itself as `mcef_version=2.1.6-1.21.1` and is at `4cecd7b1f009280694a42c255398a3e3d2ca417c`.

Relevant source:

- `common/src/main/java/com/cinemamod/mcef/MCEF.java`
  - `scheduleForInit(MCEFInitListener task)` appends the callback to `awaitingInit`.
  - `initialize()` calls `CefUtil.init()` synchronously.
  - on success it assigns `app`, then assigns `client`, then synchronously executes `awaitingInit.forEach(t -> t.onInit(true))`, clears the list, and only later returns `true`.
  - `isInitialized()` is exactly `client != null`.
  - `getApp()`, `getClient()` and browser creation assert that MCEF is initialized.
- `common/src/main/java/com/cinemamod/mcef/CefUtil.java`
  - `init()` performs `CefApp.startup(...)`, `CefApp.getInstance(...)` and `createClient()` in the caller. There is no MCEF worker handoff around the real initializer.
- `common/src/main/java/com/cinemamod/mcef/mixins/CefInitMixin.java`
  - MCEF's stock trigger is `Minecraft.setScreen(...)`.
  - after the native download is ready, it submits a Minecraft client task, sleeps one second there, then calls `MCEF.initialize()`.
  - its accepted screens include title, level-loading, receiving-level and connect screens. This establishes that MCEF itself expects initialization on the Minecraft client thread during screen transitions; it does not establish that every such screen is an acceptable BootOptim gameplay boundary.

Public source:

- <https://github.com/CinemaMod/mcef/tree/1.21.1>
- <https://github.com/CinemaMod/mcef/blob/1.21.1/common/src/main/java/com/cinemamod/mcef/MCEF.java>
- <https://github.com/CinemaMod/mcef/blob/1.21.1/common/src/main/java/com/cinemamod/mcef/CefUtil.java>
- <https://github.com/CinemaMod/mcef/blob/1.21.1/common/src/main/java/com/cinemamod/mcef/mixins/CefInitMixin.java>

### WebDisplays exact fork

PR #90 resolves the exact-pack WebDisplays source to `brother-bill/webdisplays-mc` commit `df820aa136b64368a043c2c1e1ef2d0292d233eb` (`2.5.0-1.21.1`). The relevant callgraph is public:

```text
WebDisplays.<init>
  -> client DistSafety.createProxy() -> new ClientProxy()
  -> PROXY.init()
     -> SharedProxy.init()
        -> MCEF.scheduleForInit(cef -> onCefInit())

MCEF.initialize() success
  -> app assigned
  -> client assigned
  -> awaitingInit callbacks run synchronously
     -> ClientProxy.onCefInit()
        -> new MinePadRenderer()
        -> new LaserPointerRenderer()
        -> MCEF.getApp().getHandle().registerSchemeHandlerFactory(...)
        -> MCEF.getClient().addDisplayHandler(...)
        -> MCEF.getClient().getHandle().addMessageRouter(...)
```

WebDisplays browser creation is also direct:

```text
WDBrowser.createBrowser(url, transparent)
  -> new WDClientBrowser(MCEF.getClient(), url, transparent)
  -> browser.createImmediately()
```

Therefore, if PR #90 reaches a world with MCEF still deferred, an in-world screen or MinePad can be the first guarded `MCEF.getClient()` consumer. The helper will then run the entire real native initializer synchronously before WebDisplays can construct that browser. That is a source-based **first-use hitch risk**. Title success alone cannot discharge it.

Public source:

- <https://github.com/brother-bill/webdisplays-mc/blob/df820aa136b64368a043c2c1e1ef2d0292d233eb/src/main/java/net/montoyo/wd/SharedProxy.java>
- <https://github.com/brother-bill/webdisplays-mc/blob/df820aa136b64368a043c2c1e1ef2d0292d233eb/src/main/java/net/montoyo/wd/client/ClientProxy.java>
- <https://github.com/brother-bill/webdisplays-mc/blob/df820aa136b64368a043c2c1e1ef2d0292d233eb/src/main/java/net/montoyo/wd/utilities/browser/WDBrowser.java>

## Safe pre-gameplay boundaries

There is no NeoForge client event in 1.21.1 that simultaneously means "the user has committed to entering this world", "the relevant loading screen/lifecycle has been established", and "no integrated server or remote connection has started yet".

`ClientPlayerNetworkEvent.LoggingIn` is explicitly a post-login event: its player is already initialized, and NeoForge fires it from the client packet listener while handling the login/game packet path. It is too late for a 20-second native pause. `LevelEvent.Load`, `Minecraft.setLevel(...)`, and receiving-level screens are also too late for the same reason: by then an integrated server or remote peer can already be waiting on the client. `ScreenEvent.Opening` / `Init` expose screen lifecycle but do not own the continuation that starts the integrated server or remote connector, so they cannot by themselves guarantee the no-timeout ordering.

The safe boundaries are instead two exact vanilla control-flow points.

### Local world: `Minecraft.doWorldLoad(...)` before `MinecraftServer.spin(...)`

Minecraft 1.21.1 exposes:

```java
public void Minecraft.doWorldLoad(
    LevelStorageSource.LevelStorageAccess access,
    PackRepository packRepository,
    WorldStem worldStem,
    boolean newWorld)
```

Mappings: <https://mappings.dev/1.21.1/net/minecraft/client/Minecraft.html>.

`WorldOpenFlows` calls this method only after the local world data path has committed far enough to own a valid `WorldStem`:

- new world `createFreshLevel(...)` first installs `GenericMessageScreen("selectWorld.data_read")`, blocks for `loadWorldDataBlocking(...)`, then calls `doWorldLoad(...)`;
- existing world `openWorld(...)` installs the data/resource-loading screens, completes `loadWorldStem(...)`, validates the stems, completes bundled local resource-pack handling and disk-space/backup decisions, then `openWorldDoLoad(...)` calls `doWorldLoad(...)`.

Inside `Minecraft.doWorldLoad(...)`, the integrated server has not started at method entry. The method performs its local setup and later assigns `singleplayerServer = MinecraftServer.spin(... new IntegratedServer(...))`.

**Proposed local trigger:** at `Minecraft.doWorldLoad(...)` HEAD, if MCEF is still in PR #90's deferred state, synchronously call the real MCEF initializer on the same Minecraft client thread, then let stock `doWorldLoad(...)` continue unchanged.

Why this boundary is preferable to `setLevel` / `LevelLoadingScreen`:

- `WorldStem` resource/data preparation has already completed, so it does not recreate #78's CEF-vs-resource-worker overlap.
- `MinecraftServer.spin(...)` has not started, so no integrated server is ticking or waiting while CEF blocks.
- stock backup, low-disk, datapack failure and cancel paths occur before this commit point and retain their existing resource cleanup; no BootOptim continuation has to own or close `WorldStem` / storage handles.
- a loading `GenericMessageScreen` has already been installed by the normal `WorldOpenFlows` path. Source proves the screen state exists, but not that Windows will necessarily present a freshly swapped frame immediately before a long synchronous native call; that visual detail belongs in the functional gate.

### Remote server: final `ConnectScreen.startConnecting(...)` step before private `connect(...)`

Minecraft 1.21.1 exposes:

```java
public static void ConnectScreen.startConnecting(
    Screen parent,
    Minecraft minecraft,
    ServerAddress serverAddress,
    ServerData serverData,
    boolean isQuickPlay,
    @Nullable TransferState transferState)
```

Mappings / NeoForge Javadocs:

- <https://mappings.dev/1.21.1/net/minecraft/client/gui/screens/ConnectScreen.html>
- <https://lexxie.dev/neoforge/1.21.1/net/minecraft/client/gui/screens/ConnectScreen.html>

The 1.21.1 body constructs `ConnectScreen`, preserves transfer-specific status, calls `minecraft.disconnect()`, `prepareForMultiplayer()`, report/quick-play setup, then `minecraft.setScreen(connectscreen)`, and **only then** calls the private:

```java
connectscreen.connect(minecraft, serverAddress, serverData, transferState)
```

That private `connect(...)` creates the `Server Connector #N` thread. The thread resolves DNS, calls `Connection.connect(...)`, waits for the channel, creates the handshake listener and sends login hello.

**Proposed remote trigger:** inject at the exact call site immediately before `ConnectScreen.connect(...)`. If MCEF is still deferred, synchronously run the real initializer on the Minecraft client thread, then invoke the original private `connect(...)` unchanged.

This placement is intentionally **not** `startConnecting(...)` HEAD. Keeping the stock `disconnect()` before CEF preparation matters for server transfer semantics: the old connection is not held open for the native pause. Keeping the trigger before private `connect(...)` means the new connector thread, DNS resolution, TCP connect, login and their timeouts have not begun. `ConnectScreen` is already the current screen. As with the local path, a real Windows gate must verify presentation/UX; source alone proves ordering, not that a frame swap precedes the blocking call.

## Can real CEF init run in the loading-screen phase?

**Yes, with a precise limitation.** Both proposed triggers run on the existing Minecraft client thread in a stock loading/connection screen phase and before gameplay/network-server work that should not be stalled. They do not hide the native cost: logs and world-entry measurement must bracket the real `MCEF.initialize()` and charge its full wall time to local-world entry or server-connection entry.

The initializer itself is synchronous. While native CEF is inside `CefApp.startup/getInstance/createClient`, the client thread cannot process input. Therefore this design does **not** claim that a user can click Cancel during the 20-second native call. It preserves stock cancellation before the local commit point and normal ConnectScreen cancellation after initialization/connection begins, and it avoids introducing an asynchronous continuation whose stale callback could reopen a cancelled world/server. Making native initialization itself cancellable would require MCEF/JCEF support not present in the inspected API and is outside this proposal.

If MCEF was already initialized because a FancyMenu browser/video was a real title consumer, both world-entry triggers are no-ops. Menu consumers therefore remain timely and do not wait for world entry.

If real MCEF initialization returns `false`, world entry should continue rather than turning CEF availability into a new requirement for Minecraft gameplay. The MCEF failure path remains authoritative: MCEF dispatches its failure callbacks, clears the callback list and shuts down. BootOptim must log the failure and keep the state terminal for that attempt; it must not forge readiness or dispatch callbacks itself.

## PR #90 state-machine audit

This is independent of the gameplay boundary and should be fixed before a production claim.

### 1. Same-thread callback reentry can self-wait

PR #90's current helper has:

```text
DEFERRED
  -> first consumer CAS -> FORCING_BY_CONSUMER
  -> forceInitializeNow()
     -> MCEF.initialize()
        -> client assigned (MCEF.isInitialized() becomes true)
        -> synchronous WebDisplays onCefInit callback
           -> MCEF.getApp()/getClient()
              -> PR #90 getter mixin -> beforeConsumer()
                 -> state == FORCING_BY_CONSUMER
                 -> awaitInFlightInitialization()
```

`awaitInFlightInitialization()` only takes its same-client-thread escape when `!isMcefInitialized()`. During MCEF's success callbacks that condition is false because MCEF has already assigned `client`. The method therefore falls through to:

```java
INITIALIZATION_COMPLETION.get(30L, TimeUnit.SECONDS)
```

on the same thread that must return from the callback and `MCEF.initialize()` before the outer `forceInitializeNow()` can complete that future.

This is a deterministic source-level cycle **if** the PR #90 direct API getter injections are active against the exact runtime JAR and the exact WebDisplays callback is registered as inspected. It should not be reported as an observed Windows hang without a dynamic exact-JAR gate. The earlier active-title-video smoke explicitly checked out the #88 compatibility head; it is not sufficient proof that the later production state-machine hardening exercised this callback-reentry path.

The existing `FORCE_INITIALIZE` thread-local already identifies the owner scope around the reflective `MCEF.initialize()` call, but `beforeConsumer()` does not consult it. A future implementation should make owner-thread callback reentry a non-waiting pass-through: MCEF has already published the app/client objects before invoking those callbacks, and the callback must be allowed to finish so the authoritative initializer can return.

### 2. Fixed 30-second wait is not a valid hardware lifecycle boundary

Both the worker handoff and concurrent-consumer wait use an unconditional `30 s` timeout. Current coordinator evidence has stock CEF around `21.852 s`, which leaves little margin. More importantly, #78 already demonstrated that the same native initialization can exceed 30 seconds under contention (`45.941 s`), even though that particular concurrent design is rejected.

Therefore `30 s` cannot safely distinguish "initialization failed" from "slow hardware / temporary contention". On timeout the current code sets `ABORTED` and completes the shared future `false`, while an already queued client-thread initializer may still run. A waiting consumer can then proceed before the real MCEF API is ready and hit MCEF's own initialization assertion; the queued initializer may later change state again. This is a state/lifecycle risk, not evidence that the current laptop necessarily exceeds 30 seconds in the proposed serial world-entry window.

A future state machine should make actual initializer completion (or an explicit client shutdown/interruption path), not an arbitrary 30-second wall threshold, authoritative. A timeout may remain as a diagnostic warning, but must not publish terminal failure while the owner initializer is still legitimately running.

### 3. Failure and second-consumer semantics

Current `forceInitializeNow()` sets `ABORTED` when reflection fails or MCEF returns false. Later consumers only force when state is `DEFERRED`; therefore a second consumer does not retry. That is conservative with respect to MCEF's one-shot callback list: on a real failed initialization MCEF has already called `onInit(false)` and cleared `awaitingInit`.

The future candidate should retain one authoritative real attempt per deferred lifecycle unless upstream MCEF source establishes a supported retry contract. World entry should continue after failure; WebDisplays/MCEF functionality may remain unavailable just as it would after MCEF's own failed initialization.

### 4. Required state shape for the future candidate

Do not add concurrency. Keep the same real client-thread initializer, but make ownership explicit:

```text
ARMED -> DEFERRED -> INITIALIZING(owner=client thread) -> READY
                                              \-> FAILED
```

A worker may request/schedule client-thread initialization, but must not become the initializer owner. During `INITIALIZING`, a guarded API call on the owner thread is callback reentry and must pass through without waiting or recursively initializing. A non-owner consumer waits for the actual completion signal. World-entry preparation calls the same `ensureInitialized(...)` path; it does not create a second initialization mechanism.

## Current -> proposed behavior

| Situation | PR #90 current behavior | Proposed isolated candidate | Required proof |
| --- | --- | --- | --- |
| Startup, no real MCEF consumer | Automatic pre-title `initialize()` is suppressed; title can be reached with MCEF false. | Unchanged. Do not initialize merely to reach title. | Existing #88/#90 hosted evidence remains background evidence; no new startup A/B required for this audit. |
| Title with real FancyMenu MCEF browser/video | First guarded consumer forces real MCEF synchronously on client thread and FancyMenu uses its real bridge/callbacks. | Unchanged; world-entry hooks no-op once READY. | Hosted dynamic active-title consumer must still initialize before the consumer succeeds. |
| Local-world entry with MCEF still deferred | No explicit preparation boundary; first later consumer may force CEF in gameplay. | At `Minecraft.doWorldLoad(...)` HEAD, complete/fail real MCEF before `MinecraftServer.spin(...)`; charge wall to world-entry. | Hosted ordering markers plus Windows local-world functional gate. |
| Remote-server entry with MCEF still deferred | No explicit preparation boundary; first later consumer may force CEF after login. | Immediately before the final private `ConnectScreen.connect(...)` call, complete/fail CEF after stock disconnect/screen setup but before connector thread/DNS/TCP/login. | Hosted test server ordering plus Windows remote functional gate. |
| First WebDisplays browser after world is playable | Can be the first `MCEF.getClient()` and absorb the native pause. | Native CEF is already READY or terminal FAILED; no native initialization is allowed to start here. Browser/page creation cost itself remains stock. | Windows first screen/MinePad interaction: visual success and no second CEF-init interval. |
| MCEF callback calls `getApp/getClient` while real init is in progress | Later PR #90 state can self-wait on `INITIALIZATION_COMPLETION` for up to 30 s. | Owner-thread reentry passes through; no recursive init and no wait. | Deterministic state test emulating MCEF's publish-before-callback order, then exact-JAR dynamic marker. |
| Slow initialization >30 s | Waiter can mark shared state ABORTED even while client initializer remains queued/running. | No fixed timeout publishes terminal failure while the owner is running; completion/shutdown is authoritative. | State test with a deliberately >30 s-equivalent delayed completion; no native CEF required. |
| Cancel / return before local commit | Stock `WorldOpenFlows` closes resources on backup/datapack/disk-space cancel before `doWorldLoad`. | Unchanged; preparation is never reached. | Hosted flow smoke. |
| Remote cancel | ConnectScreen is set before preparation; during the synchronous native call the client cannot process input; after it returns, stock connect/cancel behavior resumes. | No asynchronous BootOptim continuation, so a cancelled screen cannot later start a stale connection. | Windows cancel/connection-failure return-to-menu smoke. |
| Real MCEF failure | State becomes aborted/failed; later consumers cannot use CEF. | Continue world entry, preserve MCEF's real failure callbacks and terminal failure; never fake flags or retry without upstream contract. | State test plus hosted forced-failure path only if a faithful exact-JAR failure injection is available. |

## Test strategy without loading real CEF

The reentry and timeout defects are state-machine properties and can be tested without loading JCEF, but the fake must reproduce the exact MCEF ordering rather than mock away the failure:

1. Extract the ownership/transition logic behind a package-private test seam.
2. Give it an initializer operation that, exactly like MCEF success, publishes `initialized=true`, invokes a registered callback that reenters `beforeConsumer("getApp")`, and only then returns success.
3. Assert the owner-thread reentry returns immediately, the initializer runs exactly once, and READY is published only after the outer initializer returns.
4. Exercise a worker requester whose client-thread initializer remains incomplete beyond the old 30-second threshold; assert no terminal FAILED/ABORTED state is published merely because that threshold elapsed.
5. Exercise `initialize -> false` and an exception: assert terminal failure, no forged readiness, no second attempt, and world-entry caller is allowed to continue.
6. Exercise already-initialized/menu-consumer state: world-entry preparation must be a no-op.

These tests validate the coordinator, not native JCEF. They cannot replace the exact-pack dynamic callback and Windows functional gates below.

## One future implementation candidate

If this design is accepted, implement **one isolated MCEF compatibility candidate**, not multiple experiments:

- harden `McefFirstConsumerDefer` into an owner-aware single-attempt coordinator;
- expose one `prepareForGameplayEntry(reason)` operation that reuses the same real-initialize path as first-consumer forcing;
- add only two lifecycle hooks:
  1. `Minecraft.doWorldLoad(LevelStorageAccess, PackRepository, WorldStem, boolean)` HEAD for local worlds;
  2. the final `ConnectScreen.startConnecting(...) -> ConnectScreen.connect(...)` call site for remote servers;
- keep the exact MCEF `2.1.6-1.21.1` gate and kill switch;
- keep `MCEF.initialize()` synchronous on the Minecraft client/render thread;
- do not run CEF concurrently with resource reload, integrated-server startup or remote connection;
- do not defer or skip renderer listeners;
- keep first-consumer guards as a fallback for real menu consumers and unexpected paths;
- add explicit begin/end/reason/wall markers so the CEF interval is counted as world-entry wall, never hidden from reporting.

This is materially different from #78: there is no resource-reload overlap and no worker-native initialization.

## Gates before any gameplay-intact claim

### Hosted, exact fixture

Use the hardened exact-pack contract from #103: ten external resource packs in the exact order, no fallback.

The candidate must pass dynamic lifecycle gates rather than another TTMM A/B campaign:

1. **Title/no consumer:** reaches title with MCEF still deferred.
2. **Active FancyMenu consumer:** a real title MCEF browser/video still forces real CEF and succeeds before any world-entry hook.
3. **WebDisplays callback reentry:** exact WebDisplays callback enters its `getApp/getClient` calls during MCEF initialization and returns without a 30-second self-wait/abort marker.
4. **Local ordering:** when MCEF remains deferred, `prepare begin/end` occurs after `WorldStem` preparation and before the integrated-server start marker; no second CEF initialization occurs after the world becomes playable.
5. **Remote ordering:** with a local test server, preparation completes before `ConnectScreen.connect(...)` starts `Server Connector`, DNS/TCP/login; no login timeout or stale continuation.
6. **Failure/state tests:** deterministic JVM tests cover reentry, slow completion and terminal failure as described above. Do not invent a native-Cef mock and call it compatibility proof.

### Windows functional gate

A single focused exact-pack Windows validation should cover both world paths; do not ask for repetitive startup campaigns:

- local world: record click/entry -> playable wall including the entire CEF interval, then use the first WebDisplays screen/MinePad and verify there is no native CEF pause at first use;
- remote server: verify CEF completes before new connection/login begins, then verify first WebDisplays use has no native init pause;
- verify WebDisplays visuals/input and FancyMenu title MCEF media still work when each is the first consumer;
- verify a connection failure/cancel returns cleanly to the menu and no delayed BootOptim continuation later starts a connection;
- verify exactly one real MCEF initialization attempt, normal MCEF/WebDisplays callback completion, and no `concurrent_consumer_wait_failed` / `client_thread_handoff_failed` marker.

The Windows gate is about gameplay/UI correctness and native wall placement, not proving a TTMM win.

## Decision

A safe source-level pre-gameplay boundary **does exist**, but PR #90 is not ready to claim "gameplay intact" by title/hosted-video evidence alone.

The recommended boundary is:

- local: `Minecraft.doWorldLoad(...)` entry, before `MinecraftServer.spin(...)`;
- remote: after stock `disconnect()/prepareForMultiplayer()/setScreen(ConnectScreen)` but immediately before private `ConnectScreen.connect(...)` starts the connector thread.

This moves a deferred native CEF pause into world-entry time, before gameplay and before a new server can be waiting on the client. It preserves menu-first consumers. It also avoids re-opening #78.

Before implementing those hooks, the PR #90 coordinator must be hardened for owner-thread callback reentry and for slow initialization that exceeds the current fixed 30-second wait. After implementation, the exact-pack hosted lifecycle gates and one focused Windows world-entry/WebDisplays functional gate are required. Until then, PR #90 should remain a promotion candidate with an explicit gameplay boundary, not a production claim that first in-world WebDisplays use is hitch-free.

## Related PRs / history

- #78 — rejected MCEF/resource-reload overlap; hard no-go for this premise.
- #86 — documentation/rejection follow-up for the overlap line.
- #88 — first-consumer ceiling experiment.
- #89 — exact FancyMenu wedit static/dynamic MCEF consumer audit; active-title smoke checked out the #88 compatibility head.
- #90 — current promotion candidate under audit; not modified by this branch.
- #103 — integrated exact resource-pack selection/order hardening required by future hosted gates.

This branch intentionally does not touch FancyMenu wait work, MoreCulling, VoxelShaper research, renderer reload listeners, OS/JVM configuration, user mod/config files, or physical-run orchestration.