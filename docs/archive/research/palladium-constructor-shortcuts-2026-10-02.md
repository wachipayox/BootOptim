# Palladium constructor shortcuts — 2026-10-02

Disposition: **NO-GO for a BootOptim runtime patch on current evidence**.
The in-place constructor algorithm is viable in isolation. The actual compatible
hook and exclusive cost are not established; this is not an optimization win.

## Authority and prior work

Integration freshly fetched: `agent/integration-current` at
`411e17cbfe4739cdcab14bf14321c7901cb1c775`. Its mixin tree has no Palladium
identifier optimization. This investigation uses the isolated branch
`codex/palladium-constructor-audit-20261002`; it changes only an offline checker
and research documentation. No laptop, game, user instance or campaign changed.

Mandatory AGENTS, README, research/catalog indexes, deep model-pipeline history
and exact-pack contract were read. Open/closed title/body searches found no
dedicated Palladium production PR. The incidental mention in #219 concerns
startup endpoint/lifecycle attribution, not this constructor. Existing branch
`codex/palladium-identifiers-20260930`, commit `bcb826bd`, already rejects blind
getter caching: exposed arrays are mutable and most historical String.join JFR
leaf samples are filesystem work. This follow-up addresses only its residual
no-cache constructor and single-component shortcut, not the rejected cache.

Installed artifact: `mr_toad_palladium` 1.1.8, SHA-256
`70083d3eaacec032a9ff066474e6df1e81d3e77125fa59f211252dcccec3997e`.
`javap` confirms `ModelResourceLocationMixin.palladium$declareProperties()`:
live config check, `variant.split(",")`, stream mapping through the actual
synchronized `Palladium.PROPERTIES.deduplicate`, second array collection, then
field publication. `cacheInit` additionally joins the array into `variant`.
The getters/hash continue reading the publicly exposed live array.

## Concrete eliminated operation

The offline candidate preserves stock split and maps the returned array in
place before publishing it. This removes the stream/lambda/collector machinery
and the second final array; it does not remove parsing, synchronized dedup calls,
Guava entries, counters, expiry or the final constructor join.

Important details: require the deduplicator receiver before entering the loop
(including when split produces zero components); keep deduplication call order
and repetitions; publish only after every call succeeds. Split must still
discard trailing empty components. No persistent or mutable-output cache exists.

A one-component join returning `properties[0]` is not identical to stock:
the actual JDK 21 stock `String.join` returns a distinct String reference even
for one non-null component. `String.valueOf` preserves null text but not that
allocation/identity. This shortcut is not retained as an exact replacement.

## Executed offline evidence

`scripts/palladium-constructor-audit/run.py` validates the JAR SHA, extracts the
**actual compiled mixin, interface and Deduplicator**, and invokes the original
private declaration method reflectively. It compares it with the proposed
in-place algorithm. The real Deduplicator uses Guava 32.1.2-jre/failureaccess 1.0.1;
config, ResourceLocation and CallbackInfo shells are isolated test stubs.
This is not a transformed Minecraft runtime or a full compatibility proof.

Adoptium JDK 21.0.4 execution: **20,220 equivalence cases passed**. Cases cover
empty and comma-only strings, leading/trailing/middle empty properties,
duplicates, Unicode, deterministic randomized strings, config disabled,
null variants/receiver, injected exceptions at different deduplication positions,
ordered callback values, canonical reference relationships, actual Guava
counter/cache summaries, failed-publication preservation and live array
mutation subsequently read by the stock getter. Injected exceptions use a
subclass that calls the real deduplicator on all successful calls.

There are **no CPU, wall, startup or critical-path measurements** here. Existing
sample counts cannot be converted into milliseconds or claimed savings. Saving
an intermediate array per constructor does not establish meaningful owner cost.

## Hook/source boundary and decision

The operation is inside Palladium's private injected method, merged into
Minecraft by Mixin. A redirect on the vanilla constructor does not automatically
intercept instructions of another mod's merged injection. A post-Mixin patch or
cross-mixin hook adds significant version/ordering compatibility obligations.
No such patch is introduced for an unquantified small allocation reduction.

The installed metadata points to the public repository
<https://github.com/ITsMrToad/PalladiumMod>, owned by `ITsMrToad`, not the user.
GitHub reports GPL-3.0 and default branch `1.20-1.20.4`; installed metadata reports
LGPLV3. The other published branch `1.21x-neo` has `gradle.properties` declaring
mod 1.0.9 and Minecraft 1.20.6, not the installed 1.1.8/1.21.1 build.
Neither ownership nor an exact user-maintained 1.1.8 source build is
established. Do not assume user ownership or an exact controllable fork from a
metadata URL. No new fork/license interpretation is needed for this audit.

Close this narrow BootOptim attempt without runtime code or another game run.
Reopen only with measured exclusive constructor/allocated-byte budget **and** a
validated hook, or an exact maintained source lane making the direct change
simple. That would still require callback/config/public-array behavior, package,
startup and real-pack gates; this checker is only the first semantic gate.
