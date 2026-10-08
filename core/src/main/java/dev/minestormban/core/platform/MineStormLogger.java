package dev.minestormban.core.platform;

public interface MineStormLogger {

    void info(String message);

    void warn(String message);

    void error(String message, Throwable error);
}
