# Laptop JFR evidence — 2026-09-01

Source run: exact reference pack on the 4-processor / 6 GiB laptop, BootOptim campaign wrapper `bootoptim-startup-scaling-campaign-pr65-f78e88d-v0.1.5.jar`.

Original JMC text exports supplied to the project:

- `00-summary.txt`
- `01-hot-methods.txt`
- `03-allocation-by-site.txt`
- `04-allocation-by-class.txt`
- `05-allocation-by-thread.txt`
- `06-thread-cpu-load.txt`
- `07-file-reads.txt`
- `08-file-writes.txt`
- `09-longest-class-loading.txt`
- `10-compiler-statistics.txt`
- `11-longest-compilations.txt`
- `12-gc.txt`
- `13-gc-pauses.txt`
- `14-contention-by-site.txt`
- `15-contention-by-thread.txt`

No `02-*` report was supplied/found.

## Preservation note

The source TXT files were available through ChatGPT File Library search. That interface exposed searchable text chunks but did not expose a byte-for-byte raw file stream that could be passed into GitHub. The files in this directory are therefore deliberately named `*.excerpt.txt`.

They preserve the exact rows used for the analysis in [`../../laptop-startup-scaling-2026-09-01.md`](../../laptop-startup-scaling-2026-09-01.md), not an invented reconstruction of omitted rows.

If the original export ZIP is later provided as a directly downloadable conversation attachment, commit the untouched raw TXT exports alongside these excerpts and keep this README as provenance.

## Interpretation rules

- CPU sample percentages are statistical samples, not wall-time percentages.
- JIT total time is aggregate compiler-thread time, not serial startup time.
- Allocation percentages are sampled allocation pressure, not exact total allocated bytes.
- Reload futures/listeners overlap and must not be summed.
- JFR contention views may contain several grouped rows for the same Java method.
- `09-longest-class-loading.txt` is empty because `jdk.ClassLoad` / `jdk.ClassDefine` events were not captured; it is not evidence that classloading is cheap.
