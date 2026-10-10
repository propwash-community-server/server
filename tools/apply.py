"""
Replace a map on a server with its upload in the world store (<server>/maps/<name>.tar.gz).

  uv run apply <server> <name> [--local] [-y]

--local applies to the local stack; otherwise to prod (deploy/inventory.py), over SSH.
The server restarts. The map's in-game changes and race courses are replaced; its warps stay.
"""

import argparse
import runpy
import shlex
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("server")
    parser.add_argument("name")
    parser.add_argument("--local", action="store_true")
    parser.add_argument("-y", "--yes", action="store_true", help="don't ask")
    args = parser.parse_args()

    if not (ROOT / "servers" / args.server / "maps" / f"{args.name}.toml").exists():
        raise SystemExit(f"no servers/{args.server}/maps/{args.name}.toml")
    target, data = runpy.run_path(str(ROOT / "deploy/inventory.py"))["local" if args.local else "prod"][0]

    if not args.yes:
        answer = input(f"replace maps:{args.name} on {args.server} ({target})? Its in-game changes are lost. [y/N] ")
        if answer.lower() != "y":
            raise SystemExit("aborted")

    # The server is started again even if seeding fails.
    seed = shlex.join(["mc-seed-dimension", args.server, f"maps:{args.name}", data["world_store"]])
    command = f"{seed}; status=$?; systemctl start {shlex.quote(f'mc@{args.server}')}; exit $status"
    if args.local:
        subprocess.run(["docker", "exec", target.removeprefix("@docker/"), "sh", "-c", command], check=True)
    else:
        subprocess.run(["ssh", f"{data['ssh_user']}@{target}", command], check=True)
    print(f"applied maps:{args.name} on {args.server}")
