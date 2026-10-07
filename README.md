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
