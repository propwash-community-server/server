import math, random, sys
from PIL import Image
src, worn_out, torn_out = sys.argv[1:4]
base = Image.open(src).convert('RGBA')
W, H = base.size

def faded(p, amount, rnd):
    r, g, b, a = p
    if a == 0: return p
    grey = (r + g + b) / 3
    k = amount + rnd.uniform(-0.05, 0.05)
    r, g, b = [c * (1 - k) + grey * k for c in (r, g, b)]
    dark = 0.88 + rnd.uniform(-0.06, 0.04)
    return (int(r * dark), int(g * dark * 1.01), int(b * dark * 0.97), a)

# Worn: greyer, dirtier rope, a few strands broken (1-2 px gaps).
rnd = random.Random(7)
worn = base.copy(); px = worn.load()
for y in range(H):
    for x in range(W):
        px[x, y] = faded(px[x, y], 0.45, rnd)
for (x, y) in [(11, 21), (11, 22), (24, 12), (27, 6), (4, 29), (19, 30), (19, 31)]:
    px[x, y] = (0, 0, 0, 0)
worn.save(worn_out)

# Torn: as worn, plus a ragged hole with strand ends hanging into it.
rnd = random.Random(11)
torn = base.copy(); px = torn.load()
for y in range(H):
    for x in range(W):
        px[x, y] = faded(px[x, y], 0.35, rnd)
cx, cy = 16.5, 16.0
radius = [7.5 + rnd.uniform(-1.8, 1.8) for _ in range(16)]
def r_at(ang):
    t = (ang % (2 * math.pi)) / (2 * math.pi) * 16
    i = int(t); f = t - i
    return radius[i % 16] * (1 - f) + radius[(i + 1) % 16] * f
for y in range(H):
    for x in range(W):
        dx, dy = x + 0.5 - cx, y + 0.5 - cy
        d = math.hypot(dx, dy)
        if d < r_at(math.atan2(dy, dx)):
            # Strands hanging down from the top edge of the hole, a couple of pixels long.
            above = math.hypot(dx, -abs(dy)) if dy < 0 else 99
            if dy < 0 and d > r_at(math.atan2(dy, dx)) - 2.5 and px[x, y][3] and abs(dx) < 5:
                r, g, b, a = px[x, y]
                px[x, y] = (int(r * 0.8), int(g * 0.8), int(b * 0.8), a)
                continue
            px[x, y] = (0, 0, 0, 0)
torn.save(torn_out)
