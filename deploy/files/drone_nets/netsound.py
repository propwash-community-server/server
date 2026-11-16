"""Drone net sounds: netsound.py <out dir>. break1-3: thump + rope creak + ticks (twangy); place1-3: soft thump, rustle, settle."""
import sys, numpy as np, soundfile as sf
out = sys.argv[1]
SR = 44100

def lowpass(x, cutoff):
    a = np.exp(-2 * np.pi * cutoff / SR); y = np.zeros_like(x); s = 0.0
    for i, v in enumerate(x): s = (1 - a) * v + a * s; y[i] = s
    return y

def bandpass(x, lo, hi):
    return lowpass(x, hi) - lowpass(x, lo)

for k in range(3):
    rng = np.random.default_rng(100 + k)
    n = int(SR * (0.42 + 0.05 * k)); t = np.arange(n) / SR
    # 1. Soft "whump": the net taking up slack, a low thud with a little fabric noise.
    f0 = 85 + 15 * k
    thud = np.sin(2 * np.pi * (f0 * t - 30 * t * t)) * np.exp(-t * 28)
    swish = bandpass(rng.standard_normal(n), 300, 2200) * np.exp(-((t - 0.03) / 0.05) ** 2)
    # 2. Rope creak: a short, rough, pitch-wobbling stick-slip burst a moment later.
    start = 0.07 + 0.02 * k
    tc = np.clip(t - start, 0, None)
    env_c = (t > start) * (1 - np.exp(-tc * 120)) * np.exp(-tc * 11)
    freq = 210 + 70 * k + 40 * np.sin(2 * np.pi * 7 * tc)
    phase = 2 * np.pi * np.cumsum(freq) / SR
    pulses = (np.sin(phase) > 0.85).astype(float)
    creak = bandpass(pulses + 0.15 * rng.standard_normal(n), 250, 3000) * env_c
    # 3. Knots and strands settling: a few soft ticks.
    ticks = np.zeros(n)
    for _ in range(5):
        p = int(SR * rng.uniform(0.04, 0.3)); L = int(SR * 0.012)
        if p + L < n: ticks[p:p + L] += rng.standard_normal(L) * np.exp(-np.arange(L) / (SR * 0.002))
    ticks = bandpass(ticks, 800, 5000)
    mix = 0.9 * thud + 0.5 * swish / (np.abs(swish).max() + 1e-9) + 0.55 * creak / (np.abs(creak).max() + 1e-9) + 0.25 * ticks / (np.abs(ticks).max() + 1e-9)
    fade = np.minimum(1, (n - np.arange(n)) / (SR * 0.04))
    mix *= fade
    mix = 0.85 * mix / np.abs(mix).max()
    sf.write(f"{out}/break{k + 1}.ogg", mix.astype(np.float32), SR, format="OGG", subtype="VORBIS")
    print("break%d.ogg" % (k + 1), round(n / SR, 2), "s")

# Placing: no creak (that tone is the twang). A muffled thump as the net lands, a rope/cloth rustle,
# and a smaller second thump as it settles; everything kept below about 3.5 kHz.
for k in range(3):
    rng = np.random.default_rng(300 + k)
    n = int(SR * (0.32 + 0.04 * k)); t = np.arange(n) / SR
    def thump(at, f0, decay, amount):
        tt = np.clip(t - at, 0, None); on = (t >= at)
        body = np.sin(2 * np.pi * (f0 * tt - 25 * tt * tt)) * np.exp(-tt * decay)
        knock = lowpass(rng.standard_normal(n), 400) * np.exp(-tt * decay * 2.5)
        return on * amount * (body + 1.5 * knock / (np.abs(knock).max() + 1e-9))
    land = thump(0.0, 70 + 8 * k, 32, 1.0)
    settle = thump(0.085 + 0.015 * k, 95 + 6 * k, 45, 0.35)
    grains = lowpass(np.abs(rng.standard_normal(n)), 35)
    grains = grains / grains.max()
    rustle = bandpass(rng.standard_normal(n), 500, 2600) * grains * np.exp(-((t - 0.05) / 0.09) ** 2)
    mix = land / (np.abs(land).max() + 1e-9) + 0.4 * settle / (np.abs(land).max() + 1e-9)         + 0.45 * rustle / (np.abs(rustle).max() + 1e-9)
    mix = lowpass(mix, 3500)
    attack = np.minimum(1, np.arange(n) / (SR * 0.003))
    fade = np.minimum(1, (n - np.arange(n)) / (SR * 0.05))
    mix *= attack * fade
    mix = 0.85 * mix / np.abs(mix).max()
    sf.write(f"{out}/place{k + 1}.ogg", mix.astype(np.float32), SR, format="OGG", subtype="VORBIS")
    print("place%d.ogg" % (k + 1), round(n / SR, 2), "s")
