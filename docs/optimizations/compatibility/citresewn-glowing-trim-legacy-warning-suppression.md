# CITResewn Glowing Trim legacy-name warning suppression

Status: production candidate, explicitly user-authorized on 2026-09-11.

## Scope and authorization

The user explicitly authorized BootOptim to suppress only the repeated CITResewn compatibility warning emitted by `Glowing Trim Armors v5.0.zip` for legacy `nbt.display.Name` conditions. The exact pack emits this warning 7,920 times during initial CIT loading. This authorization does not cover `mute_warns`, other CITResewn warnings/errors/fallbacks, other packs, other message families, or resource/CIT behavior changes.

Historical PR #97 proved that a direct filter on the concrete `CITResewn` Log4j logger can remove these emitted lines without changing CIT parsing or model behavior. PR #258 later classified all 7,920 records as real logger emissions and rejected suppression only because the then-current contract forbade hiding any warning. The 2026-09-11 user authorization intentionally overrides that narrow product-policy rejection for this one warning family; it does not change any broader logging policy.

## Exact matcher

The production candidate waits until NeoForge common setup, after Sinytra Connector has created the historical CITResewn logger, and arms only when exactly one core logger named `CITResewn` is present. An event is denied only when every condition is true:

- logger name is exactly `CITResewn`;
- Log4j level is exactly `ERROR`, matching the pinned historical lineage;
- message begins exactly with `[citresewn] Using legacy nbt.display.Name @L`;
- the descriptor contains a resource path under `minecraft:optifine/cit/` ending in `.properties`;
- message ends exactly with ` from file/Glowing Trim Armors v5.0.zip`.

Any level/message/logger/pack/path drift is fail-open and remains visible. The filter returns `NEUTRAL` for all nonmatching events. A startup contract self-check verifies representative target, wrong-level, wrong-logger, wrong-pack, unrelated-warning, and parsing-error cases before the filter can arm.

At the first title screen BootOptim sets the matcher inactive, stops the filter, drops its own filter reference and emits one summary with the exact suppression count. Log4j 2.24.x exposes `Logger.addFilter` but no symmetric public `removeFilter`; the logger therefore retains only the stopped filter shell until its own context is destroyed. Because `armed=false`, that shell returns `NEUTRAL` for every later event and captures no logger, CIT, resource, parsed-property, model or reload-generation object. BootOptim itself retains no runtime object from CITResewn or the resource pack.

## Kill switch

Enabled by default for the authorized exact-pack behavior. Disable with:

```text
-Dboot_optim.citresewnGlowingTrimLegacyWarningSuppression=false
```

Disabling leaves CITResewn logging fully stock.

## Semantic boundary

This mechanism acts after CITResewn has already parsed and converted the legacy condition and built the diagnostic message. It does not skip or reorder resource enumeration, parsing, normalization, fallback handling, resource-pack precedence, model loading, active-CIT publication, callbacks, or gameplay behavior. It also does not use CITResewn's broad `mute_warns` configuration.

## Validation gate

Promotion requires build/startup success plus hosted exact-pack 3x3 interleaved A/B against the kill switch. Candidate artifacts must show 14/14 resource packs in order, reload count 1, title/main menu reached, zero BootOptim Mixin failures, zero target warning lines in both `latest.log` and captured console, exactly one BootOptim suppression summary, and a suppression count consistent with the pinned 7,920-event workload. Non-target/error preservation is part of the matcher contract and must remain fail-open.

Performance is judged only from comparable hosted time-to-main-menu and initial reload-to-FancyMenu metrics; event counts or the historical 2.886 s first-to-last warning span are not recoverable-wall claims.

## Risks and reopening

The main maintenance risk is upstream/event-shape drift. That risk is intentionally handled by fail-open matching: a moved severity, renamed logger, changed pack descriptor, changed path family, or changed warning text stops matching rather than broadening suppression. Multiple/absent logger contexts also leave logging stock.

Reopen or remove this exception if the exact pack migrates the legacy keys, upgrades to a CITResewn version with its own once-per-pack warning policy, the target pack name/path changes, the historical logger shape no longer exists, the suppression count materially diverges, any non-target warning/error is hidden, or startup/resource/gameplay validation fails.
