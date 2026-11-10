# First MCEF screen task — 2026-11-10

**Keep the audited task skip.** The first automatic screen task in MCEF
2.1.6 sleeps on the client thread before asking MCEF to initialize.
BootOptim #294 previously suppressed later tasks while the deferral state
was already DEFERRED. The first task started in ARMED and reached the
deferral hook only after sleeping.

The candidate matches the submission and the entire no-capture runnable in
the final mixed-in Minecraft class. It verifies the calls, control flow
and exception handler. It replaces only that task's submission. On the
client thread, successful suppression advances ARMED to DEFERRED through
the existing lifecycle guard. The download and error branches stay in
MCEF's handler and real consumers use the existing initializer. An absent,
changed or ambiguous task is not rewritten. If MCEF is present, that
fallback is reported with its actual mod version. The five-case offline
matcher probe accepted the shipped body and rejected changed delay, an
extra call, a different lambda owner and duplicate sites.

## Hosted startup

[Exact-pack smoke](https://github.com/wachipayox/BootOptim/actions/runs/42838722373),
Beta revision `rev_621da0e4b8f761e7d438327b888b49b2`, manifest
`81c4717b1cfa4ab0194f208be388a031635976bcd950df1b3868544f2f673d6a`.
Linux/Xvfb, Java 25.0.4, JVM start to BootOptim `main_menu` in 69.902 s.
The candidate logged the DEFERRED transition and an audited task skip.
Zero compatibility fallbacks and zero BootOptim mixin errors. This is
a startup gate, with Windows timing measured separately.

## Physical A/B

Windows 11, i5-12600KF, RTX 3070, same fixed local NeoForge 21.1.252 pack,
Java 25.0.1 with one separate discarded AOT recording per variant. Both
caches were verified active for all four timed launches. Control
`d82747b1`, candidate `a595b7ae`. The JVM is the start of each measurement.
BootOptim's `main_menu` marker and the RRLS initial overlay `HIDE` are
separate elapsed-wall endpoints. The segment is the wall interval from
the last registration marker to `common_setup_start`.

| Order | Variant | Main menu (s) | Visible menu (s) | Segment (s) |
|---|---|---:|---:|---:|
| A1 | Control | 44.818 | 49.497 | 3.597 |
| B1 | Candidate | 44.042 | 49.330 | 2.450 |
| B2 | Candidate | 43.254 | 49.367 | 2.265 |
| A2 | Control | 45.930 | 52.978 | 3.563 |
| | Candidate minus control medians | -1.726 | -1.889 | -1.223 |

Both candidate runs reached both menu endpoints earlier than both controls.
The candidate segment was 1.1–1.3 s shorter. The bytecode and transition
locate the removed one-second render-thread wait on the path to the first
reload. The entire end-to-end delta cannot be assigned to that wait.
The pre-segment last registration was already 0.354 s earlier in the
candidate median. These observations measure elapsed wall, with no CPU
saving attributed.

The four timed launches had zero BootOptim compatibility fallbacks and
no new BootOptim or Mixin errors. Texture lookup warnings occurred in
both variants and the affected sprite varied between launches.

A separate, untimed Windows diagnostic invoked the real `MCEF.getApp()`
on the client thread after the menu. MCEF was uninitialized beforehand.
The existing first-consumer path initialized it on the render thread in
1,105.587 ms and returned a real app. No visual workload was changed for
the timed runs. The diagnostic is excluded from the shipped code.

Candidate bootstrap JAR SHA-256
`8c51219a0730b7bc10e6c14d0f1f8a42b77d3cd2a1f5e0a922d26d334d83e744`.
