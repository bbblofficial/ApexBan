package dev.apexban.core.model;

/** Result of a moderation action. Futures returned by the manager never complete exceptionally. */
public record Outcome(Status status, Punishment punishment) {

    public enum Status {
        SUCCESS,
        NOT_FOUND,
        NOT_ONLINE,
        EXEMPT,
        ALREADY_ACTIVE,
        NOT_ACTIVE,
        ERROR
    }

    public static Outcome of(Status status) {
        return new Outcome(status, null);
    }

    public static Outcome of(Status status, Punishment punishment) {
        return new Outcome(status, punishment);
    }

    public boolean success() {
        return status == Status.SUCCESS;
    }
}
