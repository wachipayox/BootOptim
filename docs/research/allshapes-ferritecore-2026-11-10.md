# AllShapes prewarm and the piston state table

Create's initializer takes the default vanilla piston head state, sets
FACING to UP and SHORT to true, then calls getShape with a null world
and position. The captured final BlockStateBase overloads pass an empty
collision context to the concrete block. The vanilla PistonHeadBlock
implementation chooses a static short or long array by state properties.
Mixin did not export PistonHeadBlock in the control capture. The candidate
observes the final class through its own audit mixin and checks the
vanilla method fingerprint before enabling prewarm.

## FerriteCore publication

The Windows Mixin export shows StateHolder.setValue calling the FerriteCore
redirect, which reads the FastMap and index and calls FastMap.with.
StateHolder.getValue reads the installed property map. The physical pack's
property-map replacement uses FastMapEntryMap.get, which consults the
same table and index.

The ASM scan of exported classes found StateDefinition's constructor calling
populateNeighbours and found only the two FerriteCore setters writing the
map and index fields. The scan of the pack JARs, including JarJar entries,
found only StateHolderImpl.populateNeighbors calling those setters.
[FerriteCore 7.0.3 source](https://github.com/malte0811/FerriteCore/blob/build-7.0.3/Common/src/main/java/malte0811/ferritecore/impl/StateHolderImpl.java)
shows the construction loop setting the map and index before publishing
the property-map view. Piston states are constructed as part of vanilla
block bootstrap. Thread.start publishes the completed writes to the worker.
No later writer to the existing piston states was found in the scanned
pack or NeoForge. The verified read path has no registry callback.

The worker checks that the piston is the concrete vanilla PistonHeadBlock
and that all its concrete BlockStates share the expected FastMap, have
distinct nonnegative indices, and use the expected property-map view.
It checks final method bodies and type relationships before invoking the
initializer. An unfamiliar route falls back before initializing AllShapes.
The generated Mixin prefixes for FerriteCore's `getNeighborFromFastMap`
and ModernFix's `useArrayMap` are normalized. Their owners, suffixes,
descriptors and method bodies remain checked. The guard rejects ambiguous
matches.

## Create dependencies

ChuteShapes.createSlope reads PANEL and folds moved boxes. The sibling
createShape method accesses AllBlocks.SMART_CHUTE, but createSlope does
not call it. The four TrackVoxelShapes methods reached by AllShapes use
vanilla shape operations. The audited Create, piston and FerriteCore paths
show no class-initialization edge back into the registration thread.

## Shape equivalence

The physical control at `cb0b65be` and candidate at `66375c0c` both reached
the visible title menu. The sorted dumps covered 313,449 registered states
and six shape queries per state, yielding 1,880,694 lines and identical
SHA-256 11fb472acfff0df32f394fdf99072fe6c593e2b748b9c68f33025ffb9be3e86f.
The candidate emitted ACTIVE for shape-prewarm-create. The diagnostic
runs were excluded from timing.

## Exact-pack CI

The first run reached the menu and fell back on the transformed
`StateDefinition` constructor because ModernFix's generated prefix
differed. After normalizing that prefix and checking the handler body,
fixture `rev_621da0e4b8f761e7d438327b888b49b2` reached `main_menu` in
51.364 s on hosted Linux with Java 25.0.4 and Xvfb. The smoke test reported
zero BootOptim Mixin errors and no BootOptim compatibility fallbacks.
[Passing exact-pack run](https://github.com/wachipayox/BootOptim/actions/runs/42896859280).

```text
[WBOMBO-COMPAT] ACTIVE project=BootOptim mechanism=shape-prewarm-create mod=create@6.0.10
```

## Windows desktop ABBA

The physical Windows 11 desktop used the same installed client pack and
Java 25.0.1 with AOT throughout, with one discarded AOT training launch
per variant. The only BootOptim change between control and candidate was
the AllShapes mechanism. All four measured runs loaded their variant's
AOT cache and reached the visible menu.

This is the repeat with the experimental Mixin agent removed. The
process arguments matched within each pair of training runs and across
the four measured runs, except for the expected variant-specific AOT
profile or cache path. All six used the launcher's standard ZipFS agent.
Pandora reported `mode=record` for training and `mode=use` for each
measured launch. The JVM also logged `Opened AOT cache` in all four.

All values below are wall-clock seconds. `main_menu` is the BootOptim
marker at JVM uptime. Visible menu runs from JVM process start to the
first RRLS `state=HIDE`. The registration interval is the difference
between the BootOptim `bootstrap_end` and `register_block` markers.

| Launch | main_menu | Visible menu | Bootstrap to block registration |
| --- | ---: | ---: | ---: |
| A1 control | 40.079 | 44.036 | 10.655 |
| B1 candidate | 36.268 | 43.768 | 7.383 |
| B2 candidate | 40.124 | 47.719 | 8.738 |
| A2 control | 41.940 | 48.395 | 11.811 |
| Median B − A | −2.814 | −0.472 | −3.173 |

The AllShapes worker ran from 22.840 to 25.535 s of JVM uptime in B1 and
from 24.988 to 28.750 s in B2. The visible-menu delta includes later
startup phases and their variance. The registration interval shortened
in both pairs, supporting the guarded prewarm in
[#397](https://github.com/wachipayox/BootOptim/pull/397).

The earlier ABBA reported median deltas of −2.450 s for `main_menu`,
−5.389 s for the visible menu and −2.188 s for registration. Both variants
used AOT caches whose argument fingerprint matched only when including
the experimental Mixin agent. The complete command lines from that
series were not retained. The repeat above supersedes it for the
standard launcher configuration.
