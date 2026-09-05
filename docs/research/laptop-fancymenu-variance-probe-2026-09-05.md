# Fixed-configuration laptop variance probe

Diagnostic only; never promote this branch as production. Extends the exact
stock-call wait instrumentation from PR109, measured hosted code30e67ce, rather
than adding another per-resource profiler. Physical workload/timings are in
PR111 (`laptop-fullpack-variance-2026-09-05.md`).

Hypotheses remain CPU polling/worker contention, I/O or memory/GC, and distinct
post-preload work. None is established as the physical cause yet. Use one fixed
configuration across repeated runs, not alternating MCEF modes. This branch has
stock MCEF behavior, not PR90's defer implementation.

Three snapshots (preload entry, preload return, first title) report process CPU,
heap used, available physical memory and summed GC collection count/time. No
sampling thread, per-resource identity map, polling or JFR is added. Existing
wait-family CPU/wall measurement remains stock-observing.

Interpretation limits:

- Process CPU sums ALL Java/native worker, compiler and GC thread CPU; not decoder
  attribution and not elapsed time. Current-thread CPU comes from PR109 separately.
- GC collection time is the MXBean counter, not a claim of exact STW pause time.
- Memory snapshots miss peaks and available memory is not physical-read/hard-fault
  evidence. Do not conclude page-cache thrashing from it alone.
- Snapshot setup/counters add overhead, especially first management-bean access.
  Runs are a separate diagnostic cohort, not a production performance baseline.
- Post-preload snapshot-to-title is a broad tail, not FancyMenu self time.

The existing explicit benchmark exitOnTitle switch uses System.exit(0) here to
avoid the isolated Windows native-loop hang seen with Minecraft.stop(). No forced
exit occurs without that opt-in switch. This harness is not distributable product
behavior. Title timestamp is marked before final reporting/exit.

Gate: Build, hosted exact-pack smoke with
`-Dboot_optim.fancymenuWaitCpuDiagnostic=true`, intact selected/effective resource
packs, status=ok for wait coverage and both variance markers. Physical runs use
the packaged bootstrap and archive its hash, effective JVM flags, options and
post-exit logs. No cache purge, OS/Java changes or extra desktop interactions.
