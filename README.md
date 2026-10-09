# propwash-community-server

The PropwashFPV community server.

```
deploy/           inventory.py (prod, local, test), deploy.py, files/
servers/<name>/   server.toml, files/, maps/
local/            Local setup via Docker
tools/            Admin tools (`uv run <>`)
tests/e2e/        End-to-end tests
.github/          CI
access/           SSH public keys
secrets/          SOPS-encrypted secrets
docs/             admin.md: running the production server
```

## Setup

You need [uv](https://docs.astral.sh/uv/), [Docker](https://docs.docker.com/) with
[compose](https://docs.docker.com/compose/), and [SOPS](https://getsops.io).

### Arch

```
pacman -S uv docker docker-compose sops age
systemctl enable --now docker
```

### Ubuntu

```
apt install docker.io docker-compose-v2 age
curl -LsSf https://astral.sh/uv/install.sh | sh
```

For SOPS, install the `.deb` from its [releases](https://github.com/getsops/sops/releases).

### Windows (TODO: untested)

```
winget install astral-sh.uv Docker.DockerDesktop Mozilla.SOPS FiloSottile.age
```

## Local

This runs the [pyinfra](https://pyinfra.com) deploy against a Docker container instead of the VPS.
It uses dummy secrets from [`local/secrets/`](local/secrets/).

```
docker compose -f local/compose.yaml up -d --build
uv run pyinfra deploy/inventory.py deploy/deploy.py --limit local -y
```

Then join `localhost:25565`. Maps and backups go to a local S3 store on `localhost:8080`.

| Task | Command |
|---|---|
| Console | `docker exec -it propwash-local mc-console hub` (detach: Ctrl+B, D) |
| Wipe | `docker compose -f local/compose.yaml down -v` |

## Maps

Maps live on `fpv`. Each map is a dimension `maps:<name>`. Its archive is in a bucket, and
[`servers/fpv/maps/<name>.toml`](servers/fpv/maps/) points to it.

### Adding a map

1. Upload the save folder (the one with `level.dat`). This writes `servers/fpv/maps/<name>.toml`:

   ```
   uv run upload-world "<save folder>" flowmap --author "SHO" --local
   ```

   `--local` uploads to the local store; drop it to upload to the real bucket. `--author` and
   `--title` (by default the name, capitalized) are shown on screen to players entering the map.
   Each upload makes a new archive, so the `.toml` file points to the last one: upload to the real
   bucket last, and only then deploy to prod.
2. Commit the `.toml` file and deploy. fpv restarts with the new dimension `maps:<name>`, its clock,
   its race courses and a `/map <name>` entry.
3. In game, a builder goes to the map and sets its spawn. Until then, `/map <name>` does nothing.

   ```
   /execute in maps:flowmap run tp @s <x> <y> <z>
   /warp set flowmap
   ```

### Spawns and spots

Map locations are [Essential Commands](https://modrinth.com/mod/essential-commands) warps, set in
game by builders and stored in the `fpv` world. The warp `<map>` is the map's spawn, and
`<map>_<spot>` is a spot on it (for example `mosslorn_waterfall`).

| Task | Command |
|---|---|
| List maps | `/map` |
| Go to a map's spawn | `/map <map>` (same as `/warp tp <map>`) |
| List warps | `/warp list` |
| Go to one | `/warp tp <name>` |
| Add one (builders) | `/warp set <name>` where you stand |
| Move or remove one (builders) | `/warp delete <name>`, then set it again |

Only the overworld terrain is kept. Old maps are upgraded as you explore, so the first visit can
be slow.

### Time

Each map has its own clock, which starts paused at noon. Builders change it in game, standing on the
map, and it only affects that map.

| Task | Command |
|---|---|
| Set the time | `/time set <noon\|night\|...>` or `/time set <ticks>` |
| Let it run again | `/time resume` |
| Stop it | `/time pause` |

### Race courses

[Propwash](https://www.curseforge.com/minecraft/mc-mods/propwash-fpv) race courses in the save come
with the map. The deploy adds them to the server's courses when it unpacks the map, before the server
loads it. Only the course owner from the save can edit them in game.

### Updating and removing

To update a map, upload it again under the same name and deploy. In-game changes to it are lost,
and its race courses are replaced along with their lap times.

To remove a map, delete its `.toml` file. Servers that already have it keep it until you delete
`world/dimensions/maps/<name>` and its files in `world/datapacks/maps/data/maps/` (`dimension`,
`dimension_type`, `world_clock` and `timeline`). Its race courses stay until deleted with
`/propwash race delete`.

Locally, a map missing from the local store is an empty platform at `2 99 2`.

## Servers and mods

Each server has a [`servers/<name>/server.toml`](servers/) with its jar, mod or plugin URLs, and
these sections:

| Section | Effect |
|---|---|
| `[properties]` | server.properties, set on every deploy |
| `[new_world]` | server.properties, set only for a new server |
| `[config."<path>"]` | keys in a plugin's config file (set from the second deploy, once the plugin has created the file) |
| `[flan] deny` | [Flan](https://modrinth.com/mod/flan) permissions denied in every dimension of the server, maps included (also from the second deploy) |

The deploy never changes worlds.

| Task | How |
|---|---|
| Add or update a mod | change its URL in `server.toml`, deploy |
| Update a server jar | change `url` and `jar`, deploy |
| Add a server | add `servers/<name>/server.toml` with a free port |
| RAM | keep the total `ram` under ~12 GB |

## Checks and tests

| Command | What it does |
|---|---|
| `uv run check` | Config files parse, download URLs answer, secrets are encrypted to the keys in `.sops.yaml`, `server=` contexts in `permissions.toml` name existing servers |
| `uv run e2e` | End-to-end tests against the local stack (see below) |
| `uv run e2e --fresh` | The same, after wiping the local stack and deploying it from scratch |

CI runs `check` and `e2e --fresh` on every PR (the end-to-end tests take about seven minutes in CI).
Once a PR into `prod` is green, promote with `git push origin dev:prod`.

### End-to-end tests

The tests in [`tests/e2e/`](tests/e2e/) run against the local stack deployed with the `test`
inventory: the same containers as `local`, with the proxy in offline mode and 1 GB of RAM per server.
A Minecraft client ([HeadlessMC](https://github.com/headlesshq/headlessmc), no window) joins as
`Offline` with the mods from [`tests/e2e/client.toml`](tests/e2e/client.toml). Inside it,
[Minescript](https://minescript.net) runs [`e2e_agent.py`](tests/e2e/client/e2e_agent.py), which the
tests talk to. The servers are checked and set up through rcon. `uv run e2e` takes pytest options
(`-k fpv`, `-x`, ...); the client log ends up in `tests/e2e/logs/`.

```
uv run pyinfra deploy/inventory.py deploy/deploy.py --limit test -y   # twice on a new stack
uv run e2e -k fpv
```

A test gets the `player` fixture and checks the result on the server:

```python
def test_blocks_cannot_be_broken(on_map):
    FPV.rcon(f"execute in maps:{MAP} run setblock 4 100 4 minecraft:stone")
    on_map.look_at(4.5, 100.5, 4.5)
    on_map.attack()
    assert in_map("if block 4 100 4 minecraft:stone")
```

`Player` (in [`harness.py`](tests/e2e/harness.py)) can run commands, look, attack, use, pick a hotbar
slot, and read chat, the title, the tab list and its position. Anything else Minescript offers is
`player.call("<function>", ...)`. The `expect_*` methods wait for chat, a server, a dimension or a
title. The test player switches servers with `/server` (players use the hub portals) and starts
every test in the hub with an empty inventory. There is one player, so nothing that needs two
players is tested yet.
