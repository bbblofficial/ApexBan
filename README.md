# MineStormBan

Moderation core for Bukkit/Spigot/Paper (1.8.x - 1.21), BungeeCord and Velocity with one shared
MySQL/MariaDB (or a per-server SQLite) database. Created by Muvixo.

## Commands

| Command | Permission |
|---|---|
| `/ban [-s] [-g\|-l] <player> [duration] [reason...]` (no duration = permanent) | `minestormban.ban` |
| `/mute [-s] [-g\|-l] <player> [duration] [reason...]` | `minestormban.mute` |
| `/kick [-s] <player> [reason...]` | `minestormban.kick` |
| `/unban [-s] [-g] <player>` (alias `/pardon`), `/unmute [-s] [-g] <player>` | `minestormban.unban`, `minestormban.unmute` |
| `/msban reload\|version\|creator` (alias `/minestormban`; `creator` is public) | `minestormban.admin` |

Durations: `30m`, `2h`, `7d`, `1d12h`, `2w`, `1mo`, `1y`.

Flags: `-s` silent (`minestormban.silent`, staff with `minestormban.notify` still see it),
`-g` global / everywhere (`minestormban.global`), `-l` this server only.

## Per-server bans (scope)

By default a ban or mute is **only enforced on the server where it was issued**:
ban a player on `survival` and he can still join `lobby`.

```yaml
# config.yml
server-id: survival      # must be UNIQUE on every server that uses the same database
scope:
  ban: server            # server | global
  mute: server
```

* `/ban Steve 7d Griefing` - uses `scope.ban` (default: this server only)
* `/ban -g Steve Hacking` - global, enforced on every server sharing the database
* `/ban -l Steve` - forces "this server only" even if `scope.ban` is `global`
* `/unban Steve` removes bans that apply on this server (global ones included); a ban that
  belongs to another server is left alone. `/unban -g Steve` removes it everywhere.

Existing rows from older versions are treated as server-scoped (tied to the `server` they were
issued on). To make them network-wide again run the optional `UPDATE` at the bottom of
`schema_migration.sql`.

Install the plugin on **every backend server** (each with its own `server-id`, all pointing at the
same MySQL database). A proxy install counts as its own "server" with its own `server-id`.

## Auto-missing

* **Database** (`database.auto-repair: true`): on every start missing tables are created, missing
  columns are added with safe defaults and missing indexes are created, so databases from older
  versions upgrade by themselves. If a table or column disappears while the server is running, the
  failing query repairs the schema and is retried once. Look for `[auto-missing]` in the console.
* **Config files**: keys that exist in the bundled `config.yml`, `messages.yml` or `layout.yml` but
  not in yours are written into your file automatically (your values are never changed; the old
  file is saved as `<name>.bak`).

`schema_migration.sql` is only needed if you want to prepare a database by hand.

## Build

```
gradle build
```

Artifacts: `bukkit/build/libs/MineStormBan-Bukkit-*.jar`, `bungee/build/libs/MineStormBan-Bungee-*.jar`,
`velocity/build/libs/MineStormBan-Velocity-*.jar`. GitHub Actions (`.github/workflows/build.yml`)
builds all three on every push and attaches them to a release on `v*` tags.

## Troubleshooting

**`The filename, directory name, or volume label syntax is incorrect`** - Spigot/CraftBukkit 1.8
ships an old SQLite driver that cannot parse `?journal_mode=...` URL suffixes. The plugin uses a
plain `jdbc:sqlite:<path>` URL and runs pragmas as statements.

**`You don't have permission` after adding permissions in LuckPerms** - if MineStormBan did not
enable, `/ban`, `/kick`, `/pardon` fall back to Bukkit's built-in commands. Check the console shows
`MineStormBan ... enabled`. Grant the nodes, e.g.:

```
/lp group admin permission set minestormban.* true
/lp group admin permission set minestormban.exempt.* true   (staff that cannot be punished)
```

**A ban on one server also bans the player on another** - two servers share the same `server-id`.
Give each server a different one.

---

## راهنمای کوتاه (فارسی)

* بن/میوت به‌صورت پیش‌فرض **فقط روی همون سروری که توش دادی** اعمال می‌شه (`scope.ban: server`).
* `-g` یعنی روی همه‌ی سرورها، `-l` یعنی فقط همین سرور. `/unban -g` بن رو از همه‌جا برمی‌داره.
* `server-id` هر سرور باید یکتا باشه، وگرنه بن بین اون سرورها مشترک می‌شه.
* **Auto-missing**: جدول، ستون و ایندکس ناقص دیتابیس و کلیدهای ناقص `config.yml` / `messages.yml` /
  `layout.yml` خودکار ساخته می‌شن (لاگ: `[auto-missing]`).
