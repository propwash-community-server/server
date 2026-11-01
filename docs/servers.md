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
| `rtp_cooldown` | fpv: seconds between two `/rtp` for each player, nobody skips it (default 15; see [maps.md](maps.md)) |

Staff and admin (`propwash.help.staff`) also get the staff part of `/help`, from
[`servers/velocity/files/plugins/staffhelp/help.txt`](../servers/velocity/files/plugins/staffhelp/help.txt)
(same tags), after the server's own; `/help staff` (or `/help admin`) shows only that part. Changing it restarts the proxy
on deploy, like any file in `servers/velocity/files/`. It's our Velocity plugin
[`mods/staffhelp`](../mods/staffhelp/), rebuilt like `mods/vanish` (see [Vanish](admin.md#vanish)).
Supporters (`propwash.help.supporter`) also get
[`supporter.txt`](../servers/velocity/files/plugins/staffhelp/supporter.txt) right after the "On every
server" lines (no blank line, so it reads as more of them), before the staff part.

Announcements: every few minutes one message, picked at random by weight, goes to the chat of the
servers listed in
[`servers/velocity/files/plugins/announcements/announcements.toml`](../servers/velocity/files/plugins/announcements/announcements.toml)
(same tags; links with `<click:open_url:'https://...'>`). The proxy reads it again whenever it changes,
so an edit on the server applies at the next announcement without a restart; staff can send one now
with `/announce`, or their own message with `/announce <message>` (MiniMessage; to everyone on every
server, in chat). Every announcement starts with the file's `prefix`, `[Propwash]` in Propwash Yellow. It's our Velocity plugin [`mods/announcements`](../mods/announcements/), rebuilt like
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

## Race courses

`/race` lists the server's Propwash race courses (one a line, clickable), and `/race <course>` (its
name, or its number for an unnamed one) takes you just in front of its start gate, facing through it,
at most every 2 seconds. Everyone has it, on the hub, fpv and plots ([`mods/tweaks`](../mods/tweaks/)).
Propwash keeps the courses in `world/data/propwash_race.json` and writes it when the world is saved,
so a new course shows up after the next save (every few minutes).

## Limbo

Players who can't join the Fabric servers (no Fabric, another game version, missing or wrong mods)
go to `limbo` instead of being disconnected: when a server refuses a player while they join (also
their first join into the network), [`mods/crossserver`](../mods/crossserver/) sends them to limbo and
tells them why, with links to [propwashfpv.com](https://propwashfpv.com/) and
[Discord](https://discord.propwashfpv.com). A player refused while already on limbo just gets the
message. Limbo is Paper with [ViaVersion](https://modrinth.com/plugin/viaversion) and ViaBackwards,
so other game versions get in, and EssentialsX; staff go there with `/server limbo`. Its world was
built on dev and put in place by hand before the first deploy (the deploy never changes worlds), from
`limbo/limbo-world-2026-11-01.tar.gz` in the world store; it also has the hub's `mountains`
dimension, a datapack in the world itself.

## Colors

Texts the repo writes (chat formats, the tab list, rank prefixes, the MOTD, map titles, `/map`,
`/help` and join messages) are [MiniMessage](https://docs.advntr.dev/minimessage/format.html), and
use the colors in [`palette.toml`](../palette.toml) as tags: `<muted>[Local]</muted>`. The deploy
puts in the colors, and converts to a text component where MiniMessage isn't read (`tellraw`,
`title`) and to `&` codes for Essentials. There it understands colors, `<bold>`, `<italic>`,
`<underlined>`, `<strikethrough>` and `<reset>`; anything else in `<>` stays text, so `/map <name>`
can be written as is. Plugins' own messages keep their colors.
