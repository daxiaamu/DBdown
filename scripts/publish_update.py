"""Generate DBDown update metadata after a human has published one signed Release APK.
No APK uploads, Release creation, or forced-policy mutations are performed here.
"""
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone

ROOT = Path(__file__).resolve().parents[1]
MAX_APK = 512 * 1024 * 1024
MAX_METADATA = 1024 * 1024
PREFIXES = [
    "https://ghfast.top/", "https://gh-proxy.com/", "https://ghproxy.net/",
    "https://gh.llkk.cc/", "https://ghp.keleyaa.com/", "https://gh.monlor.com/",
    "https://ghproxy.vip/", "https://gh.jasonzeng.dev/", "https://gh.3w.pm/",
    "https://gh-proxy.org/", "https://v6.gh-proxy.com/", "https://v6.gh-proxy.org/",
]

class HTTPSRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        secure_url(newurl)
        redirected = super().redirect_request(req, fp, code, msg, headers, newurl)
        # Never forward GitHub credentials to proxies or CDN redirects.
        if redirected is not None and urllib.parse.urlsplit(newurl).hostname != "api.github.com":
            redirected.remove_header("Authorization")
        return redirected

def secure_url(url):
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password or parsed.fragment:
        raise ValueError("Invalid HTTPS URL")
    return parsed

def fetch(url, path=None, limit=MAX_METADATA, expected_size=None):
    secure_url(url)
    headers = {"User-Agent": "DBDown-Release-Updater/1", "Cache-Control": "no-cache"}
    if urllib.parse.urlsplit(url).hostname == "api.github.com" and os.environ.get("GH_TOKEN"):
        headers["Authorization"] = "Bearer " + os.environ["GH_TOKEN"]
    req = urllib.request.Request(url, headers=headers)
    opener = urllib.request.build_opener(HTTPSRedirect())
    digest = hashlib.sha256()
    received = 0
    data = bytearray()
    start = time.monotonic()
    with opener.open(req, timeout=25) as response:
        secure_url(response.url)
        if not 200 <= response.status < 300 or "text/html" in response.headers.get("Content-Type", ""):
            raise ValueError("Invalid response")
        length = response.headers.get("Content-Length")
        if length and (int(length) > limit or (expected_size is not None and int(length) != expected_size)):
            raise ValueError("Size mismatch")
        output = open(path, "wb") if path else None
        try:
            while True:
                if time.monotonic() - start > 180:
                    raise TimeoutError("Source exceeded total timeout")
                block = response.read(128 * 1024)
                if not block:
                    break
                received += len(block)
                if received > limit:
                    raise ValueError("Response too large")
                digest.update(block)
                if output:
                    output.write(block)
                else:
                    data.extend(block)
            if output:
                output.flush()
                os.fsync(output.fileno())
        finally:
            if output:
                output.close()
    if expected_size is not None and received != expected_size:
        raise ValueError("Incomplete file")
    return bytes(data), digest.hexdigest(), received, secure_url(response.url).hostname

def encoded(data):
    return (json.dumps(data, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n").encode()

def validate_manifest(data):
    for key in ("schemaVersion", "versionCode", "policyRevision", "maxForcedVersionCode", "size"):
        if type(data.get(key)) is not int:
            raise ValueError(key + " must be integer")
    if data["schemaVersion"] != 1 or data["channel"] not in ("stable", "beta"):
        raise ValueError("Invalid schema/channel")
    if not (0 <= data["maxForcedVersionCode"] < data["versionCode"]) or data["policyRevision"] <= 0:
        raise ValueError("Invalid version/policy")
    if not 0 < data["size"] <= MAX_APK or not re.fullmatch("[0-9a-f]{64}", data["sha256"]):
        raise ValueError("Invalid digest/size")
    urls = data["urls"]
    if not 5 <= len(urls) <= 20 or len(urls) != len(set(urls)):
        raise ValueError("Invalid URL count")
    hosts = {secure_url(url).hostname.lower() for url in urls}
    hosts -= {"github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com"}
    if len(hosts) < 5:
        raise ValueError("Need five distinct CDN hosts, excluding official fallback")
    if not data["versionName"] or len(data["versionName"]) > 80 or len(data["changelog"]) > 100000:
        raise ValueError("Invalid display metadata")
    published = datetime.fromisoformat(data["publishedAt"].replace("Z", "+00:00"))
    if published.tzinfo is None:
        raise ValueError("publishedAt requires a timezone")

def validate_generated():
    for channel in ("stable", "beta"):
        folder = ROOT / "updates" / channel
        if not (folder / "latest.json").exists():
            continue
        pointer = json.loads((folder / "latest.json").read_text(encoding="utf-8"))
        revision, digest = pointer["policyRevision"], pointer["manifestSha256"]
        path = f"updates/{channel}/manifests/{revision}-{digest}.json"
        if pointer["manifestPath"] != path:
            raise ValueError("Invalid immutable path")
        raw = (ROOT / path).read_bytes()
        if hashlib.sha256(raw).hexdigest() != digest:
            raise ValueError("Manifest SHA mismatch")
        manifest = json.loads(raw)
        validate_manifest(manifest)
        if manifest["channel"] != channel or manifest["policyRevision"] != revision:
            raise ValueError("Channel/revision mismatch")
        policy = json.loads((ROOT / "updates" / f"policy-{channel}.json").read_text(encoding="utf-8"))
        if policy["policyRevision"] != revision or policy["maxForcedVersionCode"] != manifest["maxForcedVersionCode"]:
            raise ValueError("Policy changed during publication; rerun with review")

def atomic_write(path, raw):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_bytes(raw)
    temporary.replace(path)

def validate_advance(old, policy, code, name, digest, size):
    if policy["policyRevision"] <= old["policyRevision"] or code < old["versionCode"]:
        raise ValueError("Increment policyRevision and never roll back APK versionCode")
    if code == old["versionCode"] and (name != old["versionName"] or digest != old["sha256"] or size != old["size"]):
        raise ValueError("Metadata-only revisions must keep the exact published APK")
    if policy["maxForcedVersionCode"] < old["maxForcedVersionCode"]:
        raise ValueError("Cannot reduce durable forced boundary")


def main():
    if "--validate-only" in sys.argv:
        validate_generated()
        return
    repository = os.environ["UPDATE_REPOSITORY"]
    tag = os.environ["UPDATE_TAG"]
    if repository != "daxiaamu/DBdown":
        raise ValueError("Workflow must run in the configured update repository")
    branch = os.environ.get("UPDATE_BRANCH", "main")
    release = json.loads(fetch(f"https://api.github.com/repos/{repository}/releases/tags/{urllib.parse.quote(tag, safe='')}")[0])
    if release["draft"]:
        raise ValueError("Release must already be published")
    assets = [a for a in release["assets"] if a["name"].lower().endswith(".apk")]
    if len(assets) != 1:
        raise ValueError("Release must contain exactly one APK")
    asset = assets[0]
    channel = "beta" if release["prerelease"] else "stable"
    policy_path = ROOT / "updates" / f"policy-{channel}.json"
    policy = json.loads(policy_path.read_text(encoding="utf-8"))
    if policy["channel"] != channel or not str(policy.get("reason", "")).strip():
        raise ValueError("Policy channel/reason missing")
    for field in ("policyRevision", "maxForcedVersionCode"):
        if type(policy.get(field)) is not int:
            raise ValueError("Policy integers required")
    official = asset["browser_download_url"]
    if not official.startswith(f"https://github.com/{repository}/releases/download/"):
        raise ValueError("Unexpected Release asset URL")
    with tempfile.TemporaryDirectory() as work:
        apk = Path(work) / "release.apk"
        _, digest, size, _ = fetch(official, apk, limit=MAX_APK, expected_size=asset["size"])
        sdk = Path(os.environ.get("ANDROID_HOME", os.environ.get("ANDROID_SDK_ROOT", "")))
        tools = sdk / "build-tools" / "36.0.0"
        output = subprocess.check_output([str(tools / "aapt2"), "dump", "badging", str(apk)], text=True)
        match = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", output)
        if not match or match[1] != "com.daxiaamu.dbdown":
            raise ValueError("Unexpected APK identity")
        code, name = int(match[2]), match[3]
        subprocess.run([str(tools / "apksigner"), "verify", str(apk)], check=True)
        if tag != "v" + name:
            raise ValueError("Tag must be v + APK versionName")
        is_beta = bool(re.search(r"(alpha|beta|rc)", name, re.I))
        if is_beta != release["prerelease"]:
            raise ValueError("Release and APK channels differ")
        previous_path = ROOT / "updates" / channel / "latest.json"
        if previous_path.exists():
            old_pointer = json.loads(previous_path.read_text(encoding="utf-8"))
            old = json.loads((ROOT / old_pointer["manifestPath"]).read_text(encoding="utf-8"))
            validate_advance(old, policy, code, name, digest, size)
        if not 0 <= policy["maxForcedVersionCode"] < code:
            raise ValueError("Invalid forced boundary")

        def verify_cdn(url):
            start = time.monotonic()
            try:
                # Each candidate is fully downloaded and hashed, never accepted on HTTP status alone.
                target = Path(work) / (hashlib.sha256(url.encode()).hexdigest() + ".apk")
                _, candidate_digest, _, final_host = fetch(url, target, limit=size, expected_size=size)
                target.unlink(missing_ok=True)
                if candidate_digest == digest and final_host not in {"github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com"}:
                    return time.monotonic() - start, url, final_host
            except Exception as error:
                print("CDN rejected:", secure_url(url).hostname, type(error).__name__, flush=True)
            return None

        candidates = [prefix + official for prefix in PREFIXES]
        candidates.append(f"https://xget.xi-xu.me/gh/{repository}/releases/download/{urllib.parse.quote(tag, safe='')}/{urllib.parse.quote(asset['name'], safe='')}")
        with concurrent.futures.ThreadPoolExecutor(max_workers=5) as pool:
            verified = [result for result in pool.map(verify_cdn, candidates) if result]
        urls, final_hosts = [], set()
        for _, url, host in sorted(verified):
            if host not in final_hosts:
                urls.append(url)
                final_hosts.add(host)
        if len({secure_url(url).hostname for url in urls}) < 5:
            raise ValueError("Fewer than five verified CDN hosts; previous metadata is unchanged")
        manifest = {
            "schemaVersion": 1, "channel": channel, "versionCode": code, "versionName": name,
            "publishedAt": release["published_at"], "changelog": release.get("body") or "",
            "policyRevision": policy["policyRevision"], "maxForcedVersionCode": policy["maxForcedVersionCode"],
            "sha256": digest, "size": size, "urls": urls + [official], "releaseUrl": release["html_url"],
        }
        validate_manifest(manifest)
        raw = encoded(manifest)
        if len(raw) > MAX_METADATA:
            raise ValueError("Metadata too large")
        manifest_hash = hashlib.sha256(raw).hexdigest()
        path = f"updates/{channel}/manifests/{policy['policyRevision']}-{manifest_hash}.json"
        if list((ROOT / "updates" / channel / "manifests").glob(f"{policy['policyRevision']}-*.json")):
            raise ValueError("Revision was already used; immutable manifests cannot be overwritten")
        pointer = {
            "schemaVersion": 1, "channel": channel, "policyRevision": policy["policyRevision"],
            "manifestPath": path, "manifestSha256": manifest_hash,
            "expiresAt": (datetime.now(timezone.utc) + timedelta(days=90)).isoformat().replace("+00:00", "Z"),
        }
        atomic_write(ROOT / path, raw)
        # The immutable bytes and pointer are published together in one Git commit.
        for other in ("stable", "beta"):
            (ROOT / "updates" / other).mkdir(parents=True, exist_ok=True)
            (ROOT / "updates" / other / ".gitkeep").touch(exist_ok=True)
        atomic_write(ROOT / "updates" / channel / "latest.json", encoded(pointer))
        validate_generated()
        print(f"Validated {channel} revision {policy['policyRevision']} with {len(urls)} CDN hosts")

if __name__ == "__main__":
    main()
