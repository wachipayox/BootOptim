"""Offline archive inventory and bounded dynamic-provider cache counterexample.

This is not GLSL preprocessing, effective-pack resolution or a runtime benchmark.
"""
import argparse
import collections
import hashlib
import json
import pathlib
import re
import zipfile


def counterexample():
    def run(cached):
        trace, cache, texts = [], {}, []
        for program in range(2):
            path = "minecraft:shaders/include/fog.glsl"
            if cached and path in cache:
                texts.append(cache[path])
                continue
            trace.extend(["open", "read", "close"])
            text = f"#define CURRENT_CONTENT {program}"
            cache[path] = text
            texts.append(text)
        return {"trace": trace, "texts": texts}
    stock, candidate = run(False), run(True)
    assert stock["texts"] != candidate["texts"]
    assert len(stock["trace"]) == 6 and len(candidate["trace"]) == 3
    return {"stock": stock, "candidate": candidate, "equivalent": False,
            "scope": "two shader programs, one provider identity/generation"}


def inventory(archive):
    refs = collections.Counter()
    shader_files = 0
    with zipfile.ZipFile(archive) as z:
        for name in z.namelist():
            if name.startswith("assets/") and "/shaders/" in name and name.endswith((".vsh", ".fsh", ".glsl")):
                shader_files += 1
                for match in re.finditer(r'#moj_import\s+[<"]([^>"\n]+)[>"]', z.read(name).decode("utf-8", errors="replace")):
                    refs[match.group(1)] += 1
    return {"archive": str(archive), "sha256": hashlib.sha256(archive.read_bytes()).hexdigest(),
            "shader_files": shader_files, "literal_import_sites": sum(refs.values()),
            "repeated_literal_names": {name: count for name, count in refs.items() if count > 1}}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("archives", nargs="*", type=pathlib.Path)
    args = parser.parse_args()
    print(json.dumps({"counterexample": counterexample(), "inventory": [inventory(p) for p in args.archives]}, indent=2))
