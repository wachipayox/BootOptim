# Ordered ZIP prefix index candidate — 2026-10-02

Base integration b076c8ca, successor to #326 attribution. Exact query caching
is retired (6.114ms third+ CPU). Different queries still rescan GlowingTrim:
74calls,428.807ms ownerCPU hosted. Unlike #142's repeated central-directory
snapshot, sorted lexical lookup removes traversal/filtering of unrelated entries
for unique prefixes too. No claimed startup win from replay or inclusive wall.

Only exact FilePackResources runtime class; private original addPrefix and
listResources still filter directories, validate paths, log invalid names and
create fresh suppliers. Build immutable Entry(name, original ZipEntry, ordinal)
index per pack/open ZipFile identity, bounded250000entries. Binary lower bound,
startsWith range, then sort original ordinal before stock loop. Duplicate names
and directory entries retained. Unknown subclasses/oversize/runtime failures
fall back to original enumeration and disable this instance. No directory,
resource hit/miss/content cache; no provider callbacks bypassed. Index lifetime
is pack object; replacing ZipFile rebuilds. Open ZIP central directory is treated
as immutable; stock I/O exceptions/closed ZIP behavior remain downstream.

boot_optim.zipPrefixIndex=true enables candidate; false/default retains stock.
boot_optim.verifyZipPrefixIndex=true compares every eligible selected entry
sequence to filtered stock enumeration before callbacks. Verification adds work
and logs; smoke cannot measure performance. Require ready/verified rows, no
fallback/mismatch, exact selection/atlas/menu/noMixin failures.

Offline pinned GlowingTrim fixture21916entries, observed74query multiset:
2173prefixes ordered equality; original/snapshot/index checksums equal. Windows
15.625ms CPU granularity and warmed repeated loops are mechanism evidence only.
First-buildCPU/retainedheap/GC/contention and actual owner CPU2C2B remain pending.
After smoke choose bounded owner comparison; physical HDD only after semantic
and hosted premise pass. No user instance/laptop changes. Candidate must reach
integrate/remove decision; never retain indefinitely as forgotten experiment.
