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
from pyinfra.facts.files import Directory, File, FindFiles
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

configs = {p.parent.name: tomllib.loads(p.read_text(encoding="utf-8")) for p in sorted(ROOT.glob("servers/*/server.toml"))}
velocity = configs.pop("velocity")
backends = configs

maps = {}
for p in sorted(ROOT.glob("servers/*/maps/*.toml")):
    maps.setdefault(p.parent.parent.name, []).append({"name": p.stem, **tomllib.loads(p.read_text(encoding="utf-8"))})


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
bot_env = read_secret("bot.env", "dotenv")
bot_keys = dict(line.split("=", 1) for line in bot_env.splitlines() if "=" in line)
if any(v.startswith("PASTE_") for v in bot_keys.values()):
    raise SystemExit("Fill in the PASTE_... values: sops edit --input-type dotenv --output-type dotenv secrets/bot.env.sops")
permissions = tomllib.loads((ROOT / "permissions.toml").read_text(encoding="utf-8"))
palette = tomllib.loads((ROOT / "palette.toml").read_text(encoding="utf-8"))


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
    # The bridge mod's key for this server (BRIDGE_KEY_FPV for fpv); the key is how the bot's API knows the server.
    if f"BRIDGE_KEY_{name.upper()}" in bot_keys:
        env += f"PROPWASH_BRIDGE_API_KEY={bot_keys[f'BRIDGE_KEY_{name.upper()}']}\n"
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
    # For what reads MiniMessage itself: LuckPerms prefixes, Carbon, TAB, the MOTD.
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


class GitHead(FactBase):
    def command(self, path):
        # Run as root on a checkout owned by another user, which git otherwise refuses.
        return f"git -c safe.directory='*' -C {path} rev-parse HEAD 2>/dev/null || true"

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
        text = p.read_text(encoding="utf-8")
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


# Maps are dimensions (maps:<name>) whose region files come from the store, void around them
# (or superflat with flat = true in the map's toml).
MAP_PACK = json.dumps({"pack": {"description": "Propwash maps", "min_format": 121, "max_format": 999}})
# Each map has its own clock (so /time on a map only changes that map), started paused at noon once.
# The vanilla files come from the 26.3 server jar; copy them again when upgrading Minecraft.
VANILLA = {p.stem: json.loads(p.read_text(encoding="utf-8")) for p in (ROOT / "deploy/vanilla").glob("*.json")}
LOAD_TAG = json.dumps({"values": ["maps:load"]})
TICK_TAG = json.dumps({"values": ["maps:tick"]})


SUPERFLAT = {"biome": "minecraft:plains", "layers": [
    {"block": "minecraft:bedrock", "height": 1},
    {"block": "minecraft:dirt", "height": 2},
    {"block": "minecraft:grass_block", "height": 1},
]}
VOID = {"biome": "minecraft:the_void", "layers": []}


def map_pack_files(m):
    """Datapack files (path under data/ -> JSON) for the map dimension maps:<name>."""
    name = m["name"]
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
            "generator": {"type": "minecraft:flat", "settings": SUPERFLAT if m.get("flat") else VOID},
        },
    }


def map_load_function(server_maps):
    lines = ["scoreboard objectives add maps_clock dummy"]
    lines += [f"scoreboard objectives add {o} dummy"
              for o in ("maps_rtp", "maps_rtp_ok", "maps_rtp_go", "maps_rtp_t", "maps_rtp_last", "maps_rtp_wait")]
    for m in server_maps:
        unset = f"execute unless score {m['name']} maps_clock matches 1 run"
        lines += [
            f"{unset} time of maps:{m['name']} set noon",
            f"{unset} time of maps:{m['name']} pause",
            f"scoreboard players set {m['name']} maps_clock 1",
            # Each dimension has its own border; without one, flying out generates void forever.
            f"execute in maps:{m['name']} run worldborder center {m['border']['x']} {m['border']['z']}",
            f"execute in maps:{m['name']} run worldborder set {m['border']['size']}",
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
    # (set in game by builders). Dropping the map's tag shows its title again (map_tick_function),
    # also when the player is on the map already. Runs as the player with op level 2, silent so
    # the tag command's feedback doesn't show.
    def run(command):
        return {"command": command, "op_level": 2, "silent": True}

    names = ", ".join(m["name"] for m in server_maps)
    return json.dumps({
        "id": "map",
        "executes": [run(f"tellraw @s {to_json('<muted>Maps:</muted> ' + names)}")],
        "literals": [{"id": m["name"], "executes": [run(f"tag @s remove map_{m['name']}"), run(f"warp tp {m['name']}")]}
                     for m in server_maps] + [{"id": "rtp", "executes": map_rtp(server_maps)}],
    }, indent=2)


RTP_TRIES = 10


def rtp_pool(server_maps):
    return [m for m in server_maps if m.get("rtp", True)]


def rtp_bounds(m):
    # Where /rtp may land on the map: its rtp_area (x and z ranges, corners in any order), else
    # anywhere, inside its world border either way (1 block in from it).
    b = m["border"]
    half = b["size"] // 2 - 1
    edge = {"x": (b["x"] - half, b["x"] + half), "z": (b["z"] - half, b["z"] + half)}
    area = m.get("rtp_area", {})
    out = {}
    for axis in ("x", "z"):
        lo, hi = sorted(area.get(axis, edge[axis]))
        lo, hi = int(max(lo, edge[axis][0])), int(min(hi, edge[axis][1]))  # a border centre may be x.5
        if lo >= hi:
            raise SystemExit(f"servers/fpv/maps/{m['name']}.toml: rtp_area {axis} is outside the border")
        out[axis] = (lo, hi)
    return out


def rtp_functions(m):
    """data/maps/function/rtp/<name>.mcfunction (and its macro step): a random spot on the map.

    A random column in rtp_bounds, then spreadplayers on just that column (range 1), which puts the
    player on its highest block (below rtp_y.max if set) unless that's a liquid or fire or there's
    none (void), or it's lower than rtp_y.min: then another column, RTP_TRIES times at most. No spot
    at all: the map's spawn, as /map <name>.
    """
    name, b = m["name"], rtp_bounds(m)
    retry = "execute unless score @s maps_rtp_ok matches 1"
    lines = ["scoreboard players set @s maps_rtp_ok 0", f"tag @s remove map_{name}"]
    for _ in range(RTP_TRIES):
        lines += [
            f"{retry} store result storage maps:rtp x int 1 run random value {b['x'][0]}..{b['x'][1]}",
            f"{retry} store result storage maps:rtp z int 1 run random value {b['z'][0]}..{b['z'][1]}",
            f"{retry} run function maps:rtp/{name}_at with storage maps:rtp",
        ]
    lines.append(f"{retry} run warp tp {name}")
    y = m.get("rtp_y", {})
    under = f" under {y['max']}" if "max" in y else ""
    at = [f"$execute store success score @s maps_rtp_ok in maps:{name} run spreadplayers $(x) $(z) 0 1{under} false @s"]
    if "min" in y:
        at.append(f"execute if score @s maps_rtp_ok matches 1 at @s unless entity @s[y={y['min']},dy=4096] "
                  "run scoreboard players set @s maps_rtp_ok 0")
    return {f"rtp/{name}.mcfunction": "\n".join(lines) + "\n", f"rtp/{name}_at.mcfunction": "\n".join(at) + "\n"}


def rtp_cooldown_function(seconds):
    """data/maps/function/rtp/cooldown.mcfunction: maps_rtp_go 1 if the player may /rtp now, else 0
    and how long to wait. Every /rtp counts, for everyone (no permission skips it). Ticks of the
    world's game time, so it holds over restarts."""
    ticks = seconds * 20
    if ticks <= 0:
        return "scoreboard players set @s maps_rtp_go 1\n"
    wait = "maps_rtp_wait"
    lines = [
        "scoreboard players set @s maps_rtp_go 1",
        "execute store result score #now maps_rtp_t run time query gametime",
        # Ticks since the last /rtp; never used: far longer than the cooldown.
        f"scoreboard players operation @s {wait} = #now maps_rtp_t",
        f"execute if score @s maps_rtp_last = @s maps_rtp_last run scoreboard players operation @s {wait} -= @s maps_rtp_last",
        f"execute unless score @s maps_rtp_last = @s maps_rtp_last run scoreboard players set @s {wait} {ticks}",
        f"execute if score @s {wait} matches 0..{ticks - 1} run scoreboard players set @s maps_rtp_go 0",
        # Seconds left, rounded up: (ticks - waited + 19) / 20.
        f"scoreboard players set #left maps_rtp_t {ticks + 19}",
        f"scoreboard players operation #left maps_rtp_t -= @s {wait}",
        "scoreboard players set #20 maps_rtp_t 20",
        "scoreboard players operation #left maps_rtp_t /= #20 maps_rtp_t",
        f"scoreboard players operation @s {wait} = #left maps_rtp_t",
        "execute if score @s maps_rtp_go matches 0 run tellraw @s "
        + json.dumps([{"text": "Wait ", "color": "gray"}, {"score": {"name": "@s", "objective": wait}, "color": "gray"},
                      {"text": " s before the next /rtp.", "color": "gray"}]),
        "execute if score @s maps_rtp_go matches 1 run scoreboard players operation @s maps_rtp_last = #now maps_rtp_t",
    ]
    return "\n".join(lines) + "\n"


def map_rtp(server_maps):
    # /rtp, /map rtp (and a mannequin tagged propwash.map.rtp): a random spot (rtp_functions) on a
    # random map, after the cooldown check (rtp_cooldown_function). The pool is every map without
    # rtp = false in its .toml; a random number in the player's maps_rtp score picks it.
    def run(command):
        return {"command": command, "op_level": 2, "silent": True}

    pool = rtp_pool(server_maps)
    if not pool:
        return [run(f"tellraw @s {to_json('<muted>No maps to pick from.')}")]
    go = "execute if score @s maps_rtp_go matches 1"
    out = [run("function maps:rtp/cooldown"),
           run(f"{go} store result score @s maps_rtp run random value 0..{max(1, len(pool) - 1)}")]
    for i, m in enumerate(pool):
        out.append(run(f"{go} if score @s maps_rtp matches {i} run function maps:rtp/{m['name']}"))
    return out


def rtp_command(server_maps):
    # /rtp: a random spot on a random map (map_rtp). /rtp <map>: a random spot on that map. Both
    # after the cooldown check.
    def run(command):
        return {"command": command, "op_level": 2, "silent": True}

    return json.dumps({
        "id": "rtp",
        "executes": map_rtp(server_maps),
        "literals": [{"id": m["name"], "executes": [
            run("function maps:rtp/cooldown"),
            run(f"execute if score @s maps_rtp_go matches 1 run function maps:rtp/{m['name']}"),
        ]} for m in rtp_pool(server_maps)],
    }, indent=2)


def map_ops(name, d, server_maps, rtp_cooldown):
    store = host.data.get("world_store")
    if not store:
        raise SystemExit(f"{host.name} has no world_store but servers/{name}/maps/ lists maps")
    world = f"{d}/world"
    pack = f"{world}/datapacks/maps"
    ops = []
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
    ops.append(files.put(name=f"{name}: /rtp command", src=StringIO(rtp_command(server_maps)),
                         dest=f"{d}/config/melius-commands/commands/rtp.json", **OWNER))
    ops.append(files.put(name=f"{name}: /rtp cooldown", src=StringIO(rtp_cooldown_function(rtp_cooldown)),
                         dest=f"{pack}/data/maps/function/rtp/cooldown.mcfunction", **OWNER))
    for m in rtp_pool(server_maps):
        for rel, text in rtp_functions(m).items():
            ops.append(files.put(name=f"{name}: /rtp {m['name']}", src=StringIO(text),
                                 dest=f"{pack}/data/maps/function/{rel}", **OWNER))
    for m in server_maps:
        for rel, data in map_pack_files(m).items():
            ops.append(files.put(
                name=f"{name}: map {m['name']} {rel}",
                src=StringIO(json.dumps(data)),
                dest=f"{pack}/data/{rel}",
                **OWNER,
            ))
        dim = f"{world}/dimensions/maps/{m['name']}"
        # The world on the server is the map (builders edit it there); the store only seeds a
        # server that doesn't have it yet. `uv run apply` replaces one that's there.
        if host.get_fact(Directory, path=dim) is None:
            ops.append(server.shell(name=f"{name}: map {m['name']} seed",
                                    commands=[f"mc-seed-dimension {name} maps:{m['name']} {store}"]))
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


def gamerules_ops(name, d, rules):
    # Game rules are saved in the world, not server.properties: a datapack sets them on every start.
    pack = f"{d}/world/datapacks/gamerules"
    load = "".join(f"gamerule {k} {str(v).lower() if isinstance(v, bool) else v}\n" for k, v in rules.items())
    pack_files = {
        "pack.mcmeta": json.dumps({"pack": {"description": "Game rules", "min_format": 121, "max_format": 999}}),
        "data/minecraft/tags/function/load.json": json.dumps({"values": ["gamerules:load"]}),
        "data/gamerules/function/load.mcfunction": load,
    }
    return [files.put(name=f"{name}: gamerules {rel}", src=StringIO(content), dest=f"{pack}/{rel}", **OWNER)
            for rel, content in pack_files.items()]


def config_ops(name, d, cfg):
    # [config."<path>"]: top-level keys in a plugin's own config ("key: value", or "key=value" for
    # .properties), palette tags expanded. Plugins write their config on first start, so this applies
    # from the next deploy; a missing .properties is written with just these keys before it
    # (FabricPlots' plot-size has to be there when the plot world is first made), and the plugin adds its defaults.
    ops = []
    for rel, settings in cfg.get("config", {}).items():
        if rel.endswith(".properties") and not host.get_fact(File, path=f"{d}/{rel}"):
            content = "".join(f"{key}={palette_tags(str(value))}\n" for key, value in settings.items())
            ops.append(files.put(name=f"{name}: {rel}", src=StringIO(content), dest=f"{d}/{rel}", **OWNER))
        elif host.get_fact(File, path=f"{d}/{rel}"):
            sep = "=" if rel.endswith((".properties", ".hocon")) else ": "
            for key, value in settings.items():
                ops.append(files.line(
                    name=f"{name}: {rel} {key}",
                    path=f"{d}/{rel}",
                    line=f"^{key}{sep.strip()}.*",
                    replace=f"{key}{sep}{palette_tags(str(value))}",
                ))
    return ops


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
    packages=["openjdk-25-jre-headless", "tmux", "ufw", "curl", "python3-yaml", "restic", "mariadb-server",
              "git", "sqlite3"],
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
    keys = [p.read_text(encoding="utf-8").strip() for p in sorted(ROOT.glob("access/*.pub"))]
    if keys:
        server.user_authorized_keys(name="root SSH keys", user="root", public_keys=keys)

for unit in ["mc@.service", "mc-backup.service", "mc-backup.timer", "propwash-bot@.service"]:
    files.put(name=f"systemd unit {unit}", src=str(ROOT / "deploy/files" / unit), dest=f"/etc/systemd/system/{unit}", mode="644", **ROOT_OWNER)
for script in ["mc-console", "paper-velocity", "mc-rcon", "mc-backup", "mc-restore", "mc-merge-courses", "mc-seed-dimension"]:
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

# --- Discord bot -----------------------------------------------------------

# The bot/ folder of github.com/Itsj5j6j7/pw-server at this commit; change it to update the bot.
# Its own settings (channels, roles, bridge servers) are in that repo's src/config.js.
BOT_REPO = "https://github.com/Itsj5j6j7/pw-server.git"
BOT_COMMIT = "3043e5ea4d7500d5a4cfb94c859595c2e4ab840b"
BOT_DIR = "/home/bot/pw-server"
PNPM = "9.15.9"

# Node.js from nodejs.org, not Ubuntu's (18 on 24.04): the bot's bridge.db uses node:sqlite, Node 22.5+.
# To update, change the version and both checksums (the tarballs' lines in
# https://nodejs.org/dist/v<NODE>/SHASUMS256.txt). x64 for prod, arm64 for an ARM VPS.
NODE = "22.23.3"
NODE_SHA256 = {
    "x64": "df450af89261115ef9f9e3830c3eeb2cc9213b63c720b1af623cb5dcbe2e02de",
    "arm64": "a44aeb94849a299b22df10b9e622ec2f605c2183501bc40590705131de7c740f",
}
NODE_BIN = "/opt/node/bin"
server.shell(name="Node.js", commands=[
    'case "$(uname -m)" in x86_64) a=x64;; aarch64) a=arm64;; *) echo "no Node.js build for $(uname -m)"; exit 1;; esac; '
    f'case $a in x64) sum={NODE_SHA256["x64"]};; arm64) sum={NODE_SHA256["arm64"]};; esac; '
    f'd=/opt/node-v{NODE}-linux-$a; '
    f'[ -x $d/bin/node ] || {{ curl -fsSL -o /tmp/node.tar.xz https://nodejs.org/dist/v{NODE}/node-v{NODE}-linux-$a.tar.xz '
    '&& echo "$sum  /tmp/node.tar.xz" | sha256sum -c - && tar -xJf /tmp/node.tar.xz -C /opt && rm /tmp/node.tar.xz; } '
    '&& ln -sfn $d /opt/node',
])

server.user(name="bot user", user="bot", home="/home/bot", shell="/usr/sbin/nologin")
server.shell(name="pnpm", commands=[f'export PATH={NODE_BIN}:$PATH; [ "$(pnpm -v 2>/dev/null)" = {PNPM} ] || npm install -g pnpm@{PNPM}'])

bot_changes = []
if host.get_fact(GitHead, path=BOT_DIR) != BOT_COMMIT:
    # A checkout in place keeps the untracked .env, bridge.db and database.yml.
    bot_changes.append(server.shell(name="bot: checkout", commands=[
        f"runuser -u bot -- sh -c '[ -d {BOT_DIR}/.git ] || {{ git init -q {BOT_DIR} && git -C {BOT_DIR} remote add origin {BOT_REPO}; }}'",
        f"runuser -u bot -- git -C {BOT_DIR} fetch -q --depth 1 origin {BOT_COMMIT}",
        f"runuser -u bot -- git -C {BOT_DIR} checkout -q --force {BOT_COMMIT}",
        f"cd {BOT_DIR}/bot && runuser -u bot -- env PATH={NODE_BIN}:/usr/bin:/bin pnpm install --frozen-lockfile --prod",
    ]))
# The hub's RCON too: the bot gives Ko-fi supporters (Discord role) the LuckPerms supporter group with
# lp commands there.
bot_rcon = f"RCON_PORT={backends['hub']['port'] + 10000}\nRCON_PASSWORD={rcon_password}\n"
# TUNNEL_TOKEN is cloudflared's (below), not the bot's.
bot_dotenv = "\n".join(line for line in bot_env.splitlines() if not line.startswith("TUNNEL_TOKEN="))
bot_changes.append(files.put(name="bot: .env", src=StringIO(bot_dotenv + "\n" + bot_rcon), dest=f"{BOT_DIR}/bot/.env",
                             mode="600", user="bot", group="bot"))
# The bot's README copies example.database.yml (empty) to database.yml; an existing one is kept as it is.
files.file(name="bot: database.yml", path=f"{BOT_DIR}/bot/database.yml", user="bot", group="bot")

# The API listens on 127.0.0.1:3006 for the bridge mods. The local stack has no Discord token, so only the API runs there.
for unit in ["api"] if LOCAL else ["api", "bot"]:
    systemd.service(
        name=f"bot: restart {unit} if changed",
        service=f"propwash-bot@{unit}",
        running=None,
        restarted=True,
        daemon_reload=True,
        _if=any_changed(*bot_changes),
    )
    systemd.service(name=f"bot: {unit} running", service=f"propwash-bot@{unit}", running=True, enabled=True, daemon_reload=True)

# Cloudflare Tunnel: Ko-fi's webhook reaches the bot API's POST /kofi (127.0.0.1:3006) through it, with
# no port open on the VPS. The tunnel and its public hostname are set up in Cloudflare (Zero Trust >
# Networks > Tunnels: kofi.propwashfpv.com, path ^/kofi$ only, so the rest of the API stays private,
# service http://localhost:3006); its token is TUNNEL_TOKEN in bot.env.sops.
# Without one, no tunnel. To update, change the version and both checksums (the release notes list them).
CLOUDFLARED = "2026.10.3"
CLOUDFLARED_SHA256 = {
    "amd64": "053b80b0a23031a038fdbf3c073f59f82b8f1f152aa67971d7383ffafc0dbab0",
    "arm64": "89297ebfb9e8c99c0dc013a1140601453cf3798d8306c5591651f4a3f5210be8",
}
CLOUDFLARED_UNIT = """[Unit]
Description=Cloudflare Tunnel (Ko-fi webhook to the bot API)
After=network-online.target
Wants=network-online.target

[Service]
EnvironmentFile=/etc/cloudflared.env
DynamicUser=yes
ExecStart=/usr/local/bin/cloudflared --no-autoupdate tunnel run
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
"""
if bot_keys.get("TUNNEL_TOKEN") and not LOCAL:
    tunnel_changes = [
        server.shell(name="cloudflared", commands=[
            'case "$(uname -m)" in x86_64) a=amd64;; aarch64) a=arm64;; *) echo "no cloudflared build for $(uname -m)"; exit 1;; esac; '
            f'case $a in amd64) sum={CLOUDFLARED_SHA256["amd64"]};; arm64) sum={CLOUDFLARED_SHA256["arm64"]};; esac; '
            f'/usr/local/bin/cloudflared --version 2>/dev/null | grep -q " {CLOUDFLARED} " || '
            f'{{ curl -fsSL -o /tmp/cloudflared https://github.com/cloudflare/cloudflared/releases/download/{CLOUDFLARED}/cloudflared-linux-$a '
            '&& echo "$sum  /tmp/cloudflared" | sha256sum -c - && install -m 755 /tmp/cloudflared /usr/local/bin/cloudflared && rm /tmp/cloudflared; }',
        ]),
        files.put(name="cloudflared: token", src=StringIO(f"TUNNEL_TOKEN={bot_keys['TUNNEL_TOKEN']}\n"),
                  dest="/etc/cloudflared.env", mode="600", **ROOT_OWNER),
        files.put(name="cloudflared: unit", src=StringIO(CLOUDFLARED_UNIT), dest="/etc/systemd/system/cloudflared.service",
                  **ROOT_OWNER),
    ]
    systemd.service(name="cloudflared: restart if changed", service="cloudflared", running=None, restarted=True,
                    daemon_reload=True, _if=any_changed(*tunnel_changes))
    systemd.service(name="cloudflared: running", service="cloudflared", running=True, enabled=True, daemon_reload=True)

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
    files.template(
        name="velocity: TAB config.yml",
        src=str(ROOT / "servers/velocity/tab.yml.j2"),
        dest=f"{v}/plugins/tab/config.yml",
        maps=maps.get("fpv", []),
        **OWNER,
    ),
    *config_ops("velocity", v, velocity),
]

# mods/crossserver moves players with a console tp over rcon, so it gets each backend's rcon port and
# the password (a file of its own, taken as it is). Read on every teleport, so no restart.
files.put(
    name="velocity: crossserver rcon ports",
    src=StringIO("".join(f"{n}={c['port'] + 10000}\n" for n, c in backends.items())),
    dest=f"{v}/plugins/crossserver/rcon.properties",
    **OWNER,
)
files.put(name="velocity: crossserver rcon password", src=StringIO(rcon_password),
          dest=f"{v}/plugins/crossserver/rcon.password", mode="600", **OWNER)

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
        changes += map_ops(name, d, maps[name], cfg.get("rtp_cooldown", 15))

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
    if cfg.get("gamerules"):
        changes += gamerules_ops(name, d, cfg["gamerules"])

    changes += config_ops(name, d, cfg)

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
