"""
Velocity + Paper/Fabric backends on one box, one folder per server under servers/.

Safe to re-run: it only changes what differs, and never touches worlds,
whitelists, ops or any server.properties setting except the ones it manages.
"""

import json
import os
import shlex
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
mariadb_password = read_secret("mariadb.secret")
permissions = tomllib.loads((ROOT / "permissions.toml").read_text())


def filename(url):
    return unquote(url.split("?")[0].rsplit("/", 1)[-1])


def service_env(name, stop_cmd):
    # Read by mc@.service. LuckPerms takes its settings from LUCKPERMS_* variables, so every
    # server shares one MariaDB database and knows its own name for server= contexts.
    env = (
        f"STOP_CMD={stop_cmd}\n"
        f"LUCKPERMS_SERVER={name}\n"
        "LUCKPERMS_STORAGE_METHOD=mariadb\n"
        "LUCKPERMS_DATA_ADDRESS=127.0.0.1\n"
        "LUCKPERMS_DATA_DATABASE=luckperms\n"
        "LUCKPERMS_DATA_USERNAME=minecraft\n"
        f"LUCKPERMS_DATA_PASSWORD={mariadb_password}\n"
    )
    return files.put(name=f"{name}: service env", src=StringIO(env), dest=f"{SERVERS}/{name}/service.env", mode="600", **OWNER)


def permission_commands():
    # permissions.toml as LuckPerms console commands. Listed groups are replaced as a whole.
    lines = []
    for group, cfg in permissions.get("groups", {}).items():
        lines += [f"lp creategroup {group}", f"lp group {group} permission clear"]
        lines += [f"lp group {group} permission set {p}" for p in cfg.get("permissions", [])]
    return "\n".join(lines + ["lp networksync"]) + "\n"


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


class JsonFile(FactBase):
    def command(self, path):
        return f"cat {path}"

    def process(self, output):
        return json.loads("\n".join(output))


SET_JSON_KEYS = "import json,sys; c=json.load(open(sys.argv[1])); c.update(json.loads(sys.argv[2])); json.dump(c, open(sys.argv[1], 'w'), indent=2)"


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
    # One by one: files.put would mkdir -p the parents as root.
    for p in sorted({q.relative_to(src) for f in src.rglob("*") if f.is_file() for q in f.parents if q != src and q.is_relative_to(src)}):
        files.directory(name=f"{name}: {p.as_posix()}/", path=f"{d}/{p.as_posix()}", **OWNER)
    return [
        files.put(name=f"{name}: {p.relative_to(src).as_posix()}", src=str(p), dest=f"{d}/{p.relative_to(src).as_posix()}", **OWNER)
        for p in sorted(src.rglob("*")) if p.is_file()
    ]


# Maps are void dimensions (maps:<name>) whose region files come from the store.
MAP_PACK = json.dumps({"pack": {"description": "Propwash maps", "min_format": 121, "max_format": 999}})
MAP_DIMENSION = json.dumps(
    {"type": "minecraft:overworld", "generator": {"type": "minecraft:flat", "settings": {"biome": "minecraft:the_void", "layers": []}}}
)


def map_ops(name, d, server_maps):
    store = host.data.get("world_store")
    if not store:
        raise SystemExit(f"{host.name} has no world_store but servers/{name}/maps/ lists maps")
    world = f"{d}/world"
    pack = f"{world}/datapacks/maps"
    ops = []
    # One by one: files.put would mkdir -p the parents as root.
    for path in [world, f"{world}/datapacks", pack, f"{pack}/data", f"{pack}/data/maps", f"{pack}/data/maps/dimension",
                 f"{world}/dimensions", f"{world}/dimensions/maps", f"/home/{MC}/world-archives"]:
        files.directory(name=f"{name}: {path}", path=path, **OWNER)
    ops.append(files.put(name=f"{name}: maps datapack", src=StringIO(MAP_PACK), dest=f"{pack}/pack.mcmeta", **OWNER))
    # Spawns and spots are Essential Commands warps, set in game; this was the old /map command.
    ops.append(files.file(name=f"{name}: no /map command", path=f"{d}/config/melius-commands/commands/map.json", present=False))
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
    packages=["openjdk-25-jre-headless", "tmux", "ufw", "curl", "python3-yaml", "restic", "mariadb-server"],
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

# MariaDB for LuckPerms and LibertyBans (one user, `minecraft`); listens on 127.0.0.1 only
# (Ubuntu default). Plain idempotent SQL, because pyinfra's mysql facts can't run before MariaDB is installed.
DATABASES = ["luckperms", "libertybans"]
mariadb_sql = "".join(f"CREATE DATABASE IF NOT EXISTS {db} CHARACTER SET utf8mb4;\n" for db in DATABASES) + "".join(
    f"CREATE USER IF NOT EXISTS 'minecraft'@'{h}' IDENTIFIED BY '{mariadb_password}';\n"
    f"ALTER USER 'minecraft'@'{h}' IDENTIFIED BY '{mariadb_password}';\n"
    + "".join(f"GRANT ALL PRIVILEGES ON {db}.* TO 'minecraft'@'{h}';\n" for db in DATABASES)
    for h in ["localhost", "127.0.0.1"]
)
systemd.service(name="MariaDB running", service="mariadb", running=True, enabled=True)
sql = files.put(name="database script", src=StringIO(mariadb_sql), dest="/root/mariadb.sql", mode="600", **ROOT_OWNER)
server.shell(name="databases + user", commands=["mysql < /root/mariadb.sql"], _if=sql.did_change)

files.put(name="backup credentials", src=StringIO(backup_env + "\n"), dest="/etc/mc-backup.env", mode="600", **ROOT_OWNER)
systemd.service(name="backup timer", service="mc-backup.timer", running=True, enabled=True, daemon_reload=True)

# --- Velocity (first: Paper backends read its forwarding.secret) ------------

v = f"{SERVERS}/velocity"
files.directory(name="velocity folder", path=v, **OWNER)

changes = [
    files.download(name="velocity jar", src=velocity["url"], dest=f"{v}/{velocity['jar']}", **OWNER),
    start_script(f"{v}/start.sh", velocity["jar"], velocity["ram"]),
    service_env("velocity", "end"),
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

# LibertyBans writes sql.yml on first start (with its own embedded database); from the next
# deploy it points at MariaDB. The auth-details keys are each unique in that file.
libertybans_sql = f"{v}/plugins/libertybans/sql.yml"
if host.get_fact(File, path=libertybans_sql):
    for key, value in {
        "rdms-vendor": "MARIADB",
        "  host": "127.0.0.1",
        "  database": "libertybans",
        "  user": "minecraft",
        "  password": mariadb_password,
    }.items():
        changes.append(files.line(
            name=f"LibertyBans {key.strip()}",
            path=libertybans_sql,
            line=f"^{key}:.*",
            replace=f"{key}: '{value}'",
        ))

service("velocity", changes)

# --- Backends --------------------------------------------------------------

for name, cfg in backends.items():
    d = f"{SERVERS}/{name}"
    kind = cfg["type"]
    files.directory(name=f"{name}: folder", path=d, **OWNER)

    changes = [
        files.download(name=f"{name}: server jar", src=cfg["url"], dest=f"{d}/{cfg['jar']}", **OWNER),
        start_script(f"{d}/start.sh", cfg["jar"], cfg["ram"], "nogui"),
        service_env(name, "stop"),
        files.put(name=f"{name}: eula", src=StringIO("eula=true\n"), dest=f"{d}/eula.txt", **OWNER),
        files.put(name=f"{name}: no ops", src=StringIO("[]"), dest=f"{d}/ops.json", **OWNER),
    ]
    if kind == "fabric":
        changes += jar_folder(f"{name}: mod", f"{d}/mods", cfg.get("mods", []))
    else:
        changes += jar_folder(f"{name}: plugin", f"{d}/plugins", cfg.get("plugins", []))
    changes += extra_files(name, d)
    if maps.get(name):
        changes += map_ops(name, d, maps[name])

    # [config."<path>"]: top-level keys in a plugin's own config ("key: value", or "key=value" for
    # .properties). Plugins write their config on first start, so this applies from the next deploy.
    for rel, settings in cfg.get("config", {}).items():
        if host.get_fact(File, path=f"{d}/{rel}"):
            sep = "=" if rel.endswith(".properties") else ": "
            for key, value in settings.items():
                changes.append(files.line(
                    name=f"{name}: {rel} {key}",
                    path=f"{d}/{rel}",
                    line=f"^{key}{sep.strip()}.*",
                    replace=f"{key}{sep}{value}",
                ))

    # [flan]: deny = Flan world rules, ALLFALSE in every dimension of the server unless a player is in
    # /flan bypass (explicit dimensions, since Flan saves "*" back expanded); other keys are top-level
    # keys of flan_config.json. Flan rewrites the file on every start, so it's compared as data. Also
    # only from the next deploy after the first start.
    flan_config = f"{d}/config/flan/flan_config.json"
    if cfg.get("flan") and host.get_fact(File, path=flan_config):
        dims = ["minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"]
        dims += [f"maps:{m['name']}" for m in maps.get(name, [])]
        rules = {dim: {f"flan:{p}": "ALLFALSE" for p in cfg["flan"]["deny"]} for dim in dims}
        wanted = {"globalDefaultPerms": rules, **{k: v for k, v in cfg["flan"].items() if k != "deny"}}
        current = host.get_fact(JsonFile, path=flan_config)
        if any(current.get(k) != v for k, v in wanted.items()):
            changes.append(server.shell(
                name=f"{name}: Flan config",
                commands=[f"python3 -c {shlex.quote(SET_JSON_KEYS)} {flan_config} {shlex.quote(json.dumps(wanted))}"],
            ))

    if cfg.get("new_world") and host.get_fact(File, path=f"{d}/server.properties") is None:
        props = "".join(f"{k}={v}\n" for k, v in cfg["new_world"].items())
        changes.append(files.put(name=f"{name}: new world settings", src=StringIO(props), dest=f"{d}/server.properties", **OWNER))

    # Created as mc first: files.line would otherwise create them as root.
    files.file(name=f"{name}: server.properties", path=f"{d}/server.properties", **OWNER)
    managed = {
        "server-ip": "127.0.0.1",
        "server-port": cfg["port"],
        "online-mode": "false",
        # Public network; who may do what is LuckPerms (permissions.toml), bans are LibertyBans.
        "white-list": "false",
        "enforce-whitelist": "false",
        "enable-rcon": "true",
        "rcon.port": cfg["port"] + 10000,
        "rcon.password": rcon_password,
        **cfg.get("properties", {}),
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
        # hackEarlySend: LuckPerms needs the real UUID at pre-login, otherwise it kicks everyone.
        # hackMessageChain: avoids "invalid chat signature" kicks after switching servers.
        for key in ["hackEarlySend", "hackMessageChain"]:
            changes.append(files.line(
                name=f"{name}: FabricProxy-Lite {key}",
                path=f"{d}/config/FabricProxy-Lite.toml",
                line=f"^{key} *=.*",
                replace=f"{key} = true",
            ))
    elif not host.get_fact(PaperVelocityOk, server_dir=d):
        changes.append(server.shell(name=f"{name}: Velocity forwarding", commands=[f"/usr/local/bin/paper-velocity {d}"]))

    # files.line creates/edits as root, so hand everything back to mc.
    files.directory(name=f"{name}: ownership", path=d, recursive=True, **OWNER)

    service(name, changes)

# --- Permissions -----------------------------------------------------------

# Applied through the hub's console only when permissions.toml changed; LuckPerms shares them
# with every server through the database.
perms = files.put(name="permissions", src=StringIO(permission_commands()), dest=f"/home/{MC}/permissions.lp", **OWNER)
server.shell(
    name="apply permissions",
    commands=[
        "for i in $(seq 90); do mc-rcon hub list >/dev/null 2>&1 && break; sleep 2; done",
        f'while read -r c; do mc-rcon hub "$c"; done < /home/{MC}/permissions.lp',
    ],
    _if=perms.did_change,
)
