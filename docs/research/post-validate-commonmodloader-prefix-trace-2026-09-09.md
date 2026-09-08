# Post-validate CommonModLoader prefix trace — 2026-09-09

Status: **ACTIVE DIAGNOSTIC / PROFILED OBSERVABILITY ONLY**

This branch adds no startup optimization and makes no TTMM claim. It is stacked on PR #206 head `2ac037a8fe48bdc39fbdf1dd384c57b3eed7791c`, itself stacked on #203/#202/#201/#200, while integration authority remains `agent/integration-current` @ `fa6df8bc8f74aae32338f521bf845a5730ac634b`.

## Target residual

PR #206 final-head hosted exact-pack run `34283133432` measured `2606.206 ms` of temporal wall between the end of `minecraft_bootstrap_validate` and the begin hook immediately before `ModLoader.gatherAndInitializeMods`. `Bootstrap.validate()` itself was only `0.240 ms`. This residual is temporal elapsed wall, not CPU time, task-sum, critical-path savings or TTMM improvement.

The retired strict `ClientModLoader.begin()` transformer is not repeated or broadened. PR #203 had also already discarded a patched `Main.main` callsite matcher after it emitted no task in hosted exact-pack.

## Verified source boundary

NeoForge patches `net.minecraft.client.main.Main` so `ClientModLoader.begin()` runs immediately after `Bootstrap.validate()`. Public NeoForge source for `CommonModLoader` shows its exact `begin(Runnable, boolean)` starts by obtaining `ModWorkManager.syncExecutor()` and then directly calls `ModLoader.gatherAndInitializeMods(syncExecutor, ModWorkManager.parallelExecutor(), periodicTask)`.

Most importantly for runtime observability, #202/#206 already prove that BootOptim's transformation-service transformer reaches the GAME-layer `net.neoforged.neoforge.internal.CommonModLoader` and that the direct gather call emits in the pinned hosted exact-pack. This gives a new boundary without depending on transformability of `ClientModLoader` or `Main`.

## Minimal hook

`FmlLoadingTraceTransformer` retains the already-validated gather wrapper unchanged and adds a separate fail-closed prefix matcher:

- exact class `net/neoforged/neoforge/internal/CommonModLoader`;
- exactly one `begin(Ljava/lang/Runnable;Z)V` method;
- exactly one direct `net/neoforged/fml/ModLoader.gatherAndInitializeMods(... )V` call inside that method;
- first executable instruction must exist.

When and only when those conditions hold, it injects `beginCommonModLoaderPrefix()` before the first executable instruction and `endCommonModLoaderPrefix()` immediately before the gather call. The task name is `neoforge_common_modloader_pregather`.

The causal chain becomes, when the prefix task emits:

`minecraft_bootstrap_validate -> neoforge_common_modloader_pregather -> fml_gather_and_initialize_mods`.

If the strict prefix matcher fails, #202's gather wrapper still behaves as before and falls back to validation/bootstrap/discovery for its predecessor.

## Safety

- `boot_optim.bootTrace.mode=off` remains default and installs zero diagnostic transformers.
- No scheduling, classloading, callbacks, threads, executor choice, GL/render work or gameplay behavior changes.
- FML `ModLoader` remains an explicit non-target in the MC-BOOTSTRAP/SERVICE layer.
- Hook bodies are trace-only and fail open.
- Duplicate/ambiguous exact prefix structure suppresses only the new prefix pair; it does not broaden matching.

## Tests

`FmlLoadingTraceTransformerTest` verifies exact order:

`prefix begin -> original syncExecutor call -> prefix end -> gather begin -> original gather call -> gather end`.

It also verifies that a duplicate gather call rejects only the prefix matcher while preserving the inherited gather wrapper, and that bootstrap-layer `net.neoforged.fml.ModLoader` remains untouched.

## Runtime gate

Required before closing this investigation:

1. build/package success;
2. normal startup to `main_menu` with trace default/off;
3. hosted exact-pack profile JSONL with one header/summary, balanced tasks, no trace errors/drops/flush failures, and an emitted `neoforge_common_modloader_pregather` pair.

The resulting profile must report separately:

- validate end -> CommonModLoader prefix begin: temporal gap, expected to contain the surviving `ClientModLoader.begin()` preamble/call transition but not yet named as a task;
- `neoforge_common_modloader_pregather`: inclusive task wall;
- gather task: inclusive task wall.

No laptop run is requested or justified by this diagnostic.
