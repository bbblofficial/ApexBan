# ApexBan

Moderation core for Bukkit/Spigot/Paper (1.8.x - 1.21), BungeeCord and Velocity with one shared
MySQL/MariaDB (or per-server SQLite) database.

## Commands
| Command | Permission |
|---|---|
| `/ban <player> [duration] [reason...]` (no duration = permanent) | `apexban.ban` |
| `/mute <player> [duration] [reason...]` (no duration = permanent) | `apexban.mute` |
| `/kick <player> [reason...]` | `apexban.kick` |
| `/unban <player>` (alias `/pardon`), `/unmute <player>` | `apexban.unban`, `apexban.unmute` |
| `/apexban reload\|version` | `apexban.admin` |

Durations: `30m`, `2h`, `7d`, `1d12h`, `2w`, `1mo`, `1y`. Add `-s` for a silent punishment (needs `apexban.silent`; staff with `apexban.notify` see it).

## Build
```
gradle build
```
Artifacts: `bukkit/build/libs/ApexBan-Bukkit-*.jar`, `bungee/build/libs/ApexBan-Bungee-*.jar`,
`velocity/build/libs/ApexBan-Velocity-*.jar`. GitHub Actions (`.github/workflows/build.yml`) builds
all three on every push and attaches them to a release on `v*` tags.

## Network setup
Point every backend and the proxy at the same MySQL database and give each a unique `server-id`
in `config.yml`. Punishments are picked up by other servers within `sync.poll-interval-seconds`.

## Troubleshooting

**`ApexBan failed to start ... The filename, directory name, or volume label syntax is incorrect`**
Spigot/CraftBukkit 1.8 ships an old SQLite driver that cannot parse `?journal_mode=...` URL
suffixes. Fixed in this version (plain `jdbc:sqlite:<path>` URL, pragmas run as statements).

**`You don't have permission` after adding permissions in LuckPerms**
If ApexBan did not enable, `/ban`, `/kick`, `/pardon` fall back to Bukkit's built-in commands, which
need `bukkit.command.ban.player` etc. Check the console shows `ApexBan ... enabled`. This version
also replaces the built-in commands when it loads. Grant the nodes, e.g.:

```
/lp group admin permission set apexban.* true
/lp group admin permission set apexban.exempt.* true   (staff that cannot be punished)
```
