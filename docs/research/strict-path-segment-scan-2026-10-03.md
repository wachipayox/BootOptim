# Strict resource-path segment scan — 2026-10-03

Status: candidate default OFF, owner corpus replay and exact-pack runtime gate pending.
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

Candidate property boot_optim.strictPathSegmentScan=true (defaultfalse). Global
stock grammar equivalence is proven, not arbitrary othermods changing this method's
body while retaining the Pattern. This compatibility surface remains a promotion
risk; other wrappers that delegate remain ordered, but interior modified validation
logic can be bypassed. Do not promote on CI alone or claim universal mod semantics.

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
