# Strict resource-path segment scan — 2026-10-03

Status: RETAIN for clean production promotion, pending clean build/startup/exact-pack gates.
This final decision supersedes historical default-OFF/pending physical status below.
Base integration7331bff11f521f1d9ec505d8463ac103f2886584.

## Evidence and distinction

Read open/closed#151/#223/#257/#264/#267/#318/#329/#330/#331/#332 and actual integration.
#332 confirms49366pointqueries and984244direct PathPack calls, predominantly
misses; no callback/existence/resource-result cache is justified. Its CIT follow-up
shows11880mutable-model parses of16strings, with704samples CPU19.543ms parse/3.044ms
IOUtils read. Sharing mutablemodels or bypassing geometry adapters is not proposed.
Small hosted samples do not close the physical CPU/HDD front; see owner ledger.

Exact Minecraft1.21.1 FileUtil STRICT_PATH_SEGMENT_CHECK is Pattern[-._a-z0-9]+,
flags0. Each boolean check constructs Matcher. Replace only this pure boolean
calculation with a linear char scan when the Pattern shape matches; keep original
method chain on disabled, null or unknown-pattern paths. Emptyfalse; '.' and '..'
are true for this raw grammar, still rejected by stock decomposePath/validatePath.
No input/result/DataResult/list/path/cache retained by the optimization. Original
resource providers, filters, lookup order, IO and mutable list allocation unchanged.
This removes a different operation from retired#331's lexical prefix reuse.

Candidate property boot_optim.strictPathSegmentScan=true (defaultfalse). The initial
containing-method bypass risk described below is addressed by the newer exact
instruction-site replacement. Do not promote from the old runtime result alone.

## Contract

Standalone tools/research/StrictPathSegmentContract.java uses actual candidate
helper against original Pattern:131930checksPASS, all65536UTF16singlechars,
all16384ASCIIpairs,50000seeded multi-char strings, empty/dot/dotdot/slash,
combining/malformedUnicode,100kchar paths/nullNPE, changedregex/flags fallback.
Compile with javac against src/main/java/.../optimization/StrictPathSegmentValidator.java;
run StrictPathSegmentContract. Local distributable bootstrapbuildPASS.

## Comparable owner gate

Separate default-off profileStrictPathSegments records original observed string,
boolean and callfrequency up to the main-menu closure. Bounded50000values/4MiBUTF16;
long>4096segments,changedPattern,activecollectors/truncation/zerocalls or>10M calls
invalidate. This capture contaminates startup; TTMM is health ONLY.

AFTER main_menu marker and BEFORE stop, validate every observed output against
stock Pattern and candidate, then warmboth outside clocks. Replay actual weighted
input corpus in same JVM C/B/B/C. Array loads prevent a constant-string innerloop
from artificially hoisting the character scan. One CPU clock per WHOLEpass, avoiding
Windows perrowtickquantization. Bothvariants write samevolatileblackhole; candidate
includes Patternshapeguard/nullbranch, not just ideal charscan. CPUonly (no wall/
allocation saving claim), GC/background contention can remain; replay is NOTactual
criticalpath or a fullstartupA/B. Parser rejects incomplete/unequal/zeroCPU results.

Hosted smoke activates candidate for actualboot AND the replay. It must pass
packselection14ordered,atlas8192x8192x2,Mixin0,menu and realnonzero corpus/CPU.
Then judge owner effect first; onlyrelated global/GCchanges later. Small hosted
or hardware-sensitive effect needs physical observed workload gate. Before
promotion, actualruntime2C/2B or netowner evidence must address wrapperoverhead/
compatibility; binaryretain/remove and strip all telemetry. No forgotten opt-in.
No laptop run yet and no other diagnostic code copied from#332.


## First hosted owner result

Exact37133837211@f9e9ab7c allPASS,14packs selection/atlas8192x8192x2/Mixin0;
actual1983segments,2499298calls,25337342characters,rejected0,inflight0/skipped0,
truncatedfalse,equivalenttrue. Whole-pass C/B/B/C CPU455.791354/48.945918/
61.328621/433.727057ms =>pairedCPUremoved406.845436/372.398436ms on sameweighted
corpus. This is owner pure-operation CPU evidence; not outerwrapper/netruntime/
allocation/criticalpath or TTMM. Startup86044ms diagnostichealth only. Raw:
C:/BootOptimBench/analysis-reload-20261003/strict-segment-37133837211/strict-segment-summary.json.

## Compatibility hardening supersedes containing-method bypass

Initial WrapMethod returned the character-scan result without invoking the original
interior. Unknown mods retaining Pattern but adding validation logic could lose
that logic. The NEW mechanism always executes original method/callback/wrapper
chain, and rewrites ONLY adjacent GETSTATIC exactPattern/ALOAD0/Pattern.matcher/
Matcher.matches into Pattern,String -> boolean helper. Actual Pattern/null/disabled
fallback preserved; unknown shape or ambiguous sites leave bytecode unchanged.
No jump target inside the replaced sequence can be removed: any intervening label,
frame, callback or changedcall breaks exact adjacency and fails open. Stack shape
unchanged, no new branches/locals. Synthetic wrapping-original method names are
allowed because descriptor+field+instruction sequence identify the exact operation.

Register IExtension during plugin onLoad/configselection, before Extensions.select.
The final postApply callback runs AFTER all ordinary mixin injectors, rather than
per-mixin postApply (which could remove an invocation another later injector needs).
Confirmed exact cachedMixin0.8.7 API and MixinProcessor/TargetClassContext ordering.
Unsupported transformer/registry remains stock; extension CLIENT only and inactive
unless candidate/profileproperty set. Other targets receive no bytecode change.
MixinExtras may move the body; scan all static(String)Z bodies, requiring oneunique
site. Original surrounding mod-like heads/tails/short-circuit branches remain.
Later external non-Mixin transformers can still alter class behavior; no universal
compatibility guarantee is claimed. Method-chain census alone no longer proves
rewrite active: NEW rewrittencounter must equal observedcalls before any replayvote.

Real ASM generated-class execution contract26checksPASS: compare stock/transformed
booleans and head/tail sideeffectcounts, custom blocked-input shortcircuit/nullNPE,
renamed original body, repeated-transform/no-site, wrongowner/descriptor/field,
ambiguouspair and interveninglabel failopen. Gradlecheck executes this contract.
Original helper grammar131930checks and local packagedbuild remainPASS. Two parser
accountingtests now reject missing/partialrewrite. No oldresult certifies newhook.
Next hosted smoke same actualpackproperties must show rewritten==calls>0, all
prior equivalent/CPU/selection/atlas/Mixin gates. No physical run before that gate.


## Hardened hosted gate and pending physical evidence
Integration 7331bff11f521f1d9ec505d8463ac103f2886584 unchanged; frozen source f42f1c7db00e90941ff85b6b88a4c21aa70c2abc. Exact37135555925 PASSED: actual calls=rewritten=2499298, 1983segments,25337342chars,equivalenttrue,rejected/inflight/skipped0,truncatedfalse. Whole-pass C/B/B/C CPU530.040450/47.873981/61.548401/427.808449ms =>482.166469/366.260048ms operation CPU removed. Replay includes Pattern guard, excludes outer wrapper/census/startup context; NO net-runtime/TTMM claim. Menu90487ms healthonly,14packs exactordered/oneinitialreload,atlas8192x8192x2,Mixin0. BuildPR37135555907/startup37135555958/pushBuild37135553037 PASS. Hardened hook verified, not merely green menu.
Frozen packaged CI bootstrap SHA D1E7DFFEFCA800FB77EC3F4E6AA9406B6E16BBE3F8EC3B11F115B52174257C6A; inner operation/plugin/reporter verified. Offline bundle C:/BootOptimBench/artifacts/strict-segment-physical-20261003/bundle READY; remote intended C:/BootOptimBench/strict-segment-20261003; NO TASK DISPATCHED and NO laptop mutation. SSH explicitkey192.168.1.139:22 timed out this turn. Need user availability/newIP beforelaunch, fresh free-process/activecampaign/Java/preflight required. Plan expected configured21.0.9, preserve user GC/6G tuning; never silently switch JVM. One menu-only startup+postmenu weighted C/B/B/C replay, autoclose/restore; no world/F3+T. Uses current333 repositoryhelpers; controller itself also atomic retry32/33, failure owned-recovery BEFORE partial-log read; all3PS syntaxPASS. Candidate/profiletrue required. No live logs during ownedJVM; metadata only; verify exactargv+endpoint+rewritten==calls+fullindependentparser+originalhashrestore afterfinish. Retain/remove decision stillpending physical mechanism evidence and net cost; no forgotdefaultOFFproductionoption. No agents, no user-PCgame changes.


## Physical result and binary disposition

Physical campaign strict-segment-20261003 FINISHED valid/restored, frozen f42f1c7d
bootstrap SHA D1E7DFFEFCA800FB77EC3F4E6AA9406B6E16BBE3F8EC3B11F115B52174257C6A.
Actual Oracle21.0.9, original user GC/6GiB settings, four processors. One fresh
startup captures the real corpus; AFTER main_menu, same JVM two controls/two
candidates C/B/B/C use the weighted corpus. Same volatile result consumer and
whole-pass CPU clocks; no Windows per-row quantization or runner comparison.
2056segments,2515897calls=rewritten,25516324chars; rejected/inflight/skipped0,
truncatedfalse,equivalenttrue. CPU C1/B1/B2/C2 1687.5/140.625/156.25/1734.375ms;
paired operation CPU removed1546.875/1578.125ms. Four Windows CPU values quantized
in15.625ms ticks, much smaller than observed effect. CPU samples are complete
passes, not extrapolated samples. No wall/allocation/criticalpath claim.

Independent parser PASS; exactly one menu378221ms diagnostichealth only,
selected14packs match fixture in order before/after, one reload, atlas8192x8192x2,
no new Mixin/reload failures. Required actualargv validated, no stale targetJVM.
Original CFG A9744BF7A4C5660F6B58A6FE1FE9309A91ECACC2AC2189BF099DD2A145AA1ECC
and original bootstrap379BC509EFD43A0D2EDF7CFB6BC1E2F99DD1601B3D8E3AB43B00C70F6DEA4989
independently rehashed after restore; no Java/Prism remaining. No world/F3+T.
Raw C:/BootOptimBench/artifacts/strict-segment-physical-20261003/results/
strict-segment-summary.json plus replay/latest.log, original/effective settings,
transaction state and GC log. Collection occurred only after JVM exit/restoration.

Decision RETAIN the exact guarded operation as deliberate CPU removal, not an
end-to-end startup improvement. This is a binary production intent; no abandoned
OFF experiment remains after promotion. Clean implementation invokes measured
StrictPathSegmentValidator.guarded DIRECTLY from the instruction site. Remove
StrictPathSegmentOperation, runtime candidate-property branch, WrapMethod census,
all maps/counters/clocks/replay/report hooks. Therefore the clean per-call entry
is the same helper and Pattern/null guard used by both measured B passes, with
no unmeasured capture/wrapper overhead to subtract. One fixed transformation
activation/fallback marker remains, not per-call telemetry. Existing external
method wrappers/callbacks run unchanged; only the unique pure four-instruction
sequence is replaced. An empty mixin anchor makes FileUtil participate in final
postApply without wrapping its method. The clean anchor/delivery must be proven
by its own exact-pack activation marker, not assumed from diagnostic #334.

Default enabled at Mixin extension selection, kill switch
-Dboot_optim.strictPathSegmentScan=false restores untouched stock instructions.
Unsupported transformer/registry or changed/ambiguous instruction shape remains
stock. No cache, retained input, provider/IO callback skip, scheduling or GL changes.
One-pass transform removes two regex methodcalls in favor of exact measured helper.
Actual generated-class26checks and grammar131930checks PASS on clean code locally.
Clean packaged build/startup/exact-pack still required before integration.

Clean local gradlew build --no-daemon PASSED (including actual ASM contract26checks and bootstrap tests); inspected bootstrap/build/libs packaged early-service wrapper and inner helper/plugin, no StrictPathSegmentBudget/Operation telemetry classes.
