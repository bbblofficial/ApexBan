package dev.minestormban.bukkit;

import dev.minestormban.core.MineStormCore;
import dev.minestormban.core.platform.MineStormSender;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.List;

/** Bridges Bukkit commands to the shared {@code CommandHandler}. */
final class BukkitCommands implements CommandExecutor, TabCompleter {

    private final MineStormCore core;

    BukkitCommands(MineStormCore core) {
        this.core = core;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        core.commands().handle(wrap(sender), command.getName(), args);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return core.commands().tabComplete(wrap(sender), command.getName(), args);
    }

    private static MineStormSender wrap(CommandSender sender) {
        return new MineStormSender() {
            @Override
            public String name() {
                return sender.getName().equals("CONSOLE") ? "Console" : sender.getName();
            }

            @Override
            public boolean hasPermission(String permission) {
                return sender.hasPermission(permission);
            }

            @Override
            public void sendMessage(String legacyMessage) {
                sender.sendMessage(legacyMessage);
            }
        };
    }
}
