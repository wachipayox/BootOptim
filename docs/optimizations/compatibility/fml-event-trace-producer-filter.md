# FML event TRACE producer filter

- **Scope:** NeoForge/FML `net.neoforged.fml.ModContainer` logger (validated FML 4.0.43 /
  NeoForge 21.1.248, not version-pinned).
- **Default:** enabled; `-Dboot_optim.fmlEventTraceProducerFilter=false` restores stock.
- **Mechanism:** a Log4j configuration-level filter denies `TRACE` from that logger before the
  message is built, installed only when a probe proves that no appender of the logger chain would
  write it. Event dispatch, other loggers and levels are unchanged.
- **Compat:** `[WBOMBO-COMPAT] ACTIVE mechanism=fml-event-trace-producer-filter`; FALLBACK when
  the logger is missing, Log4j core is not the factory, or an appender would write TRACE.
  Reconfiguring Log4j drops the filter (stock behaviour).
- **Evidence:** [research entry](../../research/fml-event-trace-producer-filter-2026-10-15.md):
  fast PC, post-menu reload freezes −49 %, F3+T −1.6 s; no `main_menu` claim.
