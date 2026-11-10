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
The FerriteCore redirect's generated Mixin prefix is normalized while
the owner, suffix, descriptor and instructions remain checked.

## Create dependencies

ChuteShapes.createSlope reads PANEL and folds moved boxes. The sibling
createShape method accesses AllBlocks.SMART_CHUTE, but createSlope does
not call it. The four TrackVoxelShapes methods reached by AllShapes use
vanilla shape operations. The audited Create, piston and FerriteCore paths
show no class-initialization edge back into the registration thread.

## Shape equivalence

The physical control at cb0b65be and the AllShapes candidate both reached
the visible title menu. The six-query sorted shape dumps had identical
SHA-256 11fb472acfff0df32f394fdf99072fe6c593e2b748b9c68f33025ffb9be3e86f.
The candidate emitted ACTIVE for shape-prewarm-create. The diagnostic
runs were excluded from timing.
