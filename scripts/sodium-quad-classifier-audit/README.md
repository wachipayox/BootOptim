# Exact-binary Sodium classifier audit

See `docs/research/sodium-quad-classifier-independent-audit-2026-09-30.md` for scope and results.

```text
python scripts/sodium-quad-classifier-audit/run.py --sodium-jar <installed-sodium-wrapper-or-inner.jar> --candidate-source-dir <candidate-source-directory> --semantic-only
```

Candidate source directory must contain `SodiumQuadFlagClassifier.java` and `QuadCoordinateView.java`. The harness executes the pinned binary baseline; it does not reimplement its arithmetic. Remove `--semantic-only` to run the synthetic paired warmed thread-CPU microbenchmark. No Minecraft instance is launched or edited.
