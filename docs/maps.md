# Maps

Maps live on `fpv`. Each map is a dimension `maps:<name>`, listed in
[`servers/fpv/maps/<name>.toml`](../servers/fpv/maps/) with its title, author and world border. The
map is what's in the server's world: builders edit it there, and it's backed up with the rest of
the server.
The bucket is where maps are handed in: `fpv/maps/<name>.tar.gz` is put on a server only when
it doesn't have the map yet (a new map, or a new server), or when someone applies it. Only the
overworld terrain is kept; old maps are upgraded as they are explored, so the first visit can be
slow.

## Adding a map

1. Upload the save folder (the one with `level.dat`):

   ```
   uv run upload "<save folder>" flowmap --local
   ```

   `--local` uploads to the local store; drop it for the real bucket. Upload to the real bucket
   before the PR below is merged: a deploy that can't find the map's archive fails.
2. Open a PR with `servers/fpv/maps/<name>.toml`, the title and author shown to players entering
   the map, and its world border (center and size in blocks, a square around the built part):

   ```toml
   title = "Flowmap"
   author = "SHO"
   border = { x = 24, z = 32, size = 224 }
   ```

   Past the border, only void would be generated. It is set on every start, so changing it
   takes effect on the next deploy.

   Once it's merged and deployed, fpv restarts with the new dimension, its clock, its race courses
   and a `/map <name>` entry.
3. In game, a builder goes to the map and sets its spawn (until then, `/map <name>` does nothing):

   ```
   /execute in maps:flowmap run tp @s <x> <y> <z>
   /warp set flowmap
   ```

   On the local stack, maps are blank platforms, and a new stack comes with their warps already set.

## Tutorial

Players new to fpv start at its world spawn, which is on the `tutorial` map; returning players come
back where they left. Until it's built it is superflat (`flat = true` in its toml; other maps are void
around their terrain). Staff set the spawn once, standing on the spot:

```
/execute in maps:tutorial run setworldspawn ~ ~ ~
```

## Drift Arena and Moto Arena

Two maps by LardTom with animated vehicles for drones to chase, each run by its own datapack:

| Map | Vehicles | Datapack | Operators |
|---|---|---|---|
| `driftpark`, "Drift Arena" | two drift cars, placeable furniture | `driftpark-datapack.zip` (hub, fpv, plots, so furniture works anywhere) | `/function drift:help` |
| `motoarena`, "Moto Arena" | bikes and a buggy | `arena-datapack.zip` (fpv) | `/function arena:help` |

The datapacks are in `files/world/datapacks/` of those servers. Their models and sounds are in one
resource pack for both, in the world store (`fpv/resourcepacks/propwash-maps-<sha1>.zip`), as the
hub's, fpv's and plots' `resource-pack`, offered on joining (not required). Without it the vehicles
and furniture are invisible. Rotating and picking up Drift Arena furniture takes a stick named
`Drift Tool` (Flan's inspection tool on fpv is a wooden hoe, so they don't clash).

Never run `/function drift:build` or `/function arena:build` on a server: besides rebuilding the map,
they move the world spawn and change game rules (`arena:build` also turns on a respawn teleport).

To update: put the new datapack zip in place, upload the new resource pack under a new name and change
the URL and SHA-1 in the three `server.toml` files, deploy, then run `/function drift:reset` or
`/function arena:reset` once on the map (it respawns the vehicles). A map itself only changes with
`uv run upload` and `uv run apply fpv <name>`.

## Halloween

For Halloween 2026 (package `propwash-halloween-server-2026-10-18`) hub, fpv and plots offer
`fpv/resourcepacks/propwash-maps-halloween-855e1980.zip` instead of the regular maps pack: the same
Drift Arena and Moto Arena assets plus Halloween drones (the 5" freestyle frame becomes a witch's
broom, a jack-o'-lantern whoop, a ghost cinewhoop and more), orange and purple parts, and spooky
sounds. It only replaces assets, never the mod or the flight physics.

`halloween-lobby-datapack.zip` (in `files/world/datapacks/` of all three) adds a biome,
`propwash_halloween:lobby`, with Halloween music, a purple sky, orange fog and autumn colours. It
does nothing until painted over an area, after the restart that loads it (new biomes need a restart,
`/reload` isn't enough), standing there so the chunks are loaded:

```
/gamerule max_block_modifications 1000000
/execute in <dimension> run fillbiome <x1> <y1> <z1> <x2> <y2> <z2> propwash_halloween:lobby
/gamerule max_block_modifications 32768
```

After Halloween, in this order:

1. Paint the old biome back over every area painted, with the same `fillbiome` (for example
   `minecraft:plains`). A chunk left on the Halloween biome breaks once the datapack is gone.
2. Remove `halloween-lobby-datapack.zip` from the three servers' `files/world/datapacks/`.
3. In the three `server.toml` files, swap `resource-pack` and `resource-pack-sha1` back to the
   regular pack in the comment above them (`propwash-maps-6557a509.zip`, still in the world store).
4. Deploy, then delete `world/datapacks/halloween-lobby-datapack.zip` on each server (deploys copy
   `files/` but never delete) and restart them.

## In game

Spawns and spots are [Essential Commands](https://modrinth.com/mod/essential-commands) warps stored
in the `fpv` world: `<map>` is the map's spawn, `<map>_<spot>` a spot on it (e.g. `mosslorn_waterfall`).
Each map also has its own clock, paused at noon by default.

| Task | Command |
|---|---|
| List maps / go to one | `/map`, `/map <map>` |
| List warps / go to one | `/warp list`, `/warp tp <name>` |
| Add a warp (builders) | `/warp set <name>`; to move one, `/warp delete` it first |
| Set the map's time (builders) | `/time set <noon\|night\|ticks>`, `/time pause`, `/time resume` |

[Propwash](https://www.curseforge.com/minecraft/mc-mods/propwash-fpv) race courses in the save are
added when the map is put on the server (seeded or applied). Only the course owner from the save
can edit them.

## Updating and removing

Deploys never touch a map the server already has. To replace one with a new save, upload it again
under the same name and apply it (`--local` for the local stack; fpv restarts):

```
uv run upload "<save folder>" flowmap
uv run apply fpv flowmap
```

In-game changes to the map are lost and its race courses (with lap times) are replaced; its warps
stay.

To remove a map, delete its `.toml` file. Servers that already have it keep it until you delete
`world/dimensions/maps/<name>` and its files in `world/datapacks/maps/data/maps/`. Its race courses
stay until `/propwash race delete`.

Locally, a map missing from the local store is a 5x5 stone platform at y 99 (a flat map is just
its superflat ground). These are added when the stack starts, so run `docker compose -f local/compose.yaml up -d` again after adding a map.
