# Bounded mod-JAR model-input reuse — design gate, 2026-09-23

Status: **HYPOTHESIS; NO RUNTIME CANDIDATE OR PERFORMANCE CLAIM**. This is a
different premise from the rejected two-permit input lane (#283), global
reload executor cap (#151), Decocraft full-JAR read-ahead (#76), and ZIP
enumeration snapshot (#142). The target is repeated physical resource entry
opening on later reload generations, not parallelism or JSON parsing.

## Physical signal and cost bound

The valid three-generation laptop diagnostic holds winning model/state source
fingerprints stable while `Resource.openAsReader` task-sum rises from
11.770/2.963 s (models/states) initially to 168.506/86.673 s after the pack
is restored. The corresponding future walls rise from 26.427/8.441 s to
191.037/93.639 s. JFR attributes slow file reads to
`UnionFileSystem.byteChannel`, but file-event durations are overlapping task
sums, not disk-busy or critical-path time. The 44,708 model and 11,484 state
tasks reoccur every generation; source fingerprints establish selection, not
byte equality.

An offline inventory of the pinned `exact-pack-2026-09-02-v1` fixture (SHA-256
`7f586ecd90497a4d4aa1d2024af2643dbd64691864edbad9eb2ed40551c55639`)
found 35,384 `assets/*/models/*.json` entries containing 24,424,776
uncompressed bytes, and 11,235 `assets/*/blockstates/*.json` entries
containing 15,793,853 bytes, across *all* nested mod JARs and resource-pack
ZIPs. Combined ~40.2 MB is a fixture archive subtotal, **not** the
active winner set or a cache-size measurement: the fixture includes inactive
duplicate mod versions and omits vanilla's JAR. Runtime task counts include
vanilla and discovered IDs beyond this archive inventory. These data support
testing a memory-bounded input cache but do not establish its hit rate or
survival under the observed late-bake GC pressure.

This mechanism has a hard limit: the first startup generation is cold, so a
cross-reload cache alone cannot improve first time-to-main-menu. Its only
possible user-facing gain is a later pack reload in the same JVM. Moving a
prefill earlier would merely relocate first-load I/O unless a measured idle
window and critical-path improvement are demonstrated. It must therefore be
judged as a manual-reload candidate, while the initial-open architecture
remains a separate unsolved front.

## Source and identity constraints

NeoForge 21.1.248 `ResourcePackLoader.createPackForMod` constructs a
`PathPackResources.PathResourcesSupplier` from each mod's
`getSecureJar().getRootPath()`. `PackRepository.openAllSelected()` opens new
pack-resource instances for reloads. ModelManager obtains a *winning*
`Resource` from the resource manager and calls `openAsReader`; that open goes
through PathPackResources / SecureJar UnionFS / ZipFS for mod JARs. A
`Resource`/`PackResources` object identity cannot be the cross-generation key.
Pack ID plus resource path is also inadequate: a ZIP/directory pack may be
edited or replaced without changing its ID; precedence can change on reload.

The conservative candidate domain is only a physical mod archive that can be
matched to a loaded `IModFile` and its SecureJar root, with a stable archive
identity snapshot. User resource-pack ZIPs, directories, generated resources,
vanilla and any unknown/custom suppliers must always take the stock path.
The candidate must use the current reload's winning `Resource` and source
classification before a lookup, so changed pack precedence automatically
falls through or selects a different key. A mod-archive mutation during a JVM
session must invalidate or disable the cache; same filename or pack ID is not
enough. At minimum the design needs resolved archive path and reliable file
identity/size/mtime checks at generation boundaries; if that cannot defend
against replacement/mutation on the supported filesystems, fail open rather
than rely on a weak key. Module/Connector remapped or nested archives require
explicit mapping or exclusion.

## Contract for an isolated experiment

* Keep ModelManager discovery, task count/order, parser, failure handling,
  mod callbacks, barrier and render-thread ownership unchanged. On a hit,
  supply a fresh UTF-8 reader over immutable raw bytes to the original parse
  call; never reuse a parsed `BlockModel`, blockstate object, reader, or
  current-generation sprite/baked model.
* Populate only after a successful complete read of an eligible winning
  resource. A failed open/read/parse must follow stock behavior and must not
  publish partial bytes. Concurrent reloads or source changes cannot publish
  into a newer generation under an older identity.
* Make the cache opt-in for the experiment, cap total retained bytes and
  entry size, count eligible/hit/miss/eviction/fallback/byte totals, and
  provide a kill switch. Retained bytes must be assessed against G1 live-set
  and pause behavior. A large open-time saving that merely shifts the third
  bake into longer GC is not a win.
* Compare output/pack selection and expected atlas/menu markers with the
  control. Validate one initial load and two manual reloads (pack removed and
  restored) in hosted smoke where possible, then same-branch hosted A/B.
  Physical HDD evidence is the final gate only after hosted semantics and a
  plausible critical-path mechanism. The user currently forbids accessing
  the laptop until a new signal.

The first coding gate is proving that a `Resource.source()` can be mapped to
an exact loaded mod archive without custom-pack false positives, and that the
chosen archive fingerprint is defensible for the process lifetime. If either
fails, this cache design is NO-GO. The separate late-bake GC amplification
requires its own diagnosis rather than being charged to or hidden by this
experiment.
