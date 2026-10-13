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

The first three are random strings. To regenerate one, run the following; the next deploy rolls it out:

```
openssl rand -hex 32 | tr -d '\n' | sops -e --input-type binary --output-type binary \
  --filename-override secrets/rcon.secret.sops /dev/stdin > secrets/rcon.secret.sops
```

## Permissions

[`permissions.toml`](../permissions.toml) defines the [LuckPerms](https://luckperms.net) groups
(`default`, `builder`, `staff`, `admin`). Each entry is passed to
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

The `builder` group is what map editing needs on fpv: WorldEdit, `/flan bypass`, setting and
deleting warps, `/time` on a map, and `/tp` and `/execute` to reach a map that has no warp yet.

## Protection

[Flan](https://modrinth.com/mod/flan) runs on fpv: `[flan] deny` in its
`server.toml` stops everyone from breaking, placing or using anything, on every map. Builders and
staff run `/flan bypass` to edit and run it again to turn it off.

## Plots

The `plots` server is a creative plot world from [FabricPlots](https://modrinth.com/mod/fabricplots)
(PlotSquared-style, on Fabric so drones work). Players arrive in the plot world (the datapack in
`servers/plots/files/`) and are put in creative there. FabricPlots protects plots and roads, so
plots doesn't run Flan.

Plots are 120x120 with roads in between: `/plot auto` gives a whole plot, no merging. Limits are
`fabricplots.limit.<n> server=plots` in [`permissions.toml`](../permissions.toml) (default 1,
builder 2, staff 64).

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
[`fabricplots.patch`](../servers/plots/fabricplots.patch), which adds two settings to
`config/fabricplots.properties`; both default to upstream behavior:

| Setting | Default | Ours | What it does |
|---|---|---|---|
| `plot-size` | 32 | 120 | Plot size, read at startup; the spawn is derived from it |
| `portals` | true | false | Exit portals on the roads, Portal Keys (compass) on claim, `/plot key`, frame portals; `false` also removes existing ones on start |

It also fixes `/plot admin` for staff who aren't op: upstream only lets vanilla ops use the build
mode (editing roads and other plots); ours also accepts the `fabricplots.admin` permission, which
staff and admin have on plots.

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

[NetworkJoinMessages](https://modrinth.com/plugin/networkjoinmessages) on the proxy tells everyone when
a player joins the network (with the server they land on) or leaves it, and tells the players on a
server when someone arrives there from another one; the server they left says nothing. A leave is held
back 1.5 seconds, so a quick reconnect says nothing either. Vanished players are never announced
(mods/vanish gives them `networkjoinmessages.silent`). Its config is
[`files/plugins/networkjoinmessages/config.yml`](../servers/velocity/files/plugins/networkjoinmessages/config.yml),
kept as the plugin rewrites it on start (like Carbon's channels below), so edit it in place.
The servers' own join and leave messages are hidden (below).

Typing a channel's command switches to it; `/g hello` sends one message without switching.
Channels are [`files/plugins/carbonchat/channels/`](../servers/velocity/files/plugins/carbonchat/channels/),
kept as Carbon rewrites them on start, so edit them in place (a reformatted file would restart the
proxy on every deploy). Who may use a channel is `carbon.channel.<name>.see` / `.speak` in
[`permissions.toml`](../permissions.toml).

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

## Startup

For two minutes after the proxy starts, nobody can join ("The server is starting. Try again in N
seconds."), so the servers have time to load after a restart. Staff and admin can
(`warmup.bypass`).

| Task | Command (admin, `warmup.admin`) |
|---|---|
| Let everyone in before the two minutes are up | `/open` |
| Kick everyone but staff from every server and keep them out ("The server is temporarily closed to the public. Try again soon.") | `/lockdown` |
| End a lockdown | `/open` |

A lockdown stays on through a proxy restart until `/open`. Both commands are logged in the proxy log.

It's our Velocity plugin [`mods/warmup`](../mods/warmup/); to change it (JDK 25),
edit it, raise the version in `gradle.properties` and `Warmup.java`, `./gradlew build` there, upload
`build/libs/warmup-<version>.jar` to the world store as `mods/warmup-<version>.jar`, and change the
URL in the velocity `server.toml`.

## Teleporting and invsee

Our Velocity plugin [`mods/crossserver`](../mods/crossserver/) handles these on every server, in place
of each server's own; a player on another server is moved there first:

| Task | Command | Who |
|---|---|---|
| Ask to go to someone / ask them to come to you | `/tpa <player>`, `/tpahere <player>` | everyone (`propwash.tpa`) |
| Answer (clickable in the message) | `/tpaccept [player]`, `/tpdeny [player]` | everyone |
| Go to someone | `/tp <player>` | staff (`propwash.tp`) |
| See and change someone's inventory | `/invsee <player>` | staff (`propwash.invsee`) |

Requests last two minutes. The teleport itself is a console `tp` over rcon (the deploy writes the
ports and password to `plugins/crossserver/`), a second after arriving, since the hub sends arrivals
to spawn. `/tp` with anything else (coordinates, someone on the same server) is the server's own.
Each server keeps its own inventory of a player, so `/invsee` shows the one on the server they're
on: EssentialsX `/invsee` on the hub, [InvView](https://modrinth.com/mod/invview) `/view inv` on
fpv and plots (also `/view echest <player>` for the ender chest). InvView doesn't open inventories
of players with `invview.protected`, which admins have through `*`. Vanished players can't be found
by anyone who can't see them.

Staff also have Carbon's `/spy`: private messages between anyone, and other servers' local chat.

## Vanish

`/v` (admin, `propwash.vanish`) hides you on every server until you `/v` again, through restarts and
server switches: not in any tab list, the server list or `/list`, not drawn at all (no invisibility
particles), not in name suggestions, and commands, `/tpa` and `/msg` don't find you. Public chat is
off while vanished; staff chat (`#` or `/sc`) works. Staff and admin see vanished players
(`propwash.vanish.see`, `tab.seevanished`, `vanish.feature.view`, `essentials.vanish.see`,
`carbon.whisper.vanished`).

| Part | Does |
|---|---|
| [`mods/vanish`](../mods/vanish/) (proxy) | `/v`; keeps who is vanished; TAB tab list, server list, blocks chat; tells Carbon; no join messages |
| [Vanish](https://modrinth.com/mod/vanish) (fpv, plots) | hides you; switched on by `/v` and on arrival (LuckPerms meta `vanish_on_join`) |
| EssentialsX `/vanish` (hub) | hides you; switched on by `/v` and on arrival (`essentials.silentjoin.vanish`, `allow-silent-join-quit`) |
| our Carbon build | leaves vanished players out of `/msg` and its suggestions |

`/v` toggles on the proxy and is sent on to the server you're on as its own `/vanish on|off`.
Admins' `*` excludes `essentials.vanish.effect` (the invisibility effect),
`essentials.silentjoin.vanish` and `networkjoinmessages.silent`, which `/v` sets per player. The Paper servers are listed in
`files/plugins/vanish/config.properties`.

Carbon on Velocity doesn't know about vanish (its check is a TODO that says nobody is), so we run
Carbon with [`servers/velocity/carbon-vanish.patch`](../servers/velocity/carbon-vanish.patch), which
asks `mods/vanish`. To rebuild it for a new Carbon (JDK 25): clone
[Carbon](https://github.com/Hexaoxide/Carbon) at the release tag, `git apply` the patch, set
`projectVersion=<version>-vanish.<n>` in `gradle.properties`, then
`./gradlew :carbonchat-velocity:build -x test -x spotlessCheck`, upload
`velocity/build/libs/carbonchat-velocity-<version>-vanish.<n>.jar` to the world store under `mods/`
and change the URL in the velocity `server.toml`. `mods/vanish` is rebuilt like `mods/warmup`
(version in `gradle.properties` and `Vanish.java`).

## Backups

Every night at 04:00, [restic](https://restic.net) backs up `/home/mc/servers` (without jars and
logs) and a [MariaDB](https://mariadb.org) dump to the bucket, with saving paused during the
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
| fpv spawn | `/execute in maps:tutorial run setworldspawn ~ ~ ~` in game; new players start there (see [Maps](maps.md#tutorial)) |
