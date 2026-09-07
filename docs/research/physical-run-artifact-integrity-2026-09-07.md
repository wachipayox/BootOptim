# Physical-run artifact and process-state integrity — 2026-09-07

**Status: PROCESS FIX REQUIRED.** A laptop smoke exposed two independent ways to
accept an invalid run: the process monitor used an AND-combined `tasklist`
filter, and the file called “production-restored” was not built from the current
integration commit.

## Incident

The run was launched after a reboot with one active JAR named
`bootoptim-production-restored-final-20260907.jar` (112,724 bytes, SHA-256
`CA7BCF0A26419FEDBCE88F3601814444B5A164869B85E355ACB7FEAA219C2D1E`). A
startup report from that run enabled `resource_reload_pool` and mentioned
`ResourceReloadThreadPolicy`. Neither the class nor that optimization exists on
`agent/integration-current` at `2bccf5f4fa221c78e286d052beb78636fa4c317b`;
the file was an old experimental artifact from the resource-reload-pool lane.

The first post-launch check used both `/FI "IMAGENAME eq javaw.exe"` and
`/FI "IMAGENAME eq prismlauncher.exe"` in one `tasklist` invocation. Windows
combines those filters with AND, so it falsely reported no tasks while the Java
PID was still running. The log was read too early. The run reached
`main_menu=461106 ms` and autoclosing worked, but it is rejected completely as a
performance result because both its artifact identity and observation boundary
were invalid.

## Corrected contract

The distributable was rebuilt from a clean worktree at the integration SHA. The
verified bootstrap JAR is 103,479 bytes with SHA-256
`03842956B7AF47CAB4C1C3AC94D16A2563A0A652EA7A49B1ACE3C7E528C329F6`; its nested
regular mod contains no `ResourceReloadThreadPolicy`. The laptop now has exactly
one active JAR, `bootoptim-integration-2bccf5f.jar`, with that hash. The old file
was moved to an incoming quarantine directory, not deleted.

Every physical run must now:

1. resolve the exact integration/experiment commit and verify the packaged
   bootstrap hash before launch;
2. enumerate all Java/Prism processes once, or query each image name separately;
3. record the actual Java PID and start time after the launcher spawns it;
4. refrain from reading logs, copying them, or polling their contents until that
   PID has disappeared;
5. require both a matching `main_menu`/summary marker and a postflight with no
   Java process before accepting the result;
6. classify any stale JVM, wrong artifact, duplicate JAR, missing marker, or
   early log read as invalid rather than as a slow or fast sample.

The corrected current-integration run is the first one eligible for a future
baseline, but it must still be analyzed only after the PID and launcher have
exited and the postflight hash is recorded.

## Corrected baseline result

The subsequent run used the corrected JAR and was observed only through its
recorded Java PID until that PID disappeared. Its startup report began at
`2026-09-07T08:22:50.709798300Z` and reached the menu at
`367561 ms` (`mod_entrypoint=146171 ms`). It reported Java 25.0.4, four
processors, a 6144 MiB heap, and the normal `mod_scan_cache`; it did not report
the stale `resource_reload_pool` optimization. The postflight hash remained
`03842956B7AF47CAB4C1C3AC94D16A2563A0A652EA7A49B1ACE3C7E528C329F6`, with
exactly one active BootOptim JAR and no Java or Prism process left.

The run is a valid **single corrected baseline**, not an A/B or a promotion
claim. The latest log shows normal Realtek OpenAL initialization and no
BootOptim Mixin failure. Existing pack warnings (missing model variants,
Realms authentication, and EMF repeated-model warnings) remain separate
follow-up signals; they do not invalidate the startup measurement.
