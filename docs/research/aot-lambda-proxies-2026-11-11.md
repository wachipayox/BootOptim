# JDK 25 AOT cache: why mod lambda proxies are not archived — 2026-11-11

**Status: rejected (structural JDK limitation). Reopen criteria at the end.**

## Question

Before `main_menu`, a trained AOT cache serves ~25,000 classes, yet ~33,000 lambda proxy classes (`X$$Lambda/0x…`) are
still spun at run time. 32,695 of them belong to host classes that *are* served from the cache (Create `AllBlocks`,
`BdBlocks`, `SCContent`, `TFMGBlocks`… registration lambdas); only 161 lambda proxies are served. Why?

## Evidence

Origin: fast PC, Wachiland Launcher, Java 25.0.1, instance «1.21.1», one training (record) run with
`-Xlog:aot+resolve=trace,aot+class=debug,cds+lambda=trace` (log of the record + create steps, 247k lines).

- The archive holds 69k class entries tagged by loader. The built-in loaders (`boot` 1,579, `plat` 385, `app` 104, arrays
  1,975) are all **linked**. Every class of the NeoForge `ModuleClassLoader` (tag `unreg`, "unregistered") — 27,231 classes
  in the dump — is stored **unlinked**.
- Archived lambda proxies: 785 (`Archiving CP entries for …$$Lambda`), all hosted by JDK, `kotlin.reflect` and `cpw.mods.*`
  classes (built-in loaders). `AllBlocks`, `SCContent` and the other mod hosts have none.
- JDK 25's AOT linking resolves a lambda call site at dump time only for linked classes; `Skipping …: Unlinked class not
  supported by AOTClassLinking` and `Not resolved: class not linked` appear in the log. Only one explicit lambda rejection
  (`Cannot aot-resolve Lambda proxy of interface type java.lang.classfile.CodeTransform`), unrelated to mods.

Conclusion: classes of custom loaders (ModLauncher's `ModuleClassLoader`) are archived but unlinked, so the AOT cache can
neither pre-link them nor archive their lambda proxies. Nothing on the BootOptim/Pandora side (bytes, names, flags) changes
that; it depends on the JVM.

Not measured: how much wall time the 33k proxies cost (a rough bound from the per-class cost seen elsewhere is 1–3 s of CPU,
partly parallel). Nothing was implemented.

## Reopen when

- A JDK release links/archives classes of unregistered (custom) loaders under AOT (watch the AOT-cache JEPs after 483/514/515)
  — re-run the same log and check that mod `klasses[...]` lines are `linked`.
- Or mod classes can be loaded by a built-in loader (not realistic with ModLauncher).
- Raw logs are kept outside git (`aot-diag.log`, `aot-diag2.log`, wbombo evidence folder).
