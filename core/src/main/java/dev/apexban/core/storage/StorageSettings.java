package dev.apexban.core.storage;

import java.nio.file.Path;

public record StorageSettings(
        Dialect type,
        String tablePrefix,
        Path sqliteFile,
        String host,
        int port,
        String database,
        String username,
        String password,
        boolean useSsl,
        int poolSize,
        long connectionTimeoutMs) {
}
