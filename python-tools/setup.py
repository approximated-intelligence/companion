#!/usr/bin/env python3
"""
setup.py — one-time bootstrap for the travel companion infrastructure.

Orchestrates:
  1. GitHub — OAuth flow in browser, ensure {username}.github.io repo + Pages enabled
  2. Backblaze B2 — create private bucket, create scoped app keys
  3. HTTP auth — enter endpoint, static URL, user, password for plain HTTP PUT + digest auth
  4. S3 direct — enter upload access key + secret directly
  5. S3 minio — setting up access using admin key + secret
  6. Backup key — derive NaCl keypair from passphrase
  7. QR codes — terminal (ANSI half-blocks) + PNG files + printable markdown report

Standalone (not in --all):
  --cloudflare  enable Smart Tiered Cache, create WAF HMAC rule
  --minio       create scoped upload user via MinIO admin API

Usage:
    uv run setup.py --config config.json --all
    uv run setup.py --config config.json --cloudflare
    uv run setup.py --config config.json --http-auth
    uv run setup.py --config config.json --s3-direct
    uv run setup.py --config config.json --s3-minio
    uv run setup.py --config config.json --qr
"""

# /// script
# requires-python = ">=3.11"
# dependencies = [
#   "requests",
#   "qrcode[pil]",
#   "pillow",
#   "argon2-cffi",
#   "cryptography",
# ]
# ///

import argparse
import json
import secrets
import sys
import http.server
import threading
import urllib.parse
import webbrowser
import uuid as _uuid
from dataclasses import dataclass, field
from getpass import getpass
from pathlib import Path

import requests
import qrcode
import qrcode.constants

from passphrase_utils import derive_keypair, validate_passphrase

# ---------------------------------------------------------------------------
# Config I/O
# ---------------------------------------------------------------------------


def load_config(path: str = "config.json") -> dict:
    p = Path(path)
    if not p.exists():
        return {}
    with p.open() as f:
        return json.load(f)


def save_config(cfg: dict, path: str = "config.json") -> None:
    scrubbed = {
        k: v for k, v in cfg.items()
        if k not in ("sk", "priv", "private", "secret", "s3_admin_key_id", "s3_admin_secret_key")
    }
    with open(path, "w") as f:
        json.dump(scrubbed, f, indent=2)
    print(f"Config saved → {path}")


def require(cfg: dict, *keys: str) -> None:
    missing = [k for k in keys if k not in cfg]
    if missing:
        print(f"ERROR: missing config keys: {', '.join(missing)}", file=sys.stderr)
        sys.exit(1)


# ---------------------------------------------------------------------------
# QR output
# ---------------------------------------------------------------------------

_UPPER = "\u2580"
_LOWER = "\u2584"
_FULL  = "\u2588"
_EMPTY = " "


def make_qr(data: str, error_correction=qrcode.constants.ERROR_CORRECT_M) -> qrcode.QRCode:
    qr = qrcode.QRCode(version=None, error_correction=error_correction, box_size=10, border=2)
    qr.add_data(data)
    qr.make(fit=True)
    return qr


def render_terminal(data: str, label: str = "") -> None:
    qr = make_qr(data)
    matrix = qr.get_matrix()
    rows, cols = len(matrix), len(matrix[0]) if matrix else 0
    if label:
        print(f"\n{'─' * (cols + 4)}\n  {label}\n{'─' * (cols + 4)}")
    for row_idx in range(0, rows, 2):
        line = "  "
        for col_idx in range(cols):
            top = matrix[row_idx][col_idx]
            bot = matrix[row_idx + 1][col_idx] if row_idx + 1 < rows else False
            line += _FULL if (top and bot) else _UPPER if top else _LOWER if bot else _EMPTY
        print(line)
    print()


def render_png(data: str, output_path: Path) -> Path:
    qr = make_qr(data)
    img = qr.make_image(fill_color="black", back_color="white")
    output_path.parent.mkdir(parents=True, exist_ok=True)
    img.save(str(output_path))
    return output_path


@dataclass
class QrEntry:
    label: str
    data: str
    description: str
    slug: str


@dataclass
class QrReport:
    entries: list[QrEntry] = field(default_factory=list)

    def add(self, label: str, data: str, description: str = "") -> None:
        slug = label.lower().replace(" ", "_").replace("/", "_")
        self.entries.append(QrEntry(label=label, data=data, description=description, slug=slug))

    def render_all_terminal(self) -> None:
        for entry in self.entries:
            render_terminal(entry.data, label=entry.label)
            if entry.description:
                print(f"  → {entry.description}")
            print(f"  Value: {entry.data[:80]}{'...' if len(entry.data) > 80 else ''}\n")

    def write_pngs(self, png_dir: Path) -> list[Path]:
        paths = []
        for entry in self.entries:
            p = render_png(entry.data, png_dir / f"{entry.slug}.png")
            paths.append(p)
            print(f"PNG → {p}")
        return paths

    def write_markdown(self, md_path: Path, png_dir: Path) -> Path:
        png_paths = self.write_pngs(png_dir)
        lines = ["# Companion — Setup QR Codes\n", "_Print this page and store it securely._\n", ""]
        for entry, png_path in zip(self.entries, png_paths):
            rel = png_path.relative_to(md_path.parent) if png_path.is_relative_to(md_path.parent) else png_path
            lines += [f"## {entry.label}\n", f"![{entry.label}]({rel}){{width=45%}}\n", ""]
            if entry.description:
                lines.append(f"**Where to scan:** {entry.description}\n")
            lines += [f"**Value:** `{entry.data}`\n", "", "---\n", ""]
        md_path.parent.mkdir(parents=True, exist_ok=True)
        md_path.write_text("\n".join(lines), encoding="utf-8")
        print(f"\nMarkdown report → {md_path}")
        print(f"Generate PDF:     pandoc {md_path.name} -o setup_report.pdf")
        return md_path


# ---------------------------------------------------------------------------
# Cloudflare client
# ---------------------------------------------------------------------------

CF_BASE          = "https://api.cloudflare.com/client/v4"
HMAC_TTL_SECONDS = 315_360_000


def _cf_headers(token: str) -> dict:
    return {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}


def cf_get(token: str, path: str) -> dict:
    r = requests.get(f"{CF_BASE}{path}", headers=_cf_headers(token))
    r.raise_for_status()
    data = r.json()
    if not data["success"]:
        raise RuntimeError(f"CF API error: {data['errors']}")
    return data


def cf_patch(token: str, path: str, body: dict) -> dict:
    r = requests.patch(f"{CF_BASE}{path}", headers=_cf_headers(token), json=body)
    r.raise_for_status()
    data = r.json()
    if not data["success"]:
        raise RuntimeError(f"CF API error: {data['errors']}")
    return data


def cf_put(token: str, path: str, body: dict) -> dict:
    r = requests.put(f"{CF_BASE}{path}", headers=_cf_headers(token), json=body)
    r.raise_for_status()
    data = r.json()
    if not data["success"]:
        raise RuntimeError(f"CF API error: {data['errors']}")
    return data


def get_tiered_cache(token: str, zone_id: str) -> str:
    return cf_get(token, f"/zones/{zone_id}/cache/tiered_cache_smart_topology_enable")["result"]["value"]


def set_tiered_cache(token: str, zone_id: str, value: str) -> str:
    return cf_patch(token, f"/zones/{zone_id}/cache/tiered_cache_smart_topology_enable", {"value": value})["result"]["value"]


def get_zone_ruleset(token: str, zone_id: str) -> dict:
    return cf_get(token, f"/zones/{zone_id}/rulesets/phases/http_request_firewall_custom/entrypoint")["result"]


def update_rule_in_ruleset(token: str, zone_id: str, ruleset_id: str, rule_id: str, new_expression: str, description: str, action: str = "block") -> dict:
    ruleset = get_zone_ruleset(token, zone_id)
    rules   = ruleset["rules"]
    for rule in rules:
        if rule["id"] == rule_id:
            rule["expression"]  = new_expression
            rule["description"] = description
            rule["action"]      = action
            break
    else:
        raise ValueError(f"Rule {rule_id} not found in ruleset {ruleset_id}")
    return cf_put(token, f"/zones/{zone_id}/rulesets/{ruleset_id}", {"rules": rules})["result"]


def build_hmac_expression(media_host: str, secret: str) -> str:
    return (
        f'(http.host eq "{media_host}" and '
        f'not is_timed_hmac_valid_v0("{secret}", http.request.uri, '
        f"{HMAC_TTL_SECONDS}, http.request.timestamp.sec, 8))"
    )


# ---------------------------------------------------------------------------
# B2 client
# ---------------------------------------------------------------------------

B2_AUTH_URL = "https://api.backblazeb2.com/b2api/v3/b2_authorize_account"


def b2_authorize(key_id: str, application_key: str) -> dict:
    r = requests.get(B2_AUTH_URL, auth=(key_id, application_key), timeout=15)
    r.raise_for_status()
    data    = r.json()
    storage = data["apiInfo"]["storageApi"]
    if "apiUrl" not in storage:
        raise RuntimeError(f"B2 auth failed: {data}")
    return {
        "accountId":          data["accountId"],
        "authorizationToken": data["authorizationToken"],
        "apiUrl":             storage["apiUrl"],
        "s3ApiUrl":           storage["s3ApiUrl"],
        "downloadUrl":        storage["downloadUrl"],
    }


def b2_region_from_s3_url(s3_api_url: str) -> str:
    """Pure: extract B2 region from S3 API URL.
    e.g. https://s3.us-west-004.backblazeb2.com → us-west-004
    """
    host  = s3_api_url.removeprefix("https://").removeprefix("http://")
    parts = host.split(".")
    return parts[1] if len(parts) >= 2 else "us-west-004"


def _b2_post(auth: dict, endpoint: str, body: dict) -> dict:
    r = requests.post(
        f"{auth['apiUrl']}/b2api/v3/{endpoint}",
        headers={"Authorization": auth["authorizationToken"]},
        json=body, timeout=15,
    )
    r.raise_for_status()
    return r.json()


def b2_list_buckets(auth: dict) -> list[dict]:
    return _b2_post(auth, "b2_list_buckets", {"accountId": auth["accountId"]})["buckets"]


def b2_find_bucket(auth: dict, name: str) -> dict | None:
    return next((b for b in b2_list_buckets(auth) if b["bucketName"] == name), None)


def b2_create_bucket(auth: dict, name: str) -> dict:
    return _b2_post(auth, "b2_create_bucket", {
        "accountId":      auth["accountId"],
        "bucketName":     name,
        "bucketType":     "allPrivate",
        "lifecycleRules": [],
    })


def b2_ensure_bucket(auth: dict, name: str) -> dict:
    existing = b2_find_bucket(auth, name)
    if existing:
        print(f"  B2 bucket '{name}' exists", end=" ", flush=True)
        if existing["bucketType"] != "allPrivate":
            print("— NOT private, fixing...", end=" ", flush=True)
            existing = _b2_post(auth, "b2_update_bucket", {
                "accountId":  auth["accountId"],
                "bucketId":   existing["bucketId"],
                "bucketType": "allPrivate",
            })
        print("✓")
        return existing
    print(f"  Creating B2 bucket '{name}'...", end=" ", flush=True)
    bucket = b2_create_bucket(auth, name)
    print("✓")
    return bucket


def b2_list_app_keys(auth: dict) -> list[dict]:
    return _b2_post(auth, "b2_list_keys", {"accountId": auth["accountId"]})["keys"]


def b2_create_scoped_key(auth: dict, bucket_id: str, key_name: str, capabilities: list[str]) -> dict:
    return _b2_post(auth, "b2_create_key", {
        "accountId":    auth["accountId"],
        "keyName":      key_name,
        "bucketId":     bucket_id,
        "capabilities": capabilities,
    })


def b2_delete_key(auth: dict, key_id: str) -> None:
    _b2_post(auth, "b2_delete_key", {"applicationKeyId": key_id})


def b2_ensure_scoped_key(auth: dict, bucket_id: str, key_name: str, capabilities: list[str], existing_key_id: str | None) -> dict | None:
    required = set(capabilities)
    for k in b2_list_app_keys(auth):
        if k["keyName"] != key_name:
            continue
        actual = set(k["capabilities"])
        if actual != required:
            print(f"  App key '{key_name}' capabilities changed, rotating...", end=" ", flush=True)
            b2_delete_key(auth, k["applicationKeyId"])
            print("deleted old", end=" ", flush=True)
            new_key = b2_create_scoped_key(auth, bucket_id, key_name, capabilities)
            print("✓")
            return new_key
        if existing_key_id and k["applicationKeyId"] == existing_key_id:
            print(f"  App key '{key_name}' ✓")
        else:
            print(f"  App key '{key_name}' exists on B2 but secret is not in config.")
            print("  Delete the key at b2.com → App Keys, then re-run.")
        return None
    print(f"  Creating scoped B2 app key '{key_name}'...", end=" ", flush=True)
    key = b2_create_scoped_key(auth, bucket_id, key_name, capabilities)
    print("✓")
    return key


# ---------------------------------------------------------------------------
# MinIO admin client (MinIO-proprietary API, not standard S3)
# ---------------------------------------------------------------------------


def _sigv4_headers(method: str, path: str, body: bytes, access_key: str, secret_key: str, endpoint: str) -> dict:
    """Pure: build AWS SigV4 headers."""
    import hashlib
    import hmac as _hmac
    from datetime import datetime, timezone

    now          = datetime.now(timezone.utc)
    amz_date     = now.strftime("%Y%m%dT%H%M%SZ")
    date_stamp   = now.strftime("%Y%m%d")
    host         = urllib.parse.urlparse(endpoint).netloc
    payload_hash = hashlib.sha256(body).hexdigest()

    canonical = "\n".join([
        method, path, "",
        f"host:{host}",
        f"x-amz-content-sha256:{payload_hash}",
        f"x-amz-date:{amz_date}",
        "",
        "host;x-amz-content-sha256;x-amz-date",
        payload_hash,
    ])

    credential_scope = f"{date_stamp}/us-east-1/s3/aws4_request"
    string_to_sign   = "\n".join([
        "AWS4-HMAC-SHA256", amz_date, credential_scope,
        hashlib.sha256(canonical.encode()).hexdigest(),
    ])

    def _sign(key: bytes, msg: str) -> bytes:
        return _hmac.new(key, msg.encode(), hashlib.sha256).digest()

    signing_key = _sign(_sign(_sign(_sign(f"AWS4{secret_key}".encode(), date_stamp), "us-east-1"), "s3"), "aws4_request")
    signature   = _hmac.new(signing_key, string_to_sign.encode(), hashlib.sha256).hexdigest()

    return {
        "Authorization": (
            f"AWS4-HMAC-SHA256 Credential={access_key}/{credential_scope}, "
            f"SignedHeaders=host;x-amz-content-sha256;x-amz-date, "
            f"Signature={signature}"
        ),
        "x-amz-date":           amz_date,
        "x-amz-content-sha256": payload_hash,
    }


def _minio_admin(method: str, path: str, access_key: str, secret_key: str, endpoint: str, body: bytes = b"") -> dict:
    """I/O: call MinIO admin API."""
    headers = _sigv4_headers(method, path, body, access_key, secret_key, endpoint)
    if body:
        headers["Content-Type"] = "application/json"
    r = requests.request(method, f"{endpoint}{path}", headers=headers, data=body, timeout=15)
    r.raise_for_status()
    return r.json() if r.content else {}


def minio_add_user(endpoint: str, access_key: str, secret_key: str, new_user: str, new_password: str) -> None:
    body = json.dumps({"secretKey": new_password}).encode()
    _minio_admin("POST", f"/minio/admin/v3/add-user?accessKey={new_user}", access_key, secret_key, endpoint, body)


def minio_add_policy(endpoint: str, access_key: str, secret_key: str, policy_name: str, policy: dict) -> None:
    body = json.dumps(policy).encode()
    _minio_admin("PUT", f"/minio/admin/v3/add-canned-policy?name={policy_name}", access_key, secret_key, endpoint, body)


def minio_attach_policy(endpoint: str, access_key: str, secret_key: str, policy_name: str, user: str) -> None:
    body = json.dumps({"policies": [policy_name], "user": user}).encode()
    _minio_admin("POST", "/minio/admin/v3/attach-user-or-group-policy", access_key, secret_key, endpoint, body)


def minio_create_access_key(endpoint: str, access_key: str, secret_key: str, target_user: str) -> dict:
    body = json.dumps({"targetUser": target_user}).encode()
    return _minio_admin("PUT", "/minio/admin/v3/idp/builtin/user/service-account", access_key, secret_key, endpoint, body)


def minio_bucket_policy(bucket: str) -> dict:
    """Pure: IAM policy granting PutObject, DeleteObject, ListBucket on one bucket."""
    return {
        "Version": "2012-10-17",
        "Statement": [
            {"Effect": "Allow", "Action": ["s3:PutObject", "s3:DeleteObject"], "Resource": [f"arn:aws:s3:::{bucket}/*"]},
            {"Effect": "Allow", "Action": ["s3:ListBucket"],                   "Resource": [f"arn:aws:s3:::{bucket}"]},
        ],
    }


# ---------------------------------------------------------------------------
# GitHub OAuth
# ---------------------------------------------------------------------------

GITHUB_AUTHORIZE_URL = "https://github.com/login/oauth/authorize"
GITHUB_TOKEN_URL     = "https://github.com/login/oauth/access_token"
CALLBACK_PORT        = 8742
SCOPES               = "repo,pages"


def build_authorize_url(client_id: str, port: int = CALLBACK_PORT) -> str:
    params = urllib.parse.urlencode({
        "client_id":    client_id,
        "redirect_uri": f"http://localhost:{port}/callback",
        "scope":        SCOPES,
    })
    return f"{GITHUB_AUTHORIZE_URL}?{params}"


@dataclass
class _OAuthResult:
    code:  str | None = None
    error: str | None = None


def _make_handler(result: _OAuthResult, shutdown_event: threading.Event):
    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            parsed = urllib.parse.urlparse(self.path)
            params = urllib.parse.parse_qs(parsed.query)
            if parsed.path == "/callback":
                if "code" in params:
                    result.code = params["code"][0]
                    body = b"<html><body><h2>Authorized! You can close this tab.</h2></body></html>"
                elif "error" in params:
                    result.error = params["error_description"][0] if "error_description" in params else "Unknown error"
                    body = b"<html><body><h2>Authorization failed.</h2></body></html>"
                else:
                    result.error = "No code or error in callback"
                    body = b"<html><body><h2>Unexpected response.</h2></body></html>"
            else:
                body = b"<html><body><p>Waiting for GitHub callback...</p></body></html>"
            self.send_response(200)
            self.send_header("Content-Type", "text/html")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            shutdown_event.set()

        def log_message(self, fmt, *args):
            pass

    return Handler


def _wait_for_callback(port: int) -> _OAuthResult:
    result         = _OAuthResult()
    shutdown_event = threading.Event()
    server         = http.server.HTTPServer(("localhost", port), _make_handler(result, shutdown_event))
    thread         = threading.Thread(target=server.handle_request)
    thread.start()
    shutdown_event.wait(timeout=120)
    thread.join(timeout=5)
    server.server_close()
    return result


def exchange_code(client_id: str, client_secret: str, code: str, port: int = CALLBACK_PORT) -> str:
    r = requests.post(
        GITHUB_TOKEN_URL,
        headers={"Accept": "application/json"},
        data={"client_id": client_id, "client_secret": client_secret, "code": code, "redirect_uri": f"http://localhost:{port}/callback"},
        timeout=15,
    )
    r.raise_for_status()
    data = r.json()
    if "error" in data:
        raise RuntimeError(f"GitHub token exchange failed: {data['error']} — {data['error_description']}")
    return data["access_token"]


def run_oauth_flow(client_id: str, client_secret: str, port: int = CALLBACK_PORT) -> str:
    url = build_authorize_url(client_id, port)
    print(f"\nOpening GitHub authorization in your browser...")
    print(f"If it doesn't open, visit:\n  {url}\n")
    webbrowser.open(url)
    print("Waiting for callback (timeout: 120s)...")
    result = _wait_for_callback(port)
    if result.error:
        raise RuntimeError(f"OAuth authorization failed: {result.error}")
    if not result.code:
        raise RuntimeError("No code received — did you authorize the app?")
    print("Code received, exchanging for token...", end=" ", flush=True)
    token = exchange_code(client_id, client_secret, result.code, port)
    print("✓")
    return token


def get_authenticated_user(token: str) -> dict:
    r = requests.get(
        "https://api.github.com/user",
        headers={"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json"},
        timeout=10,
    )
    r.raise_for_status()
    return r.json()


def ensure_repo(token: str, username: str, repo_name: str) -> dict:
    gh_headers = {"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json", "X-GitHub-Api-Version": "2022-11-28"}
    r = requests.get(f"https://api.github.com/repos/{username}/{repo_name}", headers=gh_headers, timeout=10)
    if r.status_code == 200:
        print(f"Repo {repo_name} already exists ✓")
        return r.json()
    if r.status_code != 404:
        r.raise_for_status()
    print(f"Creating repo {repo_name}...", end=" ", flush=True)
    r = requests.post(
        "https://api.github.com/user/repos", headers=gh_headers,
        json={"name": repo_name, "description": "Travel blog", "homepage": f"https://{repo_name}", "auto_init": True, "private": False},
        timeout=15,
    )
    r.raise_for_status()
    print("✓")
    return r.json()


def enable_pages(token: str, username: str, repo_name: str) -> None:
    gh_headers = {"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json", "X-GitHub-Api-Version": "2022-11-28"}
    r = requests.get(f"https://api.github.com/repos/{username}/{repo_name}/pages", headers=gh_headers, timeout=10)
    if r.status_code == 200:
        print("GitHub Pages already enabled ✓")
        return
    print("Enabling GitHub Pages...", end=" ", flush=True)
    r = requests.post(
        f"https://api.github.com/repos/{username}/{repo_name}/pages", headers=gh_headers,
        json={"build_type": "legacy", "source": {"branch": "main", "path": "/"}}, timeout=15,
    )
    if r.status_code in (201, 409):
        print("✓")
    else:
        r.raise_for_status()


# ---------------------------------------------------------------------------
# Interactive prompts
# ---------------------------------------------------------------------------

_GITHUB_PROMPTS = [
    ("github_client_id", "GitHub OAuth Client ID",
     "Create an OAuth App at:\n"
     "  github.com → Settings → Developer Settings → OAuth Apps → New OAuth App\n"
     "  Application name:         companion-publisher\n"
     "  Homepage URL:             http://localhost\n"
     "  Authorization callback:   http://localhost:8742/callback"),
    ("github_client_secret", "GitHub OAuth Client Secret", None),
]

_B2_PROMPTS = [
    ("b2_master_key_id", "B2 Master Key ID",
     "Create a master application key at:\n"
     "  backblaze.com → Account → App Keys → Add a New Application Key\n"
     "  Type of access: Read and Write, allow access to all buckets"),
    ("b2_master_application_key", "B2 Master Application Key", None),
]

_CF_PROMPTS = [
    ("cf_api_token", "Cloudflare API Token",
     "Create an API token at:\n"
     "  dash.cloudflare.com → My Profile → API Tokens → Create Token\n"
     "  Permissions: Zone → Cache Purge, Firewall Services, Zone Settings"),
    ("cf_zone_id", "Cloudflare Zone ID",
     "Found at: dash.cloudflare.com → your domain → Overview (right sidebar)"),
    ("media_host", "Media hostname (e.g. media.yourdomain.com)", None),
]

_S3_PROMPTS = [
    ("s3_endpoint",   "MinIO S3 endpoint (e.g. https://s3.yourdomain.com)", None),
    ("s3_static_url", "Static files URL (e.g. https://static.yourdomain.com)", None),
    ("s3_bucket",     "Bucket name (must match nginx root dir name)", None),
]

_S3_ADMIN_PROMPTS = [
    ("s3_admin_key_id",     "MinIO admin access key ID", None),
    ("s3_admin_secret_key", "MinIO admin secret key",    None),
]

_S3_DIRECT_PROMPTS = [
    ("s3_upload_key_id",     "Upload access key ID", None),
    ("s3_upload_secret_key", "Upload secret key",    None),
]

_HTTP_AUTH_PROMPTS = [
    ("http_media_endpoint",   "HTTP PUT endpoint (e.g. https://media.yourdomain.com)", None),
    ("http_media_static_url", "Static URL (e.g. https://static.yourdomain.com)",       None),
    ("http_media_user",       "Username for digest auth",                               None),
    ("http_media_password",   "Password for digest auth",                               None),
]


def prompt_missing(cfg: dict, prompts: list[tuple[str, str, str | None]], config_path: str) -> dict:
    instruction_shown = False
    for key, label, instruction in prompts:
        if key in cfg:
            continue
        if instruction and not instruction_shown:
            print(f"\n{instruction}\n")
            instruction_shown = True
        value = input(f"  {label}: ").strip()
        if not value:
            print(f"ERROR: {label} is required.", file=sys.stderr)
            sys.exit(1)
        cfg[key] = value
        save_config(cfg, config_path)
    return cfg


# ---------------------------------------------------------------------------
# Setup sections
# ---------------------------------------------------------------------------


def setup_github(cfg: dict, config_path: str) -> dict:
    cfg = prompt_missing(cfg, _GITHUB_PROMPTS, config_path)
    print("\n=== GitHub OAuth ===")
    if "github_token" in cfg:
        print("Checking existing GitHub token...", end=" ", flush=True)
        try:
            user      = get_authenticated_user(cfg["github_token"])
            username  = user["login"]
            repo_name = cfg["github_repo"] if "github_repo" in cfg else f"{username}.github.io"
            print(f"✓ (authenticated as {username})")
            cfg["github_username"]  = username
            cfg["github_repo"]      = repo_name
            cfg["github_pages_url"] = f"https://{repo_name}"
            ensure_repo(cfg["github_token"], username, repo_name)
            enable_pages(cfg["github_token"], username, repo_name)
            return cfg
        except Exception:
            print("expired or invalid — re-authenticating")

    token     = run_oauth_flow(cfg["github_client_id"], cfg["github_client_secret"])
    user      = get_authenticated_user(token)
    username  = user["login"]
    repo_name = f"{username}.github.io"
    print(f"Authenticated as: {username}\nTarget repo:      {repo_name}")
    ensure_repo(token, username, repo_name)
    enable_pages(token, username, repo_name)
    cfg["github_token"]     = token
    cfg["github_username"]  = username
    cfg["github_repo"]      = repo_name
    cfg["github_pages_url"] = f"https://{repo_name}"
    return cfg


def setup_cloudflare(cfg: dict, config_path: str) -> dict:
    cfg = prompt_missing(cfg, _CF_PROMPTS, config_path)
    token, zone_id, media_host = cfg["cf_api_token"], cfg["cf_zone_id"], cfg["media_host"]
    print("\n=== Cloudflare ===")
    if get_tiered_cache(token, zone_id) != "on":
        print("Enabling Smart Tiered Cache...", end=" ", flush=True)
        print(f"-> {set_tiered_cache(token, zone_id, 'on')}")
    else:
        print("Smart Tiered Cache already enabled ✓")
    if "hmac_secret_hex" not in cfg:
        print("Generating HMAC secret...", end=" ", flush=True)
        cfg["hmac_secret_hex"] = secrets.token_bytes(32).hex()
        print("done")
    else:
        print("Using existing HMAC secret ✓")
    expression = build_hmac_expression(media_host, cfg["hmac_secret_hex"])
    if "cf_waf_ruleset_id" in cfg and "cf_waf_rule_id" in cfg:
        print(f"Updating WAF rule {cfg['cf_waf_rule_id']}...", end=" ", flush=True)
        update_rule_in_ruleset(token, zone_id, cfg["cf_waf_ruleset_id"], cfg["cf_waf_rule_id"], expression, "Require HMAC token for media")
        print("✓")
    else:
        print("Creating WAF HMAC rule...", end=" ", flush=True)
        ruleset    = get_zone_ruleset(token, zone_id)
        ruleset_id = ruleset["id"]
        r = requests.post(
            f"{CF_BASE}/zones/{zone_id}/rulesets/{ruleset_id}/rules",
            headers=_cf_headers(token),
            json={"description": "Require HMAC token for media", "expression": expression, "action": "block"},
            timeout=15,
        )
        r.raise_for_status()
        data = r.json()
        if not data["success"]:
            raise RuntimeError(f"CF API error: {data['errors']}")
        new_rule                 = next(ru for ru in reversed(data["result"]["rules"]) if ru["description"] == "Require HMAC token for media")
        cfg["cf_waf_ruleset_id"] = ruleset_id
        cfg["cf_waf_rule_id"]    = new_rule["id"]
        print(f"✓ (rule id: {new_rule['id']})")
    print(f"WAF expression:\n  {expression}\n")
    return cfg


def setup_backup_key(cfg: dict) -> dict:
    print("\n=== Backup Encryption Key ===")
    if "nacl_public_key_hex" in cfg:
        print(f"NaCl public key already in config: {cfg['nacl_public_key_hex'][:16]}…")
        phrase = getpass("Backup encryption passphrase: ")
        print("Deriving keypair (Argon2id ~2s)…", end=" ", flush=True)
        try:
            priv, pub = validate_passphrase(phrase, cfg["nacl_public_key_hex"])
            cfg["secret"] = priv.hex()
            print("✓ passphrase matches")
        except ValueError as e:
            print(f"\nERROR: {e}", file=sys.stderr)
            sys.exit(1)
        return cfg
    phrase  = getpass("Backup encryption passphrase: ")
    confirm = getpass("Confirm:                       ")
    if phrase != confirm:
        print("ERROR: passphrases do not match", file=sys.stderr)
        sys.exit(1)
    print("Deriving keypair (Argon2id ~2s)…", end=" ", flush=True)
    priv, pub = derive_keypair(phrase)
    print("done")
    cfg["nacl_public_key_hex"] = pub.hex()
    cfg["secret"]              = priv.hex()
    print(f"Public key: {pub.hex()}\nPrivate key is NOT stored — passphrase recovers it.")
    return cfg


def setup_b2(cfg: dict, config_path: str) -> dict:
    cfg = prompt_missing(cfg, _B2_PROMPTS, config_path)
    print("\n=== Backblaze B2 ===")
    print("Authenticating with B2...", end=" ", flush=True)
    auth = b2_authorize(cfg["b2_master_key_id"], cfg["b2_master_application_key"])
    print("✓")
    bucket_name     = cfg["b2_bucket"] if "b2_bucket" in cfg else str(_uuid.uuid4())
    bucket          = b2_ensure_bucket(auth, bucket_name)
    cfg["b2_bucket"]    = bucket_name
    cfg["b2_bucket_id"] = bucket["bucketId"]
    cfg["b2_endpoint"]  = auth["s3ApiUrl"]
    cfg["b2_region"]    = b2_region_from_s3_url(auth["s3ApiUrl"])

    for key_name, caps, id_field, key_field, label in [
        ("companion-android", ["listFiles", "writeFiles", "readFiles"],                "b2_rw_key_id",       "b2_rw_application_key",   "Android"),
        ("companion-admin",   ["listFiles", "readFiles", "writeFiles", "deleteFiles"], "b2_admin_key_id", "b2_admin_application_key", "Admin"),
    ]:
        existing = cfg[id_field] if id_field in cfg else None
        key = b2_ensure_scoped_key(auth, bucket["bucketId"], key_name, caps, existing)
        if key:
            cfg[id_field]  = key["applicationKeyId"]
            cfg[key_field] = key["applicationKey"]
            print(f"  {label} key created — applicationKey saved to config (shown only once).")
    return cfg


def setup_http_auth(cfg: dict, config_path: str) -> dict:
    """Enter HTTP PUT + digest auth credentials directly."""
    if all(k in cfg for k in ("http_media_endpoint", "http_media_static_url", "http_media_user", "http_media_password")):
        print("\n=== HTTP Auth ===")
        print(f"  Already configured — {cfg['http_media_user']} @ {cfg['http_media_endpoint']} ✓")
        return cfg
    cfg = prompt_missing(cfg, _HTTP_AUTH_PROMPTS, config_path)
    print("\n=== HTTP Auth ===")
    print(f"  {cfg['http_media_user']} @ {cfg['http_media_endpoint']} ✓")
    return cfg


def setup_s3_direct(cfg: dict, config_path: str) -> dict:
    """Enter upload access key + secret directly — no admin API calls."""
    if "s3_upload_key_id" in cfg and "s3_upload_secret_key" in cfg:
        print("\n=== S3 (direct) ===")
        print(f"  Already configured — upload key ID: {cfg['s3_upload_key_id']} ✓")
        return cfg
    cfg = prompt_missing(cfg, _S3_PROMPTS, config_path)
    cfg = prompt_missing(cfg, _S3_DIRECT_PROMPTS, config_path)
    cfg["s3_region"] = "us-east-1"
    print("\n=== S3 (direct) ===")
    print(f"  Upload key ID: {cfg['s3_upload_key_id']} ✓")
    return cfg


def setup_s3_minio(cfg: dict, config_path: str) -> dict:
    """Create a scoped upload user via MinIO admin API."""
    if "s3_upload_key_id" in cfg and "s3_upload_secret_key" in cfg:
        print("\n=== MinIO ===")
        print(f"  Already configured — upload key ID: {cfg['s3_upload_key_id']} ✓")
        return cfg
    cfg = prompt_missing(cfg, _S3_PROMPTS, config_path)
    cfg = prompt_missing(cfg, _S3_ADMIN_PROMPTS, config_path)

    endpoint     = cfg["s3_endpoint"]
    bucket       = cfg["s3_bucket"]
    admin_key    = cfg["s3_admin_key_id"]
    admin_secret = cfg["s3_admin_secret_key"]
    policy_name  = f"companion-{bucket}-rw"
    upload_user  = cfg["s3_upload_user"] if "s3_upload_user" in cfg else f"companion-upload-{secrets.token_hex(4)}"
    upload_pass  = cfg["s3_upload_password"] if "s3_upload_password" in cfg else secrets.token_urlsafe(32)

    print("\n=== MinIO ===")

    print(f"  Ensuring IAM policy '{policy_name}'...", end=" ", flush=True)
    minio_add_policy(endpoint, admin_key, admin_secret, policy_name, minio_bucket_policy(bucket))
    print("✓")

    print(f"  Ensuring user '{upload_user}'...", end=" ", flush=True)
    minio_add_user(endpoint, admin_key, admin_secret, upload_user, upload_pass)
    print("✓")

    print(f"  Attaching policy...", end=" ", flush=True)
    minio_attach_policy(endpoint, admin_key, admin_secret, policy_name, upload_user)
    print("✓")

    print(f"  Creating access key...", end=" ", flush=True)
    keys = minio_create_access_key(endpoint, admin_key, admin_secret, upload_user)
    print("✓")

    cfg["s3_upload_user"]       = upload_user
    cfg["s3_upload_password"]   = upload_pass
    cfg["s3_upload_key_id"]     = keys["accessKey"]
    cfg["s3_upload_secret_key"] = keys["secretKey"]
    cfg["s3_region"]            = "us-east-1"

    del cfg["s3_admin_key_id"]
    del cfg["s3_admin_secret_key"]

    print(f"  Upload key ID: {keys['accessKey']}")
    return cfg


# ---------------------------------------------------------------------------
# QR report
# ---------------------------------------------------------------------------


def _dest(screen: str) -> str:
    return f"App → Settings → {screen}"


def generate_qr_report(cfg: dict, output_dir: Path) -> None:
    print("\n=== Generating QR Codes ===")
    report = QrReport()
    bundle: dict = {"type": "companion_bootstrap_v1"}

    if "github_token" in cfg:
        gh_bundle = {"token": cfg["github_token"], "owner": cfg["github_username"], "repo": cfg["github_repo"]}
        report.add("GitHub config", json.dumps({"type": "github", **gh_bundle}), _dest("GitHub"))
        bundle["github"] = gh_bundle

    if "hmac_secret_hex" in cfg:
        report.add("Cloudflare HMAC Secret", cfg["hmac_secret_hex"], _dest("Cloudflare → HMAC Secret"))
        bundle["hmac_secret_hex"] = cfg["hmac_secret_hex"]

    if "cf_api_token" in cfg:
        cf_bundle = {
            "token":      cfg["cf_api_token"],
            "zone_id":    cfg["cf_zone_id"],
            "ruleset_id": cfg["cf_waf_ruleset_id"],
            "rule_id":    cfg["cf_waf_rule_id"],
            "media_host": cfg["media_host"],
        }
        report.add("Cloudflare API Config", json.dumps(cf_bundle), _dest("Cloudflare → API Config"))
        bundle["cloudflare"] = cf_bundle

    if "nacl_public_key_hex" in cfg:
        report.add("Backup Encryption Key (NaCl)", json.dumps({"type": "nacl_backup_pubkey", "hex": cfg["nacl_public_key_hex"]}), _dest("Backup → Scan Encryption Key"))
        bundle["nacl_public_key_hex"] = cfg["nacl_public_key_hex"]

    if "b2_rw_key_id" in cfg and "b2_rw_application_key" in cfg:
        b2_bundle = {
            "endpoint":        cfg["b2_endpoint"],
            "region":          cfg["b2_region"],
            "key_id":          cfg["b2_rw_key_id"],
            "application_key": cfg["b2_rw_application_key"],
            "bucket":          cfg["b2_bucket"],
        }
        report.add("Backblaze B2 Credentials", json.dumps(b2_bundle), _dest("Backup → B2 Credentials"))
        bundle["b2"] = b2_bundle

    if "s3_upload_key_id" in cfg and "s3_upload_secret_key" in cfg:
        s3_bundle = {
            "type":       "s3_media",
            "endpoint":   cfg["s3_endpoint"],
            "region":     cfg["s3_region"],
            "static_url": cfg["s3_static_url"],
            "bucket":     cfg["s3_bucket"],
            "key_id":     cfg["s3_upload_key_id"],
            "secret_key": cfg["s3_upload_secret_key"],
        }
        report.add("MinIO S3 Media Credentials", json.dumps(s3_bundle, separators=(",", ":")), _dest("Media → S3 Credentials"))
        bundle["s3"] = s3_bundle

    if "http_media_endpoint" in cfg and "http_media_password" in cfg:
        http_bundle = {
            "type":       "http_media",
            "endpoint":   cfg["http_media_endpoint"],
            "static_url": cfg["http_media_static_url"],
            "user":       cfg["http_media_user"],
            "password":   cfg["http_media_password"],
        }
        report.add("HTTP Media Credentials", json.dumps(http_bundle, separators=(",", ":")), _dest("Media → HTTP Credentials"))
        bundle["http"] = http_bundle

    if len(bundle) > 1:
        report.add("Full Bootstrap Config (scan once)", json.dumps(bundle, separators=(",", ":")), "App → Settings → Import Full Config")

    report.render_all_terminal()
    report.write_markdown(output_dir / "setup_report.md", output_dir / "qr_codes")


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------


def main():
    parser = argparse.ArgumentParser(description="Bootstrap travel companion infrastructure")
    parser.add_argument("--config",     default="config.json")
    parser.add_argument("--output",     default=".", help="Dir for QR PNGs + markdown report")
    parser.add_argument("--all",        action="store_true", help="github, b2, backup-key, qr")
    parser.add_argument("--github",     action="store_true")
    parser.add_argument("--b2",         action="store_true")
    parser.add_argument("--http-auth",  action="store_true", help="HTTP PUT + digest auth credentials (standalone)")
    parser.add_argument("--s3-direct",  action="store_true", help="Enter upload keys directly (standalone)")
    parser.add_argument("--s3-minio",   action="store_true", help="Create upload user via MinIO admin API (standalone)")
    parser.add_argument("--cloudflare", action="store_true", help="Cloudflare cache + WAF setup (standalone)")
    parser.add_argument("--backup-key", action="store_true")
    parser.add_argument("--qr",         action="store_true")
    args = parser.parse_args()

    if not any([args.all, args.github, args.b2, args.s3_direct, args.http_auth, args.s3_minio, args.cloudflare, args.backup_key, args.qr]):
        parser.print_help()
        sys.exit(0)

    cfg        = load_config(args.config)
    output_dir = Path(args.output).expanduser().resolve()
    changed    = False

    try:
        if args.all or args.github:
            cfg = setup_github(cfg, args.config);     changed = True
        if args.all or args.b2:
            cfg = setup_b2(cfg, args.config);         changed = True
        if args.http_auth:
            cfg = setup_http_auth(cfg, args.config);  changed = True
        if args.s3_direct:
            cfg = setup_s3_direct(cfg, args.config);  changed = True
        if args.s3_minio:
            cfg = setup_s3_minio(cfg, args.config);      changed = True
        if args.cloudflare:
            cfg = setup_cloudflare(cfg, args.config); changed = True
        if args.all or args.backup_key:
            cfg = setup_backup_key(cfg);              changed = True
        if args.all or args.qr or args.backup_key:
            generate_qr_report(cfg, output_dir)
    finally:
        if changed:
            save_config(cfg, args.config)

    print("\n✓ Setup complete.")
    for label, key, fmt in [
        ("Blog",   "github_pages_url",    lambda v: v),
        ("Media",  "media_host",          lambda v: f"https://{v}"),
        ("S3",     "s3_endpoint",         lambda v: v),
        ("HTTP",   "http_media_endpoint", lambda v: v),
        ("Static", "s3_static_url",       lambda v: v),
    ]:
        if key in cfg:
            print(f"  {label}:  {fmt(cfg[key])}")
    print(f"\nScan QR codes into the app, then:")
    print(f"  pandoc {output_dir}/setup_report.md -o setup_report.pdf")


if __name__ == "__main__":
    main()
