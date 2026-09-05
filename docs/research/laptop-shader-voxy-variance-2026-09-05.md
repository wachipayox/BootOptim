# Laptop shader fallback and Voxy-save variance — 2026-09-05

Status: **REJECTED AS A STARTUP OPTIMIZATION / DIAGNOSTIC CLOSED**

Base: `agent/integration-current` @ `2fd0f62748b7a65aca24bcdbad43aba5d4b469d9`.

Related: PR #114 (hosted shader/Voxy diagnostic), PR #115 (physical
FancyMenu wait/variance evidence). This entry records one valid physical
diagnostic retry; it is not an A/B result and contains no production change.

## Workload and validity

The first launch attempt was discarded because the remote `instance.cfg` still
contained the previous FancyMenu wait-diagnostic property. The valid retry used
PR #114's bootstrap artifact (SHA-256
`64FBD0C15C4A8BBF9B4ABA204F61269F64995C1BB2188F1CCD28E14D5FB598A5`) and
verified `-Dboot_optim.shaderVoxyVarianceDiagnostic=true` in the effective
instance configuration. It ran the complete ten-ZIP fixture with one reload;
the resource-selection checker returned `valid=true`, with all 15 entries in
the reference order and no resource-pack fallback. The JAR was restored to the
production benchmark SHA `C0B20FA7874B6837297B78320910EBE755A250F8278F3BFA8246C0B3A80A5E25`
and the normal JVM arguments were restored before leaving the laptop idle.

The laptop reported `D3D12 (Microsoft Basic Render Driver)` and OpenGL 4.2
Mesa compatibility. The run reached the main menu in **477.432 s**. This total
is a noisy diagnostic cohort and cannot be compared causally with runs 017–021;
the probe is reported after the title marker and does not turn the run into an
A/B.

The coarse, non-overlapping log partition was: JVM→mod entrypoint 179.752 s,
mod entrypoint→reload 71.248 s, reload→blocks atlas 132.642 s,
atlas→preload 41.905 s, FancyMenu preload 26.413 s,
preload→FancyMenu `resource reload: FINISHED` 12.002 s, and that marker→title
13.470 s. These are log-clock partitions, not attribution of any one mod and
not proof that a listener is serial; they show that the shader/Voxy probes are
orders of magnitude below the large reload and post-preload variance surfaces.

## Measurements

The diagnostic markers were:

| Mechanism | Calls / result | Inclusive wall | Current-thread CPU | Other evidence |
| --- | ---: | ---: | ---: | --- |
| Shader capability probes | 6 / 5 expected failures | 155.418 ms | 62.500 ms | 6 render-thread callbacks; Flywheel 460→450→440→430→420 plus Voxy 430 |
| Voxy config save | 1 / 1 completed | 5.534 ms | 0.000 ms | `max_concurrent=1`, `active_at_title=0`, render-thread call |
| FancyMenu phase snapshot | 1 | 26,773.039 ms | 24,921.875 ms | Aggregate phase, not a replacement or saving |

The log still contains the deterministic four Flywheel GLSL fallback errors,
the two Voxy 4.30/compute errors, the nine sampler/uniform warnings and one
`AccessDeniedException` for the temporary Voxy config move. The shader marker
attributes the five failed probes to the deliberate capability ladder; it does
not show repeated pack-shader recompilation. The single Voxy save and
`max_concurrent=1` give no evidence of an internal save race. A 5.5 ms failed
replace is not a meaningful time-to-menu target without a separate causal
mechanism.

## Decision

Do not suppress the warnings, change the software renderer, alter Windows or
Java, serialize Voxy saves, or add a shader fallback workaround. The measured
shader capability work is sub-second and the Voxy write is single-threaded and
millisecond-scale; neither is a credible explanation for the multi-second
startup variance or a safe production win. The FancyMenu phase remains a
separate wait/critical-path investigation.

Reopen only if a future exact-pack physical run demonstrates either a material
shader-probe wall interval on the target hardware or `max_concurrent > 1` / a
repeatable Voxy-save wall cost that moves a critical-path marker. Hosted
llvmpipe evidence alone cannot certify the Microsoft Basic Render Driver path.
