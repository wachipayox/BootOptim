# Global optimizations

Global optimizations target shared startup paths used by Minecraft, NeoForge/FML, or many mods. They should not depend on a particular content mod being installed.

- [Persistent mod scan cache](mod-scan-cache.md)
- [Asynchronous scan-cache writes](async-scan-cache-write.md)
- [Vanilla blockstate bake identity reuse](blockstate-bake-dedup.md)

The acceptance bar is semantic compatibility first. A global optimization that accelerates one phase by slowing the full launch, changes ordering-sensitive behavior, or requires lowering concurrency for unrelated work should remain experimental rather than production.
