package dev.apexban.core.platform;

/** Anything that can run a command: a player or the console. */
public interface ApexSender {

    String name();

    boolean hasPermission(String permission);

    /** Sends a message already converted to legacy section-sign colour codes. */
    void sendMessage(String legacyMessage);
}
