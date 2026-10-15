import hashlib
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import profile_fixture as pf  # noqa: E402

SEED = bytes(range(32))
PUB = pf.ed25519_public_key(SEED)
KEYS = {"k1": PUB}
GAME = {"minecraft": "1.21.1", "neoforge": "21.1.200"}


def blob(text):
    data = text.encode()
    return data, {"sha256": hashlib.sha256(data).hexdigest(), "size": len(data)}


def manifest(profile, rev, seq, base=None, **lists):
    m = {
        "schema_version": 2,
        "revision": {"id": rev, "sequence": seq, "created_at": "2026-10-14T00:00:00Z"},
        "profile": {"id": profile, "name": profile, "official": True},
        "game": GAME,
        "base": base,
        "permissions": {"derive_local": True, "mods": {"add": True, "remove": True},
                        "configs": {"override_enforced": True, "override_default_once": True},
                        "max_inheritance_depth": 8},
        "mods": [], "remove_mods": [], "configs": [], "remove_configs": [], "objects": [],
    }
    m.update(lists)
    return m


def envelope(m, seed=SEED):
    canonical = pf.canonicalize(m)
    return {"canonicalization": "RFC8785-JCS", "manifest_sha256": hashlib.sha256(canonical).hexdigest(),
            "manifest": m, "signature": {"key_id": "k1", "algorithm": "Ed25519",
                                         "value": pf.b64url_encode(pf.ed25519_sign(seed, canonical))}}


def verified(m):
    return pf.verify_envelope(envelope(m), KEYS, m["profile"]["id"], m["revision"]["id"], None)


class CryptoTests(unittest.TestCase):
    def test_rfc8032_vector_1(self):
        pub = bytes.fromhex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
        sig = bytes.fromhex("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b")
        self.assertTrue(pf._ed25519_verify_reference(pub, b"", sig))
        self.assertFalse(pf._ed25519_verify_reference(pub, b"x", sig))
        seed = bytes.fromhex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        self.assertEqual(pf.ed25519_public_key(seed), pub)
        self.assertEqual(pf.ed25519_sign(seed, b""), sig)

    def test_jcs(self):
        self.assertEqual(pf.canonicalize({"b": [1, 2.5, 1e21, 1e-7, 1.0], "a": "é\n", "\u20ac": None, "\U0001F600": True}),
                         '{"a":"é\\n","b":[1,2.5,1e+21,1e-7,1],"\u20ac":null,"\U0001F600":true}'.encode())

    def test_tampered_manifest_rejected(self):
        env = envelope(manifest("profile_a", "rev_1", 1))
        env["manifest"]["revision"]["sequence"] = 2
        with self.assertRaises(pf.FixtureError):
            pf.verify_envelope(env, KEYS, "profile_a", "rev_1", None)
        env = envelope(manifest("profile_a", "rev_1", 1))
        with self.assertRaises(pf.FixtureError):
            pf.verify_envelope(env, KEYS, "profile_a", "rev_1", "0" * 64)
        with self.assertRaises(pf.FixtureError):
            pf.verify_envelope(envelope(manifest("profile_a", "rev_1", 1), seed=b"\x01" * 32), KEYS, "profile_a", "rev_1", None)


class ResolutionTests(unittest.TestCase):
    def setUp(self):
        self.blobs = {}

        def ref(text):
            data, r = blob(text)
            self.blobs[r["sha256"]] = data
            return r

        self.ref = ref
        root = manifest("profile_base", "rev_r", 1,
                        mods=[{"id": "mod_a", "path": "mods/a-1.jar", "object": ref("a1")},
                              {"id": "mod_b", "path": "mods/b.jar", "object": ref("b")},
                              {"id": "mod_bo", "path": "mods/BootOptim-1.0.jar", "object": ref("bo")}],
                        configs=[{"path": "config/x.properties", "policy": "enforced", "object": ref("# c\nfoo = 1\nbar=2\n")},
                                 {"path": "config/old.toml", "policy": "enforced", "object": ref("k=1\n")},
                                 {"path": "config/lines.txt", "policy": "default_once", "object": ref("l1\nl2\n")}],
                        objects=[{"id": "obj_opts", "path": "options.txt", "object": ref("o")}])
        self.root = verified(root)
        pin = {"profile_id": "profile_base", "revision_id": "rev_r", "manifest_sha256": self.root.manifest_sha256}
        child = manifest("profile_beta", "rev_c", 3, base=pin,
                         mods=[{"id": "mod_a", "path": "mods/a-2.jar", "object": ref("a2")}],
                         remove_mods=[{"id": "mod_b", "expect_base_object_sha256": blob("b")[1]["sha256"]}],
                         remove_configs=[{"path": "config/old.toml", "expect_base_object_sha256": blob("k=1\n")[1]["sha256"]}],
                         config_settings=[{"path": "config/x.properties", "format": "properties", "key": "foo", "value": " v", "policy": "enforced"},
                                          {"path": "config/x.properties", "format": "properties", "key": "new", "value": "n", "policy": "default_once"},
                                          {"path": "config/lines.txt", "format": "text_lines", "key": "line:2", "value": "L2", "policy": "enforced"}])
        self.child = verified(child)

    def chain(self):
        return pf.resolve_chain(self.child, lambda pin: self.root)

    def test_overlay_and_removals(self):
        profile = pf.effective_profile(self.chain())
        self.assertEqual(sorted(profile.files), ["config/lines.txt", "config/x.properties", "mods/BootOptim-1.0.jar",
                                                 "mods/a-2.jar", "options.txt"])
        self.assertEqual(profile.files["mods/a-2.jar"].revision_id, "rev_c")

    def test_wrong_parent_pin(self):
        bad = verified(manifest("profile_base", "rev_r", 1, mods=[{"id": "mod_z", "path": "mods/z.jar", "object": self.ref("z")}]))
        with self.assertRaises(pf.FixtureError):
            pf.resolve_chain(self.child, lambda pin: bad)

    def test_remove_digest_mismatch(self):
        pin = {"profile_id": "profile_base", "revision_id": "rev_r", "manifest_sha256": self.root.manifest_sha256}
        child = verified(manifest("profile_beta", "rev_d", 4, base=pin,
                                  remove_mods=[{"id": "mod_b", "expect_base_object_sha256": "1" * 64}]))
        with self.assertRaises(pf.FixtureError):
            pf.effective_profile(pf.resolve_chain(child, lambda p: self.root))

    def test_unsafe_path(self):
        for path in ("../x", "saves/w/level.dat", "a//b", "con.txt", "C:/x"):
            with self.assertRaises(pf.FixtureError, msg=path):
                pf.check_manifest_path(path)

    def test_materialize_and_zip_deterministic(self):
        profile = pf.effective_profile(self.chain())
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            store = tmp / "store"
            store.mkdir()
            for sha, data in self.blobs.items():
                (store / sha).write_bytes(data)
            result = pf.materialize(profile, tmp / "tree", lambda sha, size: store / sha)
            self.assertEqual(result.excluded, ["mods/BootOptim-1.0.jar"])
            self.assertEqual((tmp / "tree/config/x.properties").read_text(), "# c\nfoo = \\ v\nbar=2\nnew=n\n")
            self.assertEqual((tmp / "tree/config/lines.txt").read_text(), "l1\nL2\n")
            pf.write_deterministic_zip(tmp / "tree", result.files, tmp / "a.zip")
            pf.write_deterministic_zip(tmp / "tree", reversed(list(result.files)), tmp / "b.zip")
            self.assertEqual(pf.sha256_file(tmp / "a.zip"), pf.sha256_file(tmp / "b.zip"))
            with zipfile.ZipFile(tmp / "a.zip") as z:
                self.assertEqual(z.namelist(), sorted(result.files))
            sidecar = pf.build_sidecar(self.chain(), {"k1": "x"}, result, [], tmp / "a.zip", "t", "test")
            self.assertEqual(sidecar["ancestors"][0]["revision_id"], "rev_r")
            self.assertEqual(sidecar["zip_sha256"], pf.sha256_file(tmp / "a.zip"))

    def test_corrupt_object_rejected(self):
        profile = pf.effective_profile(self.chain())
        with tempfile.TemporaryDirectory() as tmp:
            bad = Path(tmp) / "bad"
            bad.write_bytes(b"xx")
            with self.assertRaises(pf.FixtureError):
                pf.materialize(profile, Path(tmp) / "tree", lambda sha, size: bad)

    def test_toml_settings_require_opt_in(self):
        profile = pf.EffectiveProfile(files={}, config_settings=[{"path": "c.toml", "format": "toml", "key": "a", "value": 1, "policy": "enforced"}])
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(pf.FixtureError):
                pf.materialize(profile, Path(tmp) / "t", lambda s, n: None)
            self.assertEqual(len(pf.materialize(profile, Path(tmp) / "u", lambda s, n: None, True).unapplied_settings), 1)


if __name__ == "__main__":
    unittest.main()
