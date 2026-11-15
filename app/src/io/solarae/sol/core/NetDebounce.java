package io.solarae.sol.core;

/**
 * Decides when a change of the phone's network should make the node
 * reconnect.
 *
 * Android reports a network switch as a burst of callbacks (lost, available,
 * addresses, capabilities), and a phone at the edge of Wi-Fi range flaps.
 * Reconnecting means Tor rebuilds its circuits, so it must happen once per
 * real change, after the change has settled, and not more often than
 * {@code minGapMs}. The latest state always wins: a change that arrives
 * during the gap is acted on when the gap ends, never dropped.
 *
 * Not thread-safe: call it from one thread (the service uses its main
 * thread). Time is passed in, so the logic can be tested without waiting.
 */
public final class NetDebounce {
    private final long settleMs;
    private final long minGapMs;

    private String acted;        // the network the node last (re)connected on; null before the first report
    private String current = ""; // the latest report; "" means no network
    private boolean pending;
    private long changedAt;
    private long lastFire = Long.MIN_VALUE / 2;

    /**
     * @param settleMs how long the network must stay the same before acting
     * @param minGapMs the least time between two reconnects
     */
    public NetDebounce(long settleMs, long minGapMs) {
        this.settleMs = settleMs;
        this.minGapMs = minGapMs;
    }

    /**
     * Reports the default network as it is now.
     *
     * @param signature identifies the network and its addresses; "" when
     *                  there is none
     * @return how many milliseconds from {@code now} to call
     *         {@link #fire}, or -1 when there is nothing to do
     */
    public long report(String signature, long now) {
        current = signature == null ? "" : signature;
        if (acted == null) {
            // The first report describes the network the node started on.
            acted = current;
            return -1;
        }
        if (current.isEmpty()) {
            // Offline: nothing to reconnect to. Remember it, so that the
            // same network coming back counts as a change.
            acted = "";
            pending = false;
            return -1;
        }
        if (current.equals(acted)) {
            pending = false;
            return -1;
        }
        pending = true;
        changedAt = now;
        return delay(now);
    }

    /**
     * Asks for a reconnect although the network looks the same (the phone
     * left Doze, where Android had cut the app's network).
     *
     * @return as {@link #report}
     */
    public long force(long now) {
        if (current.isEmpty()) {
            return -1;
        }
        pending = true;
        changedAt = now - settleMs;
        return delay(now);
    }

    /**
     * Called when the delay from {@link #report} or {@link #force} is over.
     *
     * @return 0 when the node should reconnect now, a positive number of
     *         milliseconds to wait and call again, or -1 when the change
     *         was undone in the meantime
     */
    public long fire(long now) {
        if (!pending) {
            return -1;
        }
        long d = delay(now);
        if (d > 0) {
            return d;
        }
        pending = false;
        acted = current;
        lastFire = now;
        return 0;
    }

    /** Whether the last report said there is no network. */
    public boolean offline() {
        return current.isEmpty();
    }

    private long delay(long now) {
        long at = Math.max(changedAt + settleMs, lastFire + minGapMs);
        return Math.max(0, at - now);
    }
}
