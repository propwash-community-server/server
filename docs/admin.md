# Admin

Running the production server. The tools are the same as in the [README](../README.md#setup), plus
[age](https://github.com/FiloSottile/age) for creating a key.

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
| `s3.env.sops` | `S3_ENDPOINT`, `S3_BUCKET`, `S3_ACCESS_KEY`, `S3_SECRET_KEY` (world store, for [`upload-world`](../tools/upload_world.py)) |

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
([Velocitab](https://modrinth.com/plugin/velocitab), on the proxy, configured in
[`tab_groups.yml`](../servers/velocity/files/plugins/velocitab/tab_groups.yml)).

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

[Flan](https://modrinth.com/mod/flan) runs on fpv and survival. On fpv, `[flan] deny` in its
`server.toml` stops everyone from breaking, placing or using anything, on every map. Builders and
staff run `/flan bypass` to edit and run it again to turn it off. On survival, players claim land with
a golden hoe.

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
`servers/plots/server.toml`, applied from the second deploy (FabricPlots writes its config on
first start). The grid is generated with the plot size, so **changing `plot-size` needs a fresh
plots world**; keep the spawn in the datapack and `spawn-x/y/z` at the same spot.

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
Its commands are `/ban`, `/tempban`, `/mute`, `/kick`, `/unban` and `/history`.

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

## Backups

Every night at 04:00, [restic](https://restic.net) backs up `/home/mc/servers` (without jars and
logs) and a [MariaDB](https://mariadb.org) dump to the bucket, with saving paused during the
snapshot. It keeps 7 daily, 4 weekly and 6 monthly snapshots.

| Task (as root) | Command |
|---|---|
| Back up now | `systemctl start mc-backup` (log: `journalctl -u mc-backup`) |
| List snapshots | `set -a; . /etc/mc-backup.env; restic snapshots` |
| Restore a world | [`mc-restore`](../deploy/files/mc-restore) `<server> [snapshot]` (the old world is kept as `world.before-restore-<time>`) |
| Restore permissions + bans | `restic restore latest --target / --include /var/backups/mariadb.sql && mysql < /var/backups/mariadb.sql` |

## On the VPS

| Task | How |
|---|---|
| Console | [`mc-console`](../deploy/files/mc-console) `<name>` |
| Status / logs | `systemctl status mc@<name>` |
| Hub spawn | [`/setspawn`](https://essentialsx.net) in game; players are sent there on join |
