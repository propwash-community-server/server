"""
Velocity + Paper/Fabric backends on one box, one folder per server under servers/.

Safe to re-run: it only changes what differs, and never touches worlds,
whitelists, ops or any server.properties setting except the ones it manages.
"""

import json
import os
import subprocess
import tomllib
from io import StringIO
from pathlib import Path
from urllib.parse import unquote

from pyinfra import host
from pyinfra.api import FactBase
from pyinfra.facts.files import File, FindFiles
from pyinfra.operations import apt, files, server, systemd
from pyinfra.operations.util import any_changed

ROOT = Path(__file__).resolve().parent.parent
MC = "mc"
SERVERS = f"/home/{MC}/servers"
OWNER = {"user": MC, "group": MC}
# Explicit, or the docker connector keeps the deploying user's uid.
ROOT_OWNER = {"user": "root", "group": "root"}
LOCAL = host.data.get("local", False)

configs = {p.parent.name: tomllib.loads(p.read_text()) for p in sorted(ROOT.glob("servers/*/server.toml"))}
velocity = configs.pop("velocity")
backends = configs

maps = {}
for p in sorted(ROOT.glob("servers/*/maps/*.toml")):
    maps.setdefault(p.parent.parent.name, []).append({"name": p.stem, **tomllib.loads(p.read_text())})


def read_secret(name, kind="binary"):
    # Local runs decrypt the dummies in local/secrets/ with the committed local/age.key.
    folder, env = (ROOT / "local/secrets", {**os.environ, "SOPS_AGE_KEY_FILE": str(ROOT / "local/age.key")}) if LOCAL else (ROOT / "secrets", None)
    return subprocess.run(
        ["sops", "-d", "--input-type", kind, "--output-type", kind, str(folder / f"{name}.sops")],
        check=True, capture_output=True, text=True, env=env,
    ).stdout.strip()


secret = read_secret("forwarding.secret")
rcon_password = read_secret("rcon.secret")
backup_env = read_secret("backup.env", "dotenv")


def filename(url):
    return unquote(url.split("?")[0].rsplit("/", 1)[-1])


def start_script(path, jar, ram, args=""):
    script = (
        "#!/bin/sh\n"
        "# Managed by pyinfra - edit servers/<name>/server.toml instead.\n"
        f"exec java -Xms{ram} -Xmx{ram} -jar {jar} {args}\n"
    )
    return files.put(name=f"start script {path}", src=StringIO(script), dest=path, mode="755", **OWNER)


class PaperVelocityOk(FactBase):
    def command(self, server_dir):
        return f"/usr/local/bin/paper-velocity --check {server_dir} 2>/dev/null || true"

    def process(self, output):
        return bool(output) and output[0].strip() == "ok"


class MapKey(FactBase):
    """Archive key a map folder was unpacked from, or None."""

    def command(self, path):
        return f"cat {path}/.key 2>/dev/null || true"

    def process(self, output):
        return output[0].strip() if output else None


def jar_folder(label, folder, urls):
    # Download listed jars, delete any other jar in the folder.
    wanted = {filename(u): u for u in urls}
    ops = [files.directory(name=f"{label} folder", path=folder, **OWNER)]
    for jar, url in wanted.items():
        ops.append(files.download(name=f"{label} {jar}", src=url, dest=f"{folder}/{jar}", **OWNER))
    for path in host.get_fact(FindFiles, path=folder, maxdepth=1, fname="*.jar") or []:
        if Path(path).name not in wanted:
            ops.append(files.file(name=f"{label} remove {Path(path).name}", path=path, present=False))
    return ops


def extra_files(name, d):
    # servers/<name>/files/ is copied as is into the server folder.
    src = ROOT / "servers" / name / "files"
    return [
        files.put(name=f"{name}: {p.relative_to(src).as_posix()}", src=str(p), dest=f"{d}/{p.relative_to(src).as_posix()}", **OWNER)
        for p in sorted(src.rglob("*")) if p.is_file()
    ]


# Maps are void dimensions (maps:<name>) whose region files come from the store.
MAP_PACK = json.dumps({"pack": {"description": "Propwash maps", "min_format": 121, "max_format": 999}})
MAP_DIMENSION = json.dumps(
    {"type": "minecraft:overworld", "generator": {"type": "minecraft:flat", "settings": {"biome": "minecraft:the_void", "layers": []}}}
)


def map_command(server_maps):
    # Melius Commands: /map lists the maps, /map <name> teleports there. Runs as the player
    # with op level 2, so players don't need op themselves.
    def run(command):
        return {"command": command, "op_level": 2}

    names = ", ".join(m["name"] for m in server_maps)
    return json.dumps({
        "id": "map",
        "executes": [run(f"tellraw @s {json.dumps('Maps: ' + names)}")],
        "literals": [
            {"id": m["name"], "executes": [run(f"execute in maps:{m['name']} run tp @s {' '.join(map(str, m['spawn']))}")]}
            for m in server_maps
        ],
    }, indent=2)


def map_ops(name, d, server_maps):
    store = host.data.get("world_store")
    if not store:
        raise SystemExit(f"{host.name} has no world_store but servers/{name}/maps/ lists maps")
    world = f"{d}/world"
    pack = f"{world}/datapacks/maps"
    ops = []
    # One by one: files.put would mkdir -p the parents as root.
    for path in [world, f"{world}/datapacks", pack, f"{pack}/data", f"{pack}/data/maps", f"{pack}/data/maps/dimension",
                 f"{world}/dimensions", f"{world}/dimensions/maps", f"/home/{MC}/world-archives",
                 f"{d}/config", f"{d}/config/melius-commands", f"{d}/config/melius-commands/commands"]:
        files.directory(name=f"{name}: {path}", path=path, **OWNER)
    ops.append(files.put(name=f"{name}: maps datapack", src=StringIO(MAP_PACK), dest=f"{pack}/pack.mcmeta", **OWNER))
    ops.append(files.put(
        name=f"{name}: /map command",
        src=StringIO(map_command(server_maps)),
        dest=f"{d}/config/melius-commands/commands/map.json",
        **OWNER,
    ))
    for m in server_maps:
        ops.append(files.put(
            name=f"{name}: map {m['name']} dimension",
            src=StringIO(MAP_DIMENSION),
            dest=f"{pack}/data/maps/dimension/{m['name']}.json",
            **OWNER,
        ))
        dim = f"{world}/dimensions/maps/{m['name']}"
        # Unpacked again whenever the manifest points at a different archive.
        if host.get_fact(MapKey, path=dim) != m["key"]:
            archive = f"/home/{MC}/world-archives/{m['key']}"
            files.download(name=f"{name}: map {m['name']} archive", src=f"{store}/{m['key']}", dest=archive,
                           # Locally the store holds blank stand-ins, which can't match the real hash.
                           sha256sum=None if LOCAL else m["sha256"], **OWNER)
            ops.append(server.shell(
                name=f"{name}: map {m['name']} unpack",
                commands=[
                    f"systemctl stop mc@{name}",
                    f"rm -rf {dim}",
                    f"mkdir -p {dim}",
                    f"tar -xzf {archive} -C {dim}",
                    f"echo {m['key']} > {dim}/.key",
                    f"chown -R {MC}:{MC} {dim}",
                ],
            ))
    return ops


def service(name, changes):
    # Restart only if already running and something changed, then make sure it's up.
    systemd.service(
        name=f"{name}: restart if changed",
        service=f"mc@{name}",
        running=None,
        restarted=True,
        daemon_reload=True,
        _if=any_changed(*changes),
    )
    systemd.service(name=f"{name}: running", service=f"mc@{name}", running=True, enabled=True, daemon_reload=True)


# --- Base system -----------------------------------------------------------

apt.packages(
    name="Install Java and tools",
    packages=["openjdk-25-jre-headless", "tmux", "ufw", "curl", "python3-yaml", "restic"],
    update=True,
    cache_time=3600,
)

server.user(name="mc user", user=MC, home=f"/home/{MC}", shell="/bin/bash")

if not LOCAL:
    server.shell(
        name="Firewall: SSH + Velocity only",
        commands=["ufw allow 22/tcp", "ufw allow 25565/tcp", "ufw --force enable"],
    )

    # Additive for now: switch to delete_keys=True once every current key is in access/.
    keys = [p.read_text().strip() for p in sorted(ROOT.glob("access/*.pub"))]
    if keys:
        server.user_authorized_keys(name="root SSH keys", user="root", public_keys=keys)

for unit in ["mc@.service", "mc-backup.service", "mc-backup.timer"]:
    files.put(name=f"systemd unit {unit}", src=str(ROOT / "deploy/files" / unit), dest=f"/etc/systemd/system/{unit}", mode="644", **ROOT_OWNER)
for script in ["mc-console", "paper-velocity", "mc-rcon", "mc-backup", "mc-restore"]:
    files.put(name=f"{script} script", src=str(ROOT / "deploy/files" / script), dest=f"/usr/local/bin/{script}", mode="755", **ROOT_OWNER)

files.directory(name="servers folder", path=SERVERS, **OWNER)

files.put(name="backup credentials", src=StringIO(backup_env + "\n"), dest="/etc/mc-backup.env", mode="600", **ROOT_OWNER)
systemd.service(name="backup timer", service="mc-backup.timer", running=True, enabled=True, daemon_reload=True)

# --- Velocity (first: Paper backends read its forwarding.secret) ------------

v = f"{SERVERS}/velocity"
files.directory(name="velocity folder", path=v, **OWNER)

changes = [
    files.download(name="velocity jar", src=velocity["url"], dest=f"{v}/{velocity['jar']}", **OWNER),
    start_script(f"{v}/start.sh", velocity["jar"], velocity["ram"]),
    files.put(name="velocity: stop command", src=StringIO("STOP_CMD=end\n"), dest=f"{v}/service.env", **OWNER),
    files.template(
        name="velocity.toml",
        src=str(ROOT / "servers/velocity/velocity.toml.j2"),
        dest=f"{v}/velocity.toml",
        backends=backends,
        try_order=velocity["try"],
        **OWNER,
    ),
    files.put(name="forwarding.secret", src=StringIO(secret), dest=f"{v}/forwarding.secret", mode="600", **OWNER),
    *jar_folder("velocity: plugin", f"{v}/plugins", velocity.get("plugins", [])),
    *extra_files("velocity", v),
]

service("velocity", changes)

# --- Backends --------------------------------------------------------------

for name, cfg in backends.items():
    d = f"{SERVERS}/{name}"
    kind = cfg["type"]
    files.directory(name=f"{name}: folder", path=d, **OWNER)

    changes = [
        files.download(name=f"{name}: server jar", src=cfg["url"], dest=f"{d}/{cfg['jar']}", **OWNER),
        start_script(f"{d}/start.sh", cfg["jar"], cfg["ram"], "nogui"),
        files.put(name=f"{name}: stop command", src=StringIO("STOP_CMD=stop\n"), dest=f"{d}/service.env", **OWNER),
        files.put(name=f"{name}: eula", src=StringIO("eula=true\n"), dest=f"{d}/eula.txt", **OWNER),
    ]
    if kind == "fabric":
        changes += jar_folder(f"{name}: mod", f"{d}/mods", cfg.get("mods", []))
    else:
        changes += jar_folder(f"{name}: plugin", f"{d}/plugins", cfg.get("plugins", []))
    changes += extra_files(name, d)
    if maps.get(name):
        changes += map_ops(name, d, maps[name])

    if cfg.get("new_world") and host.get_fact(File, path=f"{d}/server.properties") is None:
        props = "".join(f"{k}={v}\n" for k, v in cfg["new_world"].items())
        changes.append(files.put(name=f"{name}: new world settings", src=StringIO(props), dest=f"{d}/server.properties", **OWNER))

    # Created as mc first: files.line would otherwise create them as root.
    files.file(name=f"{name}: server.properties", path=f"{d}/server.properties", **OWNER)
    managed = {
        "server-ip": "127.0.0.1",
        "server-port": cfg["port"],
        "online-mode": "false",
        "enable-rcon": "true",
        "rcon.port": cfg["port"] + 10000,
        "rcon.password": rcon_password,
    }
    for key, value in managed.items():
        changes.append(
            files.line(name=f"{name}: {key}", path=f"{d}/server.properties", line=f"^{key}=.*", replace=f"{key}={value}")
        )

    files.directory(name=f"{name}: config folder", path=f"{d}/config", **OWNER)
    if kind == "fabric":
        files.file(name=f"{name}: FabricProxy-Lite.toml", path=f"{d}/config/FabricProxy-Lite.toml", **OWNER)
        changes.append(
            files.line(
                name=f"{name}: Velocity secret",
                path=f"{d}/config/FabricProxy-Lite.toml",
                line="^secret *=.*",
                replace=f'secret = "{secret}"',
            )
        )
    elif not host.get_fact(PaperVelocityOk, server_dir=d):
        changes.append(server.shell(name=f"{name}: Velocity forwarding", commands=[f"/usr/local/bin/paper-velocity {d}"]))

    # files.line creates/edits as root, so hand everything back to mc.
    files.directory(name=f"{name}: ownership", path=d, recursive=True, **OWNER)

    service(name, changes)
