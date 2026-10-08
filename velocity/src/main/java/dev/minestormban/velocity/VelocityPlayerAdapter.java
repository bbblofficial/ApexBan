package dev.minestormban.velocity;

import com.velocitypowered.api.proxy.Player;
import dev.minestormban.core.platform.MineStormPlayer;

import java.util.UUID;

final class VelocityPlayerAdapter implements MineStormPlayer {

    private final Player player;

    VelocityPlayerAdapter(Player player) {
        this.player = player;
    }

    @Override
    public UUID uuid() {
        return player.getUniqueId();
    }

    @Override
    public String name() {
        return player.getUsername();
    }

    @Override
    public boolean hasPermission(String permission) {
        return player.hasPermission(permission);
    }

    @Override
    public void sendMessage(String legacyMessage) {
        player.sendMessage(VelocityPlatform.component(legacyMessage));
    }
}
