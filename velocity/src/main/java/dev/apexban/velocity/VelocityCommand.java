package dev.apexban.velocity;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import dev.apexban.core.ApexCore;
import dev.apexban.core.platform.ApexSender;

import java.util.List;

/** One Velocity command bound to the shared {@code CommandHandler}. */
final class VelocityCommand implements SimpleCommand {

    private final ApexCore core;
    private final String name;

    VelocityCommand(ApexCore core, String name) {
        this.core = core;
        this.name = name;
    }

    @Override
    public void execute(Invocation invocation) {
        core.commands().handle(wrap(invocation.source()), name, invocation.arguments());
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        return core.commands().tabComplete(wrap(invocation.source()), name, invocation.arguments());
    }

    private static ApexSender wrap(CommandSource source) {
        return new ApexSender() {
            @Override
            public String name() {
                return source instanceof Player player ? player.getUsername() : "Console";
            }

            @Override
            public boolean hasPermission(String permission) {
                return source.hasPermission(permission);
            }

            @Override
            public void sendMessage(String legacyMessage) {
                source.sendMessage(VelocityPlatform.component(legacyMessage));
            }
        };
    }
}
