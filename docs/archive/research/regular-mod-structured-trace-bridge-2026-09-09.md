# Regular-mod structured trace bridge — 2026-09-09

Status: **ACTIVE DIAGNOSTIC / NO PERFORMANCE CLAIM**

Authority at assignment start: `agent/integration-current` @ `fa6df8bc8f74aae32338f521bf845a5730ac634b`.

Implementation PR: #212, intentionally stacked on #202 -> #201 -> #200 until that diagnostic stack is resolved.

## Question

Can regular GAME-layer producers (resource reload / ModelManager in later work) append to the same `StructuredBootTrace` sequence/file as bootstrap SERVICE producers without relying on accidental classloader identity, duplicating the writer/schema, or reintroducing #198's dual-trace architecture?

## Prior evidence and classloader boundary

PR #204 established two facts that must be kept separate:

1. the packaged bootstrap wrapper contains one physical `StructuredBootTrace.class` in the outer SERVICE/library JAR and the nested regular-mod JAR contains no trace classes;
2. ModDev's development layout is not the packaged layout: `bootstrap.sourceSets.main` compiles the trace-core source tree directly into the SERVICE output, while the root regular mod previously also declared `implementation project(':trace-core')`.

Therefore source equality was not a static-singleton identity proof. A Java `static` belongs to a loaded `Class<?>`, and two modules/loaders may define the same source independently. Existing #201/#202 producers were safe because they were bootstrap-side; a future direct regular call to `StructuredBootTrace.global()` was not proven.

The rejected architecture in #198 is not reopened: this PR does not add a second trace class, writer, schema, sequence or output path.

## Design decision: SERVICE-owned local JMX bridge

The trace owner remains the bootstrap SERVICE layer. The regular mod removes its `:trace-core` dependency entirely.

When and only when `-Dboot_optim.bootTrace.mode` is not `off`, `EarlyStartupProbeService` publishes a `DynamicMBean` in the JVM's platform `MBeanServer` under the version-pinned name:

`dev.wachipayox.bootoptim:type=StructuredBootTraceBridge,version=1`

Protocol identifier:

`bootoptim.boottrace.bridge/1`

The bridge object is bootstrap-owned and delegates all operations to that layer's one `StructuredBootTrace.global()`.

The regular `RegularBootTraceBridge` depends only on JDK/JMX classes. It looks up the object name and protocol and invokes coarse operations through `MBeanServer`. It never imports, loads or reflectively resolves `StructuredBootTrace`.

This makes the cross-layer contract depend on JVM-global JDK infrastructure rather than on ModLauncher parent delegation, module readability, context-classloader choice, or source-set coincidence.

### Exposed operations

The v1 bridge exposes the existing schema semantics rather than copying them:

- `beginTask(...) -> task id`
- `endTask(...)`
- `record(eventType, ...)`
- `recordProbe(phase, detail)` for the bounded classloader/ownership proof

The writer still owns task ids, event sequence, event enum parsing, bounded buffer, JSON encoding, header/summary and flush.

A later resource/ModelManager diagnostic may call this bridge from the regular mod, but must not change reload executors, barriers, callback order, owner threads or GL/render ownership merely to make tracing convenient.

## Default-off and failure semantics

- `off` remains the default.
- SERVICE does not create/register the MBean when trace mode is off.
- regular callers first inspect the trace-mode property; off calls return immediately.
- missing/incompatible bridge, JMX failure or invocation failure is swallowed and permanently disables further regular bridge attempts in that caller class.
- no tracing failure can abort mod construction, reload or gameplay.

The bridge is diagnostic infrastructure; profile/development timing is not optimization evidence.

## Development and packaged ownership proof

### Static/package proof

The root regular mod no longer depends on `:trace-core`. Build validation requires:

- exactly one outer `dev/wachipayox/bootoptim/trace/StructuredBootTrace.class` in the packaged bootstrap wrapper;
- the nested regular mod contains `RegularBootTraceBridge.class`;
- the nested regular mod contains zero `dev/wachipayox/bootoptim/trace/` classes.

Thus packaged runtime has one physical trace implementation and one owner.

### Cross-classloader unit proof

`StructuredBootTraceBridgeTest` loads the built regular-mod JAR with a new `URLClassLoader` whose parent is only the JDK platform loader. The test asserts that this regular bridge classloader is not the bootstrap trace classloader, invokes the regular probe through JMX, and expects one JSONL document with exactly one header, one `mod_callback` event and one summary.

This test intentionally proves the bridge contract without giving the regular loader visibility to BootOptim's trace classes.

### ModDev exact-pack proof

PR #212 requests a hosted exact-pack profile smoke. The runtime acceptance criteria are:

1. startup reaches `main_menu` with no BootOptim/Mixin/classloading error;
2. exactly one `bootoptim.boottrace` v1 header and one summary exist in `run-pack-benchmark/logs/bootoptim-trace.jsonl`;
3. inherited bootstrap producers (#201 Discovery and #202 gather) remain present/balanced;
4. a `mod_callback` event with phase `regular_mod_entrypoint` is present from the regular GAME-layer caller;
5. sequence numbers are single and monotonic, with zero duplicate header/writer, zero dropped events and zero flush errors.

Hosted timings are observability only and must not be interpreted as TTMM improvement/regression.

## Ownership contract for future resource/model producers

A future regular producer is allowed only if all of these remain true:

- it calls `RegularBootTraceBridge`, never `StructuredBootTrace` directly;
- root regular code keeps no runtime `trace-core` dependency;
- the bridge protocol/object name remains version-pinned and incompatible versions fail open;
- SERVICE remains the only sequence/task-id/file owner;
- no producer adds a writer/schema copy or a second output file;
- instrumentation preserves existing resource scheduling, listener ordering, barriers, callbacks, classloading, render/GL thread ownership and gameplay;
- high-frequency resource events require a separate overhead/contamination review before use; this bridge is initially intended for coarse task/barrier/commit boundaries.

## Decision gate

If Build/classloader tests and the exact-pack profile smoke satisfy the criteria above, the architecture is **GO for coarse regular-mod producers** and unblocks later DAG instrumentation around resource reload/ModelManager.

If the regular probe is absent, a second header/summary appears, the regular JAR regains trace classes, or startup/classloading changes, the decision is **NO-GO** until the ownership contract is repaired. No fallback to #198-style dual writers is permitted.
