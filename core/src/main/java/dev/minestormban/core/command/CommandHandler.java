package dev.minestormban.core.command;

import dev.minestormban.core.MineStormCore;
import dev.minestormban.core.model.Outcome;
import dev.minestormban.core.model.PunishmentScope;
import dev.minestormban.core.model.PunishmentType;
import dev.minestormban.core.platform.MineStormSender;
import dev.minestormban.core.util.DurationFormatter;
import dev.minestormban.core.util.DurationParser;
import dev.minestormban.core.util.Placeholders;
import dev.minestormban.core.util.UuidUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;

/**
 * Platform-independent command logic for /ban, /mute, /kick, /unban, /unmute and /minestormban (/msban).
 *
 * <pre>
 *   /ban    [-s] [-g|-l] &lt;player&gt; [duration|perm] [reason...]
 *   /mute   [-s] [-g|-l] &lt;player&gt; [duration|perm] [reason...]
 *   /kick   [-s] &lt;player&gt; [reason...]
 *   /unban  [-s] [-g] &lt;player&gt;
 *   /unmute [-s] [-g] &lt;player&gt;
 * </pre>
 *
 * When the duration is omitted the punishment is permanent. Without a scope flag a ban/mute only
 * applies on the server it was issued on (see {@code scope.*} in config.yml); {@code -g} makes it
 * global (all servers sharing the database), {@code -l} forces it to stay local.
 */
public final class CommandHandler {

    private static final int MAX_REASON_LENGTH = 512;
    private static final List<String> DURATION_SUGGESTIONS = List.of("perm", "30m", "1h", "12h", "1d", "7d", "30d");

    private final MineStormCore core;

    public CommandHandler(MineStormCore core) {
        this.core = core;
    }

    public void handle(MineStormSender sender, String command, String[] args) {
        try {
            switch (command.toLowerCase(Locale.ROOT)) {
                case "ban" -> punish(sender, args, PunishmentType.BAN);
                case "mute" -> punish(sender, args, PunishmentType.MUTE);
                case "kick" -> kick(sender, args);
                case "unban" -> remove(sender, args, PunishmentType.BAN);
                case "unmute" -> remove(sender, args, PunishmentType.MUTE);
                case "minestormban", "msban" -> admin(sender, args);
                default -> sender.sendMessage(core.text("error"));
            }
        } catch (RuntimeException ex) {
            core.platform().logger().error("Command '" + command + "' failed", ex);
            sender.sendMessage(core.text("error"));
        }
    }

    // ------------------------------------------------------------------ /ban and /mute

    private void punish(MineStormSender sender, String[] args, PunishmentType type) {
        String key = type == PunishmentType.BAN ? "ban" : "mute";
        if (!sender.hasPermission("minestormban." + key)) {
            sender.sendMessage(core.text("no-permission"));
            return;
        }
        Flags flags = parseFlags(sender, args, true, true);
        if (flags == null) {
            return;
        }
        int index = flags.next();
        if (args.length - index < 1) {
            sender.sendMessage(core.text("usage." + key));
            return;
        }
        String targetName = args[index++];
        if (!UuidUtil.isValidName(targetName)) {
            sender.sendMessage(core.text("invalid-name", Placeholders.of("input", targetName)));
            return;
        }

        long duration = -1L;
        if (index < args.length) {
            String token = args[index];
            if (DurationParser.isPermanentKeyword(token)) {
                index++;
            } else if (DurationParser.isDuration(token)) {
                OptionalLong parsed = DurationParser.parse(token);
                if (parsed.isEmpty()) {
                    sender.sendMessage(core.text("invalid-duration", Placeholders.of("input", token)));
                    return;
                }
                if (parsed.getAsLong() > core.maxDurationMillis()) {
                    sender.sendMessage(core.text("duration-too-long",
                            Placeholders.of("max", DurationFormatter.format(core.maxDurationMillis()))));
                    return;
                }
                duration = parsed.getAsLong();
                index++;
            } else if (DurationParser.looksLikeDuration(token)) {
                sender.sendMessage(core.text("invalid-duration", Placeholders.of("input", token)));
                return;
            }
        }

        String reason = index < args.length
                ? String.join(" ", Arrays.copyOfRange(args, index, args.length)).trim()
                : "";
        if (reason.isEmpty()) {
            reason = core.defaultReason(type);
        }
        if (reason.length() > MAX_REASON_LENGTH) {
            reason = reason.substring(0, MAX_REASON_LENGTH);
        }

        PunishmentScope scope = flags.global() ? PunishmentScope.GLOBAL
                : flags.local() ? PunishmentScope.SERVER
                : core.defaultScope(type);
        final boolean silentFlag = flags.silent();
        CompletableFuture<Outcome> future = type == PunishmentType.BAN
                ? core.manager().ban(targetName, sender.name(), duration, reason, silentFlag, scope)
                : core.manager().mute(targetName, sender.name(), duration, reason, silentFlag, scope);
        future.thenAccept(outcome -> report(sender, key, targetName, outcome, silentFlag));
    }

    // ------------------------------------------------------------------ /kick

    private void kick(MineStormSender sender, String[] args) {
        if (!sender.hasPermission("minestormban.kick")) {
            sender.sendMessage(core.text("no-permission"));
            return;
        }
        Flags flags = parseFlags(sender, args, false, false);
        if (flags == null) {
            return;
        }
        int index = flags.next();
        if (args.length - index < 1) {
            sender.sendMessage(core.text("usage.kick"));
            return;
        }
        String targetName = args[index++];
        if (!UuidUtil.isValidName(targetName)) {
            sender.sendMessage(core.text("invalid-name", Placeholders.of("input", targetName)));
            return;
        }
        String reason = index < args.length
                ? String.join(" ", Arrays.copyOfRange(args, index, args.length)).trim()
                : "";
        if (reason.isEmpty()) {
            reason = core.defaultReason(PunishmentType.KICK);
        }
        if (reason.length() > MAX_REASON_LENGTH) {
            reason = reason.substring(0, MAX_REASON_LENGTH);
        }
        final boolean silentFlag = flags.silent();
        core.manager().kick(targetName, sender.name(), reason, silentFlag)
                .thenAccept(outcome -> report(sender, "kick", targetName, outcome, silentFlag));
    }

    // ------------------------------------------------------------------ /unban and /unmute

    private void remove(MineStormSender sender, String[] args, PunishmentType type) {
        String key = type == PunishmentType.BAN ? "unban" : "unmute";
        if (!sender.hasPermission("minestormban." + key)) {
            sender.sendMessage(core.text("no-permission"));
            return;
        }
        Flags flags = parseFlags(sender, args, true, false);
        if (flags == null) {
            return;
        }
        int index = flags.next();
        if (args.length - index < 1) {
            sender.sendMessage(core.text("usage." + key));
            return;
        }
        String targetName = args[index];
        if (!UuidUtil.isValidName(targetName)) {
            sender.sendMessage(core.text("invalid-name", Placeholders.of("input", targetName)));
            return;
        }
        final boolean silentFlag = flags.silent();
        final boolean everywhere = flags.global();
        CompletableFuture<Outcome> future = type == PunishmentType.BAN
                ? core.manager().unban(targetName, sender.name(), silentFlag, everywhere)
                : core.manager().unmute(targetName, sender.name(), silentFlag, everywhere);
        future.thenAccept(outcome -> report(sender, key, targetName, outcome, silentFlag));
    }

    // ------------------------------------------------------------------ flags

    /** Leading option flags: {@code -s} silent, {@code -g} global/everywhere, {@code -l} local. */
    private record Flags(boolean silent, boolean global, boolean local, int next) {
    }

    private static boolean isFlag(String token, boolean allowGlobal, boolean allowLocal) {
        if (token == null || token.length() != 2 || token.charAt(0) != '-') {
            return false;
        }
        char c = Character.toLowerCase(token.charAt(1));
        return c == 's' || (c == 'g' && allowGlobal) || (c == 'l' && allowLocal);
    }

    /**
     * Consumes leading flags. Returns {@code null} (after telling the sender why) when a flag is
     * used without the matching permission.
     */
    private Flags parseFlags(MineStormSender sender, String[] args, boolean allowGlobal, boolean allowLocal) {
        boolean silent = false;
        boolean global = false;
        boolean local = false;
        int index = 0;
        while (index < args.length && isFlag(args[index], allowGlobal, allowLocal)) {
            char c = Character.toLowerCase(args[index].charAt(1));
            if (c == 's') {
                if (!sender.hasPermission("minestormban.silent")) {
                    sender.sendMessage(core.text("no-permission"));
                    return null;
                }
                silent = true;
            } else if (c == 'g') {
                if (!sender.hasPermission("minestormban.global")) {
                    sender.sendMessage(core.text("no-permission"));
                    return null;
                }
                global = true;
            } else {
                local = true;
            }
            index++;
        }
        if (global && local) {
            // Contradictory: the explicit "everywhere" wins.
            local = false;
        }
        return new Flags(silent, global, local, index);
    }

    // ------------------------------------------------------------------ /minestormban (/msban)

    private void admin(MineStormSender sender, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "help";

        // /msban creator (and /minestormban creator) is PUBLIC: any player can
        // run it to see who made the plugin. No permission required, so it is
        // handled BEFORE the permission check below.
        if (sub.equals("creator") || sub.equals("author") || sub.equals("credit")) {
            sender.sendMessage(core.text("admin.creator"));
            return;
        }

        if (!sender.hasPermission("minestormban.admin")) {
            sender.sendMessage(core.text("no-permission"));
            return;
        }

        switch (sub) {
            case "reload" -> {
                try {
                    core.reload();
                    sender.sendMessage(core.text("admin.reload-success"));
                } catch (Exception ex) {
                    core.platform().logger().error("Reload failed", ex);
                    sender.sendMessage(core.text("admin.reload-failed"));
                }
            }
            case "version", "info" -> sender.sendMessage(core.text("admin.version", Placeholders.of(
                    "version", core.platform().pluginVersion(),
                    "platform", core.platform().platformName(),
                    "server", core.serverId())));
            default -> sender.sendMessage(core.text("admin.help"));
        }
    }

    // ------------------------------------------------------------------ feedback

    private void report(MineStormSender sender, String key, String targetName, Outcome outcome, boolean silent) {
        Map<String, String> byName = Placeholders.of("player", targetName);
        switch (outcome.status()) {
            case SUCCESS -> {
                if (!core.announceReaches(sender, key, silent)) {
                    Map<String, String> ph = core.placeholders(outcome.punishment());
                    ph.put("operator", sender.name());
                    sender.sendMessage(core.text("success." + key, ph));
                }
            }
            case NOT_FOUND -> sender.sendMessage(core.text("player-not-found", byName));
            case NOT_ONLINE -> sender.sendMessage(core.text("player-not-online", byName));
            case EXEMPT -> sender.sendMessage(core.text("exempt", byName));
            case ALREADY_ACTIVE -> sender.sendMessage(core.text(
                    key.equals("ban") ? "already-banned" : "already-muted", byName));
            case NOT_ACTIVE -> sender.sendMessage(core.text(
                    key.equals("unban") ? "not-banned" : "not-muted", byName));
            case ERROR -> sender.sendMessage(core.text("error"));
        }
    }

    // ------------------------------------------------------------------ tab completion

    public List<String> tabComplete(MineStormSender sender, String command, String[] args) {
        String cmd = command.toLowerCase(Locale.ROOT);
        if (cmd.equals("msban")) {
            cmd = "minestormban";
        }
        if (args.length == 0 || !sender.hasPermission("minestormban." + (cmd.equals("minestormban") ? "admin" : cmd))) {
            return new ArrayList<>();
        }
        String current = args[args.length - 1].toLowerCase(Locale.ROOT);

        if (cmd.equals("minestormban")) {
            return args.length == 1 ? filter(List.of("reload", "version", "creator"), current) : List.of();
        }

        boolean scoped = cmd.equals("ban") || cmd.equals("mute");
        boolean allowGlobal = scoped || cmd.equals("unban") || cmd.equals("unmute");

        // Skip the flags that were already typed before the cursor.
        int offset = 0;
        while (offset < args.length - 1 && isFlag(args[offset], allowGlobal, scoped)) {
            offset++;
        }
        int position = args.length - 1 - offset;
        if (position == 0) {
            List<String> options = new ArrayList<>(core.platform().onlinePlayerNames());
            if (sender.hasPermission("minestormban.silent")) {
                options.add("-s");
            }
            if (allowGlobal && sender.hasPermission("minestormban.global")) {
                options.add("-g");
            }
            if (scoped) {
                options.add("-l");
            }
            return filter(options, current);
        }
        if (position == 1 && scoped) {
            return filter(DURATION_SUGGESTIONS, current);
        }
        return new ArrayList<>();
    }

    private static List<String> filter(List<String> options, String prefixLower) {
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(prefixLower)) {
                result.add(option);
            }
        }
        return result;
    }
}
