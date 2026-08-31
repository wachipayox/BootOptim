# ModLauncher writer-tail diagnostic

This subproject is a standalone **profiling-only javaagent** for the exact runtime used by the target pack:

- ModLauncher 11.0.5
- Fabric Sponge Mixin 0.15.2+mixin.0.8.7

It does not optimize or skip any transformation work.

## What it measures

The agent instruments two exact methods before they are loaded:

1. `MixinTransformationHandler.processClass(...)` to record whether Mixin returned `true` for a target. ModLauncher's default `processClassWithFlags` maps that result to `COMPUTE_FRAMES`.
2. `ClassTransformer.transform(...)` around the original calls to:
   - `ClassNode.accept(ClassWriter)`
   - `ClassWriter.toByteArray()`

Only classes for which Mixin returned `true` are aggregated into the primary tail totals. The original calls and their return values are untouched.

Marker:

```text
BOOTOPTIM_MODLAUNCHER_TAIL
```

Primary summary fields:

```text
classes=...
accept_ms=...
to_bytes_ms=...
tail_total_ms=...
```

The output also groups by final ModLauncher flags and emits top-20 classes by total, `accept`, and `toByteArray` time.

## Real-pack launch

Build the agent with:

```text
./gradlew :tailagent:jar
```

Then add the produced jar as a temporary JVM agent:

```text
-javaagent:/absolute/path/to/bootoptim-modlauncher-tail-agent-<version>.jar
```

### Windows launcher note

Some Minecraft launchers split a free-form JVM-arguments field before invoking `java`. A `-javaagent:` path containing spaces can therefore be broken into multiple JVM arguments before Java starts, which produces a pre-logging launch failure.

For the real-pack diagnostic, use a temporary path with **no spaces** to remove launcher-specific quoting from the experiment. For example, copy the built jar to:

```text
C:\bootoptim-tail-agent.jar
```

and add exactly:

```text
-javaagent:C:\bootoptim-tail-agent.jar
```

Do not add a backslash before the colon in `-javaagent:`. Quoted paths may work with some launchers, but a no-space path is the required reference procedure because it is launcher-agnostic.

Keep the normal BootOptim experimental bootstrap/mod jar in the pack as usual. The javaagent is separate and should only be used for this profiling experiment.

Do not use this agent with other ModLauncher/Mixin versions; it fails closed when the code source does not match the supported runtime.
