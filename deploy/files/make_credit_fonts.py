"""Adds the credits fonts to the maps resource pack and writes the glyph widths the deploy needs.

propwash:credits1 / credits2: vanilla's ascii.png glyphs, one / two lines lower (for a second and third
credit line under a title's subtitle). propwash:space: PUA characters that only move the pen, +-2^k.
Usage: python make_credit_fonts.py <vanilla-font-dir> <base.zip> <out.zip> <widths.json>
"""
import io
import json
import sys
import zipfile

from PIL import Image

vanilla, base, out, widths_out = sys.argv[1:5]
default = json.load(open(f"{vanilla}/assets/minecraft/font/include/default.json", encoding="utf-8"))
ascii_provider = next(p for p in default["providers"] if p["file"] == "minecraft:font/ascii.png")
chars = ascii_provider["chars"]

img = Image.open(f"{vanilla}/assets/minecraft/textures/font/ascii.png").convert("RGBA")
cw, ch = img.width // len(chars[0]), img.height // len(chars)
widths = {" ": 4}
for row, line in enumerate(chars):
    for col, c in enumerate(line):
        if c == "\u0000" or c == " ":
            continue
        right = -1
        for x in range(cw):
            if any(img.getpixel((col * cw + x, row * ch + y))[3] > 0 for y in range(ch)):
                right = x
        # Vanilla: the glyph's width (last opaque column + 1), then 1 pixel of spacing.
        widths[c] = right + 2 if right >= 0 else 1

LINE = 11  # pixels from one subtitle line to the next (9-pixel glyphs and a gap)
fonts = {}
for n in (1, 2):
    fonts[f"assets/propwash/font/credits{n}.json"] = {"providers": [
        {"type": "space", "advances": {" ": 4}},
        {**ascii_provider, "ascent": ascii_provider["ascent"] - n * LINE},
    ]}
advances = {}
for k in range(10):
    advances[chr(0xE000 + k)] = 2 ** k
    advances[chr(0xE010 + k)] = -(2 ** k)
fonts["assets/propwash/font/space.json"] = {"providers": [{"type": "space", "advances": advances}]}

with zipfile.ZipFile(base) as src, zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as dst:
    for item in src.infolist():
        data = src.read(item.filename)
        if item.filename == "pack.mcmeta":
            meta = json.loads(data)
            meta["pack"]["description"] = "Propwash Maps – Driftpark, Arena, drone nets and map credits"
            data = json.dumps(meta, indent=1, ensure_ascii=False).encode("utf-8")
        dst.writestr(item, data)
    for name, data in fonts.items():
        dst.writestr(name, json.dumps(data, indent=1))

json.dump(widths, open(widths_out, "w", encoding="utf-8"), ensure_ascii=False, sort_keys=True)
print(len(widths), "widths;", {c: widths[c] for c in "SHOpincil l"})
