package io.solarae.sol.core;

/**
 * Whether the activity should start the service now.
 *
 * Opening the app means the user wants Sol running, so a stopped service
 * (the user pressed Stop in the notification, the service gave up, Android
 * refused a background restart) is started again. Two guards: nothing
 * before the activity has asked once itself (onCreate always does), and a
 * grace period after an ask, because the service takes a moment to leave
 * STOPPED and a second start would race the first.
 */
public final class NodeStart {
    /** How long after an ask a STOPPED phase is still the old one. */
    public static final long GRACE_MS = 3_000;

    private NodeStart() {}

    /**
     * @param askedAt elapsed-realtime of the activity's last start request, 0 if none yet
     * @param now elapsed-realtime now
     * @param phase what the service last reported
     */
    public static boolean shouldStart(long askedAt, long now, Phase phase) {
        return askedAt != 0 && phase == Phase.STOPPED && now - askedAt > GRACE_MS;
    }
}
