# Shader input reuse audit — 2026-10-02

Disposition: **REJECTED for generic cross-program include-text caching**.
No runtime code, new diagnostic, game run or laptop campaign is added.
Base: integration `411e17cbfe4739cdcab14bf14321c7901cb1c775`.

## Historical and source gates

Read both bodies and results of PRs #114, #118 and #197. No active shader
branch was found. Integration contains neither the #197 probe nor a generic
shader input cache. PR #197 is closed without merge: exact Flywheel 1.0.6
constructs one `ShaderSources` after the preparation barrier, same manager,
on Render thread. Hosted source construction measured **45.013 ms wall**
nested in **73.921 ms** commit. Its unsupported prepared-state handoff remains
NO-GO; this audit does not repeat it or move GL to workers.

Physical #118 capability probes measured 155.418 ms wall / 62.500 ms owner CPU;
these intentional GLSL version probes are not repeated resource preprocessing.
The historical shortlist's 0.20–0.21 s whole apply budget is not pure-source
CPU or promised savings. LevelRenderer's separate physical 1,458.248 ms
outline load includes only 171.875 ms owner CPU and does not establish read CPU.

Source checked:

- Mapped vanilla joined 1.21.1 JAR SHA-256
  `32dd4c8129921eb4cf693ea9d74fcabc2679878cf259a0be8e1dd0e83a906805`:
  `javap -p -c ShaderInstance$1` verifies one `importedPaths` set per
  preprocessor, then provider `openAsReader`, `IOUtils.toString`, close,
  and IOException logging/error text. This is **vanilla reference bytecode**,
  not the fully transformed live pack.
- Existing NeoForge-patched decompilation from generation-ownership audit,
  `ShaderInstance.java` SHA-256
  `C2F54FF84BFD44B8D6A796E9FC9EA48CC5450040E8355BE0750D437AF14DA08A`:
  preserves the same reader lifecycle but resolves each include through
  `ClientHooks.getShaderImportLocation`. No assumption of vanilla-only
  path handling is permitted. This local artifact is supporting source
  context, not a newly verified deployed transformed class fingerprint.

## Candidate and offline decision

Duplicate includes within **one compilation** are already suppressed by stock.
The apparent remaining opportunity is sharing decoded include text across
programs in one reload. A manager identity plus resource path plus generation
does not establish immutable content or a side-effect-free supplier. Each
fresh preprocessor can observe another read, stream-close failure, modified
content, or provider callback. A cache hit suppresses all those observations.
Caching errors additionally changes logs and retry behavior; caching processed
GLSL risks per-compilation version/directive state.

`tools/research/shader_input_audit.py` provides a bounded **abstract contract
counterexample**, not an actual-game equivalence checker: two compilations,
one provider/generation/path, changing successful source content. Stock produces
`CURRENT_CONTENT 0`, then `CURRENT_CONTENT 1`, with six open/read/close events;
the proposed cache returns `0` twice and only three events. It fails before
the cost gate; no hosted or physical runtime is justified for this generic
candidate.

The same tool inventories literal imports in an archive. On the reference
vanilla JAR it found 129 shader files and 90 import sites: `fog.glsl` appears
70 times and `light.glsl` 18 times. **These are static textual occurrences,
not live imports or avoidable operations**: comments, conditionals, program
reuse, NeoForge resolution, mod/resources override and stock dedup can change
effective execution. The count is not performance evidence or effective-pack
selection and cannot justify a cache.

## Reopening conditions

Reopen only with exact-source attribution of material repeated decode CPU and
an owner-supported immutable bytes/source interface preserving supplier/error/
close callbacks, current pack priority and fresh per-compilation preprocessor
state. Content hashing after every stock read preserves more semantics but
still pays I/O/decoding and needs evidence that hashing is cheaper than the
pure work removed. No generic shader cache remains pending or experimental.
Other shader algorithms remain possible; this decision retires this mechanism,
not all shader optimization.
