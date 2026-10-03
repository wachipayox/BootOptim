# Ordered ZIP prefix index: physical decision and clean promotion

Decision: retain for measured provider CPU removal; clean promotion still needs
its own runtime gate. Do not claim demonstrated startup or F3+T wall improvement.
Candidate #327 replaces repeated scans for DISTINCT queries with an immutable
sorted central-directory index, restores original ordinal order, and leaves
stock filtering, name validation, warning/callback/supplier behavior downstream.
This differs from rejected repeated-query memo (#326: only6.114ms eligibleCPU)
and #142's physical ordered enumeration snapshot (still scans every entry).

Hosted2C2B37071628125@ebbe44f2: all900calls/788rows/44644outputs/900CPUvalid,
orderedpack/atlas8192x8192x2/menu/Mixin0. Provider ownerCPU control526.999542/
529.994601ms, candidate183.012503/243.835411ms. Saves343.987039/286.159190ms,
first index construction/sort/queryselection/synchronization included.

Physical frozenebbe44f2, Oracle21.0.9/Xmx6G/user tuning, fresh JVM C/B/B/C,
campaignzip-prefix-20261003-r2. Each876calls/764rows/44644outputs/876CPUvalid,
exact query/call/output workload matches; packs/options unchanged, fallback0,
29indexes/52510entries both candidates. C1/B1/B2/C2 ownerCPU781.25/500/
578.125/1062.5ms => savings281.25/484.375ms (~36/46%). Windows CPU15.625ms
quantized. OwnerwallSUM5775.9754/840.0777/1045.1825/3949.4298ms, never
critical-path seconds. Do not compare876physicalcalls to900hosted as samepack
population. First fill is included, unlike the earlier JSON steady-state trial.

Same JVM-uptime origin and main_menu endpoint C1/B1/B2/C2:
376053/396496/356332/347834ms. Candidates+20.443/+8.498s versus matched
controls, not a demonstrated end-to-end gain. GC pause sums11852.377/
24026.019/12622.976/11614.676ms: +12173.642/+1008.300ms. Menuheap3247/4099/
3186/3307MiB: +852/-121MiB. These are indirect mixed effects; no sustained
heap growth demonstrated, but do not dismiss the first GC outlier or attribute
it causally to index without allocation/lifetime evidence. Index retains52510
entry wrappers/ZipEntry/name references plus sorted arrays in29pack instances;
no parsed game/resource objects. Retention is real and not zero-cost. Clean
variant releases reference on packclose, unknown subclasses use stock.

Run validity independently checked actual argv, process creation/session,
one menu/query marker, exact workload and original cfg/JAR restoration hashes.
First campaignzip-prefix-20261003 INVALID harness/noargs, excluded completely;
#328 permanently fixed harness. No surviving Java/Prism after final collection.
Rawphysical C:/BootOptimBench/artifacts/zip-prefix-physical-20261003-r2/results,
rawhosted analysis-reload-20261003/zip-prefix-owner-37071628125.

Clean promotion from integration93386aa5 removes ALL verify/profiler paths,
default-on kill switch false. Additional safety guards: exact ZipFile runtime
class, ensure-open before selection and every Enumeration method (preserves
empty/mid-iteration closed ZIP errors), release onclose. Actual helper contract
checks2112prefixes/21916entries and threeclosedowner checks PASS. Diagnostic
semantic smoke before cleanup verified900queries/29builds/fallback0. New clean
artifact gate needed because close/lifetime guards are hardened further.
Retain segment improvement under current userpolicy, not generic cache promise.
