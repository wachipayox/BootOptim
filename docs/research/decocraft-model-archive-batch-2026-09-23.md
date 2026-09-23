# Decocraft model archive batch — 2026-09-23

Status: **DEFAULT-OFF EXPERIMENT; NOT PRODUCTION**. Base is
`agent/integration-current` at `b3f0c5f`. The valid laptop
`resource-deep-20260923b` trace shows Decocraft `Resource.openAsReader`
task-sums of 1.305 → 27.011 → 55.846 s for 7,280 models and
0.896 → 8.351 → 32.097 s for 3,529 blockstates across initial and two
manual reload generations. These overlap other work and are not a savings
ceiling. The last-generation ModelManager futures take 191.037 s for models
and 93.639 s for states, while parse sums stay much smaller.

This changes the premise of the rejected full-JAR read-ahead (#76) and the
two-permit open cap (#283). Instead of touching all 93 MiB of Decocraft or
reducing concurrency when average active model tasks are near one, the
experiment reads only Decocraft's model/state JSON in archive order and
supplies fresh UTF-8 readers to stock parsing. It targets both the first
load and later reloads, with a 3.13 MiB retained-byte ceiling. No model,
blockstate, sprite, baked result, callback, task, barrier, pack selection,
executor or render-thread work is reused or reordered.

The exact fixture Decocraft JAR contains 10,809 eligible JSON entries:
7,280 models, 3,529 blockstates, 3,129,313 uncompressed bytes. An offline
Java replay using SecureJarHandler 3.0.8 compared every direct `ZipFile`
entry to `Files.readAllBytes(SecureJar.from(jar).getRootPath().resolve(entry))`:
10,809/10,809 matched, with the same 3,129,313 bytes. Their archive offsets
form two contiguous clusters separated by non-JSON assets. Warm fast-PC
replay times were 223.750/127.687 ms for two UnionFS passes and
111.331/125.515 ms for two direct ZIP passes; this is not HDD or startup
evidence, and the second pair is effectively tied.

The runtime guard requires the current winning `Resource` to come from
`mod/decocraft`, from `PathPackResources` whose root equals the loaded
Decocraft `IModFile` SecureJar root, and from a Decocraft model or blockstate
JSON path. The snapshot has exact archive-size, entry-count, total-byte,
per-entry size and SHA-256 corpus checks; any mismatch uses stock. A changed
archive file identity/size/mtime at reload start invalidates the snapshot.
Unknown resource packs, edited external ZIPs, overlays, absent Decocraft and
other mod versions fail open. The loaded mod archive is assumed immutable
within a JVM, as its classes and SecureJar filesystem are already mounted.
The feature is off unless
`-Dboot_optim.experimentDecocraftModelArchiveBatch=true` is set.

A separate verification mode
`-Dboot_optim.experimentDecocraftModelArchiveBatchVerify=true` additionally
opens every eligible winning resource through stock `openAsReader`, compares
the decoded text, then gives the parser that stock text. It is a semantic
smoke gate only and must be **off in timed A/B**. The diagnostic marker
reports snapshot readiness, eligible hits, fallbacks, verification count and
retained bytes. Build and startup CI must pass, then hosted exact-pack smoke
must show 10,809 verified calls, zero mismatch/Mixin errors, unchanged
selection/atlas/menu. Only then run same-branch 3×3 hosted A/B without
verification. A small hosted gain remains physically unresolved because this
is a storage-order mechanism. The laptop must not be accessed until the user
explicitly signals it.

This branch is not mergeable as production solely because it builds or
passes smoke. A material ModelManager barrier or time-to-menu win, no
late-bake GC regression and final physical validation are required.
