# AllShapes prewarm and the piston state table

**Investigation reopened.**
The static audit below uses the Create and FerriteCore binaries from the
physical pack snapshot. It does not establish a race or a startup crash.

Create 6.0.10's initializer reads the vanilla piston-head block, takes its
default state, sets FACING to UP and SHORT to true, then calls the two-argument
getShape with a null world and position. The vanilla dispatch forwards to the
block with an empty collision context. The piston implementation chooses its
short or long static shape array and indexes it by the state's facing.

In the audited pack configuration, FerriteCore enables both neighbor-lookup
and property-map replacement and disables population of the vanilla neighbor
table. Its setValue redirect reads the per-state FastMap and index and calls
FastMap.with. StateHolderImpl.populateNeighbors installs the map and index
through setters and may replace the property map with FastMapEntryMap. That
last replacement also matters for the property reads in the piston dispatch.
Thread.start can publish the bootstrap thread's completed writes. The current
ShapePrewarm method and type fingerprints do not check the selected receiver,
table, index or the absence of later writers. The raw mixin bodies are
evidence for the dependency, not a capture of the final applied methods.

The Create chute call is createSlope, which reads PANEL and folds moved boxes.
ChuteShapes initializes two empty maps and three vanilla shapes. The sibling
createShape method accesses AllBlocks.SMART_CHUTE, but createSlope does not
call it. The four TrackVoxelShapes methods reached by AllShapes use vanilla
box and shape operations. No Create registry read or back edge into AllShapes
was observed on those raw helper paths.

Reopen with an observational ASM capture of the final StateHolder redirects,
property lookup and concrete piston shape dispatch, and a post-bootstrap
probe that identifies the actual piston states and their published table.
Audit all reachable writers and class-initialization edges before attempting
Class.forName with initialization enabled. The latter cannot recover from
a failed initializer. Then compare the six-query shape dumps and run the
exact-pack gate and physical A/B. No candidate, timing or differential was
produced by this static rejection.
