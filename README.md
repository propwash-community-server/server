# propwash-community-server

Everything for the Propwash community Minecraft network: a Velocity proxy, a Paper hub and Fabric
backends running Propwash, deployed to one VPS with [pyinfra](https://pyinfra.com).

```
deploy/          inventory.py (prod + local), deploy.py, files/ (systemd unit, helpers)
servers/<name>/  server.toml (type, port, ram, jar, mods/plugins), files/ (copied as is)
local/           Docker stand-in for the VPS
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

## Day to day

| Task | How |
|---|---|
| Update a mod/plugin | swap its URL in `servers/<name>/server.toml`, deploy (old jar is removed, server restarts) |
| Update a server jar | change both `url` and `jar`, deploy |
| Add a server | new `servers/<name>/server.toml` with a free port, add it to `try` in `servers/velocity/server.toml` if players should land there |
| Console on the VPS | `mc-console <name>` |
| Status / logs | `systemctl status mc@<name>` |

Worlds, whitelists, ops and server.properties (except `server-ip`, `server-port`, `online-mode`)
are never touched.
