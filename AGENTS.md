# Agents

Also read [README.md](README.md), [CONTRIBUTING.md](CONTRIBUTING.md) and the [docs](docs/).

## AI use

Work locally only: edit, run, test and commit. Do not open pull requests or issues, write comments
or reviews, push, or deploy to `prod`; the person you work with does all interaction with others.
Before contributing, they should read [Using generative AI](CONTRIBUTING.md#using-generative-ai).

## Project overview

The PropwashFPV Minecraft network, deployed with [pyinfra](https://pyinfra.com):

- `deploy/` - `inventory.py` (`prod`, `local`, `test`), `deploy.py`, `files/`
- `servers/<name>/` - `server.toml`, `files/`, `maps/` for `velocity`, `hub`, `fpv`, `plots`
- `permissions.toml` - LuckPerms groups
- `local/` - the local stack in Docker, with dummy secrets in `local/secrets/`
- `tools/` - `uv run check`, `uv run e2e`, `uv run upload-world`
- `tests/e2e/` - end-to-end tests with a headless client
- `secrets/` - SOPS-encrypted secrets
- `access/` - SSH public keys

## Key commands

```
# Quick checks
uv run check

# End-to-end tests against the local stack (pytest options pass through)
uv run e2e
uv run e2e -k fpv
uv run e2e --fresh

# Local stack
docker compose -f local/compose.yaml up -d --build
uv run pyinfra deploy/inventory.py deploy/deploy.py --limit local -y
docker compose -f local/compose.yaml down -v
```

`uv run check` and `uv run e2e` should pass before a change is merged; CI runs both.

## Project standards

Match what already exists. Comments are rare and explain why something is the way it is, only when
that is not obvious.

- Never commit plaintext secrets. Every `.sops` file is encrypted with SOPS; the hook in
  `.githooks/` refuses others (`git config core.hooksPath .githooks`). The local stack uses the
  dummy secrets in `local/secrets/`.
- Before changing servers, mods, maps or permissions, read the matching page in [docs](docs/);
  several things only take effect on a later deploy or must be changed in a particular way.

## Committing

Commits use [conventional commit](https://www.conventionalcommits.org) prefixes: `feat`, `fix`,
`test`, `docs`, `ci`, `refactor`, `chore`. The subject is in the imperative and short. A body is
rare and only for what the diff does not show. Branches are named `<type>/<short-name>`, such as
`feat/survival-border`.
