# Production optimization retention audit — 2026-09-03

Status: **PASS / no previously promoted runtime optimization lost**

## Scope

The user requested a fresh audit after substantial MCEF/FancyMenu experimentation to verify that earlier production optimizations had not been dropped while branches were rebased or promotion work accumulated.

The authoritative integration base was refreshed immediately before the audit:

- `agent/integration-current`: `ad39b13824d71f6308050e8932f249dd18238923`
- MCEF production promotion runtime head under test: `d45df21b8ad820fae1f898bdaf6220c6adc6b9a7`

PR #90's changed-file list contains only the new MCEF first-consumer implementation/mixins plus documentation. None of the pre-existing optimization files are modified by the MCEF promotion. Direct blob comparison also showed the retained runtime files on the promotion branch are the same blobs as integration.

## Retained production mechanisms

### 1. Persistent mod scan cache

Present and enabled by default.

- implementation: `bootstrap/.../CachingModFileReader.java`
- service registration: `META-INF/services/net.neoforged.neoforgespi.locating.IModFileReader`
- property: `boot_optim.scanCache`, default `true`
- cache namespace remains `.bootoptim/mod-scan-cache-v1/`
- promotion-branch implementation blob: `647e22a642ce412563682c5d24ae2e78a332c12f`

The latest exact-pack smoke on PR #90 emitted normal `BOOTOPTIM_SCAN_CACHE` rows, proving the custom reader still executes in the real fixture. Fresh hosted VMs naturally produced misses, so this smoke proves presence/lifecycle rather than warm-cache speed.

### 2. Asynchronous scan-cache writes

Present and still wired to cache misses.

- implementation: `bootstrap/.../AsyncScanCacheWriter.java`
- single low-priority daemon writer remains in use
- cache misses submit persistence through `AsyncScanCacheWriter.submit(...)`
- promotion-branch blob: `c94d362aa5e7c9a4dd1c1b265d298742c270c6f6`

The exact-pack smoke emitted successful `BOOTOPTIM_SCAN_CACHE_WRITE` rows while scanning continued, confirming the asynchronous persistence path is active.

### 3. FancyMenu six-face panorama supplier prelaunch

Present, default-on and unchanged.

- mixin: `FancyMenuPanoramaPreloadMixin`
- property: `boot_optim.fancymenuParallelPanoramaPreload`, default `true`
- promotion/integration blob: `93c1bc7e645a86d28b43a4c148f35255037b0869`
- mixin remains registered in `boot_optim.mixins.json`

Latest exact-pack PR #90 smoke marker:

- `status=enabled`
- `panoramas=20`
- `suppliers_prelaunched=120`
- `failures=0`
- `preload_ms=4161.958`

This is the already-promoted six-existing-suppliers scheduling optimization. The rejected inter-panorama window experiments do not replace or remove it.

### 4. Decocraft quarter-turn geometry reuse

Present, default-on and unchanged.

- helper: `DecocraftRotatedQuadReuse.java`, blob `874f3f96a8511aadc6633160828627f62edd9a64`
- mixins: `ModelBakeryDecocraftReuseMixin` blob `798d456b4c74f3e70f3211f1c85550044be8da7e`; `UnbakedGeometryHelperDecocraftReuseMixin` blob `7ca63707195654a30c45712e72f2c037ead0599d`
- property: `boot_optim.decocraftQuarterTurnReuse`, default `true`

Latest exact-pack PR #90 smoke marker:

- `calls=14108`
- `base_bakes=3527`
- `derived_bakes=10581`
- `fallbacks=0`
- `rejected_models=0`

The rejected Decocraft 3D-item/sprite-elision experiment #79 is unrelated and did not replace this retained optimization.

### 5. Indexed blockstate variant matching

Present, default-on and unchanged.

- helper: `IndexedBlockStateVariantMatcher.java`, blob `d06f7cd34c923b03afdf2c2d61d08f013bf52510`
- mixin: `BlockStateModelLoaderIndexedVariantMixin`, blob `489029aacb59f8f62f36ad575bef031bde0bbdf1`
- property: `boot_optim.blockstateIndexedMatching`, default `true`
- mixin remains registered in `boot_optim.mixins.json`

This mechanism had disappeared only from the short `docs/optimizations/README.md` table. Its runtime implementation was never removed. The catalog omission is corrected by the same promotion branch.

### 6. Direct generated-item quad baking

Present, default-on and unchanged.

- helper: `DirectGeneratedItemBaker.java`, blob `6eb42b983364b24dac7a16211c7ebca3a15aec8a`
- mixin: `ModelBakerImplGeneratedItemDirectMixin`, blob `507fd7b10fae5d50e10fd7c9d80175e2a9fd53e4`
- property: `boot_optim.generatedItemDirectBake`, default `true`
- mixin remains registered in `boot_optim.mixins.json`

Like indexed blockstate matching, this was a documentation-index omission only. The production code and Trimmable Tools compatibility path remain intact.

## New MCEF candidate

MCEF first-consumer defer is additive to those six mechanisms. It does not modify their files. PR #90 adds four optional MCEF/FancyMenu consumer mixins plus `McefFirstConsumerDefer` and registers them alongside the retained production mixins.

Two independent 3×3 exact-pack confirmation campaigns were run on the exact same production runtime head `d45df21b`:

| Campaign | Candidate median | Control median | Candidate - control |
| --- | ---: | ---: | ---: |
| confirmation #1 | 92.831 s | 93.882 s | **-1.051 s / -1.12%** |
| confirmation #2 | 90.109 s | 92.311 s | **-2.202 s / -2.39%** |

Both campaigns had zero BootOptim Mixin errors. Candidate MCEF remained uninitialized before the title boundary; controls paid median CEF wall of 1.433 s and 0.944 s respectively. The two independent current-head campaigns therefore reproduce the favorable direction despite normal hosted variance.

## Decision

- No previously promoted optimization needs to be restored.
- Fix the production catalog so indexed blockstate matching and direct generated-item baking are visible alongside the other retained mechanisms.
- Do not reinterpret rejected FancyMenu windowing or Decocraft 3D-item experiments as replacements for the retained production implementations.
- Future promotion PRs should compare their changed-file list and mixin registry against the production catalog before merge so a missing optimization is detected as a process regression.
