# Offline Palladium constructor audit

Requires JDK 21 (`java`, `javac` on PATH), Python, the pinned Palladium 1.1.8
binary, Guava 32.1.2-jre and failureaccess 1.0.1. Does not launch Minecraft or
write to its instance. Temporary extracted classes are removed after the run.

```powershell
python scripts/palladium-constructor-audit/run.py --palladium '<installed JAR>' --guava '<guava JAR>' --failureaccess '<failureaccess JAR>'
```

Expected: 20,220 equivalence cases pass and stock one-component join reference
identity is false. The candidate is an offline in-place algorithm only; there
is no runtime hook, timing claim or shipping optimization. Read the linked
[research decision](../../docs/research/palladium-constructor-shortcuts-2026-10-02.md)
for stubs, exact scope and reopening criteria.
