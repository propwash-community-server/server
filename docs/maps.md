# Maps

Maps live on `fpv`. Each map is a dimension `maps:<name>`, listed in
[`servers/fpv/maps/<name>.toml`](../servers/fpv/maps/) with its title and author. The map is what's
in the server's world: builders edit it there, and it's backed up with the rest of the server.
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
   the map:

   ```toml
   title = "Flowmap"
   author = "SHO"
   ```

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

## Driftpark

`driftpark` (by LardTom) has two drift cars lapping the track for drones to chase, and placeable
furniture. Both come from a datapack and a resource pack that are on every server, so furniture
works anywhere: `files/world/datapacks/driftpark-datapack.zip` of hub, fpv and plots, and the pack
in the world store (`fpv/resourcepacks/driftpark-<sha1>.zip`) as each server's `resource-pack`,
offered on joining (not required). Without the pack the cars and furniture are invisible.
Operators: `/function drift:help` (start, stop, reset the cars; furniture items). Rotating and
picking up furniture takes a stick named `Drift Tool`. Never run `/function drift:build` on a
server: besides rebuilding the track, it moves the world spawn and changes game rules. Flan's
inspection tool on fpv is a wooden hoe, so it doesn't clash with the Drift Tool.

To update the pack, upload the new zip under a new name and change the URL and SHA-1 in the three
`server.toml` files.

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
