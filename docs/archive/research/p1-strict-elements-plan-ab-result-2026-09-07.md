# P1 strict-elements material plan A/B result — 2026-09-07

Status: **rejected as a performance candidate; retained only as research**.

The opt-in property `-Dboot_optim.compiledElementsMaterialPlan=true` was compared with the exact-pack control on fresh hosted VMs, three repetitions per side. The candidate reached the main menu in every run and reported zero BootOptim Mixin errors; atlas dimensions and resource selection were unchanged.

| variant | main-menu ms | reload→FancyMenu ms | panorama ms |
| --- | ---: | ---: | ---: |
| candidate 1 | 91,428 | 42,309 | 4,057.7 |
| candidate 2 | 92,288 | 43,207 | 4,503.9 |
| candidate 3 | 91,081 | 42,705 | 4,739.8 |
| control 1 | 85,479 | 38,583 | 3,800.9 |
| control 2 | 89,540 | 40,072 | 4,439.2 |
| control 3 | 90,191 | 40,994 | 4,278.3 |

Medians were 92,288 ms candidate vs 90,191 ms control for time-to-main-menu (**+2,097 ms**), and 43,207 vs 40,994 ms for reload→FancyMenu (**+2,213 ms**). Panorama timing differed by only +301 ms. The plan therefore does not demonstrate a critical-path win and remains disabled by default; no laptop run is justified for this premise.

The result does not prove that every structural representation is impossible. It rejects this first direct material-plan implementation under the current exact-pack gate. A future P1 attempt must explain and remove its added overhead (plan construction/branching/allocation) before another A/B, while preserving the strict fail-open domain and stock callbacks.
