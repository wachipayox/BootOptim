#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(sys.argv[1])
path = root / "src/main/java/net/neoforged/accesstransformer/parser/AccessTransformerList.java"
src = path.read_text()
old = '''        final HashMap<Target<?>, AccessTransformer> localATCopy = new HashMap<>(accessTransformers);\n        mergeAccessTransformers(accessTransformVisitor.getAccessTransformers(), localATCopy, stream.getSourceName());\n        final List<AccessTransformer> invalidTransformers = invalidTransformers(localATCopy);\n        if (!invalidTransformers.isEmpty()) {\n'''
new = '''        final List<AccessTransformer> parsedAccessTransformers = accessTransformVisitor.getAccessTransformers();\n        final HashMap<Target<?>, AccessTransformer> localATCopy = new HashMap<>(accessTransformers);\n        mergeAccessTransformers(parsedAccessTransformers, localATCopy, stream.getSourceName());\n        final List<AccessTransformer> invalidTransformers;\n        if (Boolean.getBoolean("boot_optim.atTouchedValidation")) {\n            final boolean touchedInvalid = parsedAccessTransformers.stream()\n                    .map(AccessTransformer::getTarget)\n                    .map(localATCopy::get)\n                    .anyMatch(at -> !at.isValid());\n            invalidTransformers = touchedInvalid ? invalidTransformers(localATCopy) : Collections.emptyList();\n        } else {\n            invalidTransformers = invalidTransformers(localATCopy);\n        }\n        if (!invalidTransformers.isEmpty()) {\n'''
if src.count(old) != 1:
    raise SystemExit("exact AccessTransformerList validation block mismatch")
path.write_text(src.replace(old, new))

# Make archive output deterministic for the candidate provenance gate only.
build = root / "build.gradle"
b = build.read_text()
needle = "build.dependsOn testsJar\n"
extra = '''build.dependsOn testsJar\n\ntasks.withType(AbstractArchiveTask).configureEach {\n    preserveFileTimestamps = false\n    reproducibleFileOrder = true\n}\n'''
if b.count(needle) != 1:
    raise SystemExit("build.gradle archive hook mismatch")
build.write_text(b.replace(needle, extra))
