package dev.foliate.core.player;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a {@link PlayerStore} knows of the players' sessions besides their values in game: the
 * logins waiting for their join, the quits a login still reading may have missed, and the joins
 * whose value is still loading because no pre-login was seen.
 *
 * <p>It has no lock of its own: every method but {@link #pendingLogins} is called under the store's
 * lock, the one given to the constructor, which makes a login, a join, a quit and a shutdown
 * atomic with respect to each other and to the saves they queue.
 */
final class SessionTracker<T> {

    /**
     * How long a value loaded at pre-login waits for its join. A connection can end between the
     * two without a trace (banned, server full, dropped), so an entry older than this is forgotten.
     */
    static final long LOGIN_TTL_MILLIS = 5 * 60_000L;

    private final Object lock;
    private final Clock clock;

    /** Values loaded at pre-login, waiting for a join that may never come. */
    private final Map<UUID, PendingLogin<T>> loggingIn = new ConcurrentHashMap<>();

    /**
     * Each player's last session that ended, until a while after: a login whose read began before
     * that quit read an older value than the quit saved. See {@link PlayerStore#beginLogin}.
     */
    private final Map<UUID, Left<T>> recentlyLeft = new ConcurrentHashMap<>();

    /** Counts the logins and quits, to tell which came first. Read and written under the lock. */
    private long sessionEvents;

    /**
     * The load of each player whose value is being loaded after their join, because no pre-login
     * was seen. A quit takes it out and records its time: the value is then saved as of then.
     */
    private final Map<UUID, LateJoin> pendingJoins = new ConcurrentHashMap<>();

    /** @param lock the store's lock, under which this tracker is used */
    SessionTracker(Object lock, Clock clock) {
        this.lock = lock;
        this.clock = clock;
    }

    /** Records a pending login: the value the player's join will start from. */
    void loggedIn(UUID id, T value) {
        assert Thread.holdsLock(lock);
        long now = clock.millis();
        // Logins that never became a join leave nothing else to tell us: forget them here, with
        // the quits that no read still running can predate.
        loggingIn.values().removeIf(pending -> now - pending.at() > LOGIN_TTL_MILLIS);
        recentlyLeft.values().removeIf(left -> now - left.at() > LOGIN_TTL_MILLIS);
        loggingIn.put(id, new PendingLogin<>(value, now));
    }

    /** Takes out a player's pending login: its value, unless it waited too long to be trusted. */
    Optional<T> takeLogin(UUID id, long now) {
        assert Thread.holdsLock(lock);
        PendingLogin<T> pending = loggingIn.remove(id);
        return pending != null && now - pending.at() <= LOGIN_TTL_MILLIS
                ? Optional.of(pending.value())
                : Optional.empty();
    }

    /** Forgets a player's pending login: their join will load what is stored instead. */
    void forgetLogin(UUID id) {
        assert Thread.holdsLock(lock);
        loggingIn.remove(id);
    }

    /** How many logins wait for their join. For tests. */
    int pendingLogins() {
        return loggingIn.size();
    }

    /**
     * A login starts reading the player's stored value.
     *
     * @return when it did, to give to {@link #leftSince}
     */
    long readStarts() {
        assert Thread.holdsLock(lock);
        return ++sessionEvents;
    }

    /**
     * What the player's last quit saved, if it came after a read began: the read was queued before
     * that save, so this is fresher than what it gave.
     */
    Optional<T> leftSince(UUID id, long readStarted) {
        assert Thread.holdsLock(lock);
        Left<T> left = recentlyLeft.get(id);
        return left != null && left.event() > readStarted ? Optional.of(left.value()) : Optional.empty();
    }

    /** A session ended, and {@code saved} is what its quit saved. */
    void left(UUID id, T saved, long now) {
        assert Thread.holdsLock(lock);
        recentlyLeft.put(id, new Left<>(saved, ++sessionEvents, now));
        // A second login while this session was online took a copy of its value at pre-login, and
        // the new session will start from it. What this session changed since then is in what was
        // just saved: start the new session from that instead.
        loggingIn.computeIfPresent(id, (key, pending) -> new PendingLogin<>(saved, pending.at()));
    }

    /** Whether the player's value is being loaded after their join. */
    boolean loadingLate(UUID id) {
        assert Thread.holdsLock(lock);
        return pendingJoins.containsKey(id);
    }

    /** Records that the player's value is being loaded after their join. */
    LateJoin startLateJoin(UUID id) {
        assert Thread.holdsLock(lock);
        LateJoin load = new LateJoin();
        pendingJoins.put(id, load);
        return load;
    }

    /** Whether {@code load} is still the player's: they have not quit since it started. */
    boolean stillLoading(UUID id, LateJoin load) {
        assert Thread.holdsLock(lock);
        return pendingJoins.get(id) == load;
    }

    /**
     * Ends {@code load}.
     *
     * @return whether it was still the player's: false when they quit since it started
     */
    boolean endLateJoin(UUID id, LateJoin load) {
        assert Thread.holdsLock(lock);
        return pendingJoins.remove(id, load);
    }

    /** The player quit: a load of theirs still running saves its value as of now. */
    void quitWhileLoading(UUID id, long now) {
        assert Thread.holdsLock(lock);
        LateJoin loading = pendingJoins.remove(id);
        if (loading != null) {
            loading.leftAt = OptionalLong.of(now);
        }
    }

    /** Forgets everything: the store stops. */
    void clear() {
        assert Thread.holdsLock(lock);
        loggingIn.clear();
        pendingJoins.clear();
        recentlyLeft.clear();
    }

    /** One load after a join; {@code leftAt} is written and read under the store's lock. */
    static final class LateJoin {

        private OptionalLong leftAt = OptionalLong.empty();

        /** When the player quit while their value was loading, if they did. */
        OptionalLong leftAt() {
            return leftAt;
        }
    }

    /** A value loaded at pre-login, and when. */
    private record PendingLogin<T>(T value, long at) {}

    /** What a quit saved, which login or quit came last before it ({@code event}), and when. */
    private record Left<T>(T value, long event, long at) {}
}
