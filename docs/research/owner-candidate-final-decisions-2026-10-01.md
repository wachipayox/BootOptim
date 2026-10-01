# Owner candidate final decisions — 2026-10-01

Status: **REJECTED / RETIRED** for Decocraft corner V2, generated-layer
arithmetic hoisting, multipart validated union, and FerriteCore persistent empty
capacity. This explicitly supersedes their older default-off/pending-laptop
dispositions. No candidate runtime code or diagnostic is promoted here.
Existing production Decocraft quarter-turn reuse, direct generated-item baking
and indexed blockstate matching remain unchanged.

## Three remaining actual-owner comparisons

[Diagnostic #313](https://github.com/wachipayox/BootOptim/pull/313), source
`085bbbfe2d276a6d835da4234215d1342c35979e`, passed Build, startup and
[exact-pack 36847026289](https://github.com/wachipayox/BootOptim/actions/runs/36847026289).
The strict checker accepts all 25 successful generations, exact activation,
equivalent workload and complete markers. Origin: hosted exact-pack, one JVM;
each mechanism has two measured controls and two measured candidates in
C1/B1/B2/C2 order, with a separately unmeasured same-mode primer before each.
Preparation, initial startup and primers are not votes or time saved.

| Mechanism / actual owner | Calls per observation | C1 CPU ms | B1 CPU ms | B2 CPU ms | C2 CPU ms | B1−C1 ms | B2−C2 ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Decocraft V2 / BlockbenchBakery.bakeQuad | 1,103,910 | 1914.693644 | 1931.965273 | 1857.790898 | 1862.779432 | +17.271629 | −4.988534 |
| Layer arithmetic / direct baker bakeLayer | 31,369 | 342.395980 | 324.790433 | 320.709528 | 317.719354 | −17.605547 | +2.990174 |
| Validated union / MultiPart.getDependencies in loadAll | 147,906 | 777.806309 | 783.858146 | 751.918438 | 828.043379 | +6.051837 | −76.124941 |

Each layer observation includes 1,114,190 side spans and 256,050 matching
Trimmable faces. No zero-activation inference is needed. Each owner includes
its unchanged callbacks/body. CPU is a thread-CPU sum, **not reload critical
path**; call-wall sums are not sequential phases to add together.

Two CPU reads surround each outer call identically in both arms; CPU clock
reads enclose the wall interval. Absolute CPU therefore includes observer cost
and can exceed summed method wall, particularly at a million small calls.
These are not uninstrumented owner budgets. Equal call counts/placement support
the paired comparison, but do not magically eliminate noise. None improves
both opposite-order contrasts. Under the explicit segment-first decision rule,
retire #296/#301/#305 instead of keeping disabled options for another unchanged
run. This does not prove zero effect on every machine. Reopening requires a
concrete new mechanism or a demonstrated instrumentation flaw with a repaired
bounded protocol, not a hoped-for more favorable runner/laptop.

## FerriteCore persistent storage: CPU win, physical gate fails

[Diagnostic #308](https://github.com/wachipayox/BootOptim/pull/308), timed source
`ef149f041fc8a28faeb29ef4954a8212d0704bf1`, saved 175.746/138.570 ms in actual
nonoverlapping growth + clear + trim CPU on hosted run 36838525543. Clean
promotion #310 passed packaging, absent-mod startup and exact-pack 36844245188.
Those passes establish compatibility, not the remaining physical indirect gate.

Physical campaign `ferrite-phase-20261001` completed and was collected after
owned JVM exit. Controller reports original JAR/config restored. Origin:
DESKTOP-8D4B389 laptop, Oracle Java 21.0.9, Xmx6G with existing user tuning;
same JVM, two controls/two candidates plus four unmeasured same-mode primers,
exactly nine full resource generations, menu only. No world entry. JAR SHA-256:
`92a8bf9e8b2463d4a5129eb43d531ebd11b9791fe9224c1c577c41deeb7aae4c`.

| Observation | Growth calls | Owner CPU ms | Owner wall ms | Unique quads | Empty slots | Reload wall ms | GC ms | End heap bytes |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| C1 | 19 | 218.750 | 242.2611 | 479773 | 1 | 462315.7568 | 114440 | 5204173064 |
| B1 | 0 | 0 | 1.0206 | 479798 | 1048576 | 517475.7746 | 150178 | 5443586520 |
| B2 | 0 | 0 | 38.3927 | 479806 | 1048576 | 853488.1711 | 272862 | 5434425808 |
| C2 | 19 | 250.000 | 278.4167 | 479824 | 1 | 576477.7204 | 139395 | 5361736800 |

Windows CPU resolution here is 15.625 ms; zero clear/trim CPU means quantized,
not free. Actual owner saves 218.750/250 ms, growth 19→0, with 0.011% unique
spread. But candidate full reload is +55.160/+277.010 s, GC +35.738/+133.467 s
and end heap +239413456/+72689008 bytes. The mechanism retains roughly 4 MiB
empty references across the long preparation phase (bounded maximum 8 MiB).
Retained memory is a plausible indirect cost under this observed pressure;
four observations do **not** establish that all global regression or heap
increase is caused by that array. Nevertheless the physical indirect gate is
not satisfied; do not merge #298/#310 as-is or wave away both regressions as
unrelated runner variance. Retire persistent empty capacity from the active lane.

Concrete salvage premise: retain only the previous unique count as an integer,
keep original clear and trim, then reserve bounded capacity at the first quad
insertion of the next generation using the stock set method under the stock
mutex. This eliminates persistent empty storage during preparation. A new
bounded trial must include the first reserve and per-insert guard cost as well
as growth/cleanup, verify canonical representatives and stock fallback, then
pass exact-pack before any new laptop comparison. This is a different proposed
mechanism, not an accepted optimization or indefinitely disabled old option.

## Evidence and remaining lane

Local full artifacts: `C:/BootOptimBench/analysis-reload-20261001/owners-36847026289`
and `C:/BootOptimBench/artifacts/ferrite-phase-20261001-ready/results`.
Raw observation `_ns` values are nanoseconds. The original Ferrite parser
incorrectly named converted contrast values `_ns`; contrast values in that
old output are milliseconds. Tables above use correct explicit units; repair
the parser and preserve raw logs/results instead of silently relabeling history.

Sodium exact classifier is separate: #309 showed 61.868/62.940 ms CPU saved in
actual transformed pack-quad replay. Clean #312 passes automated gates and
still needs representative fast-PC visual validation. No Sodium improvement
is attributed to whole startup/reload. Pixel-query reuse is already retired
on integration (#311) for observable callback changes. Do not resume the old
interrupted 21-step laptop suite or revive retired options in combined jars.
