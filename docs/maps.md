# Maps

Maps live on `fpv`. Each map is a dimension `maps:<name>`, whose archive is in a bucket and
referenced by [`servers/fpv/maps/<name>.toml`](../servers/fpv/maps/). Only the overworld terrain is
kept; old maps are upgraded as they are explored, so the first visit can be slow.

## Adding a map

1. Upload the save folder (the one with `level.dat`), which writes `servers/fpv/maps/<name>.toml`:

   ```
   uv run upload-world "<save folder>" flowmap --author "SHO" --local
   ```

   `--local` uploads to the local store; drop it for the real bucket. `--author` and `--title`
   (default: the capitalized name) are shown to players entering the map. Each upload makes a new
   archive, so upload to the real bucket last, and only then deploy to prod.
2. Commit the `.toml` file and deploy. fpv restarts with the new dimension, its clock, its race
   courses and a `/map <name>` entry.
3. In game, a builder goes to the map and sets its spawn (until then, `/map <name>` does nothing):

   ```
   /execute in maps:flowmap run tp @s <x> <y> <z>
   /warp set flowmap
   ```

   On the local stack, maps are blank platforms, and a new stack comes with their warps already set.

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
added when the map is unpacked. Only the course owner from the save can edit them.

## Updating and removing

To update a map, upload it again under the same name and deploy. In-game changes to it are lost,
and its race courses (with lap times) are replaced.

To remove a map, delete its `.toml` file. Servers that already have it keep it until you delete
`world/dimensions/maps/<name>` and its files in `world/datapacks/maps/data/maps/`. Its race courses
stay until `/propwash race delete`.

Locally, a map missing from the local store is a 5x5 stone platform at y 99. These are added when
the stack starts, so run `docker compose -f local/compose.yaml up -d` again after adding a map.
