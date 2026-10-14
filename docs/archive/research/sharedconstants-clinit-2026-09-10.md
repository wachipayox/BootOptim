# SharedConstants `<clinit>` attribution — Agent 103 (2026-09-10)

## Scope and authority

Diagnostic-only continuation of PR #231 and PR #235. Base authority is `agent/integration-current` at `fa6df8bc8f74aae32338f521bf845a5730ac634b`. The branch inherits the already-validated #235 diagnostic harness on a commit parented directly to that integration SHA, then adds only version-pinned/fail-closed observational boundaries inside the exact Minecraft 1.21.1 `SharedConstants.<clinit>` instruction topology.

No optimization is implemented. The probe does not force `SharedConstants` or any transitive class to initialize, preparse/substitute the version, read/change Netty properties, alter `ResourceLeakDetector` state beyond the original stock call, alter Brigadier fields/provider values, change failure order, or move work to another thread. Hooks are adjacent to existing bytecode instructions and reuse the already-entered lifecycle hook class.

## Hosted exact-pack result

Profile workflow: GitHub Actions run `34487475176`, Agent 103 SharedConstants Clinit Profile #37, head `3a0a18738a6db4f5750cf810d733d22e79121322`.

Validity gates on that head were all green:

- Build #3021 (`34487475216`)
- Startup Benchmark #860 (`34487475063`)
- Exact Pack Startup Benchmark #884 (`34487475211`)
- Agent 103 SharedConstants Clinit Profile #37 (`34487475176`)
- exact pack reached main menu at 90.861 s; this is context only, not a benchmark comparison
- resource selection was exact: expected 14, observed 14, same order, reload count 1
- `bootoptim_mixin_errors=0`
- preflight resolved exactly one diagnostic ModLauncher 11.0.5, one diagnostic Mixin `0.15.2+mixin.0.8.7`, and stock SecureJarHandler 3.0.8
- the ModLauncher parser retained exactly two Bootstrap transformation requests with the established `mixin_transformed_bytes_then_classloading` contract and the same TransformingClassLoader/TCCL identity (`633915166`)
- runtime dependency resolution pinned Brigadier `1.3.10` and Netty `netty-common:4.1.97.Final`

This is a single hosted monotonic profile. It is not A/B and no measured interval is a savings estimate.

## Coarse `<clinit>` partition

The same run measured `SharedConstants.<clinit>` at **374.690150 ms**. The Agent 103 parser tiles that interval exactly, with **0 ns residual**, on the main thread and unchanged TCCL.

| Stock boundary / active use | Wall (ms) | Share of `<clinit>` | Interpretation |
| --- | ---: | ---: | --- |
| `new BrigadierExceptions()` through constructor return | **363.085394** | **96.903%** | Dominant first active use. Includes JVM initialization of `BrigadierExceptions` and transitively initialized/linkage work before its trivial instance constructor can return. |
| first `PUTSTATIC CommandSyntaxException.ENABLE_COMMAND_STACK_TRACES` | 4.883483 | 1.303% | First active use of `CommandSyntaxException`; can include its class initialization/default provider construction before the stock field write. |
| `ResourceLeakDetector.setLevel(...)` | 2.546336 | 0.680% | Includes first initialization of `ResourceLeakDetector`, including its stock property/logger initialization, before method body execution, if not already initialized. |
| char-array/constants tail before Netty call | 0.761927 | 0.203% | Straight-line local/static setup. |
| `ResourceLeakDetector.Level.DISABLED` resolve + SharedConstants field publication | 0.579978 | 0.155% | Enum first active use/linkage plus unchanged field store. |
| final `CommandSyntaxException.BUILT_IN_EXCEPTIONS` publication | 0.561460 | 0.150% | Global provider commit; order-sensitive publication. |
| entry to Netty level active use | 0.443215 | 0.118% | Prefix/static setup. |
| after stack-trace write to Brigadier construction | 0.391620 | 0.105% | Boundary overhead/straight-line work. |
| provider publication to `<clinit>` return | 0.387784 | 0.103% | Tail. |
| Netty return to CommandSyntax active use | 0.376798 | 0.101% | Straight-line boundary. |
| `Duration.ofMillis(300).toNanos()` through max-tick publication | 0.341696 | 0.091% | Deterministic calculation, but includes active-use/linkage semantics. |
| Netty-level publication to `Duration` active use | 0.330459 | 0.088% | Straight-line boundary. |

All non-Brigadier-construction segments combined are **11.604756 ms**.

PR #235's version-path result remains consistent on the same run: outer `SharedConstants.tryDetectVersion()` was 391.316864 ms, `SharedConstants.<clinit>` was 374.690150 ms, while resource open (0.140007 ms), Gson parse/stream consumption (0.236460 ms), and `CURRENT_VERSION` publication (0.338110 ms) remained sub-ms.

## Causal interpretation

Minecraft 1.21.1 `SharedConstants` performs, in stock order, `ResourceLeakDetector.setLevel(NETTY_LEAK_DETECTION)`, then writes `CommandSyntaxException.ENABLE_COMMAND_STACK_TRACES = false`, then writes `CommandSyntaxException.BUILT_IN_EXCEPTIONS = new BrigadierExceptions()`.

`BrigadierExceptions` has no substantive explicit instance constructor. Its class initializer creates the Minecraft implementations for Brigadier's built-in exception types: multiple dynamic exception objects/lambdas plus eager `SimpleCommandExceptionType` instances. The eager simple exceptions call `Component.translatable(...)`, which constructs `TranslatableContents`/`MutableComponent` objects and can therefore pull in Minecraft chat/component classes transitively on first use. Consequently the 363.085394 ms boundary is not evidence that an empty Java constructor itself costs hundreds of milliseconds; it is evidence that **the first JVM initialization/linkage closure rooted at `BrigadierExceptions` is the dominant owner in this run**.

The 4.883483 ms first `CommandSyntaxException` write is likewise class-initialization-sensitive. Brigadier's `CommandSyntaxException` statically initializes `BUILT_IN_EXCEPTIONS` with its default `BuiltInExceptions` provider before Minecraft subsequently replaces the provider with `new BrigadierExceptions()`. Moving or bypassing that first active use would therefore change class-initialization/order semantics even if Minecraft overwrites the field immediately afterward.

Netty is not material here. `ResourceLeakDetector` 4.1.97.Final initializes logger/property-derived leak-detection settings before the original `setLevel` call can execute; the measured active-use bucket is only 2.546336 ms. Its property observation time and global level write are explicitly order-sensitive and outside any safe speculative move.

## DAG

```text
Main.main
  -> before SharedConstants.tryDetectVersion
  -> JVM initializes SharedConstants
       -> resolve/publish ResourceLeakDetector.Level.DISABLED       0.579978 ms
       -> Duration/max tick + constants/char[]                      ~1.434082 ms across coarse local slices
       -> ResourceLeakDetector.setLevel active use                  2.546336 ms
            -> [if first use] ResourceLeakDetector <clinit>
                 -> logger + SystemPropertyUtil reads + leak settings
       -> first CommandSyntaxException PUTSTATIC                    4.883483 ms
            -> [if first use] CommandSyntaxException <clinit>
                 -> default BuiltInExceptions provider
       -> new BrigadierExceptions + ctor                          363.085394 ms
            -> BrigadierExceptions <clinit>
                 -> built-in Dynamic*/SimpleCommandExceptionType singletons
                 -> eager Component.translatable calls for simple exceptions
                 -> transitive chat/component initialization/linkage
            -> trivial instance construction
       -> publish CommandSyntaxException.BUILT_IN_EXCEPTIONS        0.561460 ms
       -> SharedConstants <clinit> tail                             0.387784 ms
     residual                                                       0.000000 ms
  -> SharedConstants.tryDetectVersion body
       -> version.json open/parse/object/publication                sub-ms components
```

The DAG is causal/nested; it is not a set of independently parallelizable nodes.

## Future-boundary decision

**No material order-independent frontier is demonstrated, so Agent 103 recommends no optimization from this result.**

The dominant 363 ms producer cannot currently be moved into a prepare phase: its first execution is itself `BrigadierExceptions` JVM class initialization, creates/publishes static exception singletons, invokes Minecraft component factories, and may trigger additional class initialization. Moving it earlier or to a worker would change class-initialization order, the thread executing initializers, failure timing/order, and potentially partially initialized state visible through initialization cycles. That violates the requested contract even if the final `CommandSyntaxException.BUILT_IN_EXCEPTIONS` store were left on the main thread.

The final provider `PUTSTATIC` is a natural conceptual **commit** point only, not an available optimization frontier. A future `prepare -> barrier -> commit` design would require independent proof that provider construction can be expressed as a pure producer that performs no JVM class initialization, static publication, property/logging access, callbacks, thread-sensitive work, or failure-order-visible action. This profile provides the opposite evidence for the current stock producer, so such a split is not justified here.

The tiny straight-line constant/array calculation slices are the closest thing to locally pure work, but they are sub-ms and there is no proof permitting reordering them across the external active-use boundaries under JVM class-initialization semantics. They are not actionable.

## Decision

Close this diagnostic as **attributed, no-go for optimization**. The wall is overwhelmingly the first `BrigadierExceptions` initialization closure, not version I/O/parsing and not Netty. Preserve the stock order and leave any deeper intervention to a separate diagnostic that can prove the `BrigadierExceptions` transitive initialization graph and purity without pre-initializing it.
