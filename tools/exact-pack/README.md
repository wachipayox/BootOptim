# Exact-pack fixture builder

Builds the hosted exact-pack CI fixture (`bootoptim-exact-pack.zip` + sidecar
`bootoptim-exact-pack.json`) from a signed BootOptimDistribution profile
revision instead of by hand. The exact-pack workflow runs it itself; it also
works offline on a local machine. Python 3.11+ standard library only
(`cryptography`, if installed, is used for Ed25519; otherwise a built-in
RFC 8032 verifier is used).

```text
python tools/exact-pack/build_fixture_from_profile.py \
  --server "$DISTRIBUTION_URL" --profile <beta-profile-id> \
  [--revision latest | channel:<name> | rev_<id>] \
  --out fixture/bootoptim-exact-pack.zip [--cache .exact-pack-objects] \
  [--trusted-key KEY_ID=<base64url>] [--overlay mods/mcef-libraries=<dir>]
```

- The server URL comes from `--server` or `DISTRIBUTION_URL`; it is never
  printed. An optional request header is read from `DISTRIBUTION_KEY`
  (`Name: value`). Trusted signing keys: `--trusted-key` or
  `BOOTOPTIM_DISTRIBUTION_TRUSTED_KEYS` (JSON map); unknown key ids are fetched
  from `/v1/signing-keys` and must not disagree with a pinned key.
- Verification: every envelope's RFC 8785 digest and Ed25519 signature, the
  exact parent pins (at most 8 ancestors, signed depth limit, equal game
  versions), and every object's size and SHA-256.
- Materialization mirrors the launcher's **first install** into an empty
  `.minecraft` (Pandora `distribution.rs`): ancestors root first; per revision
  mods, remove_mods, configs, remove_configs, objects; `properties` and
  `text_lines` config settings applied. TOML settings cannot be reproduced
  byte-exactly without `toml_edit`: the build fails unless
  `--allow-unapplied-toml-settings`, which keeps the published file and lists
  the rules in the sidecar.
- Fixture contract exclusions: `mods/mcef-cache/` and any `mods/*bootoptim*.jar`
  (the PR build is injected by ModDevGradle). They are listed in the sidecar.
- The ZIP is deterministic (sorted entries, fixed timestamps/permissions);
  the sidecar records profile, revision, sequence, manifest_sha256, ancestors,
  signing keys, `tree_sha256`, `zip_sha256` and `built_at`.
- `--resolve-only` prints the resolved pin without downloading objects (the
  workflow uses it to key its cache by `manifest_sha256`).

Tests: `python -m unittest discover -s tools/exact-pack -p 'test_*.py'`.
