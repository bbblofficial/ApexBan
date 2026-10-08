package dev.minestormban.bungee;

import dev.minestormban.core.MineStormCore;
import dev.minestormban.core.platform.MineStormSender;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.TabExecutor;

/** One BungeeCord command bound to the shared {@code CommandHandler}. */
final class BungeeCommand extends Command implements TabExecutor {

    private final MineStormCore core;
    private final String name;

    BungeeCommand(MineStormCore core, String name, String... aliases) {
        super(name, null, aliases);
        this.core = core;
        this.name = name;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        core.commands().handle(wrap(sender), name, args);
    }

    @Override
    public Iterable<String> onTabComplete(CommandSender sender, String[] args) {
        return core.commands().tabComplete(wrap(sender), name, args);
    }

    private static MineStormSender wrap(CommandSender sender) {
        return new MineStormSender() {
            @Override
            public String name() {
                return BungeePlatform.senderName(sender);
            }

            @Override
            public boolean hasPermission(String permission) {
                return sender.hasPermission(permission);
            }

            @Override
            public void sendMessage(String legacyMessage) {
                sender.sendMessage(BungeePlatform.component(legacyMessage));
            }
        };
    }
}
