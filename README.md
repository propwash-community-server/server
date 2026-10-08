# propwash-community-server

The PropwashFPV community server.

```
deploy/           inventory.py (prod + local), deploy.py, files/
servers/<name>/   server.toml, files/, maps/
local/            Local setup via Docker
tools/            Admin tools (`uv run <>`)
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

Upload the save folder (the one with `level.dat`) and its spawn point. This also writes the
`.toml` file:

```
uv run upload-world "<save folder>" mosslorn --author "Mosslorn authors" --spawn 212 315 199 --local
```

Drop `--local` to upload to the real bucket. After a deploy, `/map mosslorn` on `fpv` takes you
there, and `/map` lists all maps.

Only the overworld terrain is kept. Old maps are upgraded as you explore, so the first visit can
be slow.

### Updating and removing

To update a map, upload it again under the same name and deploy. In-game changes to it are lost.

To remove a map, delete its `.toml` file. Servers that already have it keep it until you delete
`world/dimensions/maps/<name>` and `datapacks/maps/data/maps/dimension/<name>.json`.

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
