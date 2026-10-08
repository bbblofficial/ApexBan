package dev.minestormban.core.util;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Pattern;

public final class UuidUtil {

    private static final Pattern NAME = Pattern.compile("^[.A-Za-z0-9_]{1,16}$");

    private UuidUtil() {
    }

    /** The UUID a cracked / offline-mode server assigns to a username. */
    public static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    /** Java names are 3-16 chars of [A-Za-z0-9_]; Bedrock (Floodgate) names may start with '.'. */
    public static boolean isValidName(String name) {
        return name != null && NAME.matcher(name).matches();
    }
}
