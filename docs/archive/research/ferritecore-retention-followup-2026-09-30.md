# FerriteCore quad capacity: physical follow-up must not be forgotten

Current policy: retain the default-off candidate for physical evaluation; not promoted. The user explicitly requested on 2026-09-30 that this evidence remain available for future laptop testing while work continues on multipart dependency enumeration.

Candidate: [#298](https://github.com/wachipayox/BootOptim/pull/298), property `boot_optim.ferriteCoreQuadCapacity`. Final production disposition remains pending an uninstrumented gate and comparable physical evidence.

[Decision harness #304](https://github.com/wachipayox/BootOptim/pull/304), frozen head `e25b81b043236e4e2d0ce153d19cb9d0aa46dcec`, hosted exact-pack run [36772896582](https://github.com/wachipayox/BootOptim/actions/runs/36772896582), passed all 22 generation/order/pack contracts. Opposite-order in-process comparisons gave candidate-minus-control whole-bake worker CPU of **-679.951 and -250.350 ms**, reload wall of **-410.454 and -285.599 ms**, GC wall deltas **+7 and +44 ms**. These are coherent surrogate results, not laptop or initial startup savings. The earlier statement that no candidate qualified was corrected: FerriteCore alone qualified for the next final gate.

The capacity mechanism affects the following generation after clear/trim, so each measured mode needs a same-mode conditioning reload. Do not compare immediately toggled modes without their primers.

Physical campaign `inprocess-decision-20260930` was dispatched after the user's laptop reboot. It includes FerriteCore B/C/C/B with primers, along with independent Decocraft/Sodium/generated-item candidates, without entering a world. Frozen bootstrap SHA-256: `0270B07A6A403B2A295B5A84024D30B61A6547DE8B95B94B02CBF6F2B45D63D8`. It uses the observed Oracle Java 21.0.9 laptop baseline, not hosted Java 25.0.4; record that distinction. Collection and strict validation are pending; do not invent the result.

Operational memory outside Git: `C:/BootOptimBench/analysis-reload-20260930/LEER-ANTES-DE-CONTINUAR-PORTATIL.md`; artifacts `C:/BootOptimBench/artifacts/inprocess-decision-20260930`; remote results `C:/BootOptimBench/inprocess-decision-20260930/results`. Never change the running campaign's JAR/config or launch a second controller. Require original hashes restored before reuse. If the physical comparison is inconclusive, retain this entry and its reopening gate instead of silently forgetting the flag.

Previous process-level laptop control startup drift (465 to 386 seconds; repeat reload 422 to 311 seconds) prevents causal attribution of those runs. CPU, reload wall and GC must remain separate; do not sum overlapping listener times or call whole-bake CPU a critical-path improvement.
