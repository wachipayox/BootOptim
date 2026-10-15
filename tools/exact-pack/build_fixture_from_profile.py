#!/usr/bin/env python3
"""Build the exact-pack fixture ZIP from a signed Distribution profile revision.

Used by the hosted exact-pack workflow (server URL from a masked secret) and
offline on a local machine. The server URL is never printed.

    build_fixture_from_profile.py --server "$DISTRIBUTION_URL" --profile profile_beta \
        [--revision latest|channel:<name>|rev_...] --out fixture.zip [--sidecar fixture.json]

`--resolve-only` prints the resolved pin as JSON (no object downloads) so CI
can key its cache by manifest_sha256 before building.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import ssl
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import profile_fixture as pf  # noqa: E402

MAX_JSON_BYTES = 32 * 1024 * 1024


class Client:
    def __init__(self, base_url: str, header: str | None, ca_bundle: str | None):
        parsed = urllib.parse.urlsplit(base_url)
        if parsed.scheme not in ("https", "http") or not parsed.netloc:
            raise pf.FixtureError("--server must be an http(s) base URL")
        self.base = base_url.rstrip("/")
        self.headers = {"Accept": "application/json", "User-Agent": "bootoptim-exact-pack-fixture/1"}
        if header:
            name, _, value = header.partition(":")
            if not name.strip() or not value.strip():
                raise pf.FixtureError("DISTRIBUTION_KEY header must look like 'Name: value'")
            self.headers[name.strip()] = value.strip()
        self.context = ssl.create_default_context(cafile=ca_bundle) if ca_bundle else None

    def _open(self, path: str):
        request = urllib.request.Request(self.base + path, headers=self.headers)
        try:
            return urllib.request.urlopen(request, timeout=120, context=self.context)
        except urllib.error.HTTPError as error:
            # Never echo the URL: report only the route.
            raise pf.FixtureError(f"Distribution returned HTTP {error.code} for {path}") from None
        except urllib.error.URLError as error:
            raise pf.FixtureError(f"Distribution request failed for {path}: {error.reason}") from None

    def json(self, path: str):
        with self._open(path) as response:
            data = response.read(MAX_JSON_BYTES + 1)
        if len(data) > MAX_JSON_BYTES:
            raise pf.FixtureError(f"response too large for {path}")
        return pf.loads_strict(data)

    def download(self, sha256: str, size: int, target: Path) -> None:
        tmp = target.with_name(target.name + ".partial")
        with self._open(f"/v1/objects/sha256/{sha256}") as response, tmp.open("wb") as out:
            written = 0
            for chunk in iter(lambda: response.read(1024 * 1024), b""):
                written += len(chunk)
                if written > size:
                    raise pf.FixtureError(f"object {sha256} exceeds its signed size")
                out.write(chunk)
        if pf.sha256_file(tmp) != sha256 or tmp.stat().st_size != size:
            tmp.unlink()
            raise pf.FixtureError(f"object {sha256} failed SHA-256/size verification")
        os.replace(tmp, target)


def parse_trusted_keys(values: list[str]) -> dict[str, bytes]:
    keys: dict[str, bytes] = {}
    env = os.environ.get("BOOTOPTIM_DISTRIBUTION_TRUSTED_KEYS", "").strip()
    pairs = list(values)
    if env:
        pairs.extend(f"{k}={v}" for k, v in json.loads(env).items())
    for pair in pairs:
        key_id, _, encoded = pair.partition("=")
        raw = pf.b64url_decode(encoded.strip())
        if len(raw) != 32 or key_id in keys:
            raise pf.FixtureError(f"invalid or duplicate trusted key {key_id!r}")
        keys[key_id] = raw
    return keys


class Resolver:
    def __init__(self, client: Client, pinned_keys: dict[str, bytes]):
        self.client = client
        self.pinned = pinned_keys
        self.keys = dict(pinned_keys)
        self.server_keys_loaded = False
        self.key_origin: dict[str, str] = {k: "pinned" for k in pinned_keys}

    def _ensure_key(self, key_id: str) -> None:
        if key_id in self.keys or self.server_keys_loaded:
            return
        self.server_keys_loaded = True
        doc = self.client.json("/v1/signing-keys")
        if doc.get("schema_version") != 1 or not isinstance(doc.get("keys"), dict) or len(doc["keys"]) > 128:
            raise pf.FixtureError("invalid /v1/signing-keys document")
        for kid, encoded in doc["keys"].items():
            raw = pf.b64url_decode(encoded)
            if len(raw) != 32:
                raise pf.FixtureError(f"invalid server signing key {kid}")
            if kid in self.pinned and self.pinned[kid] != raw:
                raise pf.FixtureError(f"server signing key {kid} disagrees with the pinned key")
            if kid not in self.keys:
                self.keys[kid] = raw
                self.key_origin[kid] = "server"

    def revision(self, profile: str, revision: str, digest: str | None) -> pf.VerifiedRevision:
        response = self.client.json(
            f"/v1/profiles/{urllib.parse.quote(profile, safe='')}/revisions/{urllib.parse.quote(revision, safe='')}"
        )
        if response.get("schema_version") != 1 or response.get("protocol_version") != 1:
            raise pf.FixtureError("unsupported revision protocol")
        envelope = response.get("envelope") or {}
        self._ensure_key((envelope.get("signature") or {}).get("key_id"))
        return pf.verify_envelope(envelope, self.keys, profile, revision, digest)


def select_pin(catalog: dict, profile: str, selector: str) -> tuple[str, str | None, int | None]:
    entry = next((p for p in catalog.get("profiles", []) if p.get("profile_id") == profile), None)
    if selector.startswith("rev_"):
        if entry:
            for ref in [entry.get("latest_revision")] + [c for c in entry.get("channels", [])]:
                if ref and ref.get("revision_id") == selector:
                    return selector, ref.get("manifest_sha256"), ref.get("sequence")
        return selector, None, None  # verified by signature; digest taken from the envelope
    if entry is None:
        raise pf.FixtureError(f"profile {profile} is not in the catalog")
    if selector == "latest":
        ref = entry.get("latest_revision")
    elif selector.startswith("channel:"):
        ref = next((c for c in entry.get("channels", []) if c.get("name") == selector[8:]), None)
    else:
        raise pf.FixtureError("--revision must be latest, channel:<name> or rev_...")
    if not ref or not pf.is_sha256(ref.get("manifest_sha256")):
        raise pf.FixtureError(f"no usable revision for {profile} {selector}")
    return ref["revision_id"], ref["manifest_sha256"], ref.get("sequence")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--server", default=os.environ.get("DISTRIBUTION_URL"), help="base URL (or env DISTRIBUTION_URL)")
    parser.add_argument("--profile", required=True)
    parser.add_argument("--revision", default="latest", help="latest | channel:<name> | rev_<id>")
    parser.add_argument("--manifest-sha256", help="optional expected digest for an explicit rev_ pin")
    parser.add_argument("--out", type=Path, help="fixture ZIP to write")
    parser.add_argument("--sidecar", type=Path, help="sidecar JSON (default: <out>.json)")
    parser.add_argument("--cache", type=Path, default=Path(".exact-pack-objects"), help="verified CAS object cache")
    parser.add_argument("--trusted-key", action="append", default=[], help="KEY_ID=base64url Ed25519 public key")
    parser.add_argument("--ca-bundle", help="CA bundle for a privately signed HTTPS server")
    parser.add_argument("--overlay", action="append", default=[], metavar="PREFIX=DIR",
                        help="add a local tree, e.g. mods/mcef-libraries=/path (recorded in the sidecar)")
    parser.add_argument("--allow-unapplied-toml-settings", action="store_true")
    parser.add_argument("--resolve-only", action="store_true", help="print the resolved pin as JSON and stop")
    args = parser.parse_args()
    if not args.server:
        parser.error("--server or DISTRIBUTION_URL is required")
    if not args.resolve_only and not args.out:
        parser.error("--out is required unless --resolve-only")

    try:
        client = Client(args.server, os.environ.get("DISTRIBUTION_KEY") or None, args.ca_bundle)
        resolver = Resolver(client, parse_trusted_keys(args.trusted_key))
        catalog = client.json("/v1/profiles")
        revision_id, digest, sequence = select_pin(catalog, args.profile, args.revision)
        if args.manifest_sha256:
            if digest and digest != args.manifest_sha256:
                raise pf.FixtureError("--manifest-sha256 disagrees with the catalog")
            digest = args.manifest_sha256
        target = resolver.revision(args.profile, revision_id, digest)
        if sequence is not None and target.sequence != sequence:
            raise pf.FixtureError("signed sequence does not match the catalog")
        chain = pf.resolve_chain(
            target, lambda pin: resolver.revision(pin["profile_id"], pin["revision_id"], pin["manifest_sha256"])
        )
        if args.resolve_only:
            print(json.dumps({
                "profile": target.profile_id,
                "revision_id": target.revision_id,
                "sequence": target.sequence,
                "manifest_sha256": target.manifest_sha256,
                "ancestors": [r.revision_id for r in chain[1:]],
            }))
            return 0

        profile = pf.effective_profile(chain)
        args.cache.mkdir(parents=True, exist_ok=True)

        def open_object(sha: str, size: int) -> Path:
            path = args.cache / sha[:2] / sha
            if path.is_file() and path.stat().st_size == size and pf.sha256_file(path) == sha:
                return path
            path.parent.mkdir(parents=True, exist_ok=True)
            client.download(sha, size, path)
            return path

        args.out.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=args.out.parent, prefix=".fixture-tree-") as tmp:
            tree = Path(tmp) / "pack"
            result = pf.materialize(profile, tree, open_object, args.allow_unapplied_toml_settings)
            overlays = []
            for spec in args.overlay:
                prefix, _, directory = spec.partition("=")
                files = pf.add_overlay(tree, Path(directory), prefix, result.files)
                overlays.append({"prefix": prefix.strip("/"), "file_count": len(files),
                                 "tree_sha256": pf.tree_digest(files), "files": files})
            names = list(result.files) + [p for o in overlays for p in o["files"]]
            pf.write_deterministic_zip(tree, names, args.out)
            used = {r.key_id for r in chain}
            keys_used = {k: pf.b64url_encode(resolver.keys[k]) + f" ({resolver.key_origin[k]})" for k in sorted(used)}
            sidecar = pf.build_sidecar(
                chain, keys_used, result, overlays, args.out,
                dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
                "distribution",
            )
        sidecar_path = args.sidecar or args.out.with_suffix(".json")
        sidecar_path.write_text(json.dumps(sidecar, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        print(json.dumps({k: sidecar[k] for k in ("profile", "revision_id", "manifest_sha256", "file_count",
                                                   "zip_sha256", "zip_size")}, indent=2))
        if result.excluded:
            print(f"excluded by fixture contract: {result.excluded}")
        if result.unapplied_settings:
            print(f"WARNING: {len(result.unapplied_settings)} TOML config_settings kept as published (see sidecar)")
        return 0
    except pf.FixtureError as error:
        print(f"fixture build failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
