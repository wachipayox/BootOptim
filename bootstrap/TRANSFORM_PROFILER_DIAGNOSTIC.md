# Transform profiler diagnostic

This file exists only on the diagnostic branch for PR #42.

The profiler does not cache or skip any transformation work. It measures:

- root versus recursively nested `maybeTransformClassBytes` calls;
- inclusive and exclusive transform wall time;
- top classes, package groups and transform contexts by both metrics;
- each already-initialized ModLauncher launch-plugin callback;
- slowest plugin invocations with plugin, class, reason, phase and transform depth;
- profiler bookkeeping overhead separately.

The launch-plugin wrappers are installed from BootOptim's transformation-service `transformers()` callback, after transformation-service initialization has completed. They delegate every callback to the exact original plugin object.

Primary output markers:

- `BOOTOPTIM_TRANSFORM_PROFILE`
- `BOOTOPTIM_TRANSFORM_PROFILE_TOP`
- `BOOTOPTIM_LAUNCH_PLUGIN_PROFILE`
- `BOOTOPTIM_LAUNCH_PLUGIN_PROFILE_TOP`

This diagnostic must not be merged into production.
