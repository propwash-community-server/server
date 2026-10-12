# propwash-community-server

The PropwashFPV community server.

```
deploy/           inventory.py (prod, local, test), deploy.py, files/
servers/<name>/   server.toml, files/, maps/
mods/             Our own small Fabric mods
local/            Local setup via Docker
tools/            Admin tools (`uv run <>`)
tests/e2e/        End-to-end tests
.github/          CI
access/           SSH public keys
secrets/          SOPS-encrypted secrets
docs/             Documentation
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

On Linux, add yourself to the `docker` group (`usermod -aG docker $USER`, then log in again).

In the clone, enable the hook that refuses unencrypted secrets:

```
git config core.hooksPath .githooks
```

## Running locally

This runs the [pyinfra](https://pyinfra.com) deploy against a Docker container instead of the VPS,
with dummy secrets from [`local/secrets/`](local/secrets/).

```
docker compose -f local/compose.yaml up -d --build
uv run pyinfra deploy/inventory.py deploy/deploy.py --limit local -y
```

On a new stack, deploy twice: some settings are only applied once the servers have started once
(see [Servers and mods](docs/servers.md)).

Then join `localhost:25565`. Maps and backups go to a local S3 store on `localhost:8080`.

| Task | Command |
|---|---|
| Console | `docker exec -it propwash-local mc-console hub` (detach: Ctrl+B, D) |
| Wipe | `docker compose -f local/compose.yaml down -v` |

## Docs

- [Servers and mods](docs/servers.md): `server.toml` and changing servers, mods and plugins
- [Maps](docs/maps.md): adding, updating and removing maps on fpv
- [Testing](docs/testing.md): checks, end-to-end tests and CI
- [Admin](docs/admin.md): running the production server
- [Contributing](CONTRIBUTING.md): issues, pull requests and CI
