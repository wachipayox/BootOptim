# PathPackResources empty-path postmortem — 2026-09-11

## Scope

Agent 133 investigated the stable exact-pack burst of 128

```text
[PathPackResources] Invalid path : Invalid path ''
```

ERRORs identified by PR #262. This work is diagnostic/postmortem only. It does not justify log filtering, deduplication, warning suppression, resource lifecycle changes, or startup savings claims.

Base authority: `agent/integration-current` @ `b3f0c5f6462a359483883741ac16d2f154868900`.

Runtime pins observed in hosted exact-pack evidence:

- Minecraft `1.21.1`
- NeoForge `21.1.248` in the attributed stack
- exact-pack CITResewn artifact advertised as `CITResewn 0 (citresewn)`, scan filename `(svfr) CitResewn {v0} [1.21.1] [MAINLOC].jar`
- public source lineage used to identify the injected method: `CancriRecoleta/CITResewn` @ `8cca0f127f3898472e2109f6ac6a797253756893`

## What emits the exact message

The initial icon/root-resource hypothesis was rejected.

In Minecraft 1.21.1, `PathPackResources` has two invalid-path logging surfaces relevant here. The static `getResource(ResourceLocation, Path)` helper validates a resource-location path, while `listResources(PackType, String namespace, String path, ResourceOutput)` validates the listing prefix.

Early ModLauncher instrumentation proved:

- the static `getResource(ResourceLocation, Path)` helper was transformed successfully;
- there were **zero** empty-resource-location observations;
- `listResources(...)` was transformed successfully;
- there were **128** empty-list-prefix observations;
- there were **128** stock `Invalid path : Invalid path ''` ERRORs;
- all **128** observed stacks contained `AbstractPackResources.handler$...$citresewn$brokenpaths$parseMetadata` immediately above the pack metadata path.

Therefore the 128-event family is the `listResources(..., path="")` validation surface, not `getRootResource`, not a pack-icon lookup, and not 128 independent malformed assets.

## Exact caller

The caller is CITResewn's broken-path compatibility metadata probe.

The matching public source method is `com.github.citresewn.mixin.broken_paths.AbstractFileResourcePackMixin#citresewn$brokenpaths$parseMetadata`. Its relevant logic is:

```java
for (String namespace : getNamespaces(PackType.CLIENT_RESOURCES)) {
    listResources(PackType.CLIENT_RESOURCES, namespace, "", (identifier, inputStreamInputSupplier) -> {
    });
}
```

The runtime stack is:

```text
PathPackResources.listResources(...)
AbstractPackResources.handler$...$citresewn$brokenpaths$parseMetadata(...)
AbstractPackResources.getMetadataSection(...)
NeoForge ResourcePackLoader.readMeta/readWithOptionalMeta/packFinder(...)
PackRepository.discoverAvailable/reload(...)
Minecraft.<init>(...)
```

The burst occurs during initial pack discovery/metadata reading on the Render thread, before the first resource-manager reload.

## Pack ownership

This is **not one bad icon or one bad resource pack**. CITResewn injects the probe into `AbstractPackResources#getMetadataSection`; every path-backed pack for which the hook iterates one or more client-resource namespaces can reach the same invalid empty prefix.

Diagnostic output showed multiple distinct `PathPackResources` pack ids/roots, including built-in/mod packs such as `builtin/add_pack_finders_test`, Chloride's path packs, `mod/efficient_hashing`, `createdeco`, `mod/neoforge`, `mod/modernfix`, `mod/zume`, `mod/aeronautics`, `mod/toadlib`, `mod/unlaggedfarming`, `mod/brewinandchewin`, `mod/securitycraft`, `mod/entityculling`, and others. The 128 count is therefore an iteration count over affected path-backed pack namespaces, not evidence for 128 corrupt files.

Temporal proximity to Chloride's built-in-pack registration was incidental. Chloride contributes path packs to the population, but it is not the unique caller/root cause.

## Why the call is a no-op on PathPackResources 1.21.1

For `PathPackResources#listResources`, Minecraft decomposes/validates the supplied listing prefix before walking resources. The empty string fails `FileUtil.decomposePath("")`. On that failure the method logs the stock ERROR and returns. It does not invoke `ResourceOutput`, does not enumerate resources, and does not throw the `ResourceLocationException` that CITResewn's metadata hook is trying to use as its compatibility signal.

Consequently, for `PathPackResources` specifically, CITResewn's `path=""` probe is already functionally inoperative in this runtime: its only observed effect is the ERROR emission. It does not detect broken paths there and does not change the returned pack metadata.

This is materially different from suppressing the ERROR after emission. The source defect is the invalid synthetic listing prefix itself.

## Source-fix options

### Configuration: rejected

CITResewn exposes `broken_paths`. Disabling it removes all `broken_paths.*` mixins. That changes CITResewn compatibility behavior globally and is not justified by this one invalid `PathPackResources` probe. It is not an equivalence-preserving fix for the exact pack.

### Pack metadata/resource edits: rejected

No resource-pack metadata value supplies this empty prefix. The literal `""` is constructed by CITResewn source. Editing pack icons, `pack.mcmeta`, resource paths, or user resource-pack ordering cannot correct the caller without changing unrelated content.

### Upstream source: bug still present

`CancriRecoleta/CITResewn` @ `8cca0f127f3898472e2109f6ac6a797253756893` still contains the empty-prefix call. The public `CIT-Resewn-Newer` 1.21.1 source also retains the equivalent `findResources(..., "", ...)` pattern. No existing upstream fix was found during this investigation.

### Minimal fork/upstream patch: viable

The narrow source correction is to preserve the existing broken-path probe for pack implementations where it can work, but skip the invalid `listResources(..., "")` invocation for `PathPackResources`.

A conservative shape is:

```java
for (String namespace : getNamespaces(PackType.CLIENT_RESOURCES)) {
    if (this instanceof PathPackResources)
        continue;

    listResources(PackType.CLIENT_RESOURCES, namespace, "", (identifier, supplier) -> {
    });
}
```

Keeping the `getNamespaces(...)` iteration preserves that existing side/effect surface; only the call already proven to return immediately on `PathPackResources` is omitted. ZIP/other pack implementations keep the current probe unchanged.

An even broader change such as disabling `broken_paths`, filtering the logger, changing Minecraft's global empty-prefix semantics, or skipping all `listResources(..., "")` calls is not recommended.

## Hosted validation

### Attribution smoke

Commit `dafcb560b7dbd2c97a9a7907e058001a5fde5378`, exact-pack workflow run `34617955588`:

- build/startup succeeded;
- exact resource selection valid;
- expected/observed user-resource contract remained 14/14 in exact order;
- reload count remained 1;
- `bootoptim_mixin_errors = 0`;
- 128 stock PathPack errors;
- 128 `PathPackResources.listResources` diagnostic stacks;
- 128 `citresewn$brokenpaths$parseMetadata` caller frames;
- zero empty `getResource(ResourceLocation, Path)` observations.

### Source-equivalent validation smoke

Commit `44bfe4fdaf3915949914035a7ae913762d2d17a9`, exact-pack workflow run `34618847696` used a validation-only early transformer that returns from `PathPackResources#listResources` when the listing prefix is empty. Agent 133 had already proved that every such exact-pack call was from the CITResewn probe above.

Observed:

- build: success;
- startup to main menu: success;
- exact resource selection: **valid**;
- expected/observed resource packs: **14/14, same order**;
- reload count: **1**;
- `bootoptim_mixin_errors`: **0**;
- block atlas: **8192 x 8192**, **2 levels**, matching control evidence;
- exact `Invalid path : Invalid path ''` ERROR count: **0**.

This smoke is evidence for resource-selection/reload/atlas equivalence of the no-op source correction on the exact pack. It is **not** a complete screenshot/visual-diff proof and must not be described as such. Because the skipped stock branch was proven not to enumerate or invoke callbacks, no resource-selection or model input is expected to change; nevertheless no independent pixel comparison was produced here.

The apparent startup-time difference in this single hosted smoke is intentionally not interpreted. Error-count reduction is not a savings measurement, and the validation transformer itself is diagnostic-only.

## Decision

**Source fix viable; BootOptim runtime workaround no-go.**

The root cause is CITResewn's `broken_paths` metadata probe passing an empty listing prefix to `PathPackResources#listResources` on Minecraft 1.21.1. For that pack implementation, stock behavior is already log-and-return/no-callback, so a CITResewn source patch that skips only this impossible probe preserves current resource/metadata behavior while removing the invalid source call.

Recommended next action is an upstream patch or a version-pinned CITResewn fork carrying only the `PathPackResources` guard, followed by exact-pack validation of the fork artifact. Do not promote the validation transformer in this PR, do not add logger filtering/deduplication, and do not claim startup savings from the disappearance of 128 ERROR lines.
