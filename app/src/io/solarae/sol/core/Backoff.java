package io.solarae.sol.core;

/**
 * How long the service waits before starting the node again after it died.
 *
 * The delay doubles from {@link #FIRST_MS} to {@link #MAX_MS} while the node
 * keeps dying young, and goes back to the start once a run lasted
 * {@link #STABLE_MS}: a node that Android kills once an hour restarts at
 * once, a node that cannot start at all is retried once a minute instead of
 * in a tight loop that would drain the battery.
 */
public final class Backoff {
    public static final long FIRST_MS = 1_000;
    public static final long MAX_MS = 60_000;
    /** A run this long counts as healthy and resets the delay. */
    public static final long STABLE_MS = 60_000;

    private long next = FIRST_MS;
    private int failures;

    /**
     * The delay before the next start.
     *
     * @param uptimeMs how long the run that just ended lasted
     */
    public long next(long uptimeMs) {
        if (uptimeMs >= STABLE_MS) {
            reset();
        }
        long d = next;
        next = Math.min(next * 2, MAX_MS);
        failures++;
        return d;
    }

    /** Forget past failures (the user asked for a restart, or a run was healthy). */
    public void reset() {
        next = FIRST_MS;
        failures = 0;
    }

    /** Runs that ended early since the last healthy one. */
    public int failures() {
        return failures;
    }
}
