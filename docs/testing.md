# Testing

| Command | What it does |
|---|---|
| `uv run check` | Config files parse, download URLs answer, secrets are encrypted to the keys in `.sops.yaml`, `server=` contexts in `permissions.toml` name existing servers |
| `uv run e2e` | End-to-end tests against the local stack |
| `uv run e2e --fresh` | The same, after wiping the stack and deploying it from scratch |

CI runs `check` and `e2e --fresh` on every PR (about seven minutes). Once a PR into `prod` is green,
promote with `git push origin dev:prod`.

## End-to-end tests

The tests in [`tests/e2e/`](../tests/e2e/) run against the `test` inventory: the `local` containers
with the proxy in offline mode and 1 GB of RAM per server. A headless client
([HeadlessMC](https://github.com/headlesshq/headlessmc)) joins as `Offline` with the mods from
[`client.toml`](../tests/e2e/client.toml) and runs [`e2e_agent.py`](../tests/e2e/client/e2e_agent.py)
through [Minescript](https://minescript.net). Servers are checked and set up through rcon.

```
uv run pyinfra deploy/inventory.py deploy/deploy.py --limit test -y   # twice on a new stack
uv run e2e -k fpv
```

`uv run e2e` takes pytest options; the client log ends up in `tests/e2e/logs/`.

A test uses a fixture such as `player`, `builder` or `on_map`, and checks the result on the server:

```python
def test_blocks_cannot_be_broken(on_map):
    FPV.rcon(f"execute in maps:{MAP} run setblock 4 100 4 minecraft:stone")
    on_map.look_at(4.5, 100.5, 4.5)
    on_map.attack()
    assert in_map("if block 4 100 4 minecraft:stone")
```

`Player` ([`harness.py`](../tests/e2e/harness.py)) runs commands, looks, attacks, uses items, picks
hotbar slots, and reads chat, the title, the tab list and its position; other Minescript functions
are `player.call("<function>", ...)`. The `expect_*` methods wait for chat, a server, a dimension or
a title. Every test starts with an empty inventory, on the server the previous one ended on, so
tests switch with `player.go(...)` first. For what one player does to another's things, such as on
plots, the `other` fixture starts a second client, `Other`, on its own agent port.
