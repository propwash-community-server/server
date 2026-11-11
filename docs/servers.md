# Servers and mods

Each server has a [`servers/<name>/server.toml`](../servers/) with its `type` (`fabric`, `paper` or
`velocity`), `port`, `ram`, `jar` and `url`, its `mods` or `plugins` URLs, and these sections:

| Section | Effect |
|---|---|
| `[properties]` | server.properties, set on every deploy |
| `[new_world]` | server.properties, set only for a new server |
| `[config."<path>"]` | top-level keys in a plugin's config file, palette tags expanded (also on the proxy) |
| `[gamerules]` | game rules, set on every start (a generated datapack); removing one doesn't reset it |
| `[flan]` | `deny`: [Flan](https://modrinth.com/mod/flan) permissions denied in every dimension, maps and `/world create` worlds included (mods/regen copies the overworld's rules to unlisted worlds); other keys go to Flan's config |
| `help` | text shown by `/help`; the velocity `help` is added to the end of every server's |
| `welcome` | text shown in chat every time a player arrives on the server |
| `rtp_cooldown` | freestyle: seconds between two `/rtp` for each player, nobody skips it (default 15; see [maps.md](maps.md)) |

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
The same file's `[link]` section reminds players who haven't linked their Discord account (no
`propwash.linked`, which the Discord bot sets on `/link`) to `/link`: in chat on its own timer (every 10
minutes), and with a boss bar that stays up, on every server but limbo. Both stop within seconds of linking.

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

## Freestyle and Race

The maps are on two servers: `freestyle` (open maps) and `race` (race tracks), with their maps in
`servers/<server>/maps/` (see [maps.md](maps.md)). Both started in November 2026 as copies of the
old `fpv` server's world, so warps, race courses and players' spots carried over; each copy then
lost the maps, warps and race courses of the other. Players get there through the hub's portals
(`portal.freestyle`, `portal.race` in the hub's `server.toml`); staff also with `/server freestyle`, `/server race`.
`equilibrium` is on both, as two copies: its racetrack is to be taken off the Freestyle one.

## Race courses

`/race` lists the server's Propwash race courses (one a line, clickable), and `/race <course>` (its
name, or its number for an unnamed one) takes you just in front of its start gate, facing through it,
at most every 2 seconds. Everyone has it, on the hub, freestyle, race and plots ([`mods/tweaks`](../mods/tweaks/)).
Propwash keeps the courses in `world/data/propwash_race.json` and writes it when the world is saved,
so a new course shows up after the next save (every few minutes). On `race`, where every map is a
track, `/map` is the same as `/race`, and `/rtp` (`/map rtp`, the rtp mannequin) takes you to the start of a
random course from the `/race` list (on another map when there is one): `map = "race"` in its
`[config."config/tweaks.properties"]`, and the deploy then writes no Melius Commands `/map` or `/rtp` there. On `freestyle`, whose maps aren't
tracks, it's the other way round: `/race` is `/map` (`race = "map"` there).

If in front of the gate is inside blocks, `/race` lands you higher up (up to 6 blocks), else at the gate
itself. Where that's still wrong, senior builders, mods and staff (`propwash.race.setstart`) stand where
players should land, face the way they should look, and run `/race setstart <course>`; `/race clearstart
<course>` goes back to the gate. These spots are kept per server in `world/data/propwash_race_starts.json`
(by course number), so they stay through deploys.

The list is grouped by the ranking of the course's map: **Beginner**, **Intermediate**, **Advanced** and
**Pro** (easiest first; a ranking without courses shows "none yet"), then the courses on unranked maps.
A map's ranking is `rank = "Advanced"` in its `servers/<server>/maps/<map>.toml` (see
[Maps](maps.md#adding-a-map)); the deploy writes it into the server's `config/tweaks.properties` as
`rank.maps.<map>`. A course in another world (e.g. a `/world create` world, `worlds:<name>`) is ranked
with a `"rank.worlds.<name>" = "Pro"` line in that server's `[config."config/tweaks.properties"]`.
A ranked world without any course is listed itself, as `"title.worlds.<name>"` (else its name, e.g.
`gilbert_fast` as Gilbert Fast) by `"author.worlds.<name>"`; `/race <title>` takes you to the ground at 0 0
there, or to its `/race setstart` spot.

## Short commands

On the hub, freestyle, race and plots ([`mods/tweaks`](../mods/tweaks/)): `/gm <mode>` (`survival`, `creative`,
`adventure`, `spectator`, or `0`, `1`, `2`, `3`, `spec`), `/gmc` and `/gms` run `/gamemode`, and `/ci`
runs `/clear`. Whoever may use `/gamemode` or `/clear` there may use them; nobody else sees them.

## Limbo

Players who can't join the Fabric servers (no Fabric, another game version, missing or wrong mods)
go to `limbo` instead of being disconnected: when a server refuses a player while they join (also
their first join into the network), [`mods/crossserver`](../mods/crossserver/) sends them to limbo and
tells them why, just below limbo's welcome (`welcome` in `servers/limbo/server.toml`), which has
the links to [propwashfpv.com](https://propwashfpv.com/) and [Discord](https://discord.propwashfpv.com).
A player refused while already on limbo just gets the message. Limbo is Paper with [ViaVersion](https://modrinth.com/plugin/viaversion) and ViaBackwards,
so other game versions get in, and EssentialsX; staff go there with `/server limbo`. Its world was
built on dev and put in place by hand before the first deploy (the deploy never changes worlds), from
`limbo/limbo-world-2026-11-01.tar.gz` in the world store; it also has the hub's `mountains`
dimension, a datapack in the world itself.
WorldEdit schematics for limbo are in `servers/limbo/files/plugins/WorldEdit/schematics/` (the
deploy copies them; pasting is by hand): `limbo_net.schem`, a drone net built on dev, pasted with
`//schem load limbo_net` and `//paste -o` (back where it was copied). Limbo offers no resource pack,
so players there see its nets as oxidized copper bars.

## Voice chat

Proximity voice chat on the hub, freestyle, race and plots: [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat),
optional for players (without the mod they just don't hear or speak; nobody goes to limbo for it). Players
press V for its settings and groups. Its voice goes over UDP, not through the proxy's Minecraft
connection: Simple Voice Chat's Velocity plugin takes it on one public port, `24454/udp` (opened in
`deploy.py`'s firewall; a cloud firewall in front of the VPS needs it too), and passes it on to the
server the player is on. Each server's own voice port is on 127.0.0.1, its Minecraft port - 1000
(`[config."config/voicechat/voicechat-server.properties"]`); a new server with the mod needs one too.
Players are heard 150 blocks away (`max_voice_distance` there; whispering, `whisper_distance`).

Who hears whom always goes by bodies, also while flying. The voice chat mod plays voices around the
camera, which in FPV is on the drone, so [`mods/voicebody`](../mods/voicebody/) (built and uploaded like
`mods/regen`) moves every voice a pilot in FPV hears by the body-to-camera offset: the players next to
their body still sound next to them. `enabled = "false"` in `[config."config/voicebody.properties"]` turns
it off on that server.

## Colors

Texts the repo writes (chat formats, the tab list, rank prefixes, the MOTD, map titles, `/map`,
`/help` and join messages) are [MiniMessage](https://docs.advntr.dev/minimessage/format.html), and
use the colors in [`palette.toml`](../palette.toml) as tags: `<muted>[Local]</muted>`. The deploy
puts in the colors, and converts to a text component where MiniMessage isn't read (`tellraw`,
`title`) and to `&` codes for Essentials. There it understands colors, `<bold>`, `<italic>`,
`<underlined>`, `<strikethrough>` and `<reset>`; anything else in `<>` stays text, so `/map <name>`
can be written as is. Plugins' own messages keep their colors.
