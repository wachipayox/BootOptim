# Client language transition trace — 2026-09-09

Status: **ACTIVE / DIAGNOSTIC**

## Scope and stack dependency

This investigation targets only the client transition after `Bootstrap.validate()` and before `CommonModLoader.begin`. It is intentionally stacked on diagnostic PR #208 (`3116ba92383d20e81439e928c3ce95caad26fc8d`), which itself depends on #206/#203/#202/#201/#200. None of that trace stack is integrated production code.

#208's hosted exact-pack profile measured `1604.509 ms` of untraced temporal wall from validation end to entry into `CommonModLoader.begin`, while the CommonModLoader pre-gather prefix itself was only `0.899 ms`. This is a temporal interval, not CPU time or savings.

## New premise

Do not repeat or broaden the retired strict `ClientModLoader.begin()V` matcher from #206. Public NeoForge source places these operations in that method, synchronously and in order:

1. register the Log4j shutdown hook;
2. read/publish `EarlyLoadingScreenController.current()` and update progress if present;
3. call `LanguageHook.loadBuiltinLanguages()`;
4. construct the periodic tick runnable;
5. delegate to inherited `CommonModLoader.begin(Runnable, boolean)`.

The alternative boundary targets the callee `net.neoforged.neoforge.server.LanguageHook`, an ordinary NeoForge GAME-layer class rather than FML/MC-BOOTSTRAP. Its exact `loadBuiltinLanguages()V` method is instrumented only when unique and when the observed NeoForge/FML-version body contains exactly one `I18nManager.injectTranslations(Map)` publication anchor plus at least one normal return. Missing, duplicate or drifted anchors fail closed and leave the class untouched.

The trace task is `neoforge_builtin_languages`. Its predecessor is validation (falling back to bootstrap/discovery); the existing #208 CommonModLoader prefix then prefers the language task as predecessor. Hooks catch all diagnostic failures and do not alter scheduling, lifecycle, callbacks, threads, classloading, GL/render or gameplay. Trace-off still installs zero diagnostic transformers.

## Decision gate

Hosted exact-pack profile must reach `main_menu`, emit a healthy schema-v1 JSONL with balanced tasks and zero trace errors/drops/failures, and either:

- emit `neoforge_builtin_languages`, proving this GAME-layer target is transformable and splitting the residual into validation-to-language, language inclusive wall, and language-to-CommonModLoader temporal intervals; or
- omit it while adjacent #208 GAME-layer tasks still emit, establishing a bounded no-go for this alternative target without broadening `ClientModLoader`.

No optimization, A/B or laptop run is part of this investigation.

## Runtime evidence

Pending hosted exact-pack profile on the Agent 77 PR.
