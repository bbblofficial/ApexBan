package dev.apexban.core.model;

/**
 * Result of the login-time lookup.
 *
 * @param ban   active ban, or {@code null}
 * @param error true when the database could not be queried
 */
public record LoginCheck(Punishment ban, boolean error) {

    public static LoginCheck allowed() {
        return new LoginCheck(null, false);
    }

    public static LoginCheck banned(Punishment ban) {
        return new LoginCheck(ban, false);
    }

    public static LoginCheck failure() {
        return new LoginCheck(null, true);
    }
}
