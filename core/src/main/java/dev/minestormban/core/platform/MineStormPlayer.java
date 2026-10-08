package dev.minestormban.core.platform;

import java.util.UUID;

/** A connected player on the current platform. Implementations must be safe to use off-thread. */
public interface MineStormPlayer {

    UUID uuid();

    String name();

    boolean hasPermission(String permission);

    /** Sends a message already converted to legacy section-sign colour codes. */
    void sendMessage(String legacyMessage);
}
