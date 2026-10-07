package dev.apexban.core.manager;

import dev.apexban.core.ApexCore;
import dev.apexban.core.model.LoginCheck;
import dev.apexban.core.model.Outcome;
import dev.apexban.core.model.Outcome.Status;
import dev.apexban.core.model.Punishment;
import dev.apexban.core.model.PunishmentType;
import dev.apexban.core.model.Target;
import dev.apexban.core.platform.ApexPlayer;
import dev.apexban.core.platform.PlatformAdapter;
import dev.apexban.core.storage.SqlStorage;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Orchestrates all moderation actions. Every database call runs on a dedicated worker pool, so
 * none of the public asynchronous methods ever blocks a server thread. Servers sharing one
 * database stay in sync through a lightweight poller that watches {@code updated_at}.
 */
public final class PunishmentManager {

    /** Tolerance for clock differences between servers that share one database. */
    private static final long SYNC_SKEW_MS = 5_000L;

    private final ApexCore core;
    private final SqlStorage storage;
    private final ScheduledExecutorService executor;
    private final PlatformAdapter platform;

    private final Map<UUID, Punishment> muteCache = new ConcurrentHashMap<>();
    private final AtomicLong lastSync = new AtomicLong(System.currentTimeMillis());
    private ScheduledFuture<?> syncTask;

    public PunishmentManager(ApexCore core, SqlStorage storage, ScheduledExecutorService executor) {
        this.core = core;
        this.storage = storage;
        this.executor = executor;
        this.platform = core.platform();
    }

    // ------------------------------------------------------------------ synchronisation

    public synchronized void startSync(int intervalSeconds) {
        stopSync();
        int period = Math.max(1, intervalSeconds);
        syncTask = executor.scheduleWithFixedDelay(this::poll, period, period, TimeUnit.SECONDS);
    }

    public synchronized void stopSync() {
        if (syncTask != null) {
            syncTask.cancel(false);
            syncTask = null;
        }
    }

    private void poll() {
        try {
            long started = System.currentTimeMillis();
            List<Punishment> changes = storage.changedSince(lastSync.get() - SYNC_SKEW_MS);
            lastSync.set(started);
            for (Punishment p : changes) {
                applyRemoteChange(p, started);
            }
        } catch (Exception ex) {
            platform.logger().error("Synchronisation poll failed", ex);
        }
    }

    private void applyRemoteChange(Punishment p, long now) {
        if (platform.findOnline(p.uuid()).isEmpty()) {
            return;
        }
        if (p.type() == PunishmentType.BAN) {
            if (p.activeNow(now)) {
                platform.kick(p.uuid(), core.banScreen(p));
            }
        } else if (p.type() == PunishmentType.MUTE) {
            if (p.activeNow(now)) {
                muteCache.merge(p.uuid(), p, (current, incoming) -> current.id() > incoming.id() ? current : incoming);
            } else {
                muteCache.computeIfPresent(p.uuid(), (k, current) -> current.id() <= p.id() ? null : current);
            }
        }
    }

    // ------------------------------------------------------------------ login / chat hooks

    /**
     * Blocking login-time check: records the player, loads an active mute into the cache and
     * reports an active ban. Call from an asynchronous pre-login thread only.
     */
    public LoginCheck onLogin(UUID uuid, String name, String ip) {
        long now = System.currentTimeMillis();
        try {
            storage.upsertPlayer(uuid, name, ip, now);
            Optional<Punishment> ban = storage.findActive(uuid, PunishmentType.BAN, now);
            if (ban.isPresent()) {
                return LoginCheck.banned(ban.get());
            }
            Optional<Punishment> mute = storage.findActive(uuid, PunishmentType.MUTE, now);
            if (mute.isPresent()) {
                muteCache.put(uuid, mute.get());
            } else {
                muteCache.remove(uuid);
            }
            return LoginCheck.allowed();
        } catch (SQLException ex) {
            platform.logger().error("Login lookup failed for " + name, ex);
            return LoginCheck.failure();
        }
    }

    public void onQuit(UUID uuid) {
        muteCache.remove(uuid);
    }

    /** Cache-only, non-blocking; safe on any thread. Expired mutes are evicted lazily. */
    public Optional<Punishment> activeMute(UUID uuid) {
        Punishment mute = muteCache.get(uuid);
        if (mute == null) {
            return Optional.empty();
        }
        if (mute.expired(System.currentTimeMillis())) {
            muteCache.remove(uuid, mute);
            return Optional.empty();
        }
        return Optional.of(mute);
    }

    // ------------------------------------------------------------------ actions

    /** @param durationMs milliseconds, or {@code -1} for a permanent ban */
    public CompletableFuture<Outcome> ban(String targetName, String operator, long durationMs, String reason,
                                          boolean silent) {
        return supply(() -> {
            Optional<Target> resolved = resolve(targetName);
            if (resolved.isEmpty()) {
                return Outcome.of(Status.NOT_FOUND);
            }
            Target target = resolved.get();
            Optional<ApexPlayer> online = platform.findOnline(target.uuid());
            if (online.isPresent() && online.get().hasPermission("apexban.exempt.ban")) {
                return Outcome.of(Status.EXEMPT);
            }
            long now = System.currentTimeMillis();
            Optional<Punishment> existing = storage.findActive(target.uuid(), PunishmentType.BAN, now);
            if (existing.isPresent()) {
                return Outcome.of(Status.ALREADY_ACTIVE, existing.get());
            }
            Punishment draft = new Punishment(0L, PunishmentType.BAN, target.uuid(), target.name(), operator,
                    reason, now, durationMs > 0 ? now + durationMs : -1L, core.serverId(), true, null, 0L, now);
            Punishment saved = draft.withId(storage.insert(draft));

            if (online.isPresent()) {
                platform.kick(target.uuid(), core.banScreen(saved));
            }
            core.announce(saved.permanent() ? "ban.permanent" : "ban.temporary", "ban",
                    core.placeholders(saved), silent);
            return Outcome.of(Status.SUCCESS, saved);
        });
    }

    /** @param durationMs milliseconds, or {@code -1} for a permanent mute */
    public CompletableFuture<Outcome> mute(String targetName, String operator, long durationMs, String reason,
                                           boolean silent) {
        return supply(() -> {
            Optional<Target> resolved = resolve(targetName);
            if (resolved.isEmpty()) {
                return Outcome.of(Status.NOT_FOUND);
            }
            Target target = resolved.get();
            Optional<ApexPlayer> online = platform.findOnline(target.uuid());
            if (online.isPresent() && online.get().hasPermission("apexban.exempt.mute")) {
                return Outcome.of(Status.EXEMPT);
            }
            long now = System.currentTimeMillis();
            Optional<Punishment> existing = storage.findActive(target.uuid(), PunishmentType.MUTE, now);
            if (existing.isPresent()) {
                return Outcome.of(Status.ALREADY_ACTIVE, existing.get());
            }
            Punishment draft = new Punishment(0L, PunishmentType.MUTE, target.uuid(), target.name(), operator,
                    reason, now, durationMs > 0 ? now + durationMs : -1L, core.serverId(), true, null, 0L, now);
            Punishment saved = draft.withId(storage.insert(draft));

            if (online.isPresent()) {
                muteCache.put(target.uuid(), saved);
                online.get().sendMessage(core.text(
                        saved.permanent() ? "mute.notify.permanent" : "mute.notify.temporary",
                        core.placeholders(saved)));
            }
            core.announce(saved.permanent() ? "mute.permanent" : "mute.temporary", "mute",
                    core.placeholders(saved), silent);
            return Outcome.of(Status.SUCCESS, saved);
        });
    }

    public CompletableFuture<Outcome> kick(String targetName, String operator, String reason, boolean silent) {
        return supply(() -> {
            Optional<ApexPlayer> online = platform.findOnline(targetName);
            if (online.isEmpty()) {
                return Outcome.of(Status.NOT_ONLINE);
            }
            ApexPlayer player = online.get();
            if (player.hasPermission("apexban.exempt.kick")) {
                return Outcome.of(Status.EXEMPT);
            }
            long now = System.currentTimeMillis();
            Punishment draft = new Punishment(0L, PunishmentType.KICK, player.uuid(), player.name(), operator,
                    reason, now, -1L, core.serverId(), false, null, 0L, now);
            Punishment saved = draft.withId(storage.insert(draft));

            platform.kick(player.uuid(), core.kickScreen(saved));
            core.announce("kick", "kick", core.placeholders(saved), silent);
            return Outcome.of(Status.SUCCESS, saved);
        });
    }

    public CompletableFuture<Outcome> unban(String targetName, String operator, boolean silent) {
        return supply(() -> {
            Optional<Target> resolved = resolve(targetName);
            if (resolved.isEmpty()) {
                return Outcome.of(Status.NOT_FOUND);
            }
            Optional<Punishment> removed = storage.deactivate(resolved.get().uuid(), PunishmentType.BAN,
                    operator, System.currentTimeMillis());
            if (removed.isEmpty()) {
                return Outcome.of(Status.NOT_ACTIVE);
            }
            Map<String, String> ph = core.placeholders(removed.get());
            ph.put("operator", operator);
            core.announce("unban", "unban", ph, silent);
            return Outcome.of(Status.SUCCESS, removed.get());
        });
    }

    public CompletableFuture<Outcome> unmute(String targetName, String operator, boolean silent) {
        return supply(() -> {
            Optional<Target> resolved = resolve(targetName);
            if (resolved.isEmpty()) {
                return Outcome.of(Status.NOT_FOUND);
            }
            UUID uuid = resolved.get().uuid();
            Optional<Punishment> removed = storage.deactivate(uuid, PunishmentType.MUTE, operator,
                    System.currentTimeMillis());
            if (removed.isEmpty()) {
                return Outcome.of(Status.NOT_ACTIVE);
            }
            muteCache.remove(uuid);
            Map<String, String> ph = core.placeholders(removed.get());
            ph.put("operator", operator);
            platform.findOnline(uuid).ifPresent(p -> p.sendMessage(core.text("unmute.notify", ph)));
            core.announce("unmute", "unmute", ph, silent);
            return Outcome.of(Status.SUCCESS, removed.get());
        });
    }

    // ------------------------------------------------------------------ internals

    private Optional<Target> resolve(String name) throws SQLException {
        Optional<ApexPlayer> online = platform.findOnline(name);
        if (online.isPresent()) {
            return Optional.of(new Target(online.get().uuid(), online.get().name()));
        }
        Optional<Target> known = storage.findPlayerByName(name);
        if (known.isPresent()) {
            return known;
        }
        return platform.resolveOfflineUuid(name).map(uuid -> new Target(uuid, name));
    }

    private CompletableFuture<Outcome> supply(Callable<Outcome> body) {
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return body.call();
                } catch (Exception ex) {
                    platform.logger().error("Moderation action failed", ex);
                    return Outcome.of(Status.ERROR);
                }
            }, executor);
        } catch (RejectedExecutionException ex) {
            return CompletableFuture.completedFuture(Outcome.of(Status.ERROR));
        }
    }
}
