# Servers and mods

Each server has a [`servers/<name>/server.toml`](../servers/) with its `type` (`fabric`, `paper` or
`velocity`), `port`, `ram`, `jar` and `url`, its `mods` or `plugins` URLs, and these sections:

| Section | Effect |
|---|---|
| `[properties]` | server.properties, set on every deploy |
| `[new_world]` | server.properties, set only for a new server |
| `[config."<path>"]` | top-level keys in a plugin's config file |
| `[flan]` | `deny`: [Flan](https://modrinth.com/mod/flan) permissions denied in every dimension, maps included; other keys go to Flan's config |

`[config]` and `[flan]` take effect from the second deploy, once the plugin has created its files.
The deploy never changes worlds. The proxy's `server.toml` has no port, but `expansions`
(MiniPlaceholders) and `try` (the servers players land on, in order).

Jars are only downloaded when missing, so a jar is updated by changing its file name: for mods and
plugins that comes from the URL, for the server jar it is `jar`.

| Task | How |
|---|---|
| Add or update a mod | change its URL in `server.toml`, deploy |
| Update a server jar | change `url` and `jar`, deploy |
| Add a server | add `servers/<name>/server.toml` with a free port |
