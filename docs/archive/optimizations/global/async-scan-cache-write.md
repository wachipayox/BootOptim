# Asynchronous scan-cache writes

## Scope

Global companion optimization for the persistent mod scan cache.

It is active only when a scan miss produces new cache data. Disabling the scan cache also makes this path irrelevant.

## Bottleneck

Writing every freshly scanned metadata blob synchronously on the FML scan worker would turn a cold launch into a second burst of disk I/O and serialization competing with discovery/classloading work.

## Mechanism

`AsyncScanCacheWriter` owns one daemon platform thread:

- single-threaded, preserving cache-write order;
- `Thread.MIN_PRIORITY`;
- only optional persistence work is submitted to it;
- the already-completed stock scan remains authoritative regardless of write success;
- a shutdown hook gives queued writes a bounded best-effort opportunity to finish.

Cache files themselves are written to a temporary path and replaced atomically where supported.

## Safety invariants

- Failure to enqueue a write never fails startup.
- A failed write never changes the `ModFileScanData` already returned to FML.
- The writer does not parallelize or reorder FML's actual scanning.
- The writer is a daemon; it cannot keep the JVM alive indefinitely.
- Shutdown waiting is bounded and best-effort.

## Resource trade-offs

A cold cache can temporarily queue serialized scan results and one low-priority background writer consumes CPU/disk bandwidth. This is intentional: the work is moved away from FML's critical scan workers rather than removed. Warm launches normally perform reads instead of these writes.

## Measured evidence

This optimization has not been assigned a standalone end-to-end startup number because it is coupled to cold-cache population. Its acceptance criterion is architectural: persistence must not block the scanner that just produced the authoritative result. The scan-cache benchmark and exact-pack runs validate the combined warm-cache system.
