# Hosted exact-pack startup CI

Status: **ACTIVE INFRASTRUCTURE**

This document defines BootOptim's reproducible hosted surrogate for the exact reference modpack. It exists to move repeated software-pack A/B work out of scarce laptop launches while preserving the requirement that candidates be exercised against the real mods, configs and enabled resource packs.

It is a **software-pack surrogate**, not an emulation of the Intel i3-2350M laptop. Absolute hosted wall time is not a laptop baseline.

## Fixture pin

Authoritative public Release asset:

- tag: `exact-pack-2026-09-02-v1`
- asset: `bootoptim-exact-pack.zip`
- size: `1,202,581,263` bytes
- SHA-256: `7f586ecd90497a4d4aa1d2024af2643dbd64691864edbad9eb2ed40551c55639`

The workflow verifies SHA-256 before extraction and caches the ZIP by the pinned digest. Never silently replace the asset behind this fixture; a changed pack needs a new release/tag and documented hash.

The ZIP was assembled on the user's PC, not the laptop. Its purpose is to pin **software state**. `options.txt` is copied so the resource packs the user normally enables participate in hosted resource resolution too.

The fixture deliberately contains `mods/mcef-libraries/`, deliberately excludes mutable `mods/mcef-cache/`, and must not contain a BootOptim JAR. The branch/PR build is injected by ModDevGradle.

## Why the hosted client uses Linux + software OpenGL

The first Windows Server hosted smoke proved that a stock Minecraft client cannot create a usable GLFW window on the GitHub Windows runner. After the Drippy early window was disabled, vanilla reached `glfwCreateWindow`, failed, then blocked in `Window.bootCrash -> TinyFileDialogs.tinyfd_messageBox` trying to show an error dialog on the non-interactive runner.

That failure is a runner graphics limitation, not an exact-pack compatibility result.

Hosted exact-pack jobs therefore use `ubuntu-22.04` with:

- Xvfb display `:99`;
- Mesa llvmpipe software rendering;
- `LIBGL_ALWAYS_SOFTWARE=true`;
- `GALLIUM_DRIVER=llvmpipe`;
- Mesa GL/GLSL exposure forced to 4.6/460 because the exact pack requests an OpenGL 4.6 context.

`glxinfo -B` is captured for every run and the job fails if llvmpipe is not active.

This makes client/render/resource code executable in CI, but **GPU/render/native timing is not portable evidence**. Use hosted timing primarily for paired Java/resource/model/atlas directionality.

## Hosted-only Drippy exception

The extracted ephemeral copy gets:

```toml
earlyWindowControl = false
```

in `config/fml.toml`.

Exact FML behavior for this setting is to skip the `ImmediateWindowProvider`. Drippy itself remains installed and its normal resources/classes remain part of the pack.

Consequences:

- work performed by the Drippy early-window provider itself is **not measured**;
- ModLauncher, mod discovery/transforms, Minecraft client initialization, MCEF, resource reload, models, atlases and FancyMenu work that occur outside that provider still execute normally and remain in startup measurements.

The original Release ZIP is never modified.

## Deterministic MCEF setup

Exact MCEF is `2.1.6-1.21.1`. Its source has a special development path: when `../build` exists relative to the game directory it uses `../build/mcef-libraries/` instead of `mods/mcef-libraries/`. ModDevGradle exact-pack runs satisfy that condition, so merely copying the fixture's Windows MCEF directory does not preseed the binaries used by the hosted run.

Hosted CI pins the java-cef commit observed/source-resolved for this exact MCEF build:

```text
a78e832f9f13c2c688caea3d04d8b84fcd238d94
```

Before any timed Minecraft launch, the fixture job downloads and SHA-verifies the corresponding `linux_amd64` JCEF archive, extracts it, and caches it. Each benchmark VM restores that immutable cache and stages it at:

```text
build/mcef-libraries/linux_amd64/
```

MCEF 2.1.6 still performs a checksum HTTP request during its download thread even when binaries are already present. To remove WAN variance without patching MCEF, the ephemeral `config/mcef/mcef.properties` is changed to:

- `download-mirror=http://127.0.0.1:18765`;
- `skip-download=true`.

A tiny localhost HTTP server serves the already-pinned checksum during the Minecraft launch. The local checksum is also staged beside JCEF, so a match means no archive download/extraction occurs inside measured startup. `-Dmcef.java.cef.commit=...` pins MCEF's source-supported commit override explicitly.

`use-cache` and user-agent settings from the supplied pack are preserved. The fixture contains no `mcef-cache`, so each fresh hosted VM starts without prior browser-history/cache state.

Hosted MCEF initialization wall is useful as a **noise/control marker only**. Linux JCEF + llvmpipe must not be used to accept or reject a Windows-native MCEF optimization without real-hardware validation.

## Laptop JVM surrogate

Minecraft receives the user's laptop JVM arguments:

```text
-Xmx6G
-XX:+UnlockExperimentalVMOptions
-XX:+UseG1GC
-XX:G1NewSizePercent=20
-XX:G1ReservePercent=20
-XX:MaxGCPauseMillis=50
-XX:G1HeapRegionSize=32M
```

and additionally:

```text
-XX:ActiveProcessorCount=4
```

so JVM/ForkJoin heuristics see the laptop's four logical processors. The runtime is Oracle JDK `25.0.4`; BootOptim remains compiled with Java 21.

The workflow records actual hosted CPU/memory separately. `ActiveProcessorCount=4` does not make hosted hardware identical to the laptop.

## Copied instance state

`preparePackBenchmark` copies startup-relevant instance content into `run-pack-benchmark`:

- `mods/`
- `config/`
- `defaultconfigs/`
- `automodpack/`
- `resourcepacks/`
- `shaderpacks/`
- `kubejs/`
- `scripts/`
- `paxi/`
- `openloader/`
- `global_packs/`
- `fancymenu_data/`
- `options.txt`

## Trigger protocol

Workflow: `.github/workflows/exact-pack-startup-benchmark.yml`.

Smoke:

```text
[exact-pack-ci]
exact-pack-mode: smoke
```

Same-branch A/B:

```text
[exact-pack-ci]
exact-pack-mode: ab
exact-pack-repetitions: 3
exact-pack-candidate-jvm-arg: -Dboot_optim.exampleFeature=true
exact-pack-control-jvm-arg: -Dboot_optim.exampleFeature=false
```

Multiple candidate/control JVM-arg lines are supported. Repetitions are limited to 1-5 per variant. Every matrix entry gets a fresh hosted VM, so candidate/control runs do not share JVM state, Minecraft process state or OS page cache.

PR-body triggering is the durable contract because `agent/integration-current` is not the repository default branch. `workflow_dispatch` is also defined for environments where the workflow is available from the default branch.

## Evidence produced

Per run, CI keeps lightweight diagnostics rather than re-uploading the pack:

- `result.json`;
- console log;
- thread dump on failure/timeout;
- `latest.log` and `bootoptim-startup.log`;
- effective BootOptim/FML/MCEF config and `options.txt`;
- Xvfb/OpenGL and hosted-resource diagnostics.

Aggregate medians cover, when available:

- main-menu startup uptime;
- mod-entrypoint uptime;
- mod-entrypoint -> main-menu wall;
- MCEF initialization wall;
- initial reload start -> FancyMenu reload-finished wall;
- FancyMenu panorama wall;
- BootOptim Mixin failures;
- Decocraft 3D-item markers.

Never sum asynchronous listener/task-sum measurements. The same critical-path rules used on laptop evidence apply here.

## Interpretation / hardware gate

Use hosted exact-pack CI after Build CI and normal Startup CI as the default repeated-runtime gate.

Good hosted evidence is a **coherent paired effect**: candidate medians move in the expected direction, relevant phase markers move with them, mechanism markers prove the optimization ran, and unrelated MCEF/FancyMenu variance is bounded.

Real hardware is still required before production promotion when:

- the effect is small/noisy;
- physical disk, filesystem or OS page-cache behavior is the mechanism;
- native CEF or GPU behavior is central;
- Windows-specific behavior matters;
- visual/steady-state rendering must be inspected;
- hosted and laptop phase scaling disagree materially.

The goal is not to reproduce the historical ~337 s laptop wall. The goal is to remove avoidable run-to-run software/environment noise before asking the laptop to arbitrate the remaining hardware-sensitive question.

## Editable/custom mod lane

The user explicitly permits direct modifications to mods they authored or edited. If source/history shows a mod is user-authored or a user-maintained/custom `-wedit` fork, editing that mod directly is a valid optimization candidate rather than forcing an external BootOptim workaround.

Agents should present the direct-mod change for user evaluation, isolate it in the appropriate repository, and retain the normal correctness/performance gates. This permission removes a repository-boundary constraint; it does not waive semantic safety.
