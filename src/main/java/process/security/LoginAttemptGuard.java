package process.security;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Slows a password guesser down: after {@value #MAX_FAILURES} wrong passwords against one name
 * within {@value #WINDOW_SECONDS} seconds, that name rests for {@value #LOCK_SECONDS} seconds.
 *
 * Per name rather than per address, because the console sits behind a proxy on every
 * deployment that matters and the address it sees is the proxy's. In memory rather than in the
 * database: a restart forgets the counts, which costs an attacker nothing they had not already
 * lost to the lock, and keeps a table of failed sign-ins out of the schema.
 *
 * Nothing here refuses a right password on the first try: only a run of wrong ones locks.
 *
 * @author Nabeel Ahmed
 */
@Component
public class LoginAttemptGuard {

    public static final int MAX_FAILURES = 5;
    public static final long WINDOW_SECONDS = 15 * 60;
    public static final long LOCK_SECONDS = 15 * 60;

    private static final class Attempts {
        int failures;
        long firstFailureAt;
        long lockedUntil;
    }

    private final Map<String, Attempts> byName = new ConcurrentHashMap<>();
    private final java.util.function.LongSupplier clock;

    public LoginAttemptGuard() {
        this(() -> System.currentTimeMillis() / 1000);
    }

    LoginAttemptGuard(java.util.function.LongSupplier clock) {
        this.clock = clock;
    }

    private static String key(String username) {
        return username == null ? "" : username.trim().toLowerCase();
    }

    /** Seconds the name has to wait before another attempt, or 0 when it may try now. */
    public long secondsUntilAllowed(String username) {
        Attempts a = this.byName.get(key(username));
        if (a == null) return 0;
        long now = this.clock.getAsLong();
        return a.lockedUntil > now ? a.lockedUntil - now : 0;
    }

    public void failed(String username) {
        long now = this.clock.getAsLong();
        this.byName.compute(key(username), (k, a) -> {
            if (a == null || now - a.firstFailureAt > WINDOW_SECONDS) {
                a = new Attempts();
                a.firstFailureAt = now;
            }
            a.failures++;
            if (a.failures >= MAX_FAILURES) {
                a.lockedUntil = now + LOCK_SECONDS;
                a.failures = 0;
                a.firstFailureAt = now;
            }
            return a;
        });
    }

    public void succeeded(String username) {
        this.byName.remove(key(username));
    }
}
