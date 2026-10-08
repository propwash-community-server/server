# propwash-community-server

Everything for the Propwash community Minecraft network, deployed to one VPS with
[pyinfra](https://pyinfra.com): a Velocity proxy, a Paper `hub`, and Fabric + Propwash backends
`fpv` (contributed maps; creative, only ops can build) and `survival`.

```
deploy/          inventory.py (prod + local), deploy.py, files/ (systemd units, helper scripts)
servers/<name>/  server.toml (type, port, ram, jar, mods/plugins), files/ (copied as is), maps/ (map manifests)
local/           Docker stand-in for the VPS + world store
tools/           upload_world.py (`uv run upload-world`)
access/          collaborators' SSH public keys
secrets/         SOPS-encrypted secrets
```

## Setup

| | Linux | Windows |
|---|---|---|
| uv | `curl -LsSf https://astral.sh/uv/install.sh \| sh` (or your package manager) | `winget install astral-sh.uv` |
| Docker | Docker Engine + compose plugin | `winget install Docker.DockerDesktop` |
| SOPS (+ age for your own key) | `pacman -S sops age` / `apt install age` + sops release | `winget install Mozilla.SOPS FiloSottile.age` |

## Run it locally

No keys or accounts needed. The container is a stand-in for the VPS and gets the same deploy;
its secrets are dummies in `local/secrets/`, encrypted to `local/age.key`, which is public on purpose.

```
docker compose -f local/compose.yaml up -d --build
uv run pyinfra deploy/inventory.py deploy/deploy.py --limit local -y
```

Join `localhost:25565`. Consoles: `docker exec -it propwash-local mc-console hub`
(detach with Ctrl+B, D). Wipe everything with `docker compose -f local/compose.yaml down -v`.

## Deploying to the VPS

Getting access (once):

1. `age-keygen -o <keys file>` (Linux: `~/.config/sops/age/keys.txt`,
   Windows: `%AppData%\sops\age\keys.txt`), then send a PR adding the public key to `.sops.yaml`.
   Someone with access runs `sops updatekeys secrets/*.sops`.
2. Add your SSH public key as `access/<name>.pub`; it is installed on the next deploy.

`secrets/forwarding.secret.sops` holds Velocity's forwarding secret. To (re)create it from the live one:
`ssh root@2.31.25.191 cat /home/mc/servers/velocity/forwarding.secret > fs && sops -e --input-type binary --output-type binary fs > secrets/forwarding.secret.sops && rm fs`

Then, always preview first:

```
uv run pyinfra deploy/inventory.py deploy/deploy.py --limit prod --dry
uv run pyinfra deploy/inventory.py deploy/deploy.py --limit prod
```

## Maps

Contributed maps (flying courses, cities, ...) are worlds on the `fpv` server. The archives live
in a bucket, not in git; `servers/fpv/maps/<name>.toml` pins each one. On deploy, each becomes
the void dimension `maps:<name>`, filled with the map's terrain.

### Adding a map

Example: *Mosslorn* (minecraftmaps.com, used with the authors' permission).

1. **Get permission** from the map's authors and note it in the PR.
2. **Unpack the download** anywhere and find the save folder (the one with `level.dat` and
   `region/`). 26.x saves (`dimensions/minecraft/overworld/`) and older ones (`region/` at the
   top) both work; only the overworld is taken. Note the spawn: it's in the map's description,
   or `SpawnX/Y/Z` in `level.dat` (Mosslorn: `212 315 199`).
3. **Upload**; this packs the terrain, uploads it and writes the manifest:
   ```
   uv run upload-world "<save folder>" mosslorn --author "Mosslorn authors" --spawn 212 315 199 --local   # local store
   uv run upload-world "<save folder>" mosslorn --author "Mosslorn authors" --spawn 212 315 199           # bucket (SOPS key needed)
   ```
   Map names are lowercase letters, digits and `_`. `--spawn` is where `/map <name>` drops players.
   `--server` defaults to `fpv`.
4. **Check the manifest**, `servers/fpv/maps/mosslorn.toml`:
   ```toml
   key = "mosslorn-985b7f4c4c2a.tar.gz"   # archive in the store
   sha256 = "985b7f4c…"                    # checked on the VPS before unpacking
   author = "Mosslorn authors"
   spawn = [212, 315, 199]                 # where /map mosslorn teleports to
   ```
5. **Deploy** (locally first). The box downloads the archive, unpacks it into
   `world/dimensions/maps/mosslorn/` and restarts `fpv`. Old maps are upgraded by the server as
   their chunks load, so the first visit can take a moment.
6. **Visit**: `/server fpv`, then `/map mosslorn` (`/map` alone lists all maps). `/server hub` gets
   you back. The command comes from Melius Commands; the deploy generates it from the manifests.
7. **Open a PR** with the manifest once it was uploaded to the bucket (a `--local` key only
   exists in your local store).

Only terrain comes along: datapacks (custom loot, functions), `level.dat` settings, player data
and the nether/end of the save are dropped. Mosslorn's `mosslorn_loot` datapack is not loaded.

### Updating and removing

Re-run the upload for the same name: the manifest gets a new key, and the next deploy stops the
server, replaces the map (in-game changes to it are lost) and starts it again. Removing a manifest
only stops new boxes from getting the map; delete `world/dimensions/maps/<name>` and its
`datapacks/maps/data/maps/dimension/<name>.json` on the server by hand.

### Locally

Every listed map whose archive the local store doesn't have becomes a blank stand-in (void with a
small stone platform at `2 99 2`), so a fresh clone runs without the real bucket. `/map` still uses
the manifest's spawn, so for a blank stand-in use `/execute in maps:<name> run tp @s 2 100 2` (op).

## Backups

Every night at 04:00 (`mc-backup.timer`) all of `/home/mc/servers` goes to restic in the bucket
(locally: the local store), without jars, libraries and logs. Saving is paused over RCON for the
snapshot and always switched back on. Kept: 7 daily, 4 weekly, 6 monthly.

| Task | How (as root on the box) |
|---|---|
| Back up now | `systemctl start mc-backup`, log in `journalctl -u mc-backup` |
| List snapshots | `set -a; . /etc/mc-backup.env; restic snapshots` |
| Restore a world | `mc-restore <server> [snapshot]` (default latest; the current world is kept as `world.before-restore-<time>`) |

## Day to day

| Task | How |
|---|---|
| Update a mod/plugin | swap its URL in `servers/<name>/server.toml`, deploy (old jar is removed, server restarts) |
| Update a server jar | change both `url` and `jar`, deploy |
| Add a server | new `servers/<name>/server.toml` with a free port, add it to `try` in `servers/velocity/server.toml` if players should land there |
| RAM | the VPS has ~15.5 GB; keep the sum of all `ram` values under ~12 GB (now 1G + 1536M + 6G + 3584M) |
| Console on the VPS | `mc-console <name>` |
| Status / logs | `systemctl status mc@<name>` |

Worlds, whitelists, ops and server.properties (except `server-ip`, `server-port`, `online-mode`, the RCON keys
and a server's own `[properties]`)
are never touched.
