# Servers and mods

Each server has a [`servers/<name>/server.toml`](../servers/) with its `type` (`fabric`, `paper` or
`velocity`), `port`, `ram`, `jar` and `url`, its `mods` or `plugins` URLs, and these sections:

| Section | Effect |
|---|---|
| `[properties]` | server.properties, set on every deploy |
| `[new_world]` | server.properties, set only for a new server |
| `[config."<path>"]` | top-level keys in a plugin's config file, palette tags expanded (also on the proxy) |
| `[gamerules]` | game rules, set on every start (a generated datapack); removing one doesn't reset it |
| `[flan]` | `deny`: [Flan](https://modrinth.com/mod/flan) permissions denied in every dimension, maps included; other keys go to Flan's config |
| `help` | text shown by `/help`; the velocity `help` is added to the end of every server's |
| `welcome` | text shown in chat every time a player arrives on the server |

Staff and admin (`propwash.help.staff`) also get the staff part of `/help`, from
[`servers/velocity/files/plugins/staffhelp/help.txt`](../servers/velocity/files/plugins/staffhelp/help.txt)
(same tags), after the server's own; `/help staff` (or `/help admin`) shows only that part. Changing it restarts the proxy
on deploy, like any file in `servers/velocity/files/`. It's our Velocity plugin
[`mods/staffhelp`](../mods/staffhelp/), rebuilt like `mods/vanish` (see [Vanish](admin.md#vanish)).

Announcements: every few minutes one message, picked at random by weight, goes to the chat of the
servers listed in
[`servers/velocity/files/plugins/announcements/announcements.toml`](../servers/velocity/files/plugins/announcements/announcements.toml)
(same tags; links with `<click:open_url:'https://...'>`). The proxy reads it again whenever it changes,
so an edit on the server applies at the next announcement without a restart; staff can send one now
with `/announce`. It's our Velocity plugin [`mods/announcements`](../mods/announcements/), rebuilt like
`mods/staffhelp`.

`[config]` and `[flan]` take effect from the second deploy, once the plugin has created its files;
a `.properties` file that doesn't exist yet is written with just the `[config]` keys before the first start.
The deploy never changes worlds. The proxy's `server.toml` has no port, but `expansions`
(MiniPlaceholders) and `try` (the servers players land on, in order).

Jars are only downloaded when missing, so a jar is updated by changing its file name: for mods and
plugins that comes from the URL, for the server jar it is `jar`.

| Task | How |
|---|---|
| Add or update a mod | change its URL in `server.toml`, deploy |
| Update a server jar | change `url` and `jar`, deploy |
| Add a server | add `servers/<name>/server.toml` with a free port |

## Colors

Texts the repo writes (chat formats, the tab list, rank prefixes, the MOTD, map titles, `/map`,
`/help` and join messages) are [MiniMessage](https://docs.advntr.dev/minimessage/format.html), and
use the colors in [`palette.toml`](../palette.toml) as tags: `<muted>[Local]</muted>`. The deploy
puts in the colors, and converts to a text component where MiniMessage isn't read (`tellraw`,
`title`) and to `&` codes for Essentials. There it understands colors, `<bold>`, `<italic>`,
`<underlined>`, `<strikethrough>` and `<reset>`; anything else in `<>` stays text, so `/map <name>`
can be written as is. Plugins' own messages keep their colors.
