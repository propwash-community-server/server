"""
Velocity + Paper/Fabric backends on one box, one folder per server under servers/.

Safe to re-run: it only changes what differs, and never touches worlds,
whitelists, ops or any server.properties setting except the ones it manages.
"""

import json
import os
import re
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
# The test inventory runs every server with the same, smaller amount of RAM.
RAM = host.data.get("ram")

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
palette = tomllib.loads((ROOT / "palette.toml").read_text())


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


# Texts are MiniMessage, with the palette.toml colors as tags: <muted>text</muted>.
TAG = re.compile(r"<(/?)([a-z_]+|#[0-9a-fA-F]{6})>")
COLORS = {
    "black": "0", "dark_blue": "1", "dark_green": "2", "dark_aqua": "3", "dark_red": "4", "dark_purple": "5",
    "gold": "6", "gray": "7", "dark_gray": "8", "blue": "9", "green": "a", "aqua": "b", "red": "c",
    "light_purple": "d", "yellow": "e", "white": "f",
}
DECORATIONS = {"bold": "l", "italic": "o", "underlined": "n", "strikethrough": "m", "obfuscated": "k"}


def palette_tags(text):
    # For what reads MiniMessage itself: LuckPerms prefixes, Carbon, Velocitab, the MOTD.
    return TAG.sub(lambda m: f"<{m[1]}{palette[m[2]]}>" if m[2] in palette else m[0], text)


def spans(text):
    """(text, open tags) pieces of a text, for what doesn't read MiniMessage. Only colors, decorations
    and <reset> are tags; anything else in <> (like <name> in a command) stays text."""
    text, stack, out, pos = palette_tags(text), [], [], 0
    for m in TAG.finditer(text):
        tag = m[2]
        if tag not in COLORS and tag not in DECORATIONS and not tag.startswith("#") and tag != "reset":
            continue
        out.append((text[pos:m.start()], list(stack)))
        pos = m.end()
        if tag == "reset":
            stack = []
        elif not m[1]:
            stack.append(tag)
        elif tag in stack:
            del stack[len(stack) - 1 - stack[::-1].index(tag)]
    return [(t, s) for t, s in out + [(text[pos:], stack)] if t]


def color(tags):
    colors = [t for t in tags if t in COLORS or t.startswith("#")]
    return colors[-1] if colors else None


def to_json(text):
    # A text component, for tellraw and title.
    return json.dumps(["", *(
        {"text": t, **({"color": color(s)} if color(s) else {}), **{d: True for d in s if d in DECORATIONS}}
        for t, s in spans(text)
    )])


def to_legacy(text):
    # & codes, for Essentials text files. Each line is its own message, so each one restates its style.
    def codes(tags):
        c = color(tags)
        return "&r" + ("" if not c else f"&{c}" if c.startswith("#") else f"&{COLORS[c]}") + "".join(
            f"&{DECORATIONS[d]}" for d in tags if d in DECORATIONS)

    return "".join("\n".join(codes(s) + line if line else "" for line in t.split("\n")) for t, s in spans(text))


def permission_commands():
    # permissions.toml as LuckPerms console commands. Listed groups are replaced as a whole.
    lines = []
    for group, cfg in permissions.get("groups", {}).items():
        lines += [f"lp creategroup {group}", f"lp group {group} clear"]
        lines += [f"lp group {group} permission set {palette_tags(p)}" for p in cfg.get("permissions", [])]
    return "\n".join(lines + ["lp networksync"]) + "\n"


# Fabric backends wait this long (ms) for SignedVelocity's verdict on a chat message before letting
# it through. The default of 20 ms is too short on a busy server: the vanilla message then shows up
# next to Carbon's (double chat). The verdict normally comes within a few ms. Fabric only: on Paper,
# SignedVelocity waits for each command's verdict on the main thread, so with a long timeout a burst
# of commands (e.g. a Litematica paste) froze the hub until the watchdog stopped it. Paper keeps 20.
FABRIC_JVM_FLAGS = "-Dio.github._4drian3d.signedvelocity.timeout=1000"


def start_script(path, jar, ram, args="", jvm_flags=""):
    jvm = f" {jvm_flags}" if jvm_flags else ""
    script = (
        "#!/bin/sh\n"
        "# Managed by pyinfra - edit servers/<name>/server.toml instead.\n"
        f"exec java -Xms{ram} -Xmx{ram}{jvm} -jar {jar} {args}\n"
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


def palette_file(p):
    # A text file's contents with the palette tags expanded, or the file itself if it has none.
    try:
        text = p.read_text()
    except UnicodeDecodeError:
        return str(p)
    return StringIO(palette_tags(text)) if palette_tags(text) != text else str(p)


def extra_files(name, d):
    # servers/<name>/files/ is copied into the server folder, palette tags expanded.
    src = ROOT / "servers" / name / "files"
    return [
        files.put(name=f"{name}: {p.relative_to(src).as_posix()}", src=palette_file(p), dest=f"{d}/{p.relative_to(src).as_posix()}", **OWNER)
        for p in sorted(src.rglob("*")) if p.is_file()
    ]


# Maps are void dimensions (maps:<name>) whose region files come from the store.
MAP_PACK = json.dumps({"pack": {"description": "Propwash maps", "min_format": 121, "max_format": 999}})
# Each map has its own clock (so /time on a map only changes that map), started paused at noon once.
# The vanilla files come from the 26.3 server jar; copy them again when upgrading Minecraft.
VANILLA = {p.stem: json.loads(p.read_text()) for p in (ROOT / "deploy/vanilla").glob("*.json")}
LOAD_TAG = json.dumps({"values": ["maps:load"]})
TICK_TAG = json.dumps({"values": ["maps:tick"]})


def map_pack_files(name):
    """Datapack files (path under data/ -> JSON) for the map dimension maps:<name>."""
    clock = f"maps:{name}"
    return {
        f"maps/world_clock/{name}.json": {},
        f"maps/timeline/{name}_day.json": {**VANILLA["timeline_day"], "clock": clock},
        f"maps/timeline/{name}_moon.json": {**VANILLA["timeline_moon"], "clock": clock},
        f"maps/dimension_type/{name}.json": {
            **VANILLA["dimension_type_overworld"], "default_clock": clock, "timelines": [f"{clock}_day", f"{clock}_moon"],
        },
        f"maps/dimension/{name}.json": {
            "type": clock,
            "generator": {"type": "minecraft:flat", "settings": {"biome": "minecraft:the_void", "layers": []}},
        },
    }


def map_load_function(server_maps):
    lines = ["scoreboard objectives add maps_clock dummy"]
    for m in server_maps:
        unset = f"execute unless score {m['name']} maps_clock matches 1 run"
        lines += [
            f"{unset} time of maps:{m['name']} set noon",
            f"{unset} time of maps:{m['name']} pause",
            f"scoreboard players set {m['name']} maps_clock 1",
        ]
    return "\n".join(lines) + "\n"


def map_tick_function(server_maps):
    # Title and author on screen each time a player enters a map.
    lines = []
    for m in server_maps:
        tag, dim = f"map_{m['name']}", f"maps:{m['name']}"
        entering = f"execute as @a[tag=!{tag}] at @s if dimension {dim} run"
        lines += [
            f"{entering} title @s subtitle {to_json('<muted>by ' + m['author'])}",
            f"{entering} title @s title {to_json('<brand>' + m.get('title', m['name'].capitalize()))}",
            f"{entering} tag @s add {tag}",
            f"execute as @a[tag={tag}] at @s unless dimension {dim} run tag @s remove {tag}",
        ]
    return "\n".join(lines) + "\n"


def map_command(server_maps):
    # Melius Commands: /map lists the maps, /map <name> goes to the map's spawn, the warp <name>
    # (set in game by builders), without the warp's chat message. Dropping the map's tag shows its
    # title again (map_tick_function), also when the player is on the map already. Runs as the
    # player with op level 2.
    def run(command):
        return {"command": command, "op_level": 2, "silent": True}

    names = ", ".join(m["name"] for m in server_maps)
    return json.dumps({
        "id": "map",
        "executes": [run(f"tellraw @s {to_json('<muted>Maps:</muted> ' + names)}")],
        "literals": [{"id": m["name"], "executes": [run(f"tag @s remove map_{m['name']}"), run(f"warp tp {m['name']}")]}
                     for m in server_maps],
    }, indent=2)


def map_ops(name, d, server_maps):
    store = host.data.get("world_store")
    if not store:
        raise SystemExit(f"{host.name} has no world_store but servers/{name}/maps/ lists maps")
    world = f"{d}/world"
    pack = f"{world}/datapacks/maps"
    ops = []
    files.directory(name="world archives folder", path=f"/home/{MC}/world-archives", **OWNER)
    ops.append(files.put(name=f"{name}: maps datapack", src=StringIO(MAP_PACK), dest=f"{pack}/pack.mcmeta", **OWNER))
    ops.append(files.put(name=f"{name}: maps load tag", src=StringIO(LOAD_TAG),
                         dest=f"{pack}/data/minecraft/tags/function/load.json", **OWNER))
    ops.append(files.put(name=f"{name}: maps load function", src=StringIO(map_load_function(server_maps)),
                         dest=f"{pack}/data/maps/function/load.mcfunction", **OWNER))
    ops.append(files.put(name=f"{name}: maps tick tag", src=StringIO(TICK_TAG),
                         dest=f"{pack}/data/minecraft/tags/function/tick.json", **OWNER))
    ops.append(files.put(name=f"{name}: maps tick function", src=StringIO(map_tick_function(server_maps)),
                         dest=f"{pack}/data/maps/function/tick.mcfunction", **OWNER))
    ops.append(files.put(name=f"{name}: /map command", src=StringIO(map_command(server_maps)),
                         dest=f"{d}/config/melius-commands/commands/map.json", **OWNER))
    for m in server_maps:
        for rel, data in map_pack_files(m["name"]).items():
            ops.append(files.put(
                name=f"{name}: map {m['name']} {rel}",
                src=StringIO(json.dumps(data)),
                dest=f"{pack}/data/{rel}",
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
                    f"install -d -o {MC} -g {MC} {world}/data",
                    f"mc-merge-courses {world}/data/propwash_race.json {dim}/propwash_race.json maps:{m['name']}",
                    f"chown {MC}:{MC} {world}/data/propwash_race.json",
                ],
            ))
    return ops


def welcome_ops(name, d, text):
    # A datapack, the same on Paper and Fabric: welcome text to players new to the server, and to
    # those back after leaving it (the leave_game stat also counts switching servers on the proxy).
    pack = f"{d}/world/datapacks/welcome"
    message = to_json(text)
    tick = (f"tellraw @a[tag=!welcomed] {message}\n"
            f"tellraw @a[scores={{welcome.left=1..}}] {message}\n"
            "tag @a add welcomed\n"
            "scoreboard players reset @a[scores={welcome.left=1..}] welcome.left\n")
    pack_files = {
        "pack.mcmeta": json.dumps({"pack": {"description": "Join message", "min_format": 121, "max_format": 999}}),
        "data/minecraft/tags/function/load.json": json.dumps({"values": ["welcome:load"]}),
        "data/minecraft/tags/function/tick.json": json.dumps({"values": ["welcome:tick"]}),
        "data/welcome/function/load.mcfunction":
            "scoreboard objectives add welcome.left minecraft.custom:minecraft.leave_game\n",
        "data/welcome/function/tick.mcfunction": tick,
    }
    return [files.put(name=f"{name}: welcome {rel}", src=StringIO(content), dest=f"{pack}/{rel}", **OWNER)
            for rel, content in pack_files.items()]


def ownership(name, d):
    # Files and folders written as root (files.line, the parents files.put creates, the paper-velocity
    # helper, ...) go back to mc. A plain chown: files.directory(recursive=True) only recurses when the
    # top folder's owner is wrong, so root-owned files inside an mc-owned folder would stay root and
    # the server couldn't save them.
    server.shell(name=f"{name}: ownership", commands=[f"chown -R {MC}:{MC} {d}"])


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
for script in ["mc-console", "paper-velocity", "mc-rcon", "mc-backup", "mc-restore", "mc-merge-courses"]:
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
    start_script(f"{v}/start.sh", velocity["jar"], RAM or velocity["ram"]),
    service_env("velocity", "end"),
    files.template(
        name="velocity.toml",
        src=str(ROOT / "servers/velocity/velocity.toml.j2"),
        dest=f"{v}/velocity.toml",
        backends=backends,
        try_order=velocity["try"],
        online_mode="false" if host.data.get("test") else "true",
        # A JSON string is also a TOML one, quotes escaped.
        motd=json.dumps(palette_tags(velocity["motd"])),
        **OWNER,
    ),
    files.put(name="forwarding.secret", src=StringIO(secret), dest=f"{v}/forwarding.secret", mode="600", **OWNER),
    *jar_folder("velocity: plugin", f"{v}/plugins", velocity.get("plugins", [])),
    # MiniPlaceholders loads its expansions (placeholder add-ons) from its own folder, not plugins/.
    *jar_folder("velocity: expansion", f"{v}/plugins/miniplaceholders/expansions", velocity.get("expansions", [])),
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

ownership("velocity", v)
service("velocity", changes)

# --- Backends --------------------------------------------------------------

for name, cfg in backends.items():
    d = f"{SERVERS}/{name}"
    kind = cfg["type"]
    files.directory(name=f"{name}: folder", path=d, **OWNER)

    changes = [
        files.download(name=f"{name}: server jar", src=cfg["url"], dest=f"{d}/{cfg['jar']}", **OWNER),
        start_script(f"{d}/start.sh", cfg["jar"], RAM or cfg["ram"], "nogui", FABRIC_JVM_FLAGS if kind == "fabric" else ""),
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

    # /help: the server's help, then the network-wide one from the velocity server.toml.
    if cfg.get("help"):
        text = cfg["help"].strip() + "\n\n" + velocity.get("help", "").strip()
        if kind == "fabric":
            # Melius Commands, in place of vanilla /help; tellraw needs op level 2.
            help_command = {"id": "help", "executes": [{"command": f"tellraw @s {to_json(text)}", "op_level": 2}]}
            changes.append(files.put(name=f"{name}: /help", src=StringIO(json.dumps(help_command, indent=2)),
                                     dest=f"{d}/config/melius-commands/commands/help.json", **OWNER))
        elif kind == "paper":
            # EssentialsX /help shows help.txt when there is one. It's read on use, so no restart.
            files.put(name=f"{name}: /help", src=StringIO(to_legacy(text) + "\n"),
                      dest=f"{d}/plugins/Essentials/help.txt", **OWNER)
        else:
            raise SystemExit(f"servers/{name}/server.toml has help, but /help is only made for fabric and paper")

    if cfg.get("welcome"):
        changes += welcome_ops(name, d, cfg["welcome"].strip())

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
            changes.append(files.put(name=f"{name}: Flan config", src=StringIO(json.dumps({**current, **wanted}, indent=2)),
                                     dest=flan_config, **OWNER))

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

    ownership(name, d)
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
