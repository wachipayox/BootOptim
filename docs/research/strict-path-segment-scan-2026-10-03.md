# Strict resource-path segment scan — 2026-10-03

Status: candidate default OFF; initial owner gate passed, hardened instruction-site gate pending.
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
