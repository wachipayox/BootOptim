# EMF animation-expression parsing, ported from RRLS — 2026-10-16

Entity Model Features parses every entity model's animation expressions on each resource reload
(initial load and every F3+T): `EntityRenderers.createEntityRenderers` took 1.35 s in the initial
load and 1.1–1.2 s per manual reload on the fast PC (2026-09-27). The RRLS fork carried a set of
cheaper equivalents for that parsing, gated on a marker file **and** EMF exactly 3.2.4. By the
2026-10-16 user decision, optimizations live in BootOptim and must not be version-pinned, so
they are ported here with runtime guards. The RRLS copies will be removed when this is integrated
or rejected.

## Evidence carried over (RRLS, fast PC, 2026-09-27, serial runs, not a randomized A/B)

From the archived [RRLS/FancyMenu early-menu record](../archive/research/rrls-fancymenu-early-menu-2026-09-24.md),
sections "parser traversal physical evidence" and "preparation text physical result":

- Expression parse, exclusive wall per manual reload: 409/434 → 245/257 ms (about -40 %, about
  -170 ms). Initial load: 475 → 328 ms.
- Animation-text preparation (`processAnimAndKeyString`): 148/151 → 39/42 ms at the same call count.
- Not carried over: RollingReader builder reuse (its safety depends on the builder never escaping,
  which no runtime check can confirm); directory positive-resource reuse and JEM Gson reuse
  (rejected or of limited value in RRLS); the ASM class cache (left for the laptop A/B by user
  request); the existence index (#363).

## Mechanism (`EmfExpressionParsing`, default on)

All mixins are `@Pseudo`, `remap = false`, `require = 0`. They target methods and call sites, not
a version. Kill switch: `-Dboot_optim.emfExpressionParsing=false`.

| Part (`mechanism=`) | Target | Change | Guard |
|---|---|---|---|
| (no id) | `MathExpressionParser.readVariableOrConstant`, the four variable factories' `createsThisVariable` | `String.matches(regex)` → a cached `Pattern` for that regex | None needed: the JDK defines `String.matches` as `Pattern.compile(regex).matcher(s).matches()` |
| `emf-parser-numeric-probe` | `readVariableOrConstant` → `Float.parseFloat` | A token whose first non-blank, non-sign character cannot start a number throws a stackless `NumberFormatException`, which EMF catches to read the token as a variable | Sampled calls run `parseFloat` and must see it throw |
| `emf-parser-characters` | `MathExpressionParser.<init>` → `Stream.iterator()` | Iterate `originalExpression` directly instead of a boxed `chars()` stream | Sampled calls drain the stock iterator and compare it with the string |
| `emf-parser-operator-pass` | `optimiseTheseActionsIntoBinaryComponents` | When none of the pass's operators is present, return the input (stock does too, after building a stream) | Sampled calls run stock and require the same list identity back |
| `emf-method-arguments` | `MathMethod.getArgsList` | Same split with `charAt` instead of a boxed stream | Sampled calls compare the lists |
| `emf-asm-variable-index` | `ASMVariableHandler.getAndAssignVarIndex` | One `indexOf` instead of `contains` + `indexOf` | Sampled calls predict the index without mutating, run stock and compare |
| `emf-asm-variable-validation` | `ASMVariableHandler.verifyEndOfParse` | Float/bool overlap via a `HashSet`; an overlap still runs stock (its message and exception) | Sampled calls run stock and require it not to throw |
| `emf-animation-line-index` | `EMFAnimationHandler.getLine`, scoped to `ASMAnimationHandler.finishAndValidate` | Index the lines by key once per binding pass (first duplicate wins, as in the scan) | Sampled calls run the scan and compare identities |
| `emf-animation-text` | `EMFJemData.processAnimAndKeyString` | Strip `\s` by hand and skip the `this`/`part` regex when the word is absent; the same compiled patterns otherwise | Sampled calls run stock and compare |
| `emf-directory-suffix` | `EMFDirectoryHandler` → `String.replaceFirst(name + "$", file)` | Literal suffix swap when the name is a plain word and the replacement has no `$`/`\` | Sampled calls compare with stock |

Guard: the first 64 calls of each part and 1 in 512 afterwards run the original code too. A
mismatch disables that part only and logs `[WBOMBO-COMPAT] FALLBACK`; `ACTIVE` is logged after 64
confirmations.

## Gates

1. Hosted exact-pack paired A/B (candidate `true`, control `false`): every part ACTIVE, 0
   FALLBACK, menu reached. Segment: the startup reload, where EMF builds every entity renderer
   once (`EntityRenderers` / `EntityRenderDispatcher` apply).
2. Fast-PC in-world F3+T A/B, when the PC is free.

## Results

Hosted exact-pack paired run [38889138409](https://github.com/wachipayox/BootOptim/actions/runs/38889138409),
fixture `legacy` (its `options.txt` enables the user's resource packs; EMF 3.2.4), 3 pairs, commit `31776f25`:

- Gate 1 passed: in all 3 candidate runs the nine parts logged `ACTIVE`; 0 `FALLBACK` in any run;
  all 6 runs reached `main_menu`.
- Wall time, candidate − control per pair: `startup_total` −915 / −207 / +1130 ms (median −207);
  `reload_to_fancymenu_finish` −1006 / −259 / +1107 ms. Hosted noise (±1 s) is larger than the
  expected effect (~0.2 s per reload), so the hosted run neither confirms nor refutes the gain.
- The earlier runs on Beta revisions (`rev_7d5b9e50…`, `rev_0abae9be…`) do not exercise the parser:
  their `options.txt` enables only `fabric`, so no part reaches 64 verified calls.


Fast PC, in-world A/B (2026-10-16), Beta instance with the players' resource packs enabled for the series (Fresh Animations and addons; the legacy fixture's list). BootOptim `71e934ea` (CI build), RRLS #10 + auto-world-entry diagnostic, `renderer-emf-phase-trace` on in both arms. 1 primer + 4 control (`-Dboot_optim.emfExpressionParsing=false`) + 4 candidate runs, interleaved; each run starts, opens the world and does one automatic F3+T.

- Gate 2 passed: the nine parts `ACTIVE` in every candidate, none in controls, 0 `FALLBACK`; every run reached the menu, joined the world and finished F3+T.
- Same work in both arms: 98,510 `expression-parse` calls per reload.
- `EntityRenderers.createEntityRenderers` wall per reload (medians, ms; range in brackets):

| Reload | Control | Candidate | Δ |
|---|---|---|---|
| Startup | 1785 [1664–1966] | 1166 [1033–1370] | **−0.62 s** |
| In-world F3+T | 1738 [1332–2335] | 754 [715–1016] | **−0.98 s** |

  Exclusive `expression-parse` inside it: startup 985 → 376 ms, F3+T 970 → 258 ms; `jem-prepare` (includes the animation-text pass) about 150 → 50 ms.
- End-to-end F3+T (17–18 s) and time to menu (52–55 s) do not separate from run-to-run noise (±1.5 s) with 4 pairs; ctl1 is an outlier (slow world entry) and is excluded from those two.
- Caveat: the phase trace times every parse call (same overhead in both arms), so absolute numbers are inflated; the earlier RRLS measurement (2026-09-27, without per-call tracing on that segment) was 409 → 245 ms. The difference between arms is the figure that matters.

Conclusion: the port does what the RRLS experiment did, without the version pin, and the guards stayed silent on the real pack.
