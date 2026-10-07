package dev.apexban.core.platform;

public interface ApexLogger {

    void info(String message);

    void warn(String message);

    void error(String message, Throwable error);
}
