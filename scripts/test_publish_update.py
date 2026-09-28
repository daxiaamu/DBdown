import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("publisher", Path(__file__).with_name("publish_update.py"))
publisher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publisher)

class PublicationTests(unittest.TestCase):
    def manifest(self):
        return dict(schemaVersion=1, channel="stable", versionCode=10, versionName="0.6.1",
                    policyRevision=1, maxForcedVersionCode=0, size=500, sha256="a"*64,
                    changelog="## Fix\n- Test", publishedAt="2026-09-27T00:00:00Z",
                    urls=[f"https://cdn{i}.example/app.apk" for i in range(5)])
    def test_valid_and_deterministic_bytes(self):
        data = self.manifest()
        publisher.validate_manifest(data)
        self.assertEqual(publisher.encoded(data), publisher.encoded(dict(reversed(list(data.items())))))
    def test_fewer_hosts_or_official_only_are_rejected(self):
        data = self.manifest()
        data["urls"] = [f"https://github.com/a.apk?q={i}" for i in range(5)]
        with self.assertRaises(ValueError): publisher.validate_manifest(data)
    def test_no_coercion_of_invalid_schema_or_policy(self):
        for key, value in [("versionCode", "10"), ("maxForcedVersionCode", 10), ("sha256", "z"*64), ("size", 0)]:
            with self.subTest(key=key):
                data = self.manifest(); data[key] = value
                with self.assertRaises(ValueError): publisher.validate_manifest(data)
    def test_redirect_cannot_downgrade_or_embed_credentials(self):
        for url in ["http://example.com/a.apk", "https://user:pass@example.com/a.apk", "file:///tmp/a.apk"]:
            with self.assertRaises(ValueError): publisher.secure_url(url)
    def test_metadata_revision_requires_identical_apk(self):
        old = self.manifest()
        policy = dict(policyRevision=2, maxForcedVersionCode=0)
        publisher.validate_advance(old, policy, 10, "0.6.1", "a"*64, 500)
        for code, name, digest, size in [(9, "0.6.0", "a"*64, 500), (10, "0.6.2", "a"*64, 500),
                                         (10, "0.6.1", "b"*64, 500), (10, "0.6.1", "a"*64, 501)]:
            with self.assertRaises(ValueError): publisher.validate_advance(old, policy, code, name, digest, size)
        with self.assertRaises(ValueError): publisher.validate_advance(old, dict(policyRevision=1, maxForcedVersionCode=0), 10, "0.6.1", "a"*64, 500)
        old["maxForcedVersionCode"] = 5
        with self.assertRaises(ValueError): publisher.validate_advance(old, policy, 11, "0.6.2", "b"*64, 600)

    def test_time_requires_timezone(self):
        data = self.manifest(); data["publishedAt"] = "2026-09-27T12:00:00"
        with self.assertRaises(ValueError): publisher.validate_manifest(data)

if __name__ == "__main__":
    unittest.main()
