# Exact Decocraft model JSON batching

Status: clean promotion candidate, **not integrated until its PR is merged**.
Default enabled on the guarded path; kill switch
`-Dboot_optim.decocraftModelArchiveBatch=false`. Minecraft1.21.1 exact
ModelManager model/state reader callsites. Optional Decocraft archive corpus,
not a generic resource content cache or model-object cache.

Load the10809model/blockstate JSON entries in archive order once, validate
size93170262bytes, bounded entry sizes/count, total3129313payload bytes and
SHA256corpus7e1ceb06798205056f50669b4c5edde3c95b35eabd7ca5a78416b884754e68d7.
Publish an immutable map only after complete validation and unchanged file
fingerprint. Keep byte arrays private and create fresh UTF8 buffered readers.
Original parsing/model construction/GL/scheduling/callback order stays stock.

Eligibility requires Decocraft namespace/models-or-blockstates JSON, winning
sourcePackId mod/decocraft, a PathPackResources source whose root matches the
actual mod SecureJar root, and the validated physical archive. Pack overrides,
missing/unsupported mod paths, changed corpus, I/O failure and unknown source
roots use the original wrapped reader. The snapshot is process-local; at each
ModelManager reload, check archive path/fileKey/size/mtime and invalidate on
change. This preserves the tested immutable installed-mod archive premise,
not arbitrary dynamic resource-provider equivalence. Same-attributes in-place
archive mutation during an open SecureJar is outside that premise; do not
extend this design to mutable directories/providers based on timestamps alone.

Retention is3.129MBencoded payload plus map/key/array overhead, not3.129MBtotal
heap. Never retain streams, readers, parsed JSON/model objects or generation
sprites. Counters, verification duplicate reads, CPU clocks, trial selectors,
automatic reload drivers and lifecycle profiling are absent from this package.
Only fixed batch ready/invalidation/fallback log messages remain.

Evidence: diagnostic PR320 and physical campaigndecocraft-json-20261001,
frozen sourceb4024d31. Hosted taskCPU savings118.485/127.174ms; physical
full read/parse/close CPU savings953.125/1078.125ms (~69–72%). Two controls and
two candidates with same-mode primers. Steady-state observations exclude
first fill; no cold-start win inferred. Concurrent task-wall sums save53.091/
57.399s but **not critical-path seconds**. Physical whole reload instead
+11.563466/+0.192919s, GC+2499/+6611ms, heapmixed. Deliberately retained for
eliminating actual CPU work, **no demonstrated end-to-end startup/F3+T win**.

Verified original-content diagnostic compared all10809stock readers. Clean
artifact must independently pass packaging, absent-mod startup and exact-pack
runtime activation/menu/ordered-pack/atlas/no-Mixin gates before merge. Physical
original JAR/config restored and independently hash-verified. Any later world/
resourcepack visual gate belongs to the fast PC; no laptop world trial.
