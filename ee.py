#!/usr/bin/env python3
"""
fixer.py - ApexBan -> MineStormBan migration with smart auto-merge.

Run from project root:   python fixer.py
Then build:              gradle clean build

Features:
  * Renames packages, classes, permissions, gradle artifacts.
  * Rewrites resource descriptors (plugin.yml, bungee.yml, velocity-plugin.json).
  * Auto-merges YAML configs (messages.yml, layout.yml, config.yml):
      - Missing keys from the bundled defaults are added.
      - Existing user values are preserved.
      - Comments are re-emitted from a template.
  * Auto-merges the SQL schema:
      - Adds missing tables.
      - Adds missing columns to existing tables via ALTER TABLE.
      - Never drops data.
  * Adds /msban, /minestormban.
  * Adds PUBLIC /msban creator and /minestormban creator (no permission needed).
  * Updates .github/workflows/build.yml to the new artifact names.
"""

import os
import re
import shutil
import sys
from pathlib import Path

try:
    import yaml  # PyYAML
except ImportError:
    print("[fixer] PyYAML is required. Install with: pip install pyyaml")
    sys.exit(1)

ROOT = Path(__file__).resolve().parent
BACKUP_DIR = ROOT / ".fixer-backup"


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


def backup(path: Path):
    if not path.exists():
        return
    rel = path.relative_to(ROOT)
    target = BACKUP_DIR / rel
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(path, target)


def find_all(suffix: str):
    return [p for p in ROOT.rglob(f"*{suffix}")
            if ".git" not in p.parts and ".fixer-backup" not in p.parts]


# ---------------------------------------------------------------------------
# 1. Java source transformation
# ---------------------------------------------------------------------------

CLASS_RENAMES = [
    ("ApexBanBukkit",   "MineStormBanBukkit"),
    ("ApexBanBungee",   "MineStormBanBungee"),
    ("ApexBanVelocity", "MineStormBanVelocity"),
    ("ApexCore",        "MineStormCore"),
    ("ApexSender",      "MineStormSender"),
    ("ApexPlayer",      "MineStormPlayer"),
    ("ApexLogger",      "MineStormLogger"),
]

PACKAGE_RENAMES = [
    ("dev.apexban", "dev.minestormban"),
    ("dev/apexban", "dev/minestormban"),
]


def transform_java_content(text: str) -> str:
    for old, new in PACKAGE_RENAMES:
        text = text.replace(old, new)
    for old, new in CLASS_RENAMES:
        text = re.sub(rf"\b{old}\b", new, text)
    text = re.sub(r'"apexban\.', '"minestormban.', text)
    text = text.replace('"apexban"', '"minestormban"')
    text = text.replace('libs.apexban', 'libs.minestormban')
    return text


def move_java_packages():
    moved = 0
    for src_root in [p for p in ROOT.rglob("src/main/java/dev/apexban") if p.is_dir()]:
        dst_root = src_root.parent / "minestormban"
        if dst_root.exists():
            for item in src_root.iterdir():
                target = dst_root / item.name
                if not target.exists():
                    shutil.move(str(item), str(target))
            shutil.rmtree(src_root, ignore_errors=True)
        else:
            shutil.move(str(src_root), str(dst_root))
        moved += 1
    if moved:
        log(f"Moved {moved} java package dir(s)")


def rename_java_class_files():
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
            backup(path)
            write(path, updated)
            count += 1
    log(f"Rewrote {count} java file(s)")


# ---------------------------------------------------------------------------
# 2. Command handler patches
# ---------------------------------------------------------------------------

def patch_command_handler():
    """Rewrite CommandHandler with the public creator command and correct dispatch."""
    for path in find_all(".java"):
        if path.name != "CommandHandler.java":
            continue
        text = read(path)
        original = text

        # --- dispatch: apexban -> msban / minestormban
        text = text.replace(
            'case "apexban" -> admin(sender, args);',
            'case "apexban", "msban", "minestormban" -> admin(sender, args);',
        )

        # --- in admin(), handle "creator" BEFORE the permission check
        #     so it is public (any member can use it).
        #     We replace the whole permission guard at the top of admin().
        text = text.replace(
            '''private void admin(MineStormSender sender, String[] args) {
        if (!sender.hasPermission("minestormban.admin")) {
            sender.sendMessage(core.text("no-permission"));
            return;
        }
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "help";
        switch (sub) {''',
            '''private void admin(MineStormSender sender, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "help";

        // /msban creator (and /minestormban creator) is PUBLIC: any player can
        // run it to see who made the plugin. No permission required.
        if (sub.equals("creator") || sub.equals("author") || sub.equals("credit")) {
            sender.sendMessage(core.text("admin.creator"));
            return;
        }

        if (!sender.hasPermission("minestormban.admin")) {
            sender.sendMessage(core.text("no-permission"));
            return;
        }
        switch (sub) {''',
        )

        # If the source still has the old dispatch inside the switch, remove it.
        text = text.replace(
            '                case "creator", "author", "credit" -> sender.sendMessage(core.text("admin.creator"));\n',
            '',
        )

        # --- tab complete list
        text = text.replace(
            'return args.length == 1 ? filter(List.of("reload", "version"), current) : List.of();',
            'return args.length == 1 ? filter(List.of("reload", "version", "creator"), current) : List.of();',
        )

        # --- tab complete guard (creator is public; the rest need admin)
        text = text.replace(
            'if (args.length == 0 || !sender.hasPermission("minestormban." + (cmd.equals("apexban") ? "admin" : cmd))) {',
            '''if (args.length == 0) {
            return new ArrayList<>();
        }
        boolean isAdminCmd = cmd.equals("apexban") || cmd.equals("msban") || cmd.equals("minestormban");
        // /msban creator is public, so allow tab completion of "creator" for everyone.
        boolean publicCreatorOnly = false;
        if (isAdminCmd) {
            if (args.length == 1) {
                // let anyone see the "creator" suggestion, but only admins see reload/version
                if (!sender.hasPermission("minestormban.admin")) {
                    publicCreatorOnly = true;
                }
            } else {
                // second arg typed: only allow creator for non-admins
                String first = args[0].toLowerCase(Locale.ROOT);
                if (first.equals("creator") || first.equals("author") || first.equals("credit")) {
                    publicCreatorOnly = true;
                }
            }
        }
        if (isAdminCmd) {
            if (args.length == 1) {
                List<String> opts = new ArrayList<>();
                if (sender.hasPermission("minestormban.admin")) {
                    opts.add("reload");
                    opts.add("version");
                }
                opts.add("creator");
                return filter(opts, args[0].toLowerCase(Locale.ROOT));
            }
            return new ArrayList<>();
        }
        if (!sender.hasPermission("minestormban." + cmd)) {
            return new ArrayList<>();
        }''',
        )

        if text != original:
            backup(path)
            write(path, text)
            log(f"Patched CommandHandler: {path.relative_to(ROOT)}")


def patch_bukkit_main():
    for path in find_all(".java"):
        if path.name != "MineStormBanBukkit.java":
            continue
        text = read(path)
        original = text
        text = re.sub(
            r'\{"ban", "mute", "kick", "unban", "unmute", "apexban"\}',
            '{"ban", "mute", "kick", "unban", "unmute", "msban", "minestormban"}',
            text,
        )
        if text != original:
            backup(path)
            write(path, text)
            log(f"Patched Bukkit main: {path.relative_to(ROOT)}")


def patch_bungee_main():
    for path in find_all(".java"):
        if path.name != "MineStormBanBungee.java":
            continue
        text = read(path)
        original = text
        text = text.replace(
            'registerCommand(this, new BungeeCommand(core, "apexban"));',
            'registerCommand(this, new BungeeCommand(core, "msban"));\n'
            '        getProxy().getPluginManager().registerCommand(this, new BungeeCommand(core, "minestormban"));',
        )
        if text != original:
            backup(path)
            write(path, text)
            log(f"Patched Bungee main: {path.relative_to(ROOT)}")


def patch_velocity_main():
    for path in find_all(".java"):
        if path.name != "MineStormBanVelocity.java":
            continue
        text = read(path)
        original = text
        text = text.replace(
            'register(commands, "apexban");',
            'register(commands, "msban");\n'
            '        register(commands, "minestormban");',
        )
        if text != original:
            backup(path)
            write(path, text)
            log(f"Patched Velocity main: {path.relative_to(ROOT)}")


# ---------------------------------------------------------------------------
# 3. Gradle files + GitHub workflow
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
            backup(path)
            write(path, text)
            log(f"Rewrote {path.relative_to(ROOT)}")


def rewrite_github_workflow():
    path = ROOT / ".github" / "workflows" / "build.yml"
    if not path.exists():
        log("No .github/workflows/build.yml found, skipping")
        return
    text = read(path)
    original = text
    text = text.replace("name: Build ApexBan", "name: Build MineStormBan")
    text = text.replace("ApexBan-Bukkit", "MineStormBan-Bukkit")
    text = text.replace("ApexBan-Bungee", "MineStormBan-Bungee")
    text = text.replace("ApexBan-Velocity", "MineStormBan-Velocity")
    if text != original:
        backup(path)
        write(path, text)
        log("Rewrote .github/workflows/build.yml")


# ---------------------------------------------------------------------------
# 4. Resource descriptors
# ---------------------------------------------------------------------------

PLUGIN_YML = """name: MineStormBan
version: '${version}'
main: dev.minestormban.bukkit.MineStormBanBukkit
api-version: '1.13'
author: Muvixo
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
    description: MineStormBan administration (creator is public)
    usage: /<command> <reload|version|creator>
  minestormban:
    description: MineStormBan administration (creator is public)
    usage: /<command> <reload|version|creator>
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
    description: Use /msban and /minestormban (creator is public and does not need this)
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
author: Muvixo
description: Enterprise moderation core - bans, mutes and kicks with a shared global database.
"""

VELOCITY_JSON = """{
  "id": "minestormban",
  "name": "MineStormBan",
  "version": "${version}",
  "description": "Enterprise moderation core - bans, mutes and kicks with a shared global database.",
  "authors": ["Muvixo"],
  "dependencies": [],
  "main": "dev.minestormban.velocity.MineStormBanVelocity"
}
"""


def write_resource(rel_path, content):
    target = ROOT / rel_path
    if target.exists() and read(target) == content:
        return
    backup(target)
    write(target, content)
    log(f"Wrote {rel_path}")


# ---------------------------------------------------------------------------
# 5. YAML auto-merge (adds missing params, preserves user values)
# ---------------------------------------------------------------------------

def deep_merge(defaults, user):
    """
    Merge `user` on top of `defaults`.
    Returns (merged, added_keys).
    Existing user values always win. Missing keys from defaults are added.
    """
    added = []
    if not isinstance(defaults, dict):
        return user, added
    if not isinstance(user, dict):
        user = {}

    merged = dict(user)
    for key, default_val in defaults.items():
        if key not in merged:
            merged[key] = default_val
            added.append(key)
        elif isinstance(default_val, dict) and isinstance(merged[key], dict):
            sub_merged, sub_added = deep_merge(default_val, merged[key])
            merged[key] = sub_merged
            added.extend(f"{key}.{a}" for a in sub_added)
    return merged, added


def load_yaml(path: Path):
    if not path.exists():
        return {}
    try:
        with path.open("r", encoding="utf-8") as f:
            data = yaml.safe_load(f) or {}
        return data if isinstance(data, dict) else {}
    except Exception as e:
        log(f"  ! Could not parse {path.relative_to(ROOT)}: {e}")
        return {}


def dump_yaml(path: Path, data: dict, header: str = ""):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as f:
        if header:
            f.write(header.rstrip() + "\n\n")
        yaml.safe_dump(
            data, f,
            allow_unicode=True,
            sort_keys=False,
            default_flow_style=False,
            width=4096,
        )


def _flat_keys(d, prefix=""):
    out = []
    for k, v in d.items():
        full = f"{prefix}{k}"
        if isinstance(v, dict):
            out.extend(_flat_keys(v, prefix=f"{full}."))
        else:
            out.append(full)
    return out


def merge_yaml_file(path: Path, defaults: dict, header: str = ""):
    user = load_yaml(path)
    merged, added = deep_merge(defaults, user)

    if not path.exists():
        dump_yaml(path, merged, header)
        log(f"Created {path.relative_to(ROOT)} ({len(_flat_keys(merged))} keys)")
        return

    if added:
        backup(path)
        dump_yaml(path, merged, header)
        log(f"Merged {path.relative_to(ROOT)}: added {len(added)} missing key(s)")
        for a in added:
            log(f"    + {a}")
    else:
        # Still re-emit to ensure the header is present (idempotent).
        backup(path)
        dump_yaml(path, merged, header)
        log(f"{path.relative_to(ROOT)}: up-to-date, header refreshed")


# ---------------------------------------------------------------------------
# Defaults (source of truth for auto-merge)
# ---------------------------------------------------------------------------

DEFAULT_MESSAGES = {
    "prefix": "&8[&bMine&fStorm&bBan&8] &7",

    "no-permission": "%prefix%&cYou do not have permission to do that.",
    "error": "%prefix%&cAn internal error occurred. Check the console for details.",
    "player-not-found": "%prefix%&cPlayer &f%player%&c was not found. They must have joined at least once.",
    "player-not-online": "%prefix%&cPlayer &f%player%&c is not online.",
    "invalid-name": "%prefix%&f%input%&c is not a valid player name.",
    "invalid-duration": "%prefix%&f%input%&c is not a valid duration. Use e.g. &f30m&c, &f2h&c, &f7d&c, &f1w&c, &f1mo&c, &f1d12h&c.",
    "duration-too-long": "%prefix%&cThat duration is too long. The maximum is &f%max%&c.",
    "exempt": "%prefix%&cYou cannot punish &f%player%&c.",
    "already-banned": "%prefix%&f%player%&c is already banned.",
    "already-muted": "%prefix%&f%player%&c is already muted.",
    "not-banned": "%prefix%&f%player%&c is not banned.",
    "not-muted": "%prefix%&f%player%&c is not muted.",

    "time": {
        "permanent": "Permanent",
        "never": "Never",
    },

    "usage": {
        "ban": "%prefix%&bUsage: &f/ban [-s] <player> [duration] [reason...]&7\\nNo duration = permanent.\\n&7Example: &f/ban Steve 7d Griefing",
        "mute": "%prefix%&bUsage: &f/mute [-s] <player> [duration] [reason...]&7\\nNo duration = permanent.\\n&7Example: &f/mute Steve 2h Spamming",
        "kick": "%prefix%&bUsage: &f/kick [-s] <player> [reason...]",
        "unban": "%prefix%&bUsage: &f/unban [-s] <player>",
        "unmute": "%prefix%&bUsage: &f/unmute [-s] <player>",
    },

    "success": {
        "ban": "%prefix%&bBanned &f%player%&b for &f%duration%&b. Reason: &f%reason%&b. &8#%id%",
        "mute": "%prefix%&bMuted &f%player%&b for &f%duration%&b. Reason: &f%reason%&b. &8#%id%",
        "kick": "%prefix%&bKicked &f%player%&b. Reason: &f%reason%&b.",
        "unban": "%prefix%&bUnbanned &f%player%&b.",
        "unmute": "%prefix%&bUnmuted &f%player%&b.",
    },

    "broadcast": {
        "silent-prefix": "&8[&7Silent&8] ",
        "ban": {
            "permanent": "%prefix%&f%player%&7 was &bpermanently banned&7 by &f%operator%&7. Reason: &b%reason%",
            "temporary": "%prefix%&f%player%&7 was &bbanned&7 for &f%duration%&7 by &f%operator%&7. Reason: &b%reason%",
        },
        "mute": {
            "permanent": "%prefix%&f%player%&7 was &bpermanently muted&7 by &f%operator%&7. Reason: &b%reason%",
            "temporary": "%prefix%&f%player%&7 was &bmuted&7 for &f%duration%&7 by &f%operator%&7. Reason: &b%reason%",
        },
        "kick": "%prefix%&f%player%&7 was &bkicked&7 by &f%operator%&7. Reason: &b%reason%",
        "unban": "%prefix%&f%player%&7 was &bunbanned&7 by &f%operator%&7.",
        "unmute": "%prefix%&f%player%&7 was &bunmuted&7 by &f%operator%&7.",
    },

    "mute": {
        "notify": {
            "permanent": "%prefix%&bYou have been &fpermanently&b muted by &f%operator%&b.\\n&7Reason: &f%reason%",
            "temporary": "%prefix%&bYou have been muted for &f%duration%&b by &f%operator%&b.\\n&7Reason: &f%reason%\\n&7Expires: &f%expires%",
        },
        "blocked": {
            "permanent": "%prefix%&bYou are &fpermanently&b muted.\\n&7Reason: &f%reason%",
            "temporary": "%prefix%&bYou are muted for another &f%remaining%&b.\\n&7Reason: &f%reason%",
        },
        "blocked-command": "%prefix%&bYou cannot use that command while muted.",
    },

    "unmute": {
        "notify": "%prefix%&bYou have been unmuted by &f%operator%&b.",
    },

    "login": {
        "database-error": "&bThe moderation database is currently unavailable.\\n&7Please try again in a moment.",
    },

    "admin": {
        "reload-success": "%prefix%&bConfiguration reloaded. &7(Database settings require a restart.)",
        "reload-failed": "%prefix%&cReload failed. Check the console for details.",
        "version": "%prefix%&7MineStormBan &fv%version%&7 on &f%platform%&7 | server-id &f%server%",
        "creator": "%prefix%&7Created by &bMuvixo&7. Plugin: &fMineStormBan&7.",
        "help": "%prefix%&b/msban reload &7- reload configuration\\n%prefix%&b/msban version &7- show version info\\n%prefix%&b/msban creator &7- show plugin author",
    },
}

DEFAULT_LAYOUT = {
    "ban": {
        "permanent": [
            "&b&lYOU ARE PERMANENTLY BANNED",
            "",
            "&7Player: &f%player%",
            "&7Reason: &b%reason%",
            "&7Banned by: &f%operator%",
            "&7Issued: &f%date%",
            "",
            "&7Ban ID: &f#%id%",
            "&8Appeal at &bhttps://example.com/appeal&8 and quote your ban ID.",
        ],
        "temporary": [
            "&b&lYOU ARE TEMPORARILY BANNED",
            "",
            "&7Player: &f%player%",
            "&7Reason: &b%reason%",
            "&7Banned by: &f%operator%",
            "",
            "&7Duration: &f%duration%",
            "&7Time left: &b%remaining%",
            "&7Expires: &f%expires%",
            "",
            "&7Ban ID: &f#%id%",
            "&8Appeal at &bhttps://example.com/appeal",
        ],
    },
    "kick": [
        "&b&lYou have been kicked",
        "",
        "&7Reason: &b%reason%",
        "&7Kicked by: &f%operator%",
        "",
        "&8You may reconnect immediately.",
    ],
}

DEFAULT_CONFIG = {
    "server-id": "lobby-1",
    "database": {
        "type": "sqlite",
        "table-prefix": "msban_",
        "fail-open": True,
        "sqlite": {"file": "minestormban.db"},
        "mysql": {
            "host": "127.0.0.1",
            "port": 3306,
            "database": "minestormban",
            "username": "minestormban",
            "password": "change-me",
            "use-ssl": False,
            "pool-size": 8,
            "connection-timeout-ms": 10000,
        },
    },
    "sync": {"poll-interval-seconds": 5},
    "timezone": "UTC",
    "date-format": "dd/MM/yyyy HH:mm:ss",
    "defaults": {
        "ban-reason": "Violating the server rules",
        "mute-reason": "Violating the chat rules",
        "kick-reason": "Kicked by a staff member",
    },
    "limits": {"max-duration": "5y"},
    "announcements": {
        "ban": True, "mute": True, "kick": True, "unban": True, "unmute": True,
    },
    "mute": {
        "blocked-commands": [
            "msg", "tell", "w", "whisper", "r", "reply",
            "me", "say", "mail", "t", "pm",
        ],
    },
}


# ---------------------------------------------------------------------------
# 6. SQL schema auto-merge
# ---------------------------------------------------------------------------

EXPECTED_SCHEMA = {
    "msban_punishments": {
        "columns": {
            "id":         "BIGINT PRIMARY KEY AUTO_INCREMENT",
            "type":       "VARCHAR(8) NOT NULL",
            "uuid":       "VARCHAR(36) NOT NULL",
            "name":       "VARCHAR(32) NOT NULL",
            "operator":   "VARCHAR(64) NOT NULL",
            "reason":     "VARCHAR(512) NOT NULL",
            "created_at": "BIGINT NOT NULL",
            "expires_at": "BIGINT NOT NULL",
            "server":     "VARCHAR(64) NOT NULL",
            "active":     "SMALLINT NOT NULL",
            "removed_by": "VARCHAR(64) NULL",
            "removed_at": "BIGINT NOT NULL DEFAULT 0",
            "updated_at": "BIGINT NOT NULL",
        },
        "indexes": [
            "INDEX idx_lookup (uuid, type, active)",
            "INDEX idx_updated (updated_at)",
        ],
    },
    "msban_players": {
        "columns": {
            "uuid":       "VARCHAR(36) NOT NULL PRIMARY KEY",
            "name":       "VARCHAR(32) NOT NULL",
            "name_lower": "VARCHAR(32) NOT NULL",
            "ip":         "VARCHAR(64) NULL",
            "last_seen":  "BIGINT NOT NULL",
        },
        "indexes": [
            "INDEX idx_name (name_lower)",
        ],
    },
}


def emit_schema_migration_sql():
    lines = []
    lines.append("-- MineStormBan schema migration")
    lines.append("-- Safe to run on an existing database: only adds missing objects.")
    lines.append("")
    for table, spec in EXPECTED_SCHEMA.items():
        lines.append(f"-- ---------- {table} ----------")
        lines.append(f"CREATE TABLE IF NOT EXISTS {table} (")
        cols = []
        for col, decl in spec["columns"].items():
            cols.append(f"  {col} {decl}")
        cols.extend(f"  {idx}" for idx in spec.get("indexes", []))
        lines.append(",\n".join(cols))
        lines.append(");")
        lines.append("")
        for col, decl in spec["columns"].items():
            safe_decl = re.sub(r"PRIMARY KEY", "", decl)
            safe_decl = re.sub(r"AUTO_INCREMENT", "", safe_decl).strip()
            lines.append(f"ALTER TABLE {table} ADD COLUMN IF NOT EXISTS {col} {safe_decl};")
        lines.append("")
    return "\n".join(lines)


def write_schema_migration():
    target = BACKUP_DIR / "schema_migration.sql"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(emit_schema_migration_sql(), encoding="utf-8")
    log(f"Wrote {target.relative_to(ROOT)} (run manually against your MySQL DB)")


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    if not (ROOT / "settings.gradle.kts").exists():
        log("ERROR: settings.gradle.kts not found. Run from project root.")
        sys.exit(1)

    BACKUP_DIR.mkdir(exist_ok=True)
    log(f"Backups will go to: {BACKUP_DIR.relative_to(ROOT)}")

    log("Step 1/9: moving java package dirs ...")
    move_java_packages()

    log("Step 2/9: renaming java class files ...")
    rename_java_class_files()

    log("Step 3/9: rewriting java file contents ...")
    rewrite_java_files()

    log("Step 4/9: patching command handlers ...")
    patch_command_handler()
    patch_bukkit_main()
    patch_bungee_main()
    patch_velocity_main()

    log("Step 5/9: rewriting gradle files ...")
    rewrite_gradle_files()

    log("Step 6/9: rewriting GitHub workflow ...")
    rewrite_github_workflow()

    log("Step 7/9: rewriting resource descriptors ...")
    write_resource("bukkit/src/main/resources/plugin.yml", PLUGIN_YML)
    write_resource("bungee/src/main/resources/bungee.yml", BUNGEE_YML)
    write_resource("velocity/src/main/resources/velocity-plugin.json", VELOCITY_JSON)

    log("Step 8/9: auto-merging YAML configs (adds missing params) ...")
    merge_yaml_file(
        ROOT / "core/src/main/resources/messages.yml",
        DEFAULT_MESSAGES,
        header="# ============================================================================\n"
               "#  MineStormBan - messages.yml\n"
               "#  Created by Muvixo\n"
               "#  Default theme: Aqua (&b) and White (&f)\n"
               "#  Legacy & color codes only.\n"
               "# ============================================================================",
    )
    merge_yaml_file(
        ROOT / "core/src/main/resources/layout.yml",
        DEFAULT_LAYOUT,
        header="# ============================================================================\n"
               "#  MineStormBan - layout.yml\n"
               "#  Created by Muvixo\n"
               "#  Default theme: Aqua (&b) and White (&f)\n"
               "# ============================================================================",
    )
    merge_yaml_file(
        ROOT / "core/src/main/resources/config.yml",
        DEFAULT_CONFIG,
        header="# ============================================================================\n"
               "#  MineStormBan - config.yml\n"
               "#  Created by Muvixo\n"
               "#  Run every backend AND the proxy against the SAME MySQL database\n"
               "#  for network-wide bans.\n"
               "# ============================================================================",
    )

    log("Step 9/9: emitting SQL schema migration ...")
    write_schema_migration()

    log("")
    log("Done.")
    log("")
    log("Next steps:")
    log("  1. gradle clean build")
    log("  2. (optional) run .fixer-backup/schema_migration.sql against your MySQL DB")
    log("  3. install the built jars on proxy + every backend")
    log("  4. point every config.yml at the SAME MySQL database")
    log("  5. give every server a unique server-id")
    log("")
    log("Public command: /msban creator  or  /minestormban creator  ->  'Created by Muvixo'")


if __name__ == "__main__":
    main()