# Client transition after Bootstrap.validate — 2026-09-09

Status: **PROFILED / DIAGNOSTIC**

## Scope and stack dependency

This investigation targets only the client transition after `Bootstrap.validate()` and before `CommonModLoader.begin`. It is intentionally stacked on diagnostic PR #208 (`3116ba92383d20e81439e928c3ce95caad26fc8d`), which itself depends on #206/#203/#202/#201/#200. None of that trace stack is integrated production code.

#208's hosted exact-pack profile measured `1604.509 ms` of untraced temporal wall from validation end to entry into `CommonModLoader.begin`, while the CommonModLoader pre-gather prefix itself was `0.899 ms`. That was a temporal interval, not CPU time or savings.

## Static 1.21.1 correction

Do not repeat or broaden the retired strict `ClientModLoader.begin()V` matcher from #206. The official NeoForge `1.21.1` branch, inspected at `327897179428559b4dd7fd29ef3f9f83aa40452f`, gives a concrete reason it could not emit: the 1.21.1 method is not `begin()V`. Its signature is:

`begin(Minecraft, PackRepository, ReloadableResourceManager)V`.

More importantly, the 1.21.1 `Main.java.patch` does **not** call ClientModLoader immediately after validation. It leaves `Bootstrap.validate()` in `Main` and then continues into argument parsing. The 1.21.1 `Minecraft.java.patch` calls `ClientModLoader.begin(this, this.resourcePackRepository, this.resourceManager)` later inside the `Minecraft` constructor, immediately after creation of the client `ReloadableResourceManager`.

The real 1.21.1 ClientModLoader preamble then performs, synchronously before inherited `CommonModLoader.begin`:

1. register the Log4j shutdown hook;
2. `ImmediateWindowHandler.updateProgress("Loading mods")`;
3. publish `loading = true` and the `Minecraft` reference;
4. `LogicalSidedProvider.setClient(() -> minecraft)`;
5. `LanguageHook.loadBuiltinLanguages()`;
6. invoke inherited `begin(ImmediateWindowHandler::renderTick, false)` inside existing loading-error handling.

This explains the historical non-emission without licensing a broader ClientModLoader transformer and shows why the full validate-to-Common interval could never safely be labelled only "ClientModLoader preamble".

## Version-pinned GAME-layer design

PR #210 adds two independent default-off boundaries without transforming ClientModLoader:

- `MinecraftClientTransitionTraceTransformer` targets `net.minecraft.client.Minecraft` and accepts only exactly one `INVOKESTATIC ClientModLoader.begin` with the official 1.21.1 three-argument descriptor, and only when that call is in a constructor. It emits the near-zero marker task `minecraft_client_modloader_entry` immediately before the call. Missing, duplicate, moved or descriptor-drifted callsites fail closed.
- `NeoForgeLanguageTraceTransformer` targets `net.neoforged.neoforge.server.LanguageHook`. It accepts only one exact `loadBuiltinLanguages()V`, exactly one `I18nManager.injectTranslations(Map)` publication anchor and at least one normal return. It emits `neoforge_builtin_languages`. Missing/duplicate/drifted anchors fail closed.

The existing #208 CommonModLoader prefix then follows the language task. All hooks catch diagnostic failures; trace-off still installs zero diagnostic transformers. No scheduling, lifecycle, callback, thread, classloading, GL/render or gameplay behavior changes.

## Hosted exact-pack result

Validated runtime-code commit: `b2d3fac46a4421672d0bebabf28221fc74c77adb`.

- Build run `34286775206`: **success**, including transformer tests and packaged-bootstrap validation.
- Startup Benchmark `34286775200`: **success** to `main_menu` with default/off trace behavior.
- Hosted exact-pack profile `34286775143`: **success** to `main_menu`.
- Exact diagnostics artifact `10079876169`, sha256 `9fe5aec50d2b5ffef620371aebfd4a097252ae10bf9c118835ab046fe96097e3`.
- `main_menu_ms=90948`, `mod_entrypoint_ms=30102`, `reload_to_fancymenu_finish_ms=42403`, `bootoptim_mixin_errors=0`.

The JSONL is healthy: one schema-v1 header, one summary, 16 events = 8 balanced task begins / 8 balanced task ends, `dropped_events=0`, `flush_failures=0`, `development_sink_failures=0`, `error=0`. Measurement origin is `hosted_exact_pack`, endpoint `main_menu`.

Observed causal chain:

`dependency_discovery` (2) -> `minecraft_bootstrap` (3) -> `minecraft_bootstrap_validate` (4) -> `minecraft_client_modloader_entry` (5) -> `neoforge_builtin_languages` (6) -> `neoforge_common_modloader_pregather` (7) -> `fml_gather_and_initialize_mods` (8).

Relevant monotonic boundaries:

| Boundary | Thread / classification | Wall |
| --- | --- | ---: |
| `minecraft_bootstrap_validate` | main / inclusive task wall | 0.170 ms |
| validate end -> ClientModLoader callsite | cross-thread untraced temporal gap | **2059.911 ms** |
| `minecraft_client_modloader_entry` | Render thread / marker task wall | 0.009 ms |
| callsite marker end -> builtin-language begin | Render thread temporal gap | 2.705 ms |
| `neoforge_builtin_languages` | Render thread / inclusive task wall | **17.240 ms** |
| language end -> CommonModLoader begin | Render thread temporal gap | 0.436 ms |
| `neoforge_common_modloader_pregather` | Render thread / inclusive task wall | 0.878 ms |
| Common prefix end -> gather begin | Render thread temporal hook gap | 0.007 ms |
| validate end -> gather begin | elapsed temporal interval | **2081.186 ms** |
| ClientModLoader callsite -> gather begin | elapsed temporal interval | **21.276 ms** |

`Bootstrap.validate()` runs on `main`; the exact ClientModLoader callsite and every later boundary above run on `Render thread`. Therefore about 2.060 s of this run's 2.081 s validate-to-gather interval occurs **before entry into ClientModLoader**, across the main-to-render client startup transition. The complete client-loader pre-Common scope after the callsite is only about 21.3 ms here, with built-in language loading accounting for about 17.2 ms. Language loading, progress/publication and CommonModLoader pre-gather are not material explanations for the second-scale residual in this profile.

This is attribution only. The 2.060 s temporal gap is not CPU time or savings and contains multiple operations before the exact Minecraft-constructor callsite, including post-validation Main work and early client construction. No optimization or A/B conclusion follows from it.

## Disposition

The requested alternate, version-pinned instrumentation **exists and validates**. The old ClientModLoader matcher remains a no-go and should not be widened; its exact 1.21.1 signature mismatch is now statically explained.

For further diagnosis, only the `validate -> Minecraft.<init> ClientModLoader callsite` region remains materially opaque. Any future split should use independently exact 1.21.1 GAME-layer lifecycle/callsite boundaries inside Main/Minecraft and must preserve the observed main-to-Render-thread transition. Do not reopen LanguageHook/progress/CommonModLoader as a second-scale target without new evidence: this hosted profile bounds the whole callsite-to-gather interval at about 21 ms.

No laptop run and no production merge are requested from this diagnostic PR.
