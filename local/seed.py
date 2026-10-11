# /// script
# dependencies = ["nbtlib==2.0.4"]
# ///
"""
Seed the local stack, as the `seed` service in compose.yaml:
- a blank stand-in in the store (<server>/maps/<name>.tar.gz) for every map in servers/*/maps/ that
  it doesn't have yet: void with a 5x5 stone platform at x/z 0-4, y 99, or nothing for a flat map
  (its generator makes the ground);
- a warp per map on its platform or the flat ground (the map's spawn, set in game by builders on prod), on servers
  that have no warps yet.
"""

import io
import struct
import tarfile
import tomllib
import zlib
from pathlib import Path

from nbtlib import Byte, Compound, Double, File, Float, Int, List, Long, LongArray, String

SERVERS = Path("/servers")
STORE = Path("/data/worlds")
HOME = Path("/home/mc/servers")

# Written as 1.21 (DataVersion 3953); the server upgrades it to its own format on load.
DATA_VERSION = 3953
PLATFORM_Y = 99
# Standing height on superflat: bedrock, 2 dirt, grass from y -64.
FLAT_Y = -60


def section(y):
    air = Compound({"Name": String("minecraft:air")})
    biomes = Compound({"palette": List[String](["minecraft:the_void"])})
    if y != PLATFORM_Y >> 4:
        return Compound({"Y": Int(y), "block_states": Compound({"palette": List[Compound]([air])}), "biomes": biomes})
    # 4 bits per block, 16 blocks per long, index = (y * 16 + z) * 16 + x.
    longs = [0] * 256
    for x in range(5):
        for z in range(5):
            i = ((PLATFORM_Y & 15) * 16 + z) * 16 + x
            longs[i // 16] |= 1 << (i % 16 * 4)
    states = Compound({
        "palette": List[Compound]([air, Compound({"Name": String("minecraft:stone")})]),
        "data": LongArray([v - (1 << 64) if v >= 1 << 63 else v for v in longs]),
    })
    return Compound({"Y": Int(y), "block_states": states, "biomes": biomes})


def region():
    chunk = File({
        "DataVersion": Int(DATA_VERSION),
        "xPos": Int(0),
        "zPos": Int(0),
        "yPos": Int(-4),
        "Status": String("minecraft:full"),
        "LastUpdate": Long(0),
        "isLightOn": Byte(0),
        "sections": List[Compound]([section(y) for y in range(-4, 20)]),
        "block_entities": List([]),
    })
    raw = io.BytesIO()
    chunk.write(raw)
    data = zlib.compress(raw.getvalue())
    body = struct.pack(">ib", len(data) + 1, 2) + data
    body += b"\x00" * (-len(body) % 4096)
    # Chunk (0, 0) starts at sector 2, after the location and timestamp tables.
    header = struct.pack(">i", 2 << 8 | len(body) // 4096) + b"\x00" * (8192 - 4)
    return header + body


for manifest in sorted(SERVERS.glob("*/maps/*.toml")):
    archive = STORE / manifest.parent.parent.name / "maps" / f"{manifest.stem}.tar.gz"
    if archive.exists():
        continue
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w:gz") as tar:
        if tomllib.loads(manifest.read_text()).get("flat"):
            info = tarfile.TarInfo("region")
            info.type, info.mode = tarfile.DIRTYPE, 0o755
            tar.addfile(info)
        else:
            mca = region()
            info = tarfile.TarInfo("region/r.0.0.mca")
            info.size = len(mca)
            tar.addfile(info, io.BytesIO(mca))
    archive.parent.mkdir(parents=True, exist_ok=True)
    archive.write_bytes(buf.getvalue())
    print(f"seeded blank {archive.relative_to(STORE)}")

# Essential Commands keeps its warps in world/essentialcommands/world_data.dat.
for maps in sorted(SERVERS.glob("*/maps")):
    path = HOME / maps.parent.name / "world/essentialcommands/world_data.dat"
    if path.exists():
        continue
    manifests = sorted(maps.glob("*.toml"))
    names = [m.stem for m in manifests]
    warps = {m.stem: Compound({
        "name": String(m.stem),
        "WorldRegistryKey": String(f"maps:{m.stem}"),
        "x": Double(2.5), "y": Double(FLAT_Y if tomllib.loads(m.read_text()).get("flat") else PLATFORM_Y + 1),
        "z": Double(2.5), "headYaw": Float(0), "pitch": Float(0),
    }) for m in manifests}
    path.parent.mkdir(parents=True, exist_ok=True)
    File({"warps": Compound(warps), "_sv": Int(1)}, gzipped=True).save(path)
    print(f"seeded warps {', '.join(names)} on {maps.parent.name}")
