package dev.minestormban.bukkit;

import dev.minestormban.core.MineStormCore;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

public final class MineStormBanBukkit extends JavaPlugin {

    private static final String[] COMMANDS = {"ban", "mute", "kick", "unban", "unmute", "minestormban"};

    private MineStormCore core;

    @Override
    public void onEnable() {
        BukkitPlatform platform = new BukkitPlatform(this);
        core = new MineStormCore(platform);
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
        takeOverBuiltInCommands();
    }

    /**
     * Spigot/CraftBukkit 1.8 has its own /ban, /kick, /pardon ... that are guarded by
     * {@code bukkit.command.*} permissions. If one of them keeps the label, staff who were given
     * {@code minestormban.*} in LuckPerms still get "You don't have permission". Replace only built-in
     * (non-plugin) commands so ApexBan's permission nodes are the ones that count. Commands that
     * belong to another plugin are never touched.
     */
    @SuppressWarnings("unchecked")
    private void takeOverBuiltInCommands() {
        try {
            Field mapField = Bukkit.getPluginManager().getClass().getDeclaredField("commandMap");
            mapField.setAccessible(true);
            Object commandMap = mapField.get(Bukkit.getPluginManager());
            Field knownField = SimpleCommandMap.class.getDeclaredField("knownCommands");
            knownField.setAccessible(true);
            Map<String, Command> known = (Map<String, Command>) knownField.get(commandMap);

            for (String name : COMMANDS) {
                PluginCommand ours = getCommand(name);
                if (ours == null) {
                    continue;
                }
                List<String> labels = new ArrayList<>();
                labels.add(name);
                labels.addAll(ours.getAliases());
                for (String label : labels) {
                    Command existing = known.get(label);
                    if (existing != null && existing != ours && !(existing instanceof PluginCommand)) {
                        known.put(label, ours);
                    }
                }
            }
        } catch (Exception | LinkageError ex) {
            getLogger().log(Level.WARNING, "Could not override built-in commands; if /ban still asks for "
                    + "bukkit.command.* permissions use /apexban:ban instead.", ex);
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
