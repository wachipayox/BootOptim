# JEI index edge-promotion audit — 2026-10-02

## Authority and scope

Native bounded subagent audit requested by the user. Refreshed integration
`411e17cbfe4739cdcab14bf14321c7901cb1c775`; isolated branch
`codex/jei-index-audit-20261002`. Read AGENTS, README, research/catalog indexes,
the September 30 original shortlist, and the RRLS/FancyMenu research ledger.
Open/closed JEI-related PR screening included #131, #216, #238 and #79 bodies
and #216 runtime results. No dedicated open JEI index rewrite branch was found.
The old private RRLS work already performs scoped text/token/intern changes:
this audit must not recreate those mechanisms in BootOptim.

Current BootOptim integration source contains no JEI index optimization;
the deployed RRLS lane is separate. Exact installed JEI binary:
`19.27.0.340`, SHA-256
`8AAF547432F1B4958239B036356B910692FE40F858C3073D996F56BBF7C99826`.
Bytecode authority was the user's local enabled JEI JAR, read only. The
offline checker uses Minecraft's fastutil 8.5.12 and Java 21.0.4.

## Existing budget, not predicted savings

The September 30 fast-PC diagnostic attributes JEI ordered apply to
456–597 ms. Earlier activated suffix-local-text generations measured the
original suffix put work at 186/163 ms for 56,599 true key insertions and
474,686 characters. Full index was 529/438 ms, tooltip providers 257/211 ms.
Those nested historical wall scopes are not additive, not current CPU
measurements, not A/B economic evidence, and not a guaranteed saving ceiling
for the narrow operation below. No new physical or hosted timing was run.

## Concrete new redundancy

Exact `Node.addEdge` switches solely on current edge-map size. At size 8 it
constructs a `Char2ObjectOpenHashMap` from the previous map and then inserts
the new edge, even when replacing an existing edge. If replacement leaves
size 8, subsequent replacements clone an already-promoted hash map again.
Replacing that operation with direct `put` appears to remove allocation and
copying without changing the character-to-node mapping.

However, the exact suffix tree exposes ordered search callbacks:
`Node.getData` recursively iterates `edges.values()`. Cloning fastutil maps
can alter collision placement and therefore traversal order. Preserving only
the mathematical set of results is insufficient under this project's strict
callback-order contract.

## Exact-binary counterexample: reject clone skipping

`tools/research/JeiEdgePromotionCheck.java` loads the original installed Node
and SubString classes; it does not emulate their construction. Both arms
insert eight characters and replace an existing edge once to promote the
array map. On the next replacement, stock invokes real `Node.addEdge`, while
the hypothesis arm invokes real map `put` without the clone. Seed 20261002
finds a counterexample at trial 1 (UTF-16 character codes):

```text
before    [166, 69, 161, 36, 150, 187, 92, 199]
stock     [69, 166, 161, 36, 150, 187, 92, 199]
skipClone [166, 69, 161, 36, 150, 187, 92, 199]
```

The checker also asserts stock changed the map object, confirming a real
clone. Different values traversal is a source-proven different recursive
callback sequence when children hold distinct payloads. Runtime frequency,
exclusive CPU and practical impact of this clone remain unmeasured. A
synthetic counterexample proves a correctness boundary, not performance.

Reproduce with `javac -d <output> tools/research/JeiEdgePromotionCheck.java`,
then `java -ea -cp <output>;<exact-jei-jar>;<fastutil-8.5.12.jar>
JeiEdgePromotionCheck` on Windows. Both paths are caller supplied; no game
installation or network dependency is needed.

## Other boundaries and decision

`LimitedStringStorage` already indexes each unique text once and keeps
shared identity-set payloads. `SetMultiMap.get` exposes a live unmodifiable
view; replacing it with a snapshot would change later ingredient additions.
`ElementSearch.addAll`'s UID pass precedes prefix/tooltip processing. Naive
one-pass rebuilding, generic duplicate-key caching, caching tooltips, or
moving arbitrary providers to workers are therefore not new safe candidates.

**NO-GO for skipping the eight-edge hash-map clone.** No runtime patch,
diagnostic property, pack mutation, game run, laptop access or CI campaign
is added. The general JEI algorithm front is not declared exhausted.

Reopen this narrow operation only with (1) exclusive actual-runtime cost and
replacement frequency, and (2) an implementation that preserves exact
collision/traversal/callback ordering and mutable payload visibility. A new
search representation would require comparison of ordered callbacks on the
actual index API, dynamic additions, Unicode, failures and retained memory,
not only equivalent result sets or fewer allocations.
