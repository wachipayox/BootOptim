# Ferrite late presize: final physical decision

**RETIRED, not integrated**. This explicitly supersedes the earlier segment
retain/final-gate-pending disposition for#315/#316/#317. The separate older
persistent empty-storage retirement remains unchanged. No default-off option
is retained and no further identical campaign is queued.

Frozen source9372b1f68861e93928505266da43d1abbc116f89, PUSH Build36887626259,
packaged SHA256F1B1A3805091F2DEE8A18106FE9979217B11569E142735359FED5E85002AB29D.
Physical DESKTOP-8D4B389 configured Oracle21.0.9, original user heap/GC baseline.
Menu only; nine generations: initial warmup and four same-mode primers, then
two controls/two candidates C1/B1/B2/C2. Each measured boundary is reload
invocation→future completion; launcher/staging/primers excluded. No per-insertion
CPU reads. Explicit growth_cleanup scope; insertion CPU/wall=-1 unmeasured.

Campaignferrite-presize-20261001 completed2026-10-01T18:37:50Z, collected after
owned process exit. Resource selection/order, nine generations, four observations,
same-mode masks/primers, equal1,250,500insertions, unique479773..479806 and final
one-slot/no-key lifetime validate. Both candidate reservations execute once;
growth19/1/1/19. Automatic original JAR/config restoration reports success.
Raw evidence and strict checker result:
`C:/BootOptimBench/artifacts/ferrite-presize-20261001-ready/results`.

| Observation | Growth+cleanup CPU ms | Reload wall ms | GC ms | End heap bytes |
| --- | ---: | ---: | ---: | ---: |
| C1 | 234.375 | 636700.4574 | 96964 | 5261653736 |
| B1 | 15.625 | 690773.4308 | 165437 | 5587515672 |
| B2 | 0 | 985671.4158 | 202447 | 5558930112 |
| C2 | 343.750 | 708178.4210 | 209069 | 5060654768 |

Primary segment improves in both opposite-order contrasts: CPU−218.75/
−343.75ms and wall−229.1548/−322.589ms. Windows thread CPU is quantized in
15.625ms increments; candidate0 does not mean free. Cheap scope measures only
growth+stock clear/trim. Prior full-owner hosted#315 covers insertion guard:
CPU−225.986303/−165.729526ms. Separate sparse hosted#317 CPU−177.986879/
−168.285905ms; neither hosted population is physical hardware equivalence.

Then indirect observations: whole reload+54072.9734/+277492.9948ms,
GC+68473/−6622ms, endheap+325861936/+498275344bytes. GC and whole-wall do not
track each other consistently; neither proves that all global regression is
caused by presize, nor that the heap difference is retained table data. Both
tables are actually trimmed to one slot. The valid data nevertheless fails the
finite low-end indirect-cost gate: there is no demonstrated physical end-to-end
benefit or controlled assurance that the altered allocation timing is harmless.
The conservative product decision is retirement, **not** a claim that the
primary CPU saving is false or that global delays have been causally attributed.

Clean#316 builds/absent-mod startup/hosted exact pack and user's world/F3+T
visual test passed; those correctness gates do not override this final decision.
Clean PC reservation479042 succeeded, with no new resource/Mixin failures.
Production integration never received the helper/mixins/profiler; existing
Sodium, Decocraft quarter-turn/direct-generated and indexed variants stay intact.
Close the promotion/diagnostic lanes and remove them from active experiment
queues, retaining source/history/raw evidence.

Reopen only with a concrete changed allocation/scheduling premise and evidence
that separates heap/page-pressure effects from unrelated drift. Do not launch
another identical nine-generation test, resurrect persistent empty storage, or
keep an indefinite disabled property. No other executable laptop candidate is
currently prepared/validated; prefix IO audit#318 is semantic-only, not a
performance-ready game diagnostic. Saving credits/time takes precedence over
inventing unnecessary runs to keep the laptop occupied.
