# zipfs read-only agent (experimental, not part of the mod)

A Java agent that makes the JDK zip file system open selected JARs read-only, without changing any file on disk.
It is the agent alternative to setting the Windows read-only attribute on `libraries/` and `mods/` (research entry
`docs/research/zipfs-jar-open-isWritable-2026-10-16.md`, sections 5e–8).

## What it does

It prepends one call to `jdk.nio.zipfs.ZipFileSystemProvider.getZipFileSystem(Path, Map)`:

    env = ZipfsReadOnlyPolicy.adjust(path, env)

`adjust` adds `accessMode=readOnly` only when all of these hold:

- the path ends in `.jar`;
- the caller did not pass `accessMode` or `create`;
- the JAR is directly inside a folder named `mods`, or below a folder named `libraries`.

With that flag the JDK builds the file system with `readOnly = true` and does not call `Files.isWritable`. That is
the same effect as the attribute, for the same JARs, with no disk change. Any other open is returned unchanged.

## Wiring

- `Premain-Class` is `ZipfsReadOnlyAgent`.
- `Boot-Class-Path: zipfs-readonly-agent.jar` makes the policy visible to the boot loader.
- `jdk.zipfs` is a platform-loader module here, so the agent looks it up there, then falls back to the boot loader.
- The agent gives `jdk.zipfs` read access to the boot unnamed module (`Instrumentation.redefineModule`), because named
  modules do not read unnamed modules by default.
- Only the class-file API of JDK 25 is used. No ASM or other library is bundled.

## Failure behaviour (fail open)

Any failure in the transform or in the policy logs `[WBOMBO-COMPAT] FALLBACK project=BootOptim
mechanism=zipfs-readonly-agent reason=...` and leaves the JDK code or the caller's environment unchanged, which is
stock behaviour. The first successful change logs `[WBOMBO-COMPAT] ACTIVE ...`.

## Known risks

- A mod that writes into its own JAR through the zip file system API will now get `ReadOnlyFileSystemException` on
  managed JARs. The stock behaviour would replace the JAR in place. No such writer was found (research entry, section
  6), but this is not proven for all 251 mods.
- The transform matches the private method by name and descriptor. Another JDK would not match and the agent would
  do nothing. Pandora ships a fixed JDK 25.0.1, so that is the tested case.
- A `-javaagent` may change JVM startup (CDS, class loading). Measure it, do not assume it is free.

## Build and self-test (no game involved)

Needs a JDK 25 (`javac`, `jar`, `java`). The script builds into `build/` (ignored by git):

    ./run-selftest.ps1 -Java "C:/path/to/jdk-25"

The self-test opens JARs in a temporary folder twice, once without and once with the agent, and checks the read-only
flag and that writes are rejected. The same script runs in `.github/workflows/zipfs-readonly-agent.yml` on
`windows-latest` with Temurin 25.

## Not done yet

- A launch measurement with `-javaagent` in `jvm_flags` (ABBA against stock and against the attribute). Prepared in
  the research entry, section 8; not run.
- An exact-pack CI run with the agent. Linux `isWritable` is cheap, so it would only prove that the game reaches the
  menu and nothing breaks.
