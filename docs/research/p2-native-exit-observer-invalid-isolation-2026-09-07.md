# Native world-entry observer run: invalid null-audio isolation (2026-09-07)

Status: **DIAGNOSTIC / INVALID FOR NULL-BACKEND A/B / NOT A BOOTOPTIM REGRESSION**

## Purpose and boundary

Run `p2-native-exit-20260907a` was intended to collect one causal world-entry
trace after the repeated laptop-native crash. It used the diagnostic artifact
from PR #169, JDK Oracle 21.0.9, the production BootOptim wrapper, the imported
world, and the opt-in native MCEF probe. It was not a startup-performance A/B:
the only useful startup value is a diagnostic endpoint, not a TTMM claim.

The transaction runner reported `invalid` after its Java-detection timeout, but
Prism launched Java later. The actual process was PID 828, created at
`2026-09-07T21:31:27.1461220+02:00`. This race is itself a harness defect: a
late process must not be silently treated as a valid transaction result.

## Why the audio isolation was invalid

The staged Prism instance contained `Env={ALSOFT_DRIVERS:null}`. Prism parses
the instance environment as a JSON object; an unquoted `null` is not the
string value required by OpenAL Soft. The resulting Java command did not
receive the intended override. The log explicitly says:

```text
OpenAL initialized on device OpenAL Soft on Altavoces (Realtek High Definition Audio)
```

Therefore this run is normal Realtek/OpenAL audio, not `ALSOFT_DRIVERS=null`,
and it cannot be used to claim that the null backend fails or succeeds. The
configuration was corrected after postflight to
`Env={"ALSOFT_DRIVERS":"null"}` and the malformed file was preserved as
`incoming/preserved/instance.cfg.before-env-json-20260907a`.

## What the run still establishes

- `bootoptim-startup.log` reached `main_menu` at `388029 ms` and recorded
  `mod_entrypoint` at `137606 ms`; these values are not an A/B result because
  the run was invalid and there is no matched control.
- The world-entry log reached the integrated-server start and
  `level_loading_screen`; the process then terminated during the world-entry
  window.
- Windows Application Error 1000/1001 identified `javaw.exe` PID 828 failing
  in `C:\Users\wachi\AppData\Local\Temp\lwjgl_wachi\3.3.3+5\x64\OpenAL.dll`,
  OpenAL 1.23.1.0, exception `0xc0000409`, offset `0xA2B05`.
- The native probe did not produce `native-exit.json`, and no
  `BOOTOPTIM_MCEF_NATIVE_PROBE` marker or `Chromium Embedded Framework
  initialized` line was captured before the process ended. Temporal proximity
  to world/MCEF log messages is not causal evidence.

This is consistent with the previous normal-audio failures, but it does not
replace the already valid null-backend world-entry evidence. It also does not
identify whether the OpenAL fault is the sole trigger or the native termination
site of a broader client/audio/render interaction.

## Observer and transaction follow-up

PR #169's observer did not leave a final JSON when the JVM exited natively. A
continuation was sent to Agent 47 to harden the observer and transaction
protocol: always emit a final result or an error sidecar, bind observations to
PID and creation time, record the effective Prism environment/backend, and
classify WER evidence separately from MCEF markers. The continuation must not
launch another laptop run or modify production runtime code without a separate
decision.

The next physical gate, if still necessary, is one fresh run with the corrected
JSON environment and the same direct-world target. It must be accepted only if
the effective log says `No Output`, the PID/creation tuple matches, and the
observer transaction completes; otherwise classify it as inconclusive and stop
the campaign.

## Disposition

No BootOptim production change is justified by this run. Keep PR #169 draft and
diagnostic-only until the observer/harness is reliable. Do not use the startup
numbers above to promote an optimization, and do not infer that MCEF caused the
crash merely because its messages are near the termination.
