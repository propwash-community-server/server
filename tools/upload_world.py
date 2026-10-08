"""
Pack a world save as a map, upload it to the world store and write servers/<server>/maps/<name>.toml.

  uv run upload-world <save folder> <name> --author <you> [--server fpv] [--local]

--local uploads to the store from local/compose.yaml; otherwise the bucket from secrets/s3.env.sops.
Only the overworld is taken; the map shows up in game as the dimension maps:<name>.
"""

import argparse
import hashlib
import re
import subprocess
import tarfile
import tempfile
from pathlib import Path

import boto3
from botocore import UNSIGNED
from botocore.config import Config

ROOT = Path(__file__).resolve().parent.parent


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("save", type=Path)
    parser.add_argument("name")
    parser.add_argument("--author", required=True)
    parser.add_argument("--server", default="fpv")
    parser.add_argument("--local", action="store_true")
    args = parser.parse_args()

    if not re.fullmatch(r"[a-z0-9_]+", args.name):
        raise SystemExit("name must be lowercase letters, digits and _")
    if not (ROOT / "servers" / args.server / "server.toml").exists():
        raise SystemExit(f"no server '{args.server}' in servers/")

    # 26.x saves keep the overworld under dimensions/, older ones at the top level.
    source = args.save / "dimensions/minecraft/overworld"
    if not source.is_dir():
        source = args.save
    parts = [p for p in ("region", "entities", "poi") if (source / p).is_dir()]
    if "region" not in parts:
        raise SystemExit(f"no region/ folder in {source}")

    with tempfile.TemporaryDirectory() as tmp:
        archive = Path(tmp) / "map.tar.gz"
        with tarfile.open(archive, "w:gz") as tar:
            for p in parts:
                tar.add(source / p, arcname=p)
        sha256 = hashlib.sha256(archive.read_bytes()).hexdigest()
        key = f"{args.name}-{sha256[:12]}.tar.gz"

        if args.local:
            s3 = boto3.client("s3", endpoint_url="http://localhost:8080", config=Config(signature_version=UNSIGNED))
            bucket = "worlds"
        else:
            env = subprocess.run(
                ["sops", "-d", "--input-type", "dotenv", "--output-type", "dotenv", str(ROOT / "secrets/s3.env.sops")],
                check=True, capture_output=True, text=True,
            ).stdout
            env = dict(line.split("=", 1) for line in env.splitlines() if "=" in line)
            s3 = boto3.client(
                "s3",
                endpoint_url=env["S3_ENDPOINT"],
                aws_access_key_id=env["S3_ACCESS_KEY"],
                aws_secret_access_key=env["S3_SECRET_KEY"],
            )
            bucket = env["S3_BUCKET"]
        s3.upload_file(str(archive), bucket, key)

    manifest = Path("servers", args.server, "maps", f"{args.name}.toml")
    (ROOT / manifest).parent.mkdir(exist_ok=True)
    (ROOT / manifest).write_text(f'key = "{key}"\nsha256 = "{sha256}"\nauthor = "{args.author}"\n')
    print(f"uploaded {key}, wrote {manifest}")
