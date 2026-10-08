"""
Fill the local store with a blank stand-in for every map in servers/*/maps/ that it doesn't have yet:
void with a 5x5 stone platform at x/z 0-4, y 99. Runs as the `seed` service in compose.yaml.
"""

import io
import struct
import tarfile
import tomllib
import zlib
from pathlib import Path

SERVERS = Path("/servers")
STORE = Path("/data/worlds")

# Written as 1.21 (DataVersion 3953); the server upgrades it to its own format on load.
DATA_VERSION = 3953
PLATFORM_Y = 99


def encode(kind, value):
    if kind == 1:
        return struct.pack(">b", value)
    if kind == 3:
        return struct.pack(">i", value)
    if kind == 4:
        return struct.pack(">q", value)
    if kind == 8:
        raw = value.encode()
        return struct.pack(">H", len(raw)) + raw
    if kind == 9:
        item_kind, items = value
        return struct.pack(">bi", item_kind, len(items)) + b"".join(encode(item_kind, i) for i in items)
    if kind == 10:
        out = b""
        for name, (k, v) in value.items():
            out += struct.pack(">b", k) + encode(8, name) + encode(k, v)
        return out + b"\x00"
    if kind == 12:
        return struct.pack(">i", len(value)) + b"".join(struct.pack(">q", v) for v in value)
    raise ValueError(kind)


def section(y):
    air = {"Name": (8, "minecraft:air")}
    biomes = {"palette": (9, (8, ["minecraft:the_void"]))}
    if y != PLATFORM_Y >> 4:
        return {"Y": (1, y), "block_states": (10, {"palette": (9, (10, [air]))}), "biomes": (10, biomes)}
    # 4 bits per block, 16 blocks per long, index = (y * 16 + z) * 16 + x.
    longs = [0] * 256
    for x in range(5):
        for z in range(5):
            i = ((PLATFORM_Y & 15) * 16 + z) * 16 + x
            longs[i // 16] |= 1 << (i % 16 * 4)
    states = {
        "palette": (9, (10, [air, {"Name": (8, "minecraft:stone")}])),
        "data": (12, [v - (1 << 64) if v >= 1 << 63 else v for v in longs]),
    }
    return {"Y": (1, y), "block_states": (10, states), "biomes": (10, biomes)}


def region():
    chunk = {
        "DataVersion": (3, DATA_VERSION),
        "xPos": (3, 0),
        "zPos": (3, 0),
        "yPos": (3, -4),
        "Status": (8, "minecraft:full"),
        "LastUpdate": (4, 0),
        "isLightOn": (1, 0),
        "sections": (9, (10, [section(y) for y in range(-4, 20)])),
        "block_entities": (9, (0, [])),
    }
    data = zlib.compress(b"\x0a" + encode(8, "") + encode(10, chunk))
    body = struct.pack(">ib", len(data) + 1, 2) + data
    body += b"\x00" * (-len(body) % 4096)
    # Chunk (0, 0) starts at sector 2, after the location and timestamp tables.
    header = struct.pack(">i", 2 << 8 | len(body) // 4096) + b"\x00" * (8192 - 4)
    return header + body


STORE.mkdir(parents=True, exist_ok=True)
for manifest in sorted(SERVERS.glob("*/maps/*.toml")):
    key = tomllib.loads(manifest.read_text())["key"]
    if (STORE / key).exists():
        continue
    mca = region()
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w:gz") as tar:
        info = tarfile.TarInfo("region/r.0.0.mca")
        info.size = len(mca)
        tar.addfile(info, io.BytesIO(mca))
    (STORE / key).write_bytes(buf.getvalue())
    print(f"seeded blank {key} for {manifest.stem}")
