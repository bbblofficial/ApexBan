package dev.apexban.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.apexban.core.ApexCore;
import org.slf4j.Logger;

import java.nio.file.Path;

@Plugin(
        id = "apexban",
        name = "ApexBan",
        version = "1.0.0",
        description = "Enterprise moderation core - bans, mutes and kicks with a shared global database.",
        authors = {"ApexBan"}
)
public final class ApexBanVelocity {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;

    private ApexCore core;

    @Inject
    public ApexBanVelocity(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onInitialize(ProxyInitializeEvent event) {
        String version = proxy.getPluginManager().getPlugin("apexban")
                .flatMap(container -> container.getDescription().getVersion())
                .orElse("1.0.0");
        ApexCore created = new ApexCore(new VelocityPlatform(proxy, logger, dataDirectory, version));
        try {
            created.enable();
        } catch (Exception ex) {
            logger.error("ApexBan failed to start; commands and checks are disabled", ex);
            return;
        }
        core = created;

        proxy.getEventManager().register(this, new VelocityListener(core));

        CommandManager commands = proxy.getCommandManager();
        register(commands, "ban");
        register(commands, "mute");
        register(commands, "kick");
        register(commands, "unban", "pardon");
        register(commands, "unmute");
        register(commands, "apexban");
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        if (core != null) {
            core.disable();
            core = null;
        }
    }

    private void register(CommandManager commands, String name, String... aliases) {
        commands.register(
                commands.metaBuilder(name).aliases(aliases).plugin(this).build(),
                new VelocityCommand(core, name));
    }
}
