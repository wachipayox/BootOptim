# CITResewn base item-model cache

Status: retained for the exact-pack CITResewn path.

BootOptim caches the read-only base `models/item/*.json` models that CITResewn
inspects while loading item CITs. The cache is cleared at each `ModelBakery`
reload boundary. On cache hits the repeated resource stream is not opened; the
caller still receives a normal reader and the optional redirect returns the
previously parsed model. Custom CIT model and texture assets remain on
CITResewn's stock path.

Compatibility is guarded by pseudo/fail-open Mixins targeting
`TypeItem.loadUnbakedAssets`; if CITResewn is absent or its method shape no
longer matches, the stock path remains active. The JVM kill switch is
`-Dboot_optim.citresewnItemModelCache=false`; the default is enabled when the
target class is present. The cache is reload-scoped and therefore needs no
resource-pack persistence or invalidation protocol.

Evidence: exact-pack run `34057909967` showed `3960` requests, `3944` hits and
zero Mixin errors, with a median main-menu improvement of `10.603 s` (`14.02%`)
and reload-to-FancyMenu improvement of `5.198 s` (`15.33%`) on hosted runners.
On the target HDD laptop the isolated CITResewn load/link span fell from
`64.092 s` to `52.665 s` (`-11.427 s`, `-17.8%`) in a same-boot control/
candidate pair. Total startup remains noisy; this entry deliberately claims
the measured ModelManager subphase, not a fixed total-menu guarantee.
