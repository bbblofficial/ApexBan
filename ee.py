#!/usr/bin/env python3
"""
fixer.py - Applies all ApexBan -> MineStormBan changes to the project.

Place this file in the project root (next to settings.gradle.kts) and run:
    python fixer.py

It will:
  1. Rename package directories dev/apexban -> dev/minestormban
  2. Rewrite package/import/class references in every .java file
  3. Rename Apex* class files to MineStorm* class files
  4. Rewrite Gradle files (group, artifact names, relocate targets)
  5. Rewrite plugin.yml / bungee.yml / velocity-plugin.json
  6. Rewrite config.yml / messages.yml / layout.yml with the new theme
  7. Add /msban and /minestormban command handling
"""

import os
import re
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def log(msg):
    print(f"[fixer] {msg}")


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def write(path: Path, content: str):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def find_all(suffix: str):
    return [p for p in ROOT.rglob(f"*{suffix}") if ".git" not in p.parts]


# ---------------------------------------------------------------------------
# 1. Java source transformation
# ---------------------------------------------------------------------------

# Order matters: longer / more specific names first.
CLASS_RENAMES = [
    ("ApexBanBukkit",       "MineStormBanBukkit"),
    ("ApexBanBungee",       "MineStormBanBungee"),
    ("ApexBanVelocity",     "MineStormBanVelocity"),
    ("ApexCore",            "MineStormCore"),
    ("ApexSender",          "MineStormSender"),
    ("ApexPlayer",          "MineStormPlayer"),
    ("ApexLogger",          "MineStormLogger"),
]

PACKAGE_RENAMES = [
    ("dev.apexban", "dev.minestormban"),
    ("dev/apexban", "dev/minestormban"),
]

PERMISSION_RENAMES = [
    ('"apexban.', '"minestormban.'),
    ("'apexban.", "'minestormban."),
    ('apexban.notify', 'minestormban.notify'),
    ('apexban.silent', 'minestormban.silent'),
    ('apexban.admin', 'minestormban.admin'),
    ('apexban.ban', 'minestormban.ban'),
    ('apexban.mute', 'minestormban.mute'),
    ('apexban.kick', 'minestormban.kick'),
    ('apexban.unban', 'minestormban.unban'),
    ('apexban.unmute', 'minestormban.unmute'),
    ('apexban.exempt', 'minestormban.exempt'),
    ('apexban.*', 'minestormban.*'),
]


def transform_java_content(text: str) -> str:
    # packages / imports / paths
    for old, new in PACKAGE_RENAMES:
        text = text.replace(old, new)
    # class names
    for old, new in CLASS_RENAMES:
        text = re.sub(rf"\b{old}\b", new, text)
    # permission nodes (longest first so apexban.exempt.* wins over apexban.exempt)
    for old, new in sorted(PERMISSION_RENAMES, key=lambda x: -len(x[0])):
        text = text.replace(old, new)
    # plugin id / libs package
    text = text.replace('"apexban"', '"minestormban"')
    text = text.replace('libs.apexban', 'libs.minestormban')
    text = text.replace('dev.apexban', 'dev.minestormban')
    return text


def move_java_packages():
    """Move src/main/java/dev/apexban -> src/main/java/dev/minestormban everywhere."""
    moved = 0
    for src_root in [p for p in ROOT.rglob("src/main/java/dev/apexban") if p.is_dir()]:
        dst_root = src_root.parent / "minestormban"
        if dst_root.exists():
            # merge
            for item in src_root.iterdir():
                target = dst_root / item.name
                if target.exists():
                    log(f"  merge skip (exists): {target}")
                else:
                    shutil.move(str(item), str(target))
            shutil.rmtree(src_root, ignore_errors=True)
        else:
            shutil.move(str(src_root), str(dst_root))
        moved += 1
    if moved:
        log(f"Moved {moved} java package dir(s)")


def rename_java_class_files():
    """Rename Apex*.java files to MineStorm*.java."""
    renamed = 0
    for path in find_all(".java"):
        new_name = path.name
        for old, new in CLASS_RENAMES:
            if new_name.startswith(old):
                new_name = new_name.replace(old, new, 1)
                break
        if new_name != path.name:
            target = path.with_name(new_name)
            if target.exists():
                log(f"  target exists, overwriting: {target}")
                target.unlink()
            path.rename(target)
            renamed += 1
    if renamed:
        log(f"Renamed {renamed} java class file(s)")


def rewrite_java_files():
    count = 0
    for path in find_all(".java"):
        original = read(path)
        updated = transform_java_content(original)
        if updated != original:
            write(path, updated)
            count += 1
    log(f"Rewrote {count} java file(s)")


# ---------------------------------------------------------------------------
# 2. CommandHandler: add /msban and /minestormban aliases
# ---------------------------------------------------------------------------

def patch_command_handler():
    targets = [
        p for p in find_all(".java")
        if p.name == "CommandHandler.java"
    ]
    for path in targets:
        text = read(path)
        # switch case
        text = text.replace(
            'case "apexban" -> admin(sender, args);',
            'case "apexban", "msban", "minestormban" -> admin(sender, args);',
        )
        # tab-complete check
        text = text.replace(
            'if (cmd.equals("apexban")) {',
            'if (cmd.equals("apexban") || cmd.equals("msban") || cmd.equals("minestormban")) {',
        )
        # permission lookup fallback used by tabComplete guard
        text = text.replace(
            'cmd.equals("apexban") ? "admin" : cmd',
            '(cmd.equals("apexban") || cmd.equals("msban") || cmd.equals("minestormban")) ? "admin" : cmd',
        )
        write(path, text)
        log(f"Patched CommandHandler: {path.relative_to(ROOT)}")


def patch_bukkit_main():
    targets = [
        p for p in find_all(".java")
        if p.name == "MineStormBanBukkit.java"
    ]
    for path in targets:
        text = read(path)
        text = text.replace(
            '{"ban", "mute", "kick", "unban", "unmute", "apexban"}',
            '{"ban", "mute", "kick", "unban", "unmute", "msban", "minestormban"}',
        )
        text = text.replace(
            '{"ban", "mute", "kick", "unban", "unmute", "apexban"};',
            '{"ban", "mute", "kick", "unban", "unmute", "msban", "minestormban"};',
        )
        write(path, text)
        log(f"Patched Bukkit main: {path.relative_to(ROOT)}")


def patch_bungee_main():
    targets = [
        p for p in find_all(".java")
        if p.name == "MineStormBanBungee.java"
    ]
    for path in targets:
        text = read(path)
        text = text.replace(
            'registerCommand(this, new BungeeCommand(core, "apexban"));',
            'registerCommand(this, new BungeeCommand(core, "msban"));\n'
            '        getProxy().getPluginManager().registerCommand(this, new BungeeCommand(core, "minestormban"));',
        )
        write(path, text)
        log(f"Patched Bungee main: {path.relative_to(ROOT)}")


def patch_velocity_main():
    targets = [
        p for p in find_all(".java")
        if p.name == "MineStormBanVelocity.java"
    ]
    for path in targets:
        text = read(path)
        text = text.replace(
            'register(commands, "apexban");',
            'register(commands, "msban");\n'
            '        register(commands, "minestormban");',
        )
        write(path, text)
        log(f"Patched Velocity main: {path.relative_to(ROOT)}")


# ---------------------------------------------------------------------------
# 3. Gradle files
# ---------------------------------------------------------------------------

def rewrite_gradle_files():
    for path in find_all(".gradle.kts"):
        text = read(path)
        original = text
        text = text.replace('group = "dev.apexban"', 'group = "dev.minestormban"')
        text = text.replace('rootProject.name = "ApexBan"', 'rootProject.name = "MineStormBan"')
        text = text.replace('archiveBaseName.set("ApexBan-', 'archiveBaseName.set("MineStormBan-')
        text = text.replace('"dev.apexban.libs.', '"dev.minestormban.libs.')
        text = text.replace('dev.apexban.libs', 'dev.minestormban.libs')
        if text != original:
            write(path, text)
            log(f"Rewrote {path.relative_to(ROOT)}")


# ---------------------------------------------------------------------------
# 4. Resource files
# ---------------------------------------------------------------------------

PLUGIN_YML = """name: MineStormBan
version: '${version}'
main: dev.minestormban.bukkit.MineStormBanBukkit
api-version: '1.13'
author: MineStormBan
description: Enterprise moderation core - bans, mutes and kicks with a shared global database.
commands:
  ban:
    description: Ban a player (permanent unless a duration is given)
    usage: /<command> [-s] <player> [duration] [reason...]
  mute:
    description: Mute a player (permanent unless a duration is given)
    usage: /<command> [-s] <player> [duration] [reason...]
  kick:
    description: Kick a player
    usage: /<command> [-s] <player> [reason...]
  unban:
    description: Remove a ban
    usage: /<command> [-s] <player>
    aliases: [pardon]
  unmute:
    description: Remove a mute
    usage: /<command> [-s] <player>
  msban:
    description: MineStormBan administration
    usage: /<command> <reload|version>
  minestormban:
    description: MineStormBan administration
    usage: /<command> <reload|version>
permissions:
  minestormban.ban:
    description: Use /ban
    default: op
  minestormban.mute:
    description: Use /mute
    default: op
  minestormban.kick:
    description: Use /kick
    default: op
  minestormban.unban:
    description: Use /unban
    default: op
  minestormban.unmute:
    description: Use /unmute
    default: op
  minestormban.silent:
    description: Use the -s (silent) flag
    default: op
  minestormban.notify:
    description: See silent punishments and staff-only announcements
    default: op
  minestormban.admin:
    description: Use /msban and /minestormban
    default: op
  minestormban.exempt.ban:
    description: Cannot be banned
    default: false
  minestormban.exempt.mute:
    description: Cannot be muted
    default: false
  minestormban.exempt.kick:
    description: Cannot be kicked
    default: false
  minestormban.exempt.*:
    description: Cannot be banned, muted or kicked
    default: false
    children:
      minestormban.exempt.ban: true
      minestormban.exempt.mute: true
      minestormban.exempt.kick: true
  minestormban.*:
    description: All MineStormBan staff permissions
    default: op
    children:
      minestormban.ban: true
      minestormban.mute: true
      minestormban.kick: true
      minestormban.unban: true
      minestormban.unmute: true
      minestormban.silent: true
      minestormban.notify: true
      minestormban.admin: true
"""

BUNGEE_YML = """name: MineStormBan
version: '${version}'
main: dev.minestormban.bungee.MineStormBanBungee
author: MineStormBan
description: Enterprise moderation core - bans, mutes and kicks with a shared global database.
"""

VELOCITY_JSON = """{
  "id": "minestormban",
  "name": "MineStormBan",
  "version": "${version}",
  "description": "Enterprise moderation core - bans, mutes and kicks with a shared global database.",
  "authors": ["MineStormBan"],
  "dependencies": [],
  "main": "dev.minestormban.velocity.MineStormBanVelocity"
}
"""

CONFIG_YML = """# ============================================================================
#  MineStormBan - config.yml
#  Shared by the Bukkit/Spigot/Paper, BungeeCord and Velocity builds.
#  Run every server (and the proxy) against the SAME database to share bans.
# ============================================================================

# Unique name of this server/proxy.
server-id: "lobby-1"

# ----------------------------------------------------------------------------
#  Database
# ----------------------------------------------------------------------------
database:
  # "sqlite" - single file, best for one standalone server.
  # "mysql"  - MySQL / MariaDB, REQUIRED for networks (proxy + backends).
  type: "sqlite"

  # Prefix for table names. Letters, digits and underscores only.
  table-prefix: "msban_"

  # When the database is unreachable during login:
  #   true  -> let the player in (recommended)
  #   false -> deny the login with the "login.database-error" message
  fail-open: true

  sqlite:
    file: "minestormban.db"

  mysql:
    host: "127.0.0.1"
    port: 3306
    database: "minestormban"
    username: "minestormban"
    password: "change-me"
    use-ssl: false
    pool-size: 8
    connection-timeout-ms: 10000

# ----------------------------------------------------------------------------
#  Cross-server synchronisation
# ----------------------------------------------------------------------------
sync:
  # How often (seconds) this server checks the database for punishments issued
  # elsewhere, so online players are kicked/muted/unmuted network-wide.
  poll-interval-seconds: 5

# ----------------------------------------------------------------------------
#  Time & date formatting
# ----------------------------------------------------------------------------
timezone: "UTC"
date-format: "dd/MM/yyyy HH:mm:ss"

# ----------------------------------------------------------------------------
#  Default reasons
# ----------------------------------------------------------------------------
defaults:
  ban-reason: "Violating the server rules"
  mute-reason: "Violating the chat rules"
  kick-reason: "Kicked by a staff member"

# ----------------------------------------------------------------------------
#  Limits
# ----------------------------------------------------------------------------
limits:
  max-duration: "5y"

# ----------------------------------------------------------------------------
#  Announcements
# ----------------------------------------------------------------------------
announcements:
  ban: true
  mute: true
  kick: true
  unban: true
  unmute: true

# ----------------------------------------------------------------------------
#  Mute behaviour
# ----------------------------------------------------------------------------
mute:
  blocked-commands:
    - "msg"
    - "tell"
    - "w"
    - "whisper"
    - "r"
    - "reply"
    - "me"
    - "say"
    - "mail"
    - "t"
    - "pm"
"""

MESSAGES_YML = """# ============================================================================
#  MineStormBan - messages.yml
#  Default theme: Aqua (&b) and White (&f)
#  Uses legacy & color codes only (no MiniMessage <> tags)
# ============================================================================

prefix: "&8[&bMine&fStorm&bBan&8] &7"

# --------------------------------------------------------------------- general
no-permission: "%prefix%&cYou do not have permission to do that."
error: "%prefix%&cAn internal error occurred. Check the console for details."
player-not-found: "%prefix%&cPlayer &f%player%&c was not found. They must have joined at least once."
player-not-online: "%prefix%&cPlayer &f%player%&c is not online."
invalid-name: "%prefix%&f%input%&c is not a valid player name."
invalid-duration: "%prefix%&f%input%&c is not a valid duration. Use e.g. &f30m&c, &f2h&c, &f7d&c, &f1w&c, &f1mo&c, &f1d12h&c."
duration-too-long: "%prefix%&cThat duration is too long. The maximum is &f%max%&c."
exempt: "%prefix%&cYou cannot punish &f%player%&c."
already-banned: "%prefix%&f%player%&c is already banned."
already-muted: "%prefix%&f%player%&c is already muted."
not-banned: "%prefix%&f%player%&c is not banned."
not-muted: "%prefix%&f%player%&c is not muted."

time:
  permanent: "Permanent"
  never: "Never"

# ----------------------------------------------------------------------- usage
usage:
  ban: "%prefix%&bUsage: &f/ban [-s] <player> [duration] [reason...]\\n&7No duration = permanent. Example: &f/ban Steve 7d Griefing"
  mute: "%prefix%&bUsage: &f/mute [-s] <player> [duration] [reason...]\\n&7No duration = permanent. Example: &f/mute Steve 2h Spamming"
  kick: "%prefix%&bUsage: &f/kick [-s] <player> [reason...]"
  unban: "%prefix%&bUsage: &f/unban [-s] <player>"
  unmute: "%prefix%&bUsage: &f/unmute [-s] <player>"

# ------------------------------------------------------- feedback to the staff
success:
  ban: "%prefix%&bBanned &f%player%&b (&f%duration%&b) for &f%reason%&b. &8#%id%"
  mute: "%prefix%&bMuted &f%player%&b (&f%duration%&b) for &f%reason%&b. &8#%id%"
  kick: "%prefix%&bKicked &f%player%&b for &f%reason%&b."
  unban: "%prefix%&bUnbanned &f%player%&b."
  unmute: "%prefix%&bUnmuted &f%player%&b."

# ------------------------------------------------------------------ broadcasts
broadcast:
  silent-prefix: "&8[&7Silent&8] "
  ban:
    permanent: "%prefix%&f%player%&7 was &bpermanently banned&7 by &f%operator%&7. Reason: &b%reason%"
    temporary: "%prefix%&f%player%&7 was &bbanned&7 for &f%duration%&7 by &f%operator%&7. Reason: &b%reason%"
  mute:
    permanent: "%prefix%&f%player%&7 was &bpermanently muted&7 by &f%operator%&7. Reason: &b%reason%"
    temporary: "%prefix%&f%player%&7 was &bmuted&7 for &f%duration%&7 by &f%operator%&7. Reason: &b%reason%"
  kick: "%prefix%&f%player%&7 was &bkicked&7 by &f%operator%&7. Reason: &b%reason%"
  unban: "%prefix%&f%player%&7 was &bunbanned&7 by &f%operator%&7."
  unmute: "%prefix%&f%player%&7 was &bunmuted&7 by &f%operator%&7."

# ---------------------------------------------------------- mute notifications
mute:
  notify:
    permanent: "%prefix%&bYou have been permanently muted by &f%operator%&b.\\n&7Reason: &f%reason%"
    temporary: "%prefix%&bYou have been muted for &f%duration%&b by &f%operator%&b.\\n&7Reason: &f%reason%\\n&7Expires: &f%expires%"
  blocked:
    permanent: "%prefix%&bYou are permanently muted.\\n&7Reason: &f%reason%"
    temporary: "%prefix%&bYou are muted for another &f%remaining%&b.\\n&7Reason: &f%reason%"
  blocked-command: "%prefix%&bYou cannot use that command while muted."

unmute:
  notify: "%prefix%&bYou have been unmuted by &f%operator%&b."

# ----------------------------------------------------------------------- login
login:
  database-error: "&bThe moderation database is currently unavailable.\\n&7Please try again in a moment."

# ----------------------------------------------------------------- /msban
admin:
  reload-success: "%prefix%&bConfiguration reloaded. &7(Database settings require a restart.)"
  reload-failed: "%prefix%&cReload failed. Check the console for details."
  version: "%prefix%&7MineStormBan &fv%version%&7 on &f%platform%&7 | server-id &f%server%"
  help: "%prefix%&b/msban reload &7- reload configuration\\n%prefix%&b/msban version &7- show version info"
"""

LAYOUT_YML = """# ============================================================================
#  MineStormBan - layout.yml
#  Default theme: Aqua (&b) and White (&f)
#  Uses legacy & color codes only (no MiniMessage <> tags)
# ============================================================================

ban:
  permanent:
    - "&b&lYOU ARE PERMANENTLY BANNED"
    - ""
    - "&7Player: &f%player%"
    - "&7Reason: &b%reason%"
    - "&7Banned by: &f%operator%"
    - "&7Issued: &f%date%"
    - ""
    - "&7Ban ID: &f#%id%"
    - "&8Appeal at &bhttps://example.com/appeal&8 and quote your ban ID."

  temporary:
    - "&b&lYOU ARE TEMPORARILY BANNED"
    - ""
    - "&7Player: &f%player%"
    - "&7Reason: &b%reason%"
    - "&7Banned by: &f%operator%"
    - ""
    - "&7Duration: &f%duration%"
    - "&7Time left: &b%remaining%"
    - "&7Expires: &f%expires%"
    - ""
    - "&7Ban ID: &f#%id%"
    - "&8Appeal at &bhttps://example.com/appeal"

kick:
  - "&b&lYou have been kicked"
  - ""
  - "&7Reason: &b%reason%"
  - "&7Kicked by: &f%operator%"
  - ""
  - "&8You may reconnect immediately."
"""


def write_resource(rel_path: str, content: str):
    target = ROOT / rel_path
    write(target, content)
    log(f"Wrote {rel_path}")


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    if not (ROOT / "settings.gradle.kts").exists():
        log("ERROR: settings.gradle.kts not found. Run this script from the project root.")
        sys.exit(1)

    log("Step 1/7: moving java package dirs ...")
    move_java_packages()

    log("Step 2/7: renaming java class files ...")
    rename_java_class_files()

    log("Step 3/7: rewriting java file contents ...")
    rewrite_java_files()

    log("Step 4/7: patching command handlers ...")
    patch_command_handler()
    patch_bukkit_main()
    patch_bungee_main()
    patch_velocity_main()

    log("Step 5/7: rewriting gradle files ...")
    rewrite_gradle_files()

    log("Step 6/7: rewriting resource descriptors ...")
    write_resource("bukkit/src/main/resources/plugin.yml", PLUGIN_YML)
    write_resource("bungee/src/main/resources/bungee.yml", BUNGEE_YML)
    write_resource("velocity/src/main/resources/velocity-plugin.json", VELOCITY_JSON)

    log("Step 7/7: rewriting config/messages/layout ...")
    write_resource("core/src/main/resources/config.yml", CONFIG_YML)
    write_resource("core/src/main/resources/messages.yml", MESSAGES_YML)
    write_resource("core/src/main/resources/layout.yml", LAYOUT_YML)

    log("Done. Now run: gradle clean build")


if __name__ == "__main__":
    main()