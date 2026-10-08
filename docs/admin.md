# Admin

Running the production server. The tools are the same as in the [README](../README.md#setup), plus
[age](https://github.com/FiloSottile/age) for creating a key.

## Access

1. Create an age key at `~/.config/sops/age/keys.txt` (Windows: `%AppData%\sops\age\keys.txt`):
   `age-keygen -o <that path>`.
2. Add its public key to [`.sops.yaml`](../.sops.yaml). Someone with access then runs `sops updatekeys secrets/*.sops`.
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

## Bans

[LibertyBans](https://github.com/A248/LibertyBans) runs on the proxy and applies to every server.
Its commands are `/ban`, `/tempban`, `/mute`, `/kick`, `/unban` and `/history`.

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
