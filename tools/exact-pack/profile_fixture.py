"""Offline core for building the exact-pack CI fixture from a Distribution profile.

Pure functions only (no network): RFC 8785 canonicalization, Ed25519
verification, signed-envelope checks, effective-profile resolution across the
pinned ancestor chain, config-setting application for a fresh install,
deterministic ZIP writing and the sidecar description.

The resolution mirrors the launcher (BootOptimPandora
`crates/backend/src/distribution.rs::resolve_profile_with_reuse_and_progress`)
for a *first installation* into an empty `.minecraft`: ancestors are applied
root first, and inside each revision in the order mods, remove_mods, configs,
remove_configs, objects. On a fresh install `default_once` and `enforced`
files and config settings are both written, so policy does not change the
resulting tree.

Python standard library only. The optional `cryptography` package is used for
Ed25519 when installed; otherwise a small RFC 8032 reference implementation
verifies the (at most nine) signatures.
"""

from __future__ import annotations

import base64
import decimal
import hashlib
import json
import os
import re
import zipfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Iterable

MAX_INHERITANCE_BASES = 8
CANONICALIZATION = "RFC8785-JCS"
SIGNATURE_ALGORITHM = "Ed25519"
FIXTURE_ASSET = "bootoptim-exact-pack.zip"
SIDECAR_ASSET = "bootoptim-exact-pack.json"
SIDECAR_KIND = "bootoptim-exact-pack-fixture"
ZIP_EPOCH = (1980, 1, 1, 0, 0, 0)

_SHA256_RE = re.compile(r"^[0-9a-f]{64}$")


class FixtureError(Exception):
    """Any verification or resolution failure. Never partially trusted."""


# ---------------------------------------------------------------------------
# Hashing helpers


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def is_sha256(value: object) -> bool:
    return isinstance(value, str) and bool(_SHA256_RE.match(value))


def b64url_decode(value: str) -> bytes:
    if not isinstance(value, str) or "=" in value or not value:
        raise FixtureError("expected unpadded base64url")
    return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))


def b64url_encode(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode("ascii").rstrip("=")


# ---------------------------------------------------------------------------
# JSON parsing and RFC 8785 canonicalization


def _no_duplicates(pairs):
    out = {}
    for key, value in pairs:
        if key in out:
            raise FixtureError(f"duplicate JSON key {key!r}")
        out[key] = value
    return out


def loads_strict(raw: bytes | str):
    if isinstance(raw, bytes):
        raw = raw.decode("utf-8")
    return json.loads(raw, object_pairs_hook=_no_duplicates)


def _jcs_number(value) -> str:
    if isinstance(value, bool):
        raise AssertionError
    if isinstance(value, int):
        return str(value)
    if value != value or value in (float("inf"), float("-inf")):
        raise FixtureError("JCS forbids NaN/Infinity")
    if value == 0:
        return "0"
    # ECMAScript Number.prototype.toString on the shortest round-trip digits.
    sign, digits, exponent = decimal.Decimal(repr(value)).as_tuple()
    text = "".join(map(str, digits)).rstrip("0") or "0"
    exponent += len(digits) - len(text)
    k = len(text)
    n = k + exponent
    if k <= n <= 21:
        out = text + "0" * (n - k)
    elif 0 < n <= 21:
        out = text[:n] + "." + text[n:]
    elif -6 < n <= 0:
        out = "0." + "0" * (-n) + text
    else:
        e = n - 1
        out = text[0] + ("." + text[1:] if k > 1 else "") + "e" + ("+" if e >= 0 else "-") + str(abs(e))
    return ("-" if sign else "") + out


def canonicalize(value) -> bytes:
    """RFC 8785 JSON Canonicalization Scheme for parsed JSON values."""

    def emit(item) -> str:
        if item is None:
            return "null"
        if item is True:
            return "true"
        if item is False:
            return "false"
        if isinstance(item, (int, float)):
            return _jcs_number(item)
        if isinstance(item, str):
            return json.dumps(item, ensure_ascii=False)
        if isinstance(item, list):
            return "[" + ",".join(emit(child) for child in item) + "]"
        if isinstance(item, dict):
            keys = sorted(item, key=lambda key: key.encode("utf-16-be"))
            return "{" + ",".join(json.dumps(key, ensure_ascii=False) + ":" + emit(item[key]) for key in keys) + "}"
        raise FixtureError(f"unsupported JSON value {type(item).__name__}")

    return emit(value).encode("utf-8")


# ---------------------------------------------------------------------------
# Ed25519 (RFC 8032). `cryptography` is preferred when available.

_P = 2**255 - 19
_Q = 2**252 + 27742317777372353535851937790883648493
_D = (-121665 * pow(121666, _P - 2, _P)) % _P
_SQRT_M1 = pow(2, (_P - 1) // 4, _P)


def _point_add(a, b):
    x1, y1, z1, t1 = a
    x2, y2, z2, t2 = b
    pa = (y1 - x1) * (y2 - x2) % _P
    pb = (y1 + x1) * (y2 + x2) % _P
    pc = 2 * t1 * t2 * _D % _P
    pd = 2 * z1 * z2 % _P
    e, f, g, h = pb - pa, pd - pc, pd + pc, pb + pa
    return (e * f % _P, g * h % _P, f * g % _P, e * h % _P)


def _point_mul(scalar: int, point):
    result = (0, 1, 1, 0)
    while scalar:
        if scalar & 1:
            result = _point_add(result, point)
        point = _point_add(point, point)
        scalar >>= 1
    return result


def _point_encode(point) -> bytes:
    x, y, z, _ = point
    zinv = pow(z, _P - 2, _P)
    x, y = x * zinv % _P, y * zinv % _P
    return (y | ((x & 1) << 255)).to_bytes(32, "little")


def _point_decode(data: bytes):
    if len(data) != 32:
        raise FixtureError("bad Ed25519 point length")
    y = int.from_bytes(data, "little")
    sign = y >> 255
    y &= (1 << 255) - 1
    if y >= _P:
        raise FixtureError("bad Ed25519 point")
    x2 = (y * y - 1) * pow(_D * y * y + 1, _P - 2, _P) % _P
    x = pow(x2, (_P + 3) // 8, _P)
    if (x * x - x2) % _P:
        x = x * _SQRT_M1 % _P
    if (x * x - x2) % _P:
        raise FixtureError("bad Ed25519 point")
    if x == 0 and sign:
        raise FixtureError("bad Ed25519 point")
    if (x & 1) != sign:
        x = _P - x
    return (x, y, 1, x * y % _P)


_BASE = _point_decode((4 * pow(5, _P - 2, _P) % _P).to_bytes(32, "little"))


def _ed25519_verify_reference(public_key: bytes, message: bytes, signature: bytes) -> bool:
    if len(public_key) != 32 or len(signature) != 64:
        return False
    try:
        a = _point_decode(public_key)
        r = _point_decode(signature[:32])
    except FixtureError:
        return False
    s = int.from_bytes(signature[32:], "little")
    if s >= _Q:
        return False
    h = int.from_bytes(hashlib.sha512(signature[:32] + public_key + message).digest(), "little") % _Q
    return _point_encode(_point_mul(s, _BASE)) == _point_encode(_point_add(r, _point_mul(h, a)))


def ed25519_public_key(seed: bytes) -> bytes:
    """Test/support helper: derive a public key from a 32-byte seed."""
    h = hashlib.sha512(seed).digest()
    a = int.from_bytes(h[:32], "little") & ((1 << 254) - 8) | (1 << 254)
    return _point_encode(_point_mul(a, _BASE))


def ed25519_sign(seed: bytes, message: bytes) -> bytes:
    """Test helper only (fixtures are never signed here)."""
    h = hashlib.sha512(seed).digest()
    a = int.from_bytes(h[:32], "little") & ((1 << 254) - 8) | (1 << 254)
    public = _point_encode(_point_mul(a, _BASE))
    r = int.from_bytes(hashlib.sha512(h[32:] + message).digest(), "little") % _Q
    big_r = _point_encode(_point_mul(r, _BASE))
    k = int.from_bytes(hashlib.sha512(big_r + public + message).digest(), "little") % _Q
    return big_r + ((r + k * a) % _Q).to_bytes(32, "little")


def ed25519_verify(public_key: bytes, message: bytes, signature: bytes) -> bool:
    try:
        from cryptography.exceptions import InvalidSignature
        from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
    except ImportError:
        return _ed25519_verify_reference(public_key, message, signature)
    try:
        Ed25519PublicKey.from_public_bytes(public_key).verify(signature, message)
        return True
    except (InvalidSignature, ValueError):
        return False


# ---------------------------------------------------------------------------
# Signed envelope


@dataclass
class VerifiedRevision:
    manifest: dict
    manifest_sha256: str
    key_id: str

    @property
    def profile_id(self) -> str:
        return self.manifest["profile"]["id"]

    @property
    def revision_id(self) -> str:
        return self.manifest["revision"]["id"]

    @property
    def sequence(self) -> int:
        return self.manifest["revision"]["sequence"]

    @property
    def base(self) -> dict | None:
        return self.manifest.get("base")


_ID_RE = re.compile(r"^[a-z0-9][a-z0-9_.-]{0,127}$")


def verify_envelope(
    envelope: dict,
    keys: dict[str, bytes],
    expected_profile: str,
    expected_revision: str,
    expected_digest: str | None,
) -> VerifiedRevision:
    if not isinstance(envelope, dict):
        raise FixtureError("revision envelope is not an object")
    if envelope.get("canonicalization") != CANONICALIZATION:
        raise FixtureError("unsupported canonicalization")
    signature = envelope.get("signature") or {}
    if signature.get("algorithm") != SIGNATURE_ALGORITHM:
        raise FixtureError("unsupported signature algorithm")
    manifest = envelope.get("manifest")
    if not isinstance(manifest, dict):
        raise FixtureError("envelope has no manifest")
    canonical = canonicalize(manifest)
    digest = sha256_bytes(canonical)
    if digest != envelope.get("manifest_sha256"):
        raise FixtureError("manifest digest does not match envelope manifest_sha256")
    if expected_digest is not None and digest != expected_digest:
        raise FixtureError(f"manifest digest {digest} does not match pin {expected_digest}")
    key_id = signature.get("key_id")
    if key_id not in keys:
        raise FixtureError(f"revision signed by unknown key {key_id!r}")
    if not ed25519_verify(keys[key_id], canonical, b64url_decode(signature.get("value", ""))):
        raise FixtureError(f"invalid Ed25519 signature on {expected_profile}/{expected_revision}")
    if manifest.get("schema_version") not in (1, 2):
        raise FixtureError("unsupported manifest schema_version")
    profile = manifest.get("profile") or {}
    revision = manifest.get("revision") or {}
    if profile.get("id") != expected_profile or revision.get("id") != expected_revision:
        raise FixtureError("signed manifest identity does not match the requested pin")
    if not isinstance(revision.get("sequence"), int) or revision["sequence"] <= 0:
        raise FixtureError("invalid revision sequence")
    if manifest.get("schema_version") == 1 and manifest.get("config_settings"):
        raise FixtureError("config_settings require schema_version 2")
    return VerifiedRevision(manifest=manifest, manifest_sha256=digest, key_id=key_id)


# ---------------------------------------------------------------------------
# Effective profile resolution


_PLAYER_ROOTS = {"saves", "screenshots", "logs", "crash-reports", "server-resource-packs", ".pandora-layout-v1"}
_PLAYER_FILES = {"usercache.json", "usernamecache.json", "realms_persistence.json"}
_DEVICE = re.compile(r"^(con|prn|aux|nul|com[0-9]|lpt[0-9])$")


def check_manifest_path(path: str) -> None:
    """Port of the launcher's `check_manifest_path` safety rules."""
    bad = (
        not isinstance(path, str)
        or not path
        or len(path) > 512
        or path.startswith("/")
        or "\\" in path
        or ":" in path
        or any(ord(c) < 0x20 or ord(c) == 0x7F for c in path)
        or any(part in ("", ".", "..") for part in path.split("/"))
    )
    if bad:
        raise FixtureError(f"unsafe manifest path {path!r}")
    for part in path.split("/"):
        device = part.lower().split(".")[0].rstrip(" .")
        if part.endswith((" ", ".")) or any(c in part for c in '<>"|?*') or _DEVICE.match(device):
            raise FixtureError(f"non-portable manifest path {path!r}")
    parts = path.split("/")
    if parts[0].lower() in _PLAYER_ROOTS or parts[-1].lower() in _PLAYER_FILES:
        raise FixtureError(f"manifest path manages player data {path!r}")


@dataclass
class EffectiveFile:
    path: str
    identity: str
    sha256: str
    size: int
    profile_id: str
    revision_id: str


@dataclass
class EffectiveProfile:
    files: dict[str, EffectiveFile]
    config_settings: list[dict]


def _object_ref(entry: dict) -> tuple[str, int]:
    obj = entry.get("object") or {}
    sha, size = obj.get("sha256"), obj.get("size")
    if not is_sha256(sha) or not isinstance(size, int) or size < 0:
        raise FixtureError(f"invalid object reference for {entry.get('path')!r}")
    return sha, size


def resolve_chain(target: VerifiedRevision, fetch_parent: Callable[[dict], VerifiedRevision]) -> list[VerifiedRevision]:
    """Follow exact parent pins. Returns target first, root last."""
    chain = [target]
    seen = {(target.profile_id, target.revision_id)}
    current = target
    while current.base is not None:
        if len(chain) - 1 >= MAX_INHERITANCE_BASES:
            raise FixtureError("inheritance chain exceeds 8 ancestors")
        pin = current.base
        key = (pin.get("profile_id"), pin.get("revision_id"))
        if key in seen:
            raise FixtureError("inheritance cycle")
        parent = fetch_parent(pin)
        if (parent.profile_id, parent.revision_id, parent.manifest_sha256) != (
            pin.get("profile_id"), pin.get("revision_id"), pin.get("manifest_sha256")
        ):
            raise FixtureError("pinned parent does not match the verified revision")
        if parent.manifest.get("game") != current.manifest.get("game"):
            raise FixtureError("a revision and its pinned parent use different game versions")
        seen.add(key)
        chain.append(parent)
        current = parent
    depth_limit = min(target.manifest.get("permissions", {}).get("max_inheritance_depth", 0), MAX_INHERITANCE_BASES)
    if len(chain) - 1 > depth_limit:
        raise FixtureError("inheritance chain exceeds the signed max_inheritance_depth")
    return chain


def effective_profile(chain: list[VerifiedRevision]) -> EffectiveProfile:
    """Overlay the chain root first, exactly like the launcher's first install."""
    files: dict[str, EffectiveFile] = {}
    mod_paths: dict[str, str] = {}
    object_paths: dict[str, str] = {}
    settings: dict[tuple[str, str], dict] = {}
    parent_config_permissions: dict | None = None

    def expect_base(identity: str, expected: str | None) -> None:
        if expected is None:
            return
        existing = next((f for f in files.values() if f.identity == identity), None)
        if existing is None or existing.sha256 != expected:
            raise FixtureError(f"base expectation for {identity} does not match its pinned parent")

    def insert(path: str, identity: str, entry: dict, rev: VerifiedRevision) -> None:
        sha, size = _object_ref(entry)
        existing = files.get(path)
        if existing is not None and existing.identity != identity:
            raise FixtureError(f"multiple logical entries map to {path}")
        if any(other != path and other.lower() == path.lower() for other in files):
            raise FixtureError(f"destinations collide case-insensitively: {path}")
        files[path] = EffectiveFile(path, identity, sha, size, rev.profile_id, rev.revision_id)

    for rev in reversed(chain):
        m = rev.manifest
        seen_settings = set()
        for item in m.get("config_settings") or []:
            check_manifest_path(item.get("path"))
            if item.get("policy") not in ("enforced", "default_once") or item.get("format") not in (
                "toml", "properties", "text_lines"
            ):
                raise FixtureError("invalid per-setting config rule")
            key = (item["path"], item.get("key"))
            if key in seen_settings:
                raise FixtureError("duplicate per-setting config rule")
            seen_settings.add(key)
            if key in settings:
                if parent_config_permissions is None:
                    raise FixtureError("config-setting lineage is inconsistent")
                allowed = parent_config_permissions.get(
                    "override_enforced" if settings[key]["policy"] == "enforced" else "override_default_once"
                )
                if not allowed:
                    raise FixtureError("parent permissions prohibit overriding a config setting")
            settings[key] = item
        for item in m.get("mods") or []:
            check_manifest_path(item.get("path"))
            identity = f"mod:{item['id']}"
            expect_base(identity, item.get("expect_base_object_sha256"))
            previous = mod_paths.get(item["id"])
            mod_paths[item["id"]] = item["path"]
            if previous is not None and previous != item["path"]:
                files.pop(previous, None)
            insert(item["path"], identity, item, rev)
        for item in m.get("remove_mods") or []:
            path = mod_paths.pop(item.get("id"), None)
            if path is None or path not in files:
                raise FixtureError("revision removes an unknown mod identity")
            if files[path].sha256 != item.get("expect_base_object_sha256"):
                raise FixtureError("removed mod does not match its declared parent digest")
            del files[path]
        for item in m.get("configs") or []:
            check_manifest_path(item.get("path"))
            if item.get("policy") not in ("enforced", "default_once"):
                raise FixtureError("unsupported config policy")
            identity = f"config:{item['path']}"
            expect_base(identity, item.get("expect_base_object_sha256"))
            insert(item["path"], identity, item, rev)
        for item in m.get("remove_configs") or []:
            existing = files.get(item.get("path"))
            if existing is None:
                raise FixtureError("revision removes a config absent from its pinned parent")
            if existing.sha256 != item.get("expect_base_object_sha256"):
                raise FixtureError("removed config does not match its declared parent digest")
            del files[item["path"]]
            for key in [k for k in settings if k[0] == item["path"]]:
                del settings[key]
        for item in m.get("objects") or []:
            check_manifest_path(item.get("path"))
            identity = f"object:{item['id']}"
            expect_base(identity, item.get("expect_base_object_sha256"))
            previous = object_paths.get(item["id"])
            object_paths[item["id"]] = item["path"]
            if previous is not None and previous != item["path"]:
                files.pop(previous, None)
            insert(item["path"], identity, item, rev)
        parent_config_permissions = (m.get("permissions") or {}).get("configs") or {}

    for path, _ in settings:
        if path not in files or not files[path].identity.startswith("config:"):
            raise FixtureError(f"config setting refers to a config absent from the effective profile: {path}")
    ordered = [settings[k] for k in sorted(settings)]
    return EffectiveProfile(files=dict(sorted(files.items())), config_settings=ordered)


# ---------------------------------------------------------------------------
# Config settings on a fresh install (live file absent)


def _split_lines(text: str) -> tuple[list[str], str, bool]:
    newline = "\r\n" if "\r\n" in text else "\n"
    trailing = text.endswith("\n")
    lines = [line[:-1] if line.endswith("\r") else line for line in text.split("\n")]
    if trailing:
        lines.pop()
    if text == "":
        lines = []
    return lines, newline, trailing


def _join_lines(lines: list[str], newline: str, trailing: bool) -> str:
    joined = newline.join(lines)
    return joined + newline if trailing and joined else joined


def _property_separator(content: str):
    escaped = False
    for index, char in enumerate(content):
        if escaped:
            escaped = False
            continue
        if char == "\\":
            escaped = True
            continue
        if char in "=:" or char.isspace():
            return index, char
    return None


def _property_key(line: str) -> str | None:
    trimmed = line.lstrip()
    if not trimmed or trimmed[0] in "#!":
        return None
    sep = _property_separator(trimmed)
    key = trimmed[: sep[0] if sep else len(trimmed)].strip()
    return key if re.fullmatch(r"[A-Za-z0-9_.-]{1,256}", key) else None


def _property_value_start(line: str) -> int:
    indent = len(line) - len(line.lstrip())
    content = line[indent:]
    sep = _property_separator(content)
    if sep is None:
        return len(line)
    cursor = sep[0]
    while cursor < len(content) and content[cursor].isspace():
        cursor += 1
    if cursor < len(content) and content[cursor] in "=:":
        cursor += 1
    while cursor < len(content) and content[cursor].isspace():
        cursor += 1
    return indent + cursor


def _escape_property(value: str) -> str:
    out = []
    for index, char in enumerate(value):
        out.append({"\\": "\\\\", "\t": "\\t", "\n": "\\n", "\r": "\\r", "\f": "\\f"}.get(char, char))
        if char == " " and index == 0:
            out[-1] = "\\ "
    return "".join(out)


def apply_config_settings(path: str, published: bytes, rules: list[dict]) -> bytes:
    """Apply effective rules for `path` as the launcher does on first install.

    TOML rules are not reproduced here (the launcher uses toml_edit); callers
    must decide explicitly what to do with them before calling this.
    """
    rules = [r for r in rules if r["path"] == path]
    if not rules:
        return published
    fmt = rules[0]["format"]
    text = published.decode("utf-8")
    lines, newline, trailing = _split_lines(text)
    for rule in rules:
        if rule["format"] != fmt:
            raise FixtureError(f"mixed config-setting formats for {path}")
        value = rule.get("value")
        if not isinstance(value, str):
            raise FixtureError(f"non-string value for {path}/{rule['key']}")
        if fmt == "text_lines":
            match = re.fullmatch(r"line:([1-9][0-9]{0,8})", rule["key"])
            if not match or int(match.group(1)) > len(lines):
                raise FixtureError(f"invalid line selector {rule['key']} for {path}")
            lines[int(match.group(1)) - 1] = value
        elif fmt == "properties":
            escaped = _escape_property(value)
            indexes = [i for i, line in enumerate(lines) if _property_key(line) == rule["key"]]
            if len(indexes) > 1:
                raise FixtureError(f"ambiguous properties key {rule['key']} in {path}")
            if indexes:
                line = lines[indexes[0]]
                if _property_separator(line.lstrip()) is not None:
                    line = line[: _property_value_start(line)]
                else:
                    line += "="
                lines[indexes[0]] = line + escaped
            else:
                lines.append(f"{rule['key']}={escaped}")
        else:
            raise FixtureError(f"config format {fmt} is not reproducible offline ({path})")
    return _join_lines(lines, newline, trailing).encode("utf-8")


# ---------------------------------------------------------------------------
# Materialization, exclusions and deterministic ZIP


DEFAULT_EXCLUDES = (
    # Mutable MCEF download cache: never part of the fixture contract.
    re.compile(r"^mods/mcef-cache(/|$)", re.IGNORECASE),
    # The PR build of BootOptim is injected by ModDevGradle; a pack copy must not be in the fixture.
    re.compile(r"^mods/[^/]*bootoptim[^/]*\.jar$", re.IGNORECASE),
)


def is_excluded(path: str, patterns: Iterable[re.Pattern] = DEFAULT_EXCLUDES) -> bool:
    return any(p.search(path) for p in patterns)


@dataclass
class MaterializeResult:
    files: dict[str, str]  # path -> sha256 of written bytes
    excluded: list[str] = field(default_factory=list)
    unapplied_settings: list[dict] = field(default_factory=list)


def materialize(
    profile: EffectiveProfile,
    out_dir: Path,
    open_object: Callable[[str, int], Path],
    allow_unapplied_toml: bool = False,
) -> MaterializeResult:
    """Write the effective tree into an empty directory.

    `open_object(sha256, size)` returns a local file whose content the caller
    claims has that digest; it is re-verified here regardless.
    """
    out_dir.mkdir(parents=True, exist_ok=True)
    if any(out_dir.iterdir()):
        raise FixtureError(f"output tree {out_dir} is not empty")
    result = MaterializeResult(files={})
    toml_rules = [r for r in profile.config_settings if r["format"] == "toml"]
    if toml_rules and not allow_unapplied_toml:
        raise FixtureError(
            f"{len(toml_rules)} TOML config_settings cannot be reproduced byte-exactly offline; "
            "rerun with --allow-unapplied-toml-settings to keep the published files and record them"
        )
    result.unapplied_settings = toml_rules
    other_rules = [r for r in profile.config_settings if r["format"] != "toml"]
    for path, entry in profile.files.items():
        if is_excluded(path):
            result.excluded.append(path)
            continue
        source = open_object(entry.sha256, entry.size)
        if source.stat().st_size != entry.size or sha256_file(source) != entry.sha256:
            raise FixtureError(f"object {entry.sha256} for {path} failed size/SHA-256 verification")
        target = out_dir.joinpath(*path.split("/"))
        target.parent.mkdir(parents=True, exist_ok=True)
        if any(r["path"] == path for r in other_rules):
            data = apply_config_settings(path, source.read_bytes(), other_rules)
            target.write_bytes(data)
            result.files[path] = sha256_bytes(data)
        else:
            with source.open("rb") as src, target.open("wb") as dst:
                for chunk in iter(lambda: src.read(1024 * 1024), b""):
                    dst.write(chunk)
            result.files[path] = entry.sha256
    return result


def add_overlay(out_dir: Path, overlay: Path, prefix: str, written: dict[str, str]) -> dict[str, str]:
    """Copy a local directory (e.g. mods/mcef-libraries) under `prefix`. No overwrites."""
    check_manifest_path(prefix.rstrip("/") + "/x")
    added: dict[str, str] = {}
    for source in sorted(p for p in overlay.rglob("*") if p.is_file()):
        rel = prefix.strip("/") + "/" + source.relative_to(overlay).as_posix()
        if rel in written or is_excluded(rel):
            raise FixtureError(f"overlay path {rel} collides with the profile or an exclusion")
        target = out_dir.joinpath(*rel.split("/"))
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(source.read_bytes())
        added[rel] = sha256_file(target)
    return added


def tree_digest(files: dict[str, str]) -> str:
    """Order-independent digest of `path -> sha256` (sorted `path\\0sha\\n` lines)."""
    return sha256_bytes("".join(f"{p}\0{s}\n" for p, s in sorted(files.items())).encode("utf-8"))


def write_deterministic_zip(tree: Path, files: Iterable[str], zip_path: Path) -> None:
    """Sorted entries, fixed timestamps/permissions, deflate level 6."""
    tmp = zip_path.with_name(zip_path.name + ".partial")
    with zipfile.ZipFile(tmp, "w", allowZip64=True) as archive:
        for rel in sorted(files):
            source = tree.joinpath(*rel.split("/"))
            info = zipfile.ZipInfo(rel, date_time=ZIP_EPOCH)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.create_system = 3
            info.external_attr = (0o100644 << 16)
            info.file_size = source.stat().st_size
            with source.open("rb") as src, archive.open(info, "w", force_zip64=info.file_size > 0x7FFFFFFF) as dst:
                for chunk in iter(lambda: src.read(1024 * 1024), b""):
                    dst.write(chunk)
    # ZipFile.open() ignores compresslevel per entry only on old Pythons; level comes from the default.
    os.replace(tmp, zip_path)


def build_sidecar(
    chain: list[VerifiedRevision],
    keys_used: dict[str, str],
    result: MaterializeResult,
    overlays: list[dict],
    zip_path: Path,
    built_at: str,
    server_label: str,
) -> dict:
    target = chain[0]
    all_files = dict(result.files)
    for overlay in overlays:
        all_files.update(overlay["files"])
    return {
        "schema_version": 1,
        "kind": SIDECAR_KIND,
        "profile": target.profile_id,
        "profile_name": target.manifest["profile"].get("name"),
        "revision_id": target.revision_id,
        "sequence": target.sequence,
        "manifest_sha256": target.manifest_sha256,
        "revision_created_at": target.manifest["revision"].get("created_at"),
        "game": target.manifest.get("game"),
        "ancestors": [
            {
                "profile_id": rev.profile_id,
                "revision_id": rev.revision_id,
                "sequence": rev.sequence,
                "manifest_sha256": rev.manifest_sha256,
            }
            for rev in chain[1:]
        ],
        "signing_keys": keys_used,
        "server": server_label,
        "file_count": len(all_files),
        "tree_sha256": tree_digest(all_files),
        "excluded_paths": result.excluded,
        "unapplied_config_settings": result.unapplied_settings,
        "overlays": [{k: v for k, v in o.items() if k != "files"} for o in overlays],
        "zip_asset": FIXTURE_ASSET,
        "zip_size": zip_path.stat().st_size,
        "zip_sha256": sha256_file(zip_path),
        "built_at": built_at,
        "builder": "tools/exact-pack/build_fixture_from_profile.py",
    }
