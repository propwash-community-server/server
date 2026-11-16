"""Build the maps pack with drone nets from the 1aa06938 pack: buildpack.py <dir with maps-1aa06938.zip, worn/torn_drone_net.png, sounds/> <vanilla 26.3 client jar, unzipped>. Textures: nets.py; sounds: netsound.py (numpy, soundfile)."""
import json, os, re, shutil, sys, zipfile
P, V = sys.argv[1], sys.argv[2]
src = os.path.join(P, 'build')
shutil.rmtree(src, ignore_errors=True)
with zipfile.ZipFile(os.path.join(P, 'maps-1aa06938.zip')) as z:
    z.extractall(src)
A = os.path.join(src, 'assets/minecraft')
VA = os.path.join(V, 'assets/minecraft')
def wj(path, obj):
    path = os.path.join(A, path); os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8', newline='\n') as f: f.write(json.dumps(obj, indent=2, ensure_ascii=False) + '\n')
def rj(path):
    with open(os.path.join(VA, path), encoding='utf-8') as f: return json.load(f)

T = os.path.join(A, 'textures/block')
shutil.copy(os.path.join(T, 'oxidized_copper_bars.png'), os.path.join(T, 'drone_net.png'))
for name in ('worn_drone_net', 'torn_drone_net'):
    shutil.copy(os.path.join(P, name + '.png'), os.path.join(T, name + '.png'))
# The vanilla look of oxidized copper bars, for the waxed ones' item icon.
shutil.copy(os.path.join(VA, 'textures/block/oxidized_copper_bars.png'), os.path.join(T, 'vanilla_oxidized_copper_bars.png'))

# Upright nets: copper bars -> worn, waxed copper bars -> torn (their own blockstates and models).
for block, net in (('copper_bars', 'worn_drone_net'), ('waxed_copper_bars', 'torn_drone_net')):
    for part in ('cap', 'cap_alt'):
        wj(f'models/block/{net}_{part}.json', {"ambientocclusion": False, "textures": {"particle": f"minecraft:block/{net}"}, "elements": []})
    for part in ('post_ends', 'side', 'side_alt'):
        wj(f'models/block/{net}_{part}.json', {"parent": f"minecraft:block/template_bars_{part}",
            "textures": {"bars": f"minecraft:block/{net}", "edge": "minecraft:block/drone_net_edge"}})
    post = rj('models/block/copper_bars_post.json')
    post['textures'] = {k: f"minecraft:block/{net}" for k in post['textures']}
    wj(f'models/block/{net}_post.json', post)
    bs = json.loads(json.dumps(rj(f'blockstates/{block}.json')).replace('minecraft:block/copper_bars_', f'minecraft:block/{net}_'))
    wj(f'blockstates/{block}.json', bs)
    wj(f'models/item/{net}.json', {"parent": "minecraft:item/generated", "textures": {"layer0": f"minecraft:block/{net}"}})
    wj(f'items/{block}.json', {"model": {"type": "minecraft:model", "model": f"minecraft:item/{net}"}})
# Waxed oxidized copper bars: still a net where maps placed them, but the vanilla icon in the inventory.
wj('models/item/vanilla_oxidized_copper_bars.json', {"parent": "minecraft:item/generated", "textures": {"layer0": "minecraft:block/vanilla_oxidized_copper_bars"}})
wj('items/waxed_oxidized_copper_bars.json', {"model": {"type": "minecraft:model", "model": "minecraft:item/vanilla_oxidized_copper_bars"}})

# Flat nets: copper trapdoors (plain, exposed = worn, weathered = torn); one thin net in the middle.
for block, net, tex in (('copper_trapdoor', 'flat_drone_net', 'drone_net'),
                        ('exposed_copper_trapdoor', 'worn_flat_drone_net', 'worn_drone_net'),
                        ('weathered_copper_trapdoor', 'torn_flat_drone_net', 'torn_drone_net')):
    textures = {"particle": f"minecraft:block/{tex}", "net": f"minecraft:block/{tex}"}
    face = {"uv": [0, 0, 16, 16], "texture": "#net"}
    for half, y in (('bottom', 1.5), ('top', 14.5)):
        wj(f'models/block/{net}_{half}.json', {"parent": "minecraft:block/thin_block", "ambientocclusion": False, "textures": textures,
            "elements": [{"from": [0, y, 0], "to": [16, y, 16], "faces": {"up": face, "down": face}}]})
    wj(f'models/block/{net}_open.json', {"parent": "minecraft:block/thin_block", "ambientocclusion": False, "textures": textures,
        "elements": [{"from": [0, 0, 14.5], "to": [16, 16, 14.5], "faces": {"north": face, "south": face}}]})
    vanilla = block
    bs = json.loads(json.dumps(rj(f'blockstates/{block}.json')).replace(f'minecraft:block/{vanilla}_', f'minecraft:block/{net}_'))
    wj(f'blockstates/{block}.json', bs)
    wj(f'items/{block}.json', {"model": {"type": "minecraft:model", "model": f"minecraft:block/{net}_bottom"}})

with open(os.path.join(A, 'lang/en_us.json'), encoding='utf-8') as f: lang = json.load(f)
lang.pop('block.minecraft.waxed_oxidized_copper_bars', None)
lang.update({
    "block.minecraft.copper_bars": "Worn Drone Net",
    "block.minecraft.waxed_copper_bars": "Torn Drone Net",
    "block.minecraft.copper_trapdoor": "Flat Drone Net",
    "block.minecraft.exposed_copper_trapdoor": "Worn Flat Drone Net",
    "block.minecraft.weathered_copper_trapdoor": "Torn Flat Drone Net",
    "subtitles.propwash.drone_net.place": "Drone net placed",
    "subtitles.propwash.drone_net.break": "Drone net broken"})
wj('lang/en_us.json', lang)

S = os.path.join(src, 'assets/propwash')
os.makedirs(os.path.join(S, 'sounds/block/drone_net'), exist_ok=True)
for kind in ('place', 'break'):
    for i in (1, 2, 3):
        shutil.copy(os.path.join(P, f'sounds/{kind}{i}.ogg'), os.path.join(S, f'sounds/block/drone_net/{kind}{i}.ogg'))
with open(os.path.join(S, 'sounds.json'), 'w', encoding='utf-8', newline='\n') as f:
    json.dump({f"block.drone_net.{kind}": {"subtitle": f"subtitles.propwash.drone_net.{kind}",
        "sounds": [f"propwash:block/drone_net/{kind}{i}" for i in (1, 2, 3)]} for kind in ('place', 'break')}, f, indent=2); f.write('\n')

meta_path = os.path.join(src, 'pack.mcmeta')
with open(meta_path, encoding='utf-8') as f: meta = json.load(f)
meta['pack']['description'] = "Propwash Maps – Driftpark, Arena, drone nets (worn, torn, flat) and map credits"
with open(meta_path, 'w', encoding='utf-8', newline='\n') as f: json.dump(meta, f, indent=1, ensure_ascii=False)

out = os.path.join(P, 'new.zip')
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
    for root, _, files in os.walk(src):
        for name in sorted(files):
            p = os.path.join(root, name); z.write(p, os.path.relpath(p, src).replace(os.sep, '/'))
print(out)
