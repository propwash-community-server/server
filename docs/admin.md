# Admin

Running the production server, with the tools from the [README](../README.md#setup).

## Access

1. Create an age key at `~/.config/sops/age/keys.txt` (Windows: `%AppData%\sops\age\keys.txt`):
   `age-keygen -o <that path>`.
2. Add its public key to [`.sops.yaml`](../.sops.yaml). Someone with access then runs `sops updatekeys secrets/*.secret.sops` and `sops updatekeys --input-type dotenv secrets/*.env.sops`.
3. Add your SSH public key as [`access/<name>.pub`](../access/). It's installed on the next deploy.

## Deploy

```
uv run pyinfra deploy/inventory.py deploy/deploy.py --limit prod --dry   # preview
uv run pyinfra deploy/inventory.py deploy/deploy.py --limit prod
```

## Secrets

| File | Contents |
|---|---|
| `forwarding.secret.sops` | [Velocity forwarding](https://docs.papermc.io/velocity/player-information-forwarding/) secret |
| `rcon.secret.sops` | RCON password |
| `mariadb.secret.sops` | password of the `minecraft` database user |
| `backup.env.sops` | [restic](https://restic.net) repository + password, S3 keys |
| `s3.env.sops` | `S3_ENDPOINT`, `S3_BUCKET`, `S3_ACCESS_KEY`, `S3_SECRET_KEY` (world store, for [`upload`](../tools/upload.py)) |
| `bot.env.sops` | the [Discord bot](#discord-bot)'s `.env`: `CLIENT_TOKEN`, `TYPESAFE_API_KEY`, one `BRIDGE_KEY_<SERVER>` per server with the bridge mod, `KOFI_TOKEN` (Ko-fi's webhook verification token); and `TUNNEL_TOKEN`, the Cloudflare Tunnel's (not passed to the bot) |

The first three are random strings. To regenerate one, run the following; the next deploy rolls it out:

```
openssl rand -hex 32 | tr -d '\n' | sops -e --input-type binary --output-type binary \
  --filename-override secrets/rcon.secret.sops /dev/stdin > secrets/rcon.secret.sops
```

## Permissions

[`permissions.toml`](../permissions.toml) defines the [LuckPerms](https://luckperms.net) groups
(`default`, `supporter`, `junior-builder`, `senior-builder`, `helper`, `mod`, `staff`, `admin`). Each entry is passed to
[`lp group <name> permission set`](https://luckperms.net/wiki/Command-Usage), so
[contexts](https://luckperms.net/wiki/Context) such as `"worldedit.* server=fpv"` can be used.
When the file changes, the deploy replaces the listed groups as a whole.
The `weight.*` and `prefix.*` entries set the role shown next to a player's name in the tab list
([TAB](https://modrinth.com/plugin/tab-was-taken), on the proxy, configured in
[`groups.yml`](../servers/velocity/files/plugins/tab/groups.yml) and
[`tab.yml.j2`](../servers/velocity/tab.yml.j2)). On fpv it also shows the player's map, which
[TAB-Bridge](https://modrinth.com/plugin/tab-bridge) sends from the server.

Group membership is stored in LuckPerms, not in the repo. It can be granted in game, or from the
VPS once the player has joined:

```
mc-rcon hub "lp user <name> parent set admin"
```

On the Fabric servers, [Vanilla Permissions](https://modrinth.com/mod/vanilla-permissions) exposes
vanilla commands as `minecraft.command.<name>`. [Essential Commands](https://modrinth.com/mod/essential-commands)
uses LuckPerms on fpv (`essentialcommands.<command>`, for warps); elsewhere it is configured in its own config.
Commands without a LuckPerms entry keep their default: open to everyone or op only.

Builders have two ranks, both shown as `[Builder]`. `junior-builder`: `/fly` on the hub, ten plots
on plots and WorldEdit there up to 80,000 blocks (`propwash.plotedit.staff`, like mods; still only on
their own and trusted plots). `senior-builder` has all of that (it includes `junior-builder`) and what map editing needs
on fpv: WorldEdit, `/flan bypass`, setting and deleting warps, `/time` on a map, and `/tp` and
`/execute` to reach a map that has no warp yet. Staff include `senior-builder`.

```
lpv user <name> parent add junior-builder
lpv user <name> parent add senior-builder
```

`supporter` is for Ko-fi donors: the `[Supporter]` tag, `/fly` on the hub and fpv, and `/flyspeed <0-10>`
on every server (EssentialsX's on the hub, mods/tweaks' on fpv and plots: up to the highest `propwash.flyspeed.<1-10>` a player has, supporters 6; on the hub EssentialsX's
`max-fly-speed` 0.6, the same limit for everyone without `essentials.speed.bypass`), and `/enderchest`
(`/ec`, `/echest`) on fpv and plots (mods/tweaks, `propwash.enderchest`), and 4 plots; below the builder ranks (a
builder who donates still shows `[Builder]`). By hand: `lpv user <name> parent add supporter`.

`helper` (dark green `[Helper]`, between builders and mods) has everything `supporter` has (it includes
it) and besides: staff chat, `/flyspeed` up to 10 (and `/speed` 10 on the hub), `/tp <player>` on any
server, `/gamemode creative` and `survival` on the hub, and `/maintenance on` and `off` (and staying on during it). Helpers are in the staff watch. Their `/help`
part is [`helper.txt`](../servers/velocity/files/plugins/staffhelp/helper.txt) (`/help helper` shows only
it), in place of the supporter one. `lpv user <name> parent add helper`.

`mod` (blue `[Mod]`, between builders and staff) has everything `supporter` has (it includes it) and
only this besides: staff chat, `/flyspeed` up to 10 (and `/speed` 10 on the hub), `/vote start` and
`/vote end`, `/maintenance on` and `off` (and staying on during it), `/kick`, `/ban`, `/mute`, `/unban`,
`/unmute` and `/history` (players, not IPs), `/tp <player>`, `/spy`, `/v` and `/v auto` (and seeing
vanished players), WorldEdit on plots with plotedit's staff limits (80,000 blocks), only where they
may build (`propwash.plotedit.staff`, not the bypass), and `/up` there, `/plot admin`
(`fabricplots.admin`: in it they may build, and WorldEdit, on any plot and the roads; it also lets
them change any plot's settings and flags and go onto plots they're denied from, like staff; not
`/plot setowner` or `/plot reload`), `/flan bypass` on fpv (building on the maps by hand; no
WorldEdit there), and `/gamemode creative` and `survival` on the hub. What they break and place in
`/plot admin` is in the staff watch.
Their `/help` part is [`mod.txt`](../servers/velocity/files/plugins/staffhelp/mod.txt) (`/help mod`
shows only it), in place of the supporter one. `lpv user <name> parent add mod`.

## Protection

[Flan](https://modrinth.com/mod/flan) runs on fpv: `[flan] deny` in its
`server.toml` stops everyone from breaking, placing or using anything, on every map. Builders and
staff run `/flan bypass` to edit and run it again to turn it off.

No boats on any server: the `noboats` datapack (`files/world/datapacks/noboats` of hub, fpv and
plots) removes every boat, chest boat and raft once a second and clears boat items from players.
Old boats go as soon as their chunk is loaded.

No projectiles on any server either: the `noprojectiles` datapack (same place) removes arrows,
tridents, snowballs, eggs, wind charges, ender pearls, thrown potions and bottles o' enchanting
within a tick of being shot or thrown; the item is used up. Firework rockets are allowed once every
2 seconds per player (launched by hand or for elytra boosting); others within those 2 seconds, and
rockets from crossbows or dispensers, are removed the same way. On fpv and plots `mods/tweaks` also
puts rockets on a 2-second item cooldown (the grey sweep), so the player's game doesn't try (no short
boost from a rocket the datapack removes), and says above the hotbar how long is left.

Admins can be exempt from both with `/exempt` (`propwash.exempt`; again to stop): it keeps their
projectiles, their boat items and every boat within 16 blocks of them, and lets them drop items. It
works on every server: `mods/crossserver` remembers it and sets the `propwash.exempt` tag on each
server the player arrives on (over rcon), so a `/tag` set by hand doesn't last.

A dropped item (Q, out of the inventory, from the creative menu) is deleted instead of landing on
the ground, except for `propwash.exempt` players: on fpv and plots by our Fabric mod
[`mods/tweaks`](../mods/tweaks/) (rebuilt like `mods/quiet`), on the hub by the `nodrop` datapack.
`mods/tweaks` also makes vanishing and
un-vanishing show at once: the Vanish mod left that to vanilla, which only re-checks who sees whom
when someone moves into another 16-block section. That part hooks into Vanish by name; after
updating Vanish, check that un-vanishing still shows you right away.

`/mydrone` (everyone, fpv and plots, also `mods/tweaks`) brings the player's own drone to them: the
nearest drone tagged `pw_owner_<their name>` in the map they're in (loaded), not while it's flying,
at most every 5 seconds. It takes no arguments, so nobody can aim it at someone else's drone.

Drones go with their owner (also `mods/tweaks`): when a player leaves fpv or plots (logs off or
switches server), their drones (`pw_owner_<name>`) are removed from every loaded map there, and a
drone in a chunk that loads later is removed if its owner isn't on that server. Drones without an
owner tag are left alone.

In the plot world everyone may use a debug stick (vanilla allows it to ops only), and `/debugstick`
(plots) gives one, one at a time. FabricPlots' protection applies to both its clicks, so it only
works on plots the player may build on; it changes block states, never the block itself.

## Plots

The `plots` server is a creative plot world from [FabricPlots](https://modrinth.com/mod/fabricplots)
(PlotSquared-style, on Fabric so drones work). Players arrive in the plot world (the datapack in
`servers/plots/files/`) and are put in creative there. FabricPlots protects plots and roads, so
plots doesn't run Flan. Placing a drone removes that player's older drone (`propwash_drone_limit`, as on fpv).

`/plot delete` (wipe to flat ground and release) and `/plot clear` (wipe only) need the plot's number, the owner's
plot N that `/plot info` shows, and it must be the plot the player stands on: `/plot delete` alone
only says which plot this is, `/plot delete 2` checks it and asks for the same again within 15 s.
The menu's Clear button only tells the command, so nobody confirms the wrong plot by mistake.

Plots over their owner's limit are locked (FabricPlots' PlotLocks): when a limit goes down, for
example when a supporter's time ends (4 plots, then 2), the owner keeps their first plots as
usual, and the newest ones become server plots with every block on them: anyone can walk in and
look, nobody can build or use anything (only staff in `/plot admin`), nobody can claim them, and
they're off the owner's `/plot list` and `/plot home`. They go back to the owner, as they are, when
the limit allows them again. Limits are checked at start, on join and every 5 minutes, offline
owners too.

Plots are 120x120 with roads in between: `/plot auto` gives a whole plot, no merging. Limits are
`fabricplots.limit.<n> server=plots` in [`permissions.toml`](../permissions.toml) (default 2,
builders 10, staff 64).

| Task | Command |
|---|---|
| Claim | `/plot auto` or `/plot claim` on an empty plot, or `/plot menu` |
| Let someone build | `/plot trust <name>` |
| Edit any plot (staff) | `/plot admin` (opt-in, run again to stop) |

Everyone has the build tools built into FabricPlots ([DraftSmith](https://github.com/beachfury/draftsmith)):
`/draft` opens them, `/draft wand` selects corners. They only change blocks on plots the player can
build on and refuse edits over 65,536 blocks (fixed in the mod). The `draftsmith.*` nodes are in
[`permissions.toml`](../permissions.toml); `draftsmith.admin` (editing outside plots) is left out.
WorldEdit can't be limited to plots, so on plots it stays with staff.

### Our FabricPlots build

Upstream FabricPlots has the plot size fixed at 32 in code and puts portals on the roads (for
servers where the plot world sits next to a survival world). We run its 26.3 branch with
[`fabricplots.patch`](../servers/plots/fabricplots.patch), which adds these settings to
`config/fabricplots.properties`; all default to upstream behavior:

| Setting | Default | Ours | What it does |
|---|---|---|---|
| `plot-size` | 32 | 120 | Plot size, read at startup; the spawn is derived from it |
| `portals` | true | false | Exit portals on the roads, Portal Keys (compass) on claim, `/plot key`, frame portals; `false` also removes existing ones on start |
| `minecart-cap`, `minecart-cooldown-seconds` | 0, 0 (off) | 15, 5 | Most minecarts per plot; seconds between one player's minecart placements (build admins exempt) |
| `free-items`, `free-blocks`, `free-entities` | (none) | Propwash drone, drone workbench, drone | Ids anyone may use anywhere, other people's plots too: items used on a block, blocks right-clicked (but not sneak-placing a block through them), entities clicked or hit (and damaged) |

It also fixes `/plot admin` for staff who aren't op: upstream only lets vanilla ops use the build
mode (editing roads and other plots); ours also accepts the `fabricplots.admin` permission, which
staff and admin have on plots.

And it closes a hole in upstream's protection: upstream only checks right-clicks on blocks, but
buckets, lily pads / frogspawn, spawn eggs (on water) and boats act on their own raycast (`Item#use`),
which the game sends separately, so anyone could pour water or lava onto any plot, or take fluid off
one. Ours checks those too, the block hit and the block changed, by the same rules as building
(plotsize.14). Every other vanilla item with its own `Item#use` changes nothing in the world or
throws a projectile, which the `noprojectiles` datapack removes.

Players trample farmland whatever the game rules say (mobs only with `mob_griefing`, off here), so
anyone could stomp the crops on any plot. Ours lets farmland turn to dirt under a player only where
they may build (plotsize.15); farmland drying out is unchanged.

Sand, gravel, concrete powder, anvils and the other gravity blocks don't fall in the plot world
(plotsize.16): they stay where they're placed. A sand sphere from a shape tool turned some 22,000
blocks into falling ones at once (everything below its middle has nothing under it), and their
collision checks with each other froze plots until the watchdog stopped it (2026-10-29).

Plot owners set what visitors (not trusted) may use on their plot with flags: `/plot flag set <flag>
on|off` (or `true|false`) on the plot, or `/plot menu` > the plot > Visitor permissions; `/plot flag help` lists them.
All are off by default and building stays owner and trusted only:

| Flag | Visitors may |
|---|---|
| `vehicle-use` | ride minecarts |
| `vehicle-place` | place minecarts on rails |
| `device-interact` | use buttons, levers, doors, trapdoors and fence gates |
| `container-use` | open chests, barrels and other containers |
| `animal-interact` | feed, ride and trade with animals and villagers |
| `misc-interact` | use armor stands and item frames |
| `drone-glass` | smash glass with drones, anyone's, the owner's too; it comes back after `/plot glassregen <seconds>` (default 180), see [maps.md](maps.md#breaking-glass) |
| `drone-glass-members` | the same, but only the drones of the owner and trusted players; wins over `drone-glass` |

They're saved with each plot (`fabricplots-plots.txt`); our patch adds them (PlotSquared has flags like
these: `vehicle-use`, `device-interact` and so on).

`/plot visit <player>` works for offline players too (upstream only takes online ones): it goes by
the owner name stored with each plot and suggests those names. `/plot list` lists everyone with a
plot, each name clickable to visit (upstream it opened your own plots, which `/plot edit` still does).

The jar is in the world store under `mods/`. The settings are in `[config]` of
`servers/plots/server.toml`, written before the first start (FabricPlots adds its defaults). The
grid is generated with the plot size, so **changing `plot-size` needs a fresh plots world**; keep the spawn in the datapack and `spawn-x/y/z` at the same spot.

To rebuild for a new FabricPlots or Minecraft version (JDK 25):

1. Clone [draftsmith](https://github.com/beachfury/draftsmith) and
   [fabricplots](https://github.com/beachfury/fabricplots), check out the matching branch in both.
2. In draftsmith: `./gradlew publishToMavenLocal`.
3. In fabricplots: `git apply <repo>/servers/plots/fabricplots.patch`, set
   `mod_version=<version>-plotsize.<n>` in `gradle.properties` (a new `<n>` per build, so the
   deploy sees a new jar name), then `./gradlew build`.
4. Upload `build/libs/fabricplots-*-plotsize+*.jar` to the world store as
   `mods/fabricplots-<version>-plotsize-<mc>.jar` (no `+`), and point the url in
   `servers/plots/server.toml` at it.

### WorldEdit for players (plotedit)

Everyone has WorldEdit on plots (`//wand`, `//set`, `//replace`, `//copy`, `//paste`, `//undo`, shapes
and more; the list is in `permissions.toml`, `server=plots`). [`mods/plotedit`](../mods/plotedit/)
lets an edit change only blocks where FabricPlots lets the player build (their own and trusted plots,
never roads, the bedrock floor or other plots; the rest is skipped) and limits it, in
`[config."config/plotedit.properties"]` in `servers/plots/server.toml` (read again when the file
changes, so a deploy changes them without a restart):

| Setting | Default | Does |
|---|---|---|
| `max-blocks-per-command` | 45000 | an edit stops after this many changed blocks (`//undo` takes back what went through) |
| `max-blocks-per-window`, `window-seconds` | 45000, 30 | at most this many changed blocks per player over the last `window-seconds`; reaching it pauses that player's edits for `window-seconds` |
| `max-selection-volume` | 1000000 | region commands (`//set`, `//copy`...) refuse bigger selections |
| `lag-log-ms` | 250 | ticks slower than this during players' WorldEdit are logged |
| `lag-alert-ms` | 1000 | ... and above this, staff are told in chat |
| `limit-alert-count` | 5 | staff are told when a player hits a limit this many times in 10 minutes |

Past a limit the rest of an edit is skipped, not aborted, so players always see what changed.
`//undo` has no per-command limit, works during a pause and may take the window up to twice
`max-blocks-per-window`, so undoing right after a big edit works; it still counts, so a run of undos is
limited.
`//redo` is limited like any edit.

WorldEdit's own `max-radius` (40 on plots, for everyone) limits `//sphere`, `//cyl`, `//fill` and
the like. Mods and builders have `propwash.plotedit.staff`: the `staff.*` limits instead (80,000 blocks per
command and per window), still only where they may build. Staff have `propwash.plotedit.bypass`: no
limits, anywhere, and they get the notices.

`logs/plotedit.log` on plots has a line per player edit (`EDIT`: player, command, blocks changed and
skipped, time, slowest tick, the limit that stopped it), per slow tick (`LAG`: whose edits ran in it) and
per notice (`ALERT`). To see whether a limit should be lower, or who's pushing it:

```
grep -E "LAG|ALERT" /home/mc/servers/plots/logs/plotedit.log | tail -20
```

## Bans

[LibertyBans](https://github.com/A248/LibertyBans) runs on the proxy and applies to every server.
Its commands include `/ban`, `/mute`, `/kick`, `/unban` and `/history`; a duration such as `/ban <player> 7d` makes it temporary.

## Chat

[Carbon](https://modrinth.com/plugin/carbon) runs on the proxy, so chat goes across every server in
one format, with the LuckPerms role prefix (the same one as in the tab list). It needs
[SignedVelocity](https://modrinth.com/plugin/signedvelocity) on the proxy and every backend.
The prefix and server name come from [MiniPlaceholders](https://modrinth.com/plugin/miniplaceholders)
expansions (`expansions` in the velocity `server.toml`).

| Channel | Command | Who |
|---|---|---|
| Global (default) | `/global`, `/g` | everyone, all servers |
| Local | `/local`, `/l` | everyone, same server only |
| Staff | `/staff`, `/sc`, or start a message with `#` | `staff` and `admin` |

Typing a channel's command switches to it; `/g hello` sends one message without switching.
Channels are [`files/plugins/carbonchat/channels/`](../servers/velocity/files/plugins/carbonchat/channels/),
kept as Carbon rewrites them on start, so edit them in place (a reformatted file would restart the
proxy on every deploy). Who may use a channel is `carbon.channel.<name>.see` / `.speak` in
[`permissions.toml`](../permissions.toml).

[NetworkJoinMessages](https://modrinth.com/plugin/networkjoinmessages) on the proxy tells everyone
when a player joins the network (with the server they land on, or "for the first time") or leaves
it, and tells the players on a server when someone arrives there from another one; the server they
left says nothing. A leave is held back 1.5 seconds, so a quick reconnect says nothing either.
Vanished players are never announced (mods/vanish gives them `networkjoinmessages.silent`). Its
config is
[`files/plugins/networkjoinmessages/config.yml`](../servers/velocity/files/plugins/networkjoinmessages/config.yml),
kept as the plugin rewrites it on start (like Carbon's channels above), so edit it in place.

No server says "joined the game", "left the game", "is now AFK" or "has made the advancement" in
chat (the server log still has joins, leaves and AFK):

| Server | Join, leave and AFK | Advancements |
|---|---|---|
| hub | EssentialsX `custom-join-message`, `custom-quit-message`, `broadcast-afk-message` | `files/world/datapacks/quiet` |
| fpv | our [`mods/quiet`](../mods/quiet/) | `files/world/datapacks/quiet` |
| plots | our [`mods/quiet`](../mods/quiet/) | `[gamerules]` in its `server.toml` |

The quiet mod's AFK part hooks into Essential Commands (`PlayerData.setAfk`). If an Essential
Commands update changes that, the AFK messages come back (no crash): check after updating it.

The game rule is `show_advancement_messages`. Paper keeps game rules per dimension, so the hub's
datapack also sets it in `maps:mountains`, its spawn map. Players still see their own advancement
pop-up; advancements stay on, since the fpv datapacks use them.

To change the quiet mod (JDK 25): edit [`mods/quiet`](../mods/quiet/), raise `mod_version` in its
`gradle.properties`, `./gradlew build` there, upload `build/libs/quiet-<version>+<mc>.jar` to the
world store as `mods/quiet-<version>-<mc>.jar` (no `+`), and change the URL in the fpv and plots
`server.toml`.

## Maintenance

[Maintenance](https://modrinth.com/plugin/maintenance) on the proxy closes the network to everyone but
helpers, mods, staff and admin (`maintenance.bypass`). Turning it on kicks everyone else, and they get "The server
is currently under maintenance!" until it's off; it stays on through a restart. Each countdown
message of a timer, and turning it on or off, plays a note block to everyone online (mods/crossserver). The server list shows
it too. Its commands need `maintenance.admin` (admins, through `*`).

| Task | Command |
|---|---|
| Close the network | `/maintenance on` |
| Open it again | `/maintenance off` |
| Close it after a while (e.g. `10m`) | `/maintenance starttimer <time>` |
| Open it again after a while | `/maintenance endtimer <time>` |
| Close it after a while, for a while | `/maintenance scheduletimer <in> <for>` |

After a proxy restart, players join as soon as the proxy is up; while the hub is still starting, they
land on fpv (the velocity `try` list), and with neither up, they're told to try again later.

## Teleporting and invsee

Our Velocity plugin [`mods/crossserver`](../mods/crossserver/) handles these on every server, in place
of each server's own; a player on another server is moved there first:

| Task | Command | Who |
|---|---|---|
| Ask to go to someone / ask them to come to you | `/tpa <player>`, `/tpahere <player>` | everyone (`propwash.tpa`) |
| Answer (clickable in the message) | `/tpaccept [player]`, `/tpdeny [player]` | everyone |
| Go to someone, bring them to you, or one player to another | `/tp <player>`, `/tphere <player>`, `/tp <player> <player>` | helpers, mods and staff (`propwash.tp`) |
| See and change someone's inventory | `/invsee <player>` | staff (`propwash.invsee`) |

Requests last two minutes. The teleport itself is a console `tp` over rcon (the deploy writes the
ports and password to `plugins/crossserver/`), a second after arriving, since the hub sends arrivals
to spawn; on the same server it's that console `tp` right away (mods have no `/tp` of the server's
own). `/tp` is a proxy command so that helpers' and mods' game clients offer it at all (a client only
sends commands its server lists for it). `/tp` with anything else (coordinates, a selector) goes on to
the server as `/teleport`, which only staff have there.
Each server keeps its own inventory of a player, so `/invsee` shows the one on the server they're
on: EssentialsX `/invsee` on the hub, [InvView](https://modrinth.com/mod/invview) `/view inv` on
fpv and plots (also `/view echest <player>` for the ender chest). InvView doesn't open inventories
of players with `invview.protected`, which admins have through `*`. Vanished players can't be found
by anyone who can't see them.

Staff also have Carbon's `/spy`: private messages between anyone, and other servers' local chat.

## Kits

`/kit create <name>` opens a chest titled with the kit's name: clicking a slot with an item on the
cursor (or shift-clicking an item in the inventory) puts a copy there, clicking it with an empty
hand clears it; the player's own items never move. Closing it saves the kit, and `/kit <name>` gives
copies again, once every 20 s (staff, `propwash.kits.staff`: every 2 s). `/kit edit <name>`, `/kit delete <name>`, `/kit list`.
Everyone gets 1 kit of up to 3 items; supporters (`propwash.kits.supporter`) 3 of up to 18. Set in
`[config."config/kits.properties"]` of fpv and plots ([`mods/kits`](../mods/kits/)); kits are saved in
`/home/mc/kits/<uuid>.dat`, shared by both servers (and backed up with them).

## Votes

Admins (`propwash.vote`) run a yes/no vote on every server with `/vote start <question>`: everyone
gets the question with clickable Yes and No (`/vote yes`, `/vote no`; changing your answer is fine),
and after 60 seconds, or `/vote end`, the result; a note block sounds with each. `/vote` shows the running vote. One at a time; the
question is plain text (no formatting or click actions), at most 200 characters. Part of
`mods/crossserver`; votes and results are in the proxy log.

## AFK

Everyone can `/afk` on every server: `[AFK]` after their name in the tab list, no chat message, until
they move, `/afk` again or leave. Whoever runs a command naming them (`/msg`, `/tp`, `/tpa`, ...) gets
a note that they're AFK; the command still runs (not for a vanished player, unless they can see
vanished players). Part of `mods/crossserver`, on the proxy: it can't see movement, so every 3 seconds
it asks the player's server over rcon where each AFK player is. It isn't in `/help`. The automatic AFK after 15 minutes (Essential Commands, EssentialsX) is separate and doesn't
show in the tab list.

## Backpack

Admins for now (`propwash.backpack`, which only `*` gives) wear a backpack with `/backpack` (again to take it off) on fpv and
plots, and `/backpack drone` puts a copy of the Propwash drone in their hand on the outside of it
(`/backpack drone off` takes it off). Our Fabric mod [`mods/backpack`](../mods/backpack/): item displays
that every player's game is told ride the player (in 26.3 nothing can really ride a player), turned
with their body every tick, so nothing is needed on the client. The backpack is the
`propwash_cosmetics:backpack` model in the maps resource pack; without the pack it shows as the
missing-model cube. Its item, model, size and position, and the drone's, are in each server's
`config/backpack.properties` (written on first start, read on start). Hidden while vanished, in
spectator or dead. On or off is LuckPerms meta (`propwash-backpack`), so it follows players to every
server; the drone on it is kept per server in `config/backpacks/<uuid>.snbt`.

## Vanish

`/v` or `/vanish` (admin, `propwash.vanish`) hides you on every server until you `/v` again, through restarts and
server switches: not in any tab list, the server list or `/list`, not drawn at all (no invisibility
particles), not in name suggestions, and commands, `/tpa` and `/msg` don't find you. Public chat is
off while vanished; staff chat (`#` or `/sc`) works. Staff and admin see vanished players
(`propwash.vanish.see`, `tab.seevanished`, `vanish.feature.view`, `essentials.vanish.see`,
`carbon.whisper.vanished`), with a gray `[V]` after their name in the tab list, and are told in chat
when someone vanishes or shows themselves again.

Going into `/v` posts your usual leave message ("<name> left") and coming out your usual join message,
so nobody can tell (NetworkJoinMessages' own `/njoinspoof leave` and `join`, run for you). While
vanished on hub, a boss bar says so (fpv and plots show the Vanish mod's own).
`/vanish <anything>` (like `/vanish check`) goes to the server's own vanish command.

`/v auto on` (or `/vanish auto on`): you always arrive on the network vanished, without a join
message; `/v auto off`: always visible, with it; `/v auto` shows yours. Without one you arrive the way
you left. It's applied as you leave the network (whether a join is silent is settled before you're
back), and kept in the proxy's `plugins/vanish/auto.properties`.

| Part | Does |
|---|---|
| [`mods/vanish`](../mods/vanish/) (proxy) | `/v`, `/vanish`; keeps who is vanished; TAB tab list (and `[V]` for staff), server list, blocks chat; tells Carbon and staff; fake leave/join messages; hub boss bar |
| [Vanish](https://modrinth.com/mod/vanish) (fpv, plots) | hides you; switched on by `/v` and on arrival (LuckPerms meta `vanish_on_join`) |
| EssentialsX `/vanish` (hub) | hides you; switched on by `/v` and on arrival (`essentials.silentjoin.vanish`, `allow-silent-join-quit`) |
| our Carbon build | leaves vanished players out of `/msg` and its suggestions |

`/v` toggles on the proxy and is sent on to the server you're on as its own `/vanish on|off`.
Admins' `*` excludes `essentials.vanish.effect` (the invisibility effect),
`essentials.silentjoin.vanish` and `networkjoinmessages.silent`, which `/v` sets per player. The
Paper servers are listed in `files/plugins/vanish/config.properties`.

Carbon on Velocity doesn't know about vanish (its check is a TODO that says nobody is), so we run
Carbon with [`servers/velocity/carbon-vanish.patch`](../servers/velocity/carbon-vanish.patch), which
asks `mods/vanish`. To rebuild it for a new Carbon (JDK 25): clone
[Carbon](https://github.com/Hexaoxide/Carbon) at the release tag, `git apply` the patch, set
`projectVersion=<version>-vanish.<n>` in `gradle.properties`, then
`./gradlew :carbonchat-velocity:build -x test -x spotlessCheck`, upload
`velocity/build/libs/carbonchat-velocity-<version>-vanish.<n>.jar` to the world store under `mods/`
and change the URL in the velocity `server.toml`.

To change `mods/vanish` (JDK 25): edit it, raise the version in `gradle.properties` and `Vanish.java`,
`./gradlew build` there, upload `build/libs/vanish-<version>.jar` to the world store as
`mods/vanish-<version>.jar`, and change the URL in the velocity `server.toml`.

## Discord bot

The [Propwash bot](https://github.com/Itsj5j6j7/pw-server/tree/main/bot) runs on the VPS as two
units of [`propwash-bot@.service`](../deploy/files/propwash-bot@.service), as the `bot` user from
`/home/bot/pw-server/bot`: `propwash-bot@bot` (the Discord bot) and `propwash-bot@api` (the HTTP
API on `127.0.0.1:3006` the bridge mod reports to). They share `bridge.db` there (records, `/link`
accounts), which the nightly backup includes. The local stack runs only the API.

The deploy checks out `BOT_COMMIT` in [`deploy.py`](../deploy/deploy.py): to update the bot, change
it and deploy. The bot's channels, roles and bridge servers are in that repo's `src/config.js`.

The bridge mod (`propwash-bridge`, built by the bot's author and uploaded to the world store under
`mods/`) is on fpv and plots. Each server gets its key from `BRIDGE_KEY_<SERVER>` in
`bot.env.sops` as `PROPWASH_BRIDGE_API_KEY`; a server added to the bot's config needs a key there too.

Supporters come from [Ko-fi](https://ko-fi.com/propwashfpv): Ko-fi sends every payment to the bot API's
`POST /kofi` (checked against `KOFI_TOKEN`) through a Cloudflare Tunnel (`cloudflared`, installed by
the deploy when `TUNNEL_TOKEN` is set). The tunnel is in the propwashfpv.com Cloudflare account (Zero
Trust > Networks > Tunnels): `kofi.propwashfpv.com`, path `^/kofi$` only (the rest of the bot API must
stay private), to `http://localhost:3006`; Ko-fi's webhook URL is `https://kofi.propwashfpv.com/kofi`.
Every 5 EUR of a donation or membership payment is a month of supporter time for the Discord account
the donor connected on Ko-fi, added after what's left: the bot gives the "Ko-fi supporter" role while
there is time, and takes it away when it's up; the role brings the `supporter` group on the servers
for the linked Minecraft account (`/link`). Each payment is posted in the bot's supporter log channel;
payments it can't match (no Discord on Ko-fi, another currency, under 5 EUR) get their months from
staff with `/supporter add <user> <months>` (`/supporter info`, `/supporter remove`).

Two Discord roles, and only one of them is the bot's:

| Role | Given by | Lasts | Brings |
|---|---|---|---|
| Ko-fi supporter | the bot only, from supporter time | until the time is up | the `supporter` group in game (`/link`ed account) |
| Ko-fi donor | Ko-fi (Settings > Discord, "Roles for supporters"), on any payment | for good (Ko-fi never takes it back) | nothing, it's a thank-you |

Ko-fi must not give "Ko-fi supporter": the bot only takes the role back from people it has supporter
time for, so one Ko-fi gave it to (for a donation under 5 EUR or in another currency) would keep it, and the
rank, forever. Ko-fi still gives a role ("Ko-fi donor", the only one under "Roles for supporters";
"Roles for monthly members" is empty) so that it keeps asking donors to connect their Discord: that's
what puts their Discord account in each payment it sends. The Discord connection itself ("Connected to
Propwash FPV") stays on.

| Task (as root) | Command |
|---|---|
| Status | `systemctl status propwash-bot@bot propwash-bot@api` |
| Logs | `journalctl -u propwash-bot@bot`, `journalctl -u propwash-bot@api` |
| API up | `curl -s 127.0.0.1:3006/v1/health` |

## Stats

The proxy records player activity for graphs (nothing is shown in game): [`mods/stats`](../mods/stats/)
appends one JSON object per line to `plugins/stats/events-YYYY-MM.jsonl` on the velocity server,
months in UTC:

| `e` | Fields | When |
|---|---|---|
| `join` | `uuid`, `name` | joined the network |
| `server` | `uuid`, `name`, `server`, `from` (none right after joining) | arrived on a server |
| `leave` | `uuid`, `name`, `server` | left the network |
| `online` | `total`, `servers` (`{"hub": 2, ...}`) | every 5 minutes |
| `start`, `stop` | | the proxy started or stopped (a stop ends every session) |

Every line has `t`, ISO-8601 UTC. No IP addresses; vanished players count like everyone. Unique
players, new players, sessions, playtime, peak online and per-server use all come from these. The
files are in `/home/mc/servers`, so they're backed up.

Before the plugin, the same events (without `online`) were rebuilt from the proxy's logs, from
2026-10-08: `backfill-YYYY-MM.jsonl`, each line with `"src": "log"`, made once with
[`mods/stats/backfill.py`](../mods/stats/backfill.py) (`python3 backfill.py` shows what it would
write, `--write` writes it; it stops at the plugin's first event). For a quick look, on the VPS: [`mods/stats/report.py`](../mods/stats/report.py)
(`python3 report.py --days 7`; checks the data, then players, new players, sessions, play time and
peak online per day, and the top players). Stats 1.0.0 (to 2026-10-28) missed some leaves; the
report ends those sessions at the player's last event.

## Staff watch

The proxy keeps a record of what staff do: [`mods/staffwatch`](../mods/staffwatch/). Watched is
everyone with a group other than `default` and `supporter`, or a permission set on them directly
(LuckPerms); it's checked again on every event, so a new builder or staff member is watched from
then on. For them it records every command they type (whether it works or not, with the server they
were on), their joins, server switches and leaves. Private messages keep who they're to but not what
they say: `msg Steve …`, `r …`, `mail send Steve …` (Carbon's whisper, reply and continue and their
aliases, EssentialsX's, `/mail send`). On plots, [`mods/tweaks`](../mods/tweaks/) adds
every block anyone breaks or places in `/plot admin` mode (and water, lava and the like poured from a
bucket), with whose plot it was (or road). Only players in that mode cost anything: a line handed to a
thread of its own, which writes it, so the server never waits for the disk.

| Command | |
|---|---|
| `/staffwatch` | everything, newest first (12 per page, times in UTC) |
| `/staffwatch <player> [page]` | one player; click "Older" for the next page |

Only admins (`propwash.staffwatch`, from `*`). The records are one JSON object per line in
`plugins/staffwatch/` on the velocity server, never rewritten, so they're backed up:
`YYYY-MM.jsonl` from the proxy (`e`: `command`, `join`, `server`, `leave`) and
`plots-YYYY-MM.jsonl` from plots (`e`: `admin_break` or `admin_place`, with `block` (for a bucket, the
bucket's id), `x`, `y`, `z`, `plot`). Every
line has `t` (ISO-8601 UTC), `uuid` and `name`.

## Backups

Every night at 04:00, [restic](https://restic.net) backs up `/home/mc/servers` (without jars and
logs), the players' kits (`/home/mc/kits`), the bot's `bridge.db` and a [MariaDB](https://mariadb.org) dump to the bucket, with saving paused during the
snapshot. It keeps 7 daily, 4 weekly and 6 monthly snapshots.

| Task (as root) | Command |
|---|---|
| Back up now | `systemctl start mc-backup` (log: `journalctl -u mc-backup`) |
| List snapshots | `set -a; . /etc/mc-backup.env; restic snapshots` |
| Restore a world | [`mc-restore`](../deploy/files/mc-restore) `<server> [snapshot]` (the old world is kept as `world.before-restore-<time>`) |
| Restore permissions + bans | `set -a; . /etc/mc-backup.env; restic restore latest --target / --include /var/backups/mariadb.sql && mysql < /var/backups/mariadb.sql` |

## On the VPS

| Task | How |
|---|---|
| Console | [`mc-console`](../deploy/files/mc-console) `<name>` |
| Status | `systemctl status mc@<name>` |
| Logs | `/home/mc/servers/<name>/logs/latest.log` |
| Hub spawn | [`/setspawn`](https://essentialsx.net) in game; players are sent there on join |
| Hub portals | [`/portal create`](https://advancedportals.sekwah.com) in game, then `systemctl restart mc@hub`: AdvancedPortals 2.8.0 only sets up portals fully when loading them, and a new one throws an error when it refuses a player |
| fpv spawn | `/execute in maps:lobby run setworldspawn ~ ~ ~` in game; new players start there (see [Maps](maps.md#lobby)) |
