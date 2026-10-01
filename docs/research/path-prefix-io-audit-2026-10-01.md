# Game-side lexical prefix reuse: filesystem contract audit

Status: **offline semantic premise passed; no runtime optimization implemented**.
Current authority214293eb600f4a0eefbb68fd74042bac9158b1c2. Read open/closed
#71/#264/#283/#285 bodies/comments and the recovered UnionPath source audit.
The early bootstrap/private normalization hook remains out of this lane.
The #71 null Resource.source shader regression must never be reused as a
profiler. Neither the rejected two-permit IO cap nor archive-byte batch is
this hypothesis; no resource read or existence result is cached.

Exact1.21.1 PathPackResources.getResource(PackType,ResourceLocation) constructs
root.resolve(packType.directory).resolve(namespace), then calls the stock static
getResource. FileUtil.decomposePath, suffix resolution, returnFileIfExists and
lazy stream opens remain downstream. Cached local NeoForge source hash for
PathPackResources.java is2ca816f0f9f344d8a31fef50575cdee598b3ad19f9dfd461dd5c9be0b26b3cea.
FancyMenu's accessor exposes the root; this idea leaves the root unchanged.

Earlier offline audit proved200 lexical namespace-prefix equalities but no IO
contract. A smaller design would retain **only two per-pack lexical roots**,
assets/data, leaving namespace and suffix construction fresh. Avoid a shared
namespace/identifier map. Unknown/mutable/custom providers must stay stock;
any future hook must preserve the containing method/other mod operations and
only admit the exact inspected immutable UnionPath. At most one of two prefix
constructions is removed. This is not evidence for solving19s F3+T.

## New offline evidence

`tools/research/PathPrefixIoAudit.java` runs against actual SecureJarHandler3.0.8
SHA256945c63d6deafc821616b0380c23867d9b8c2852438f8a6df72732ab933fc587d,
Oracle-independent local Temurin21.0.4. It never installs a mixin or starts a
game. Compare fresh lexical construction with a retained pack-type prefix:

- reversed two-directory priority and mixed directory/ZIP bases;
- exact successful bytes and missing-file error class/message;
- filter callback sequence/base/entry and changed deny rule;
- fresh existence/attributes and dynamic create/edit/delete after prefix exists;
- stock closed-ZIP behavior and a newly opened pack's distinct filesystem;
- four concurrent readers,1000 pairs each,8000 actual reads.

Result174contract checks and8000concurrent reads PASS. The first harness
assumed Files.exists always returns a boolean; actual closed embedded ZIP throws
ClosedFileSystemException. Corrected the harness to compare that stock error
and filter sequence in both arms; no runtime source/provider behavior changed.
UnionFileSystem.isOpen() itself returns true even after close in inspected3.0.8;
do not use it as proof that the embedded ZIP remains usable. Fixtures retained
under local Temp for review; no recursive cleanup or user-pack mutation.

Example reproduction (classpath separator is Windows-specific):

```powershell
javac -cp <exact-securejarhandler-3.0.8.jar> -d <audit-classes> tools/research/PathPrefixIoAudit.java
java --add-opens=java.base/java.lang.invoke=ALL-UNNAMED -cp '<audit-classes>;<exact-securejarhandler-3.0.8.jar>' PathPrefixIoAudit
```

This is contract evidence for pure lexical reuse, not a mod-injection identity
proof, cache implementation, CPU budget, allocation measurement or game-time
win. No runtime candidate, per-resource profiler, hosted exact-pack request or
laptop trial is created. The laptop remains exclusively on the separate Ferrite
presize gate#317. Next decision requires current actual-prefix owner cost and
cheap exact-version/lifetime/injection guards before implementation; do not
turn overlapping/sample counts into milliseconds or revive blanket FS caches.
