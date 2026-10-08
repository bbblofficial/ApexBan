package dev.minestormban.bungee;

import dev.minestormban.core.MineStormCore;
import net.md_5.bungee.api.plugin.Plugin;

import java.util.logging.Level;

public final class MineStormBanBungee extends Plugin {

    private MineStormCore core;

    @Override
    public void onEnable() {
        core = new MineStormCore(new BungeePlatform(this));
        try {
            core.enable();
        } catch (Exception ex) {
            getLogger().log(Level.SEVERE, "ApexBan failed to start; commands and checks are disabled", ex);
            core = null;
            return;
        }
        getProxy().getPluginManager().registerListener(this, new BungeeListener(core, this));
        getProxy().getPluginManager().registerCommand(this, new BungeeCommand(core, "ban"));
        getProxy().getPluginManager().registerCommand(this, new BungeeCommand(core, "mute"));
        getProxy().getPluginManager().registerCommand(this, new BungeeCommand(core, "kick"));
        getProxy().getPluginManager().registerCommand(this, new BungeeCommand(core, "unban", "pardon"));
        getProxy().getPluginManager().registerCommand(this, new BungeeCommand(core, "unmute"));
        getProxy().getPluginManager().registerCommand(this, new BungeeCommand(core, "minestormban"));
    }

    @Override
    public void onDisable() {
        if (core != null) {
            core.disable();
            core = null;
        }
    }
}
