"""
Quick checks of the repo, run in CI on every PR and push to dev: config files parse, download URLs answer,
secrets are encrypted to the right keys, and permission contexts name existing servers.

  uv run check
"""

import json
import re
import subprocess
import sys
import tomllib
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent
MAP_KEYS = ("key", "sha256", "author")

errors = []


def error(message):
    errors.append(message)
    print(f"  {message}")


def tracked(*patterns):
    return [ROOT / p for p in subprocess.run(["git", "ls-files", *patterns], cwd=ROOT, check=True, text=True,
                                             capture_output=True).stdout.split()]


def load_toml(path):
    try:
        return tomllib.loads(path.read_text())
    except tomllib.TOMLDecodeError as e:
        error(f"{path.relative_to(ROOT)}: {e}")
        return {}


def check_config():
    print("config files")
    servers = {p.parent.name: load_toml(p) for p in sorted(ROOT.glob("servers/*/server.toml"))}
    for path in sorted(ROOT.glob("servers/*/maps/*.toml")):
        missing = [k for k in MAP_KEYS if k not in load_toml(path)]
        if missing:
            error(f"{path.relative_to(ROOT)}: missing {', '.join(missing)}")
    permissions = load_toml(ROOT / "permissions.toml")
    client = load_toml(ROOT / "tests/e2e/client.toml")
    for path in tracked("servers/*.json", "deploy/*.json"):
        try:
            json.loads(path.read_text())
        except json.JSONDecodeError as e:
            error(f"{path.relative_to(ROOT)}: {e}")
    return servers, permissions, client


def answers(url):
    # Some hosts refuse HEAD; a one-byte GET then.
    for request in (urllib.request.Request(url, method="HEAD"), urllib.request.Request(url, headers={"Range": "bytes=0-0"})):
        request.add_header("User-Agent", "propwash-check")
        try:
            with urllib.request.urlopen(request, timeout=30):
                return True
        except Exception:
            pass
    return False


def check_urls(servers, client):
    print("download URLs")
    urls = [client.get("headlessmc"), *client.get("mods", [])]
    for cfg in servers.values():
        urls += [cfg.get("url"), *cfg.get("mods", []), *cfg.get("plugins", []), *cfg.get("expansions", [])]
    urls = sorted({u for u in urls if u})
    with ThreadPoolExecutor(16) as pool:
        for url, ok in zip(urls, pool.map(answers, urls)):
            if not ok:
                error(f"does not answer: {url}")


def age_keys(rule):
    # A rule's age keys: a comma-separated string, or lists under key_groups.
    if "key_groups" in rule:
        return {key for group in rule["key_groups"] for key in group.get("age", [])}
    return {key.strip() for key in rule.get("age", "").split(",") if key.strip()}


def sops_rules():
    rules = yaml.safe_load((ROOT / ".sops.yaml").read_text())["creation_rules"]
    return [(re.compile(rule["path_regex"]), age_keys(rule)) for rule in rules]


def check_secrets():
    print("secrets")
    rules = sops_rules()
    for path in tracked("*.sops"):
        rel = str(path.relative_to(ROOT))
        text = path.read_text(errors="replace")
        if not re.search(r'^sops_version=|"sops": *\{', text, flags=re.M):
            error(f"{rel}: not encrypted")
            continue
        wanted = next((keys for regex, keys in rules if regex.search(rel)), None)
        if wanted is None:
            error(f"{rel}: no rule in .sops.yaml")
            continue
        recipients = set(re.findall(r'recipient"?\s*[:=]\s*"?(age1[0-9a-z]+)', text))
        if recipients != wanted:
            error(f"{rel}: encrypted to {sorted(recipients)}, .sops.yaml wants {sorted(wanted)} (sops updatekeys)")


def check_contexts(servers, permissions):
    print("permission contexts")
    for group, cfg in permissions.get("groups", {}).items():
        for permission in cfg.get("permissions", []):
            for server in re.findall(r"\bserver=(\S+)", permission):
                if server not in servers:
                    error(f"permissions.toml: group {group}: {permission!r}: no server {server!r}")


def main():
    servers, permissions, client = check_config()
    check_urls(servers, client)
    check_secrets()
    check_contexts(servers, permissions)
    if errors:
        sys.exit(f"{len(errors)} problem(s)")
    print("all good")
