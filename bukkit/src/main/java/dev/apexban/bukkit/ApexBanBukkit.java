package dev.apexban.bukkit;

import dev.apexban.core.ApexCore;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

public final class ApexBanBukkit extends JavaPlugin {

    private static final String[] COMMANDS = {"ban", "mute", "kick", "unban", "unmute", "apexban"};

    private ApexCore core;

    @Override
    public void onEnable() {
        BukkitPlatform platform = new BukkitPlatform(this);
        core = new ApexCore(platform);
        try {
            core.enable();
        } catch (Exception ex) {
            getLogger().log(Level.SEVERE, "ApexBan failed to start; disabling plugin", ex);
            core = null;
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            platform.track(player);
        }
        Bukkit.getPluginManager().registerEvents(new BukkitListener(core, platform), this);

        BukkitCommands executor = new BukkitCommands(core);
        for (String name : COMMANDS) {
            PluginCommand command = getCommand(name);
            if (command != null) {
                command.setExecutor(executor);
                command.setTabCompleter(executor);
            }
        }
    }

    @Override
    public void onDisable() {
        if (core != null) {
            core.disable();
            core = null;
        }
    }
}
