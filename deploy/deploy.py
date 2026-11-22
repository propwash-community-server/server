"""
Velocity + Paper/Fabric backends on one box, one folder per server under servers/.

Safe to re-run: it only changes what differs, and never touches worlds,
whitelists, ops or any server.properties setting except the ones it manages.
"""

import base64
import hashlib
import json
import os
import re
import shlex
import subprocess
import tarfile
import tomllib
from io import BytesIO, StringIO
from pathlib import Path
from urllib.parse import unquote

from pyinfra import host
from pyinfra.api import FactBase
from pyinfra.facts.files import File, FindDirectories, FindFiles
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

# A map's rank (maps/<map>.toml), easiest first.
RANKS = ("Beginner", "Intermediate", "Advanced", "Pro")
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


def service_env(bundle, name, stop_cmd):
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
    # The bridge mod's key for this server (BRIDGE_KEY_RACE for race); the key is how the bot's API knows the server.
    if f"BRIDGE_KEY_{name.upper()}" in bot_keys:
        env += f"PROPWASH_BRIDGE_API_KEY={bot_keys[f'BRIDGE_KEY_{name.upper()}']}\n"
    bundle.put(f"{SERVERS}/{name}/service.env", env, mode="600")


# Texts are MiniMessage, with the palette.toml colors as tags: <muted>text</muted>.
TAG = re.compile(r"<(/?)([a-z_]+|#[0-9a-fA-F]{6})>")
COLORS = {
    "black": "0", "dark_blue": "1", "dark_green": "2", "dark_aqua": "3", "dark_red": "4", "dark_purple": "5",
    "gold": "6", "gray": "7", "dark_gray": "8", "blue": "9", "green": "a", "aqua": "b", "red": "c",
    "light_purple": "d", "yellow": "e", "white": "f",
}
DECORATIONS = {"bold": "l", "italic": "o", "underlined": "n", "strikethrough": "m", "obfuscated": "k"}
# Links in tellraw texts: <click:open_url:'https://...'>text</click>, <hover:show_text:'...'>text</hover>.
LINK = re.compile(r"<click:open_url:'([^']*)'>|</click>|<hover:show_text:'([^']*)'>|</hover>")


def palette_tags(text):
    # For what reads MiniMessage itself: LuckPerms prefixes, Carbon, TAB, the MOTD.
    return TAG.sub(lambda m: f"<{m[1]}{palette[m[2]]}>" if m[2] in palette else m[0], text)


def spans(text):
    """(text, open tags, link url, hover text) pieces of a text, for what doesn't read MiniMessage. Only
    colors, decorations, <reset> and links are tags; anything else in <> (like <name> in a command) stays text."""
    text, stack, out, pos, url, hover = palette_tags(text), [], [], 0, None, None
    # Links first: </click> and </hover> also look like color tags.
    for m in re.finditer(LINK.pattern + "|" + TAG.pattern, text):
        tag = m[4]
        if tag is not None and tag not in COLORS and tag not in DECORATIONS and not tag.startswith("#") and tag != "reset":
            continue
        out.append((text[pos:m.start()], list(stack), url, hover))
        pos = m.end()
        if tag is None:  # a link tag
            if m[0] == "</click>":
                url = None
            elif m[0] == "</hover>":
                hover = None
            elif m[1] is not None:
                url = m[1]
            else:
                hover = m[2]
        elif tag == "reset":
            stack = []
        elif not m[3]:
            stack.append(tag)
        elif tag in stack:
            del stack[len(stack) - 1 - stack[::-1].index(tag)]
    return [piece for piece in out + [(text[pos:], stack, url, hover)] if piece[0]]


def color(tags):
    colors = [t for t in tags if t in COLORS or t.startswith("#")]
    return colors[-1] if colors else None


def to_json(text):
    # A text component, for tellraw and title.
    return json.dumps(["", *(
        {"text": t, **({"color": color(s)} if color(s) else {}), **{d: True for d in s if d in DECORATIONS},
         **({"click_event": {"action": "open_url", "url": url}} if url else {}),
         **({"hover_event": {"action": "show_text", "value": hover}} if hover else {})}
        for t, s, url, hover in spans(text)
    )])


def to_legacy(text):
    # & codes, for Essentials text files. Each line is its own message, so each one restates its style.
    def codes(tags):
        c = color(tags)
        return "&r" + ("" if not c else f"&{c}" if c.startswith("#") else f"&{COLORS[c]}") + "".join(
            f"&{DECORATIONS[d]}" for d in tags if d in DECORATIONS)

    return "".join("\n".join(codes(s) + line if line else "" for line in t.split("\n")) for t, s, _, _ in spans(text))


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

# Every server, to see why one ran out of memory (docs/admin.md#frozen-server): its garbage collection
# in logs/gc.log (5 files of 10 MB at most), and a heap dump in /home/mc/diagnostics when memory
# actually runs out (as big as its RAM; delete it once looked at).
DIAGNOSTICS = "/home/mc/diagnostics"
DIAGNOSTIC_JVM_FLAGS = (
    "-Xlog:gc*:file=logs/gc.log:time,uptime,level,tags:filecount=5,filesize=10M"
    f" -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath={DIAGNOSTICS}"
)


def start_script(bundle, path, jar, ram, args="", jvm_flags=""):
    jvm = f" {DIAGNOSTIC_JVM_FLAGS}" + (f" {jvm_flags}" if jvm_flags else "")
    script = (
        "#!/bin/sh\n"
        "# Managed by pyinfra - edit servers/<name>/server.toml instead.\n"
        f"exec java -Xms{ram} -Xmx{ram}{jvm} -jar {jar} {args}\n"
    )
    bundle.put(path, script, mode="755")


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


class Contents(FactBase):
    """The files among paths that exist: {path: bytes}. One command for them all."""

    default = dict

    def command(self, paths):
        quoted = " ".join(shlex.quote(p) for p in paths)
        return f'for p in {quoted}; do [ -f "$p" ] && echo "$(base64 -w0 < "$p"):$p"; done; true'

    def process(self, output):
        return {path: base64.b64decode(data) for data, _, path in (line.partition(":") for line in output)}


class Snapshot(FactBase):
    """The files among paths that exist: {path: (sha1, octal mode)}. One command for them all."""

    default = dict

    def command(self, paths):
        quoted = " ".join(shlex.quote(p) for p in paths)
        return f'for p in {quoted}; do [ -f "$p" ] && echo "$(sha1sum < "$p" | cut -c1-40) $(stat -c %a "$p") $p"; done; true'

    def process(self, output):
        return {path: (sha, mode) for sha, mode, path in (line.split(" ", 2) for line in output)}


def edit_lines(text, edits):
    """files.line for many lines at once, in Python: for each (pattern, replacement), the lines matching
    the pattern as a whole line become the replacement; with none, it's appended unless already there."""
    trailing = text.endswith("\n")
    lines = text.split("\n")[:-1] if trailing else (text.split("\n") if text else [])
    for pattern, replacement in edits:
        pattern = (pattern if pattern.startswith("^") else f"^.*{pattern}")
        pattern = (pattern if pattern.endswith("$") else f"{pattern}.*$")
        rx = re.compile(pattern)
        hits = [i for i, line in enumerate(lines) if rx.search(line)]
        for i in hits:
            lines[i] = replacement
        if not hits and replacement not in lines:
            lines.append(replacement)
            trailing = True
    return "\n".join(lines) + ("\n" if trailing else "")


class Bundle:
    """A server's small files (configs, datapacks, scripts), compared in one command and the changed ones
    sent in one tar: a handful of operations instead of one or more per file, which made deploys slow.
    restart=False files (read on use) don't restart the server."""

    def __init__(self, label, d):
        self.label, self.d, self.files = label, d, {}
        self.remote = None

    def contents(self, paths):
        # The current contents of files this bundle edits; one command, read once per bundle.
        if self.remote is None:
            self.remote = host.get_fact(Contents, paths=sorted(paths)) if paths else {}
        return self.remote

    def put(self, dest, content, mode=None, restart=True):
        if isinstance(content, Path):
            content = content.read_bytes()
        self.files[dest] = (content.encode() if isinstance(content, str) else content, mode, restart)

    def edit(self, dest, edits, base="", restart=True):
        # Like files.line on dest for each edit, on top of what this bundle already puts there, else the
        # file on the server, else base. Unchanged files are left out when the bundle is sent.
        if dest in self.files:
            text = self.files[dest][0].decode()
        elif dest in self.remote:
            text = self.remote[dest].decode()
        else:
            text = base
        self.put(dest, edit_lines(text, edits), restart=restart)

    def ops(self):
        """(ops that should restart the server, other ops)."""
        if not self.files:
            return [], []
        remote = host.get_fact(Snapshot, paths=sorted(self.files))
        changed = {True: {}, False: {}}
        for dest, (data, mode, restart) in sorted(self.files.items()):
            have = remote.get(dest)
            if have is None or have[0] != hashlib.sha1(data).hexdigest() or (mode and have[1] != mode):
                changed[restart][dest] = (data, mode or (have[1] if have else "644"))
        return self._send(changed[True], "restart"), self._send(changed[False], "live")

    def _send(self, changed, kind):
        if not changed:
            return []
        buf = BytesIO()
        with tarfile.open(fileobj=buf, mode="w") as tar:
            for dest, (data, mode) in changed.items():
                info = tarfile.TarInfo(dest.lstrip("/"))
                info.size, info.mode, info.mtime = len(data), int(mode, 8), 0
                info.uname = info.gname = MC
                tar.addfile(info, BytesIO(data))
        buf.seek(0)
        names = [p.removeprefix(self.d + "/") for p in changed]
        shown = ", ".join(names[:3]) + (f" and {len(names) - 3} more" if len(names) > 3 else "")
        tmp = f"/tmp/deploy-{self.label}-{kind}.tar"
        return [
            files.put(name=f"{self.label}: send {shown}", src=buf, dest=tmp, mode="600"),
            server.shell(name=f"{self.label}: unpack {len(names)} file(s)", commands=[f"tar -xpf {tmp} -C / && rm {tmp}"]),
        ]


def jar_folder(label, folder, urls):
    # Download listed jars, delete any other jar in the folder. A jar's file name has its version, so one
    # that's there already is the right one: no operation for it (the listing is one command for all).
    wanted = {filename(u): u for u in urls}
    ops = [files.directory(name=f"{label} folder", path=folder, **OWNER)]
    there = {Path(p).name for p in host.get_fact(FindFiles, path=folder, maxdepth=1, fname="*.jar") or []}
    for jar, url in wanted.items():
        if jar not in there:
            ops.append(files.download(name=f"{label} {jar}", src=url, dest=f"{folder}/{jar}", **OWNER))
    for jar in sorted(there - set(wanted)):
        ops.append(files.file(name=f"{label} remove {jar}", path=f"{folder}/{jar}", present=False))
    return ops


def palette_file(p):
    # A text file's contents with the palette tags expanded, or the file itself if it has none.
    try:
        text = p.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        return p
    return palette_tags(text) if palette_tags(text) != text else p


def extra_files(bundle, name, d):
    # servers/<name>/files/ is copied into the server folder, palette tags expanded.
    src = ROOT / "servers" / name / "files"
    for p in sorted(src.rglob("*")):
        if p.is_file():
            bundle.put(f"{d}/{p.relative_to(src).as_posix()}", palette_file(p))


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


def credit_markup(line):
    # "Uncrashed map by SHO": the role muted, the name (after the last " by ") in Propwash yellow.
    role, by, who = line.rpartition(" by ")
    return f"<muted>{role}{by}</muted><propwash>{who}</propwash>" if by else f"<muted>{line}</muted>"


# Vanilla's glyph widths (pixels, spacing included) for the characters the maps resource pack's credits
# fonts have; made with deploy/files/make_credit_fonts.py from the client's ascii.png.
FONT_WIDTHS = json.loads((ROOT / "deploy/files/font_widths.json").read_text(encoding="utf-8"))


def pen_move(pixels):
    # Characters of the pack's propwash:space font that move the pen this many pixels (+-2^k each).
    sign, n, out = (0xE000 if pixels >= 0 else 0xE010), abs(pixels), ""
    for k in range(10):
        if n >> k & 1:
            out += chr(sign + k)
    return {"text": out, "font": "propwash:space"} if out else None


def credits_subtitle(credits):
    # A title has one subtitle line, so a second and third credit line are drawn with the pack's
    # propwash:credits1 / credits2 fonts (the same glyphs, one / two lines lower), each one centred
    # under the first: the pen goes back by the right amount before each, and ends where the first
    # line ends, so the subtitle's width (what the game centres) is the first line's.
    if len(credits) > 3 or any(c not in FONT_WIDTHS for line in credits for c in line):
        return None
    widths = [sum(FONT_WIDTHS[c] for c in line) for line in credits]
    parts, pen = json.loads(to_json(credit_markup(credits[0])))[1:], widths[0]
    for n, (line, width) in enumerate(zip(credits[1:], widths[1:]), start=1):
        start = (widths[0] - width) // 2
        parts += [p for p in [pen_move(start - pen)] if p]
        parts += [{**p, "font": f"propwash:credits{n}"} for p in json.loads(to_json(credit_markup(line)))[1:]]
        pen = start + width
    parts += [p for p in [pen_move(widths[0] - pen)] if p]
    return json.dumps(["", *parts])


def map_tick_function(server_maps):
    # Title and author on screen each time a player enters a map. A map with credits shows them under
    # the title, one line each (credits_subtitle); if they don't fit there, the first line under the
    # title and the others above the hotbar.
    lines = []
    for m in server_maps:
        tag, dim = f"map_{m['name']}", f"maps:{m['name']}"
        entering = f"execute as @a[tag=!{tag}] at @s if dimension {dim} run"
        credits = m.get("credits") or ["by " + m["author"]]
        subtitle = credits_subtitle(credits) if len(credits) > 1 else None
        if len(credits) > 1 and not subtitle:
            lines.append(f"{entering} title @s actionbar {to_json('<muted>  ·  </muted>'.join(credit_markup(c) for c in credits[1:]))}")
        if not subtitle:
            subtitle = to_json(credit_markup(credits[0]) if m.get("credits") else "<muted>" + credits[0])
        lines += [
            f"{entering} title @s subtitle {subtitle}",
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

    # One map a line, like /race: its title (click to go), then who made it (its credits, a line each).
    listing = [{"text": "Maps (click one to go there):", "color": "gray"}]
    for m in server_maps:
        if m.get("hidden"):
            continue  # a secret map: /map <name> goes there, but /map doesn't list it
        go = f"/map {m['name']}"
        listing += [{"text": "\n » ", "color": "dark_gray"},
                    {"text": m.get("title", m["name"].capitalize()), "color": "white",
                     "click_event": {"action": "run_command", "command": go},
                     "hover_event": {"action": "show_text", "value": go}}]
        if m.get("credits"):
            for line in m["credits"]:
                listing += [{"text": "\n     "}, *json.loads(to_json(credit_markup(line)))[1:]]
        elif m.get("author"):
            listing.append({"text": " by " + m["author"], "color": "gray"})
    return json.dumps({
        "id": "map",
        "executes": [run(f"tellraw @s {json.dumps(['', *listing])}")],
        "literals": [{"id": m["name"], "executes": [run(f"tag @s remove map_{m['name']}"), run(f"warp tp {m['name']}")]}
                     for m in server_maps] + [{"id": "rtp", "executes": map_rtp(server_maps)}],
    }, indent=2)


RTP_TRIES = 10


def rtp_pool(server_maps):
    return [m for m in server_maps if m.get("rtp", True) and not m.get("hidden")]


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
            raise SystemExit(f"maps/{m['name']}.toml: rtp_area {axis} is outside the border")
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


def map_ops(bundle, name, d, server_maps, rtp_cooldown, with_map_command=True):
    store = host.data.get("world_store")
    if not store:
        raise SystemExit(f"{host.name} has no world_store but servers/{name}/maps/ lists maps")
    world = f"{d}/world"
    pack = f"{world}/datapacks/maps"
    ops = []
    bundle.put(f"{pack}/pack.mcmeta", MAP_PACK)
    bundle.put(f"{pack}/data/minecraft/tags/function/load.json", LOAD_TAG)
    bundle.put(f"{pack}/data/maps/function/load.mcfunction", map_load_function(server_maps))
    bundle.put(f"{pack}/data/minecraft/tags/function/tick.json", TICK_TAG)
    bundle.put(f"{pack}/data/maps/function/tick.mcfunction", map_tick_function(server_maps))
    if with_map_command:
        # Not where mods/tweaks makes /map the same as /race (map = "race" in its tweaks.properties).
        bundle.put(f"{d}/config/melius-commands/commands/map.json", map_command(server_maps))
        bundle.put(f"{d}/config/melius-commands/commands/rtp.json", rtp_command(server_maps))
        bundle.put(f"{pack}/data/maps/function/rtp/cooldown.mcfunction", rtp_cooldown_function(rtp_cooldown))
        for m in rtp_pool(server_maps):
            for rel, text in rtp_functions(m).items():
                bundle.put(f"{pack}/data/maps/function/{rel}", text)
    else:
        # There mods/tweaks' /rtp (and /map rtp) is a random race, not a random spot: no Melius /rtp to clash with it.
        ops.append(files.file(name=f"{name}: no Melius /rtp", path=f"{d}/config/melius-commands/commands/rtp.json",
                              present=False))
    seeded = {Path(p).name for p in host.get_fact(FindDirectories, path=f"{world}/dimensions/maps", maxdepth=1) or []}
    for m in server_maps:
        for rel, data in map_pack_files(m).items():
            bundle.put(f"{pack}/data/{rel}", json.dumps(data))
        # The world on the server is the map (builders edit it there); the store only seeds a
        # server that doesn't have it yet. `uv run apply` replaces one that's there.
        if m["name"] not in seeded:
            ops.append(server.shell(name=f"{name}: map {m['name']} seed",
                                    commands=[f"mc-seed-dimension {name} maps:{m['name']} {store}"]))
    return ops


def welcome_ops(bundle, name, d, text):
    # A datapack, the same on Paper and Fabric: welcome text to players new to the server, and to
    # those back after leaving it (the leave_game stat also counts switching servers on the proxy).
    # The second line only for players already welcomed: a player whose player data is gone but whose
    # leave_game score is still in the world's scoreboard would otherwise get the message twice.
    pack = f"{d}/world/datapacks/welcome"
    message = to_json(text)
    tick = (f"tellraw @a[tag=!welcomed] {message}\n"
            f"tellraw @a[tag=welcomed,scores={{welcome.left=1..}}] {message}\n"
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
    for rel, content in pack_files.items():
        bundle.put(f"{pack}/{rel}", content)


def gamerules_ops(bundle, name, d, rules):
    # Game rules are saved in the world, not server.properties: a datapack sets them on every start.
    pack = f"{d}/world/datapacks/gamerules"
    load = "".join(f"gamerule {k} {str(v).lower() if isinstance(v, bool) else v}\n" for k, v in rules.items())
    pack_files = {
        "pack.mcmeta": json.dumps({"pack": {"description": "Game rules", "min_format": 121, "max_format": 999}}),
        "data/minecraft/tags/function/load.json": json.dumps({"values": ["gamerules:load"]}),
        "data/gamerules/function/load.mcfunction": load,
    }
    for rel, content in pack_files.items():
        bundle.put(f"{pack}/{rel}", content)


def config_paths(d, cfg):
    return [f"{d}/{rel}" for rel in cfg.get("config", {})]


def config_edits(bundle, d, cfg):
    # [config."<path>"]: top-level keys in a plugin's own config ("key: value", or "key=value" for
    # .properties), palette tags expanded. Plugins write their config on first start, so this applies
    # from the next deploy; a missing .properties is written with just these keys before it
    # (FabricPlots' plot-size has to be there when the plot world is first made), and the plugin adds its defaults.
    for rel, settings in cfg.get("config", {}).items():
        if rel.endswith(".properties") or f"{d}/{rel}" in bundle.remote:
            sep = "=" if rel.endswith((".properties", ".hocon")) else ": "
            bundle.edit(f"{d}/{rel}", [(f"^{key}{sep.strip()}.*", f"{key}{sep}{palette_tags(str(value))}")
                                       for key, value in settings.items()])


def ownership(name, d):
    # Files and folders written as root (the parent folders a bundle's tar creates, the paper-velocity
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
    # The JDK rather than the JRE for jcmd (mc-diagnose).
    packages=["openjdk-25-jdk-headless", "tmux", "ufw", "curl", "python3-yaml", "restic", "mariadb-server",
              "git", "sqlite3"],
    update=True,
    cache_time=3600,
)

server.user(name="mc user", user=MC, home=f"/home/{MC}", shell="/bin/bash")

if not LOCAL:
    server.shell(
        name="Firewall: SSH, Velocity and voice chat only",
        # 24454/udp: Simple Voice Chat on the proxy (servers/velocity/server.toml).
        commands=["ufw allow 22/tcp", "ufw allow 25565/tcp", "ufw allow 24454/udp", "ufw --force enable"],
    )

    # Additive for now: switch to delete_keys=True once every current key is in access/.
    keys = [p.read_text(encoding="utf-8").strip() for p in sorted(ROOT.glob("access/*.pub"))]
    if keys:
        server.user_authorized_keys(name="root SSH keys", user="root", public_keys=keys)

for unit in ["mc@.service", "mc-backup.service", "mc-backup.timer", "propwash-bot@.service"]:
    files.put(name=f"systemd unit {unit}", src=str(ROOT / "deploy/files" / unit), dest=f"/etc/systemd/system/{unit}", mode="644", **ROOT_OWNER)
for script in ["mc-console", "paper-velocity", "mc-rcon", "mc-backup", "mc-restore", "mc-merge-courses", "mc-seed-dimension", "mc-diagnose",
               "mc-maintenance", "mc-wait-empty"]:
    files.put(name=f"{script} script", src=str(ROOT / "deploy/files" / script), dest=f"/usr/local/bin/{script}", mode="755", **ROOT_OWNER)

files.directory(name="servers folder", path=SERVERS, **OWNER)
files.directory(name="diagnostics folder", path=DIAGNOSTICS, **OWNER)

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

# The bot/ folder of github.com/Itsj5j6j7/pw-server, at the head of its main branch on every deploy.
# BOT_COMMIT=<sha> in the environment deploys that commit instead (to go back to an earlier one).
# Its own settings (channels, roles, bridge servers) are in that repo's src/config.js.
BOT_REPO = "https://github.com/Itsj5j6j7/pw-server.git"
BOT_COMMIT = os.environ.get("BOT_COMMIT") or subprocess.run(
    ["git", "ls-remote", "--exit-code", BOT_REPO, "refs/heads/main"], capture_output=True, text=True, check=True,
).stdout.split()[0]
if not re.fullmatch(r"[0-9a-f]{40}", BOT_COMMIT):
    raise SystemExit(f"BOT_COMMIT must be a full 40-character commit hash, not {BOT_COMMIT!r}")
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
bot_head = host.get_fact(GitHead, path=BOT_DIR)
if bot_head != BOT_COMMIT:
    print(f"{host.name}: bot {(bot_head or 'none')[:7]} -> {BOT_COMMIT[:7]}"
          + (f", changes: https://github.com/Itsj5j6j7/pw-server/compare/{bot_head}...{BOT_COMMIT}" if bot_head else ""))
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

def tab_world_names():
    """The tab list's map after the server name, by the world TAB-Bridge reports (world_<name>):
    {world: (on race, elsewhere)}. Race shows a track's /race name (its title; for worlds:* the
    title.worlds.<id> line in race's tweaks.properties, else race's [tab] names), freestyle the world name."""
    race_tweaks = configs.get("race", {}).get("config", {}).get("config/tweaks.properties", {})
    race_names = {m["name"]: m.get("title", m["name"]) for m in maps.get("race", [])}
    race_names |= {k.removeprefix("title.worlds."): v for k, v in race_tweaks.items() if k.startswith("title.worlds.")}
    race_names |= configs.get("race", {}).get("tab", {}).get("names", {})
    other = {m["name"] for server, server_maps in maps.items() if server != "race" for m in server_maps}
    names = {}
    for world in sorted(set(race_names) | other):
        names[world] = (race_names.get(world, world) if world in race_names else None,
                        world if world in other else None)
    return names


# --- Velocity (first: Paper backends read its forwarding.secret) ------------

v = f"{SERVERS}/velocity"
files.directory(name="velocity folder", path=v, **OWNER)
bundle = Bundle("velocity", v)
libertybans_sql = f"{v}/plugins/libertybans/sql.yml"
bundle.contents(config_paths(v, velocity) + [libertybans_sql])

start_script(bundle, f"{v}/start.sh", velocity["jar"], RAM or velocity["ram"])
service_env(bundle, "velocity", "end")
bundle.put(f"{v}/forwarding.secret", secret, mode="600")
extra_files(bundle, "velocity", v)
config_edits(bundle, v, velocity)

# mods/crossserver moves players with a console tp over rcon, so it gets each backend's rcon port and
# the password (a file of its own, taken as it is). Read on every teleport, so no restart.
bundle.put(f"{v}/plugins/crossserver/rcon.properties", "".join(f"{n}={c['port'] + 10000}\n" for n, c in backends.items()),
           restart=False)
bundle.put(f"{v}/plugins/crossserver/rcon.password", rcon_password, mode="600", restart=False)

# LibertyBans writes sql.yml on first start (with its own embedded database); from the next
# deploy it points at MariaDB. The auth-details keys are each unique in that file.
if libertybans_sql in bundle.remote:
    bundle.edit(libertybans_sql, [(f"^{key}:.*", f"{key}: '{value}'") for key, value in {
        "rdms-vendor": "MARIADB",
        "  host": "127.0.0.1",
        "  database": "libertybans",
        "  user": "minecraft",
        "  password": mariadb_password,
    }.items()])

changes = [
    files.download(name="velocity jar", src=velocity["url"], dest=f"{v}/{velocity['jar']}", **OWNER),
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
    *jar_folder("velocity: plugin", f"{v}/plugins", velocity.get("plugins", [])),
    # MiniPlaceholders loads its expansions (placeholder add-ons) from its own folder, not plugins/.
    *jar_folder("velocity: expansion", f"{v}/plugins/miniplaceholders/expansions", velocity.get("expansions", [])),
    files.template(
        name="velocity: TAB config.yml",
        src=str(ROOT / "servers/velocity/tab.yml.j2"),
        dest=f"{v}/plugins/tab/config.yml",
        tab_worlds=tab_world_names(),
        **OWNER,
    ),
]
restart_ops, _ = bundle.ops()
changes += restart_ops

ownership("velocity", v)
service("velocity", changes)

# --- Backends --------------------------------------------------------------

for name, cfg in backends.items():
    d = f"{SERVERS}/{name}"
    kind = cfg["type"]
    files.directory(name=f"{name}: folder", path=d, **OWNER)

    # Map rankings (a map's rank in its maps/<map>.toml): /race groups the courses by them (mods/tweaks).
    # Credits (a map's credits, one line per role) go under its courses in /race too.
    ranks = {f"rank.maps.{m['name']}": m["rank"] for m in maps.get(name, []) if "rank" in m}
    ranks |= {f"credits.maps.{m['name']}": "|".join(m["credits"]) for m in maps.get(name, []) if m.get("credits")}
    # A map's only course goes by the map's title in /race.
    ranks |= {f"title.maps.{m['name']}": m["title"] for m in maps.get(name, []) if m.get("title")}
    # Secret maps: not in /race, /map or /rtp, their records not announced or sent to Discord (mods/tweaks SecretMaps).
    ranks |= {f"hidden.maps.{m['name']}": "true" for m in maps.get(name, []) if m.get("hidden")}
    for m in maps.get(name, []):
        if "rank" in m and m["rank"] not in RANKS:
            raise SystemExit(f"servers/{name}/maps/{m['name']}.toml: rank must be one of {', '.join(RANKS)}")
        credits = m.get("credits", [])
        if not isinstance(credits, list) or not all(isinstance(c, str) and c.strip() and "|" not in c for c in credits):
            raise SystemExit(f"servers/{name}/maps/{m['name']}.toml: credits must be a list of lines (without |)")
    if ranks:
        tweaks = {**ranks, **cfg.get("config", {}).get("config/tweaks.properties", {})}
        cfg = {**cfg, "config": {**cfg.get("config", {}), "config/tweaks.properties": tweaks}}

    server_json = f"{d}/config/propwash/server.json"
    server_properties = f"{d}/server.properties"
    proxy_toml = f"{d}/config/FabricProxy-Lite.toml"
    spigot_yml = f"{d}/spigot.yml"
    bundle = Bundle(name, d)
    bundle.contents(config_paths(d, cfg) + [server_json, server_properties, proxy_toml, spigot_yml])

    start_script(bundle, f"{d}/start.sh", cfg["jar"], RAM or cfg["ram"], "nogui", FABRIC_JVM_FLAGS if kind == "fabric" else "")
    service_env(bundle, name, "stop")
    bundle.put(f"{d}/eula.txt", "eula=true\n")
    bundle.put(f"{d}/ops.json", "[]")
    changes = [files.download(name=f"{name}: server jar", src=cfg["url"], dest=f"{d}/{cfg['jar']}", **OWNER)]
    if kind == "fabric":
        changes += jar_folder(f"{name}: mod", f"{d}/mods", cfg.get("mods", []))
    else:
        changes += jar_folder(f"{name}: plugin", f"{d}/plugins", cfg.get("plugins", []))
    extra_files(bundle, name, d)
    if maps.get(name):
        tweaks = cfg.get("config", {}).get("config/tweaks.properties", {})
        changes += map_ops(bundle, name, d, maps[name], cfg.get("rtp_cooldown", 15), with_map_command=tweaks.get("map") != "race")

    # /help: the server's help, then the network-wide one from the velocity server.toml.
    if cfg.get("help"):
        text = cfg["help"].strip() + "\n\n" + velocity.get("help", "").strip()
        if kind == "fabric":
            # Melius Commands, in place of vanilla /help; tellraw needs op level 2.
            help_command = {"id": "help", "executes": [{"command": f"tellraw @s {to_json(text)}", "op_level": 2}]}
            bundle.put(f"{d}/config/melius-commands/commands/help.json", json.dumps(help_command, indent=2))
        elif kind == "paper":
            # EssentialsX /help shows help.txt when there is one. It's read on use, so no restart.
            bundle.put(f"{d}/plugins/Essentials/help.txt", to_legacy(text) + "\n", restart=False)
        else:
            raise SystemExit(f"servers/{name}/server.toml has help, but /help is only made for fabric and paper")

    if cfg.get("welcome"):
        welcome_ops(bundle, name, d, cfg["welcome"].strip())
    if cfg.get("gamerules"):
        gamerules_ops(bundle, name, d, cfg["gamerules"])

    config_edits(bundle, d, cfg)
    # [propwash]: top-level keys of Propwash's config/propwash/server.json (written on first start, so this
    # applies from the next deploy), e.g. worldCourses = false. Edited line by line, so a key must already be
    # in the file and not be its last one (each line ends in a comma).
    if cfg.get("propwash") and server_json in bundle.remote:
        bundle.edit(server_json, [(rf'^\s*"{key}":.*', f'  "{key}": {json.dumps(value)},') for key, value in cfg["propwash"].items()])

    # disable_advancements (Paper): Spigot's list of disabled advancements becomes "*", all of them, so
    # nobody gets advancement pop-ups. From the deploy after the first start, when spigot.yml exists.
    # The pattern also matches the already-replaced line, so it's never appended a second time.
    if cfg.get("disable_advancements") and kind == "paper" and spigot_yml in bundle.remote:
        bundle.edit(spigot_yml, [(r"^  - (minecraft:story/disabled|'\*')$", "  - '*'")])

    # [flan]: deny = Flan world rules, ALLFALSE in every dimension of the server unless a player is in
    # /flan bypass (explicit dimensions, since Flan saves "*" back expanded, and "*" only reaches the
    # worlds loaded when Flan first reads it). Worlds not listed here (/world create, maps added by hand)
    # get the overworld's rules from mods/regen (FlanAllWorldsMixin). Other keys are top-level
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

    # A new server starts from its new_world settings (the server adds its defaults on first start).
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
    bundle.edit(server_properties, [(f"^{key}=.*", f"{key}={value}") for key, value in managed.items()],
                base="".join(f"{k}={v}\n" for k, v in cfg.get("new_world", {}).items()))

    files.directory(name=f"{name}: config folder", path=f"{d}/config", **OWNER)
    if kind == "fabric":
        # hackEarlySend: LuckPerms needs the real UUID at pre-login, otherwise it kicks everyone.
        # hackMessageChain: avoids "invalid chat signature" kicks after switching servers.
        bundle.edit(proxy_toml, [("^secret *=.*", f'secret = "{secret}"'),
                                 *((f"^{key} *=.*", f"{key} = true") for key in ["hackEarlySend", "hackMessageChain"])])

    restart_ops, _ = bundle.ops()
    changes += restart_ops
    if kind != "fabric" and not host.get_fact(PaperVelocityOk, server_dir=d):
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
