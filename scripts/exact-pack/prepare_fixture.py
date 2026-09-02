#!/usr/bin/env python3
import argparse
import hashlib
import os
import re
import shutil
import zipfile
from pathlib import Path


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def identify_pack_root(extract_root: Path) -> Path:
    candidates = []
    if (extract_root / "mods").is_dir():
        candidates.append(extract_root)
    candidates.extend(
        child for child in extract_root.iterdir()
        if child.is_dir() and (child / "mods").is_dir()
    )
    unique = sorted({candidate.resolve() for candidate in candidates})
    if len(unique) != 1:
        raise RuntimeError(
            "Could not identify one exact-pack root containing mods/. "
            f"candidates={[str(path) for path in unique]}"
        )
    return unique[0]


def disable_hosted_early_window(pack_root: Path) -> None:
    fml_config = pack_root / "config" / "fml.toml"
    fml_config.parent.mkdir(parents=True, exist_ok=True)
    text = fml_config.read_text(encoding="utf-8", errors="replace") if fml_config.exists() else ""
    pattern = re.compile(r"(?m)^\s*earlyWindowControl\s*=.*$")
    if pattern.search(text):
        text = pattern.sub("earlyWindowControl = false", text, count=1)
    else:
        if text and not text.endswith("\n"):
            text += "\n"
        text += "\n# BootOptim hosted exact-pack exception: no hardware early window.\nearlyWindowControl = false\n"
    fml_config.write_text(text, encoding="utf-8")
    print("Exact-pack hosted exception applied: config/fml.toml earlyWindowControl=false (Drippy mod retained).")


def set_java_property(text: str, key: str, value: str) -> str:
    lines = text.splitlines()
    replacement = f"{key}={value}"
    found = False
    output = []
    for line in lines:
        stripped = line.strip()
        if stripped and not stripped.startswith(("#", "!")) and "=" in stripped:
            current_key = stripped.split("=", 1)[0].strip()
            if current_key == key:
                if not found:
                    output.append(replacement)
                    found = True
                continue
        output.append(line)
    if not found:
        output.append(replacement)
    return "\n".join(output).rstrip() + "\n"


def configure_hosted_mcef(pack_root: Path, mirror_url: str) -> None:
    settings = pack_root / "config" / "mcef" / "mcef.properties"
    settings.parent.mkdir(parents=True, exist_ok=True)
    text = settings.read_text(encoding="utf-8", errors="replace") if settings.exists() else ""
    # MCEF 2.1.6 always fetches the checksum. Point that tiny request at the
    # local mirror and prevent an archive download if the preseeded checksum
    # unexpectedly disagrees. Preserve user-agent/use-cache and any unknown keys.
    text = set_java_property(text, "skip-download", "true")
    text = set_java_property(text, "download-mirror", mirror_url)
    settings.write_text(text, encoding="utf-8")
    print(f"Exact-pack hosted MCEF exception applied: local checksum mirror={mirror_url} skip-download=true")


def write_github_env(path: str | None, key: str, value: str) -> None:
    if not path:
        return
    with Path(path).open("a", encoding="utf-8") as handle:
        handle.write(f"{key}={value}\n")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--zip", required=True)
    parser.add_argument("--sha256", required=True)
    parser.add_argument("--extract", required=True)
    parser.add_argument("--mcef-mirror-url", default="http://127.0.0.1:18765")
    args = parser.parse_args()

    zip_path = Path(args.zip).resolve()
    expected = args.sha256.lower()
    actual = sha256(zip_path)
    if actual != expected:
        raise SystemExit(f"Exact-pack SHA-256 mismatch. expected={expected} actual={actual}")

    extract_root = Path(args.extract).resolve()
    if extract_root.exists():
        shutil.rmtree(extract_root)
    extract_root.mkdir(parents=True)
    with zipfile.ZipFile(zip_path) as archive:
        archive.extractall(extract_root)

    pack_root = identify_pack_root(extract_root)
    mods = pack_root / "mods"
    options = pack_root / "options.txt"
    if not options.is_file():
        raise SystemExit("Exact-pack fixture must contain options.txt so enabled resource packs are reproducible.")

    # The authoritative Windows fixture intentionally carries these binaries.
    # Linux hosted runs use a separately pinned linux_amd64 JCEF cache because
    # MCEF selects natives by OS and its dev path is ../build/mcef-libraries.
    fixture_mcef_libraries = mods / "mcef-libraries"
    if not fixture_mcef_libraries.exists():
        raise SystemExit("Exact-pack fixture is missing mods/mcef-libraries.")
    if (mods / "mcef-cache").exists():
        raise SystemExit("Exact-pack fixture contains mods/mcef-cache; mutable browser cache must stay out of the fixture.")

    bootoptim = []
    for jar in mods.glob("*.jar"):
        lower = jar.name.lower()
        if "bootoptim" in lower or "boot_optim" in lower:
            bootoptim.append(jar.name)
    if bootoptim:
        raise SystemExit(
            "Exact-pack fixture must not contain BootOptim; the PR build is injected separately. "
            f"Found: {bootoptim}"
        )

    disable_hosted_early_window(pack_root)
    configure_hosted_mcef(pack_root, args.mcef_mirror_url)

    mod_count = sum(1 for _ in mods.glob("*.jar"))
    print(f"Exact-pack fixture verified: sha256={actual} mod_jars={mod_count} root={pack_root}")
    write_github_env(os.environ.get("GITHUB_ENV"), "BOOTOPTIM_PACK_DIR", str(pack_root))
    print(pack_root)


if __name__ == "__main__":
    main()
