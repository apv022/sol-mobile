package io.solarae.sol.core;

import java.util.List;

/**
 * What the node is doing, as the notification and the start screen say it.
 * Worked out from facts only: is the process running, does its panel
 * answer, what do its transports report, does the phone have a network.
 */
public enum Phase {
    /** The service is not running the node (the user stopped it). */
    STOPPED,
    /** The process was started and its panel does not answer yet. */
    STARTING,
    /** The node runs; no transport has been ready yet in this run. */
    CONNECTING,
    /** A transport is ready: paired devices can reach this phone. */
    CONNECTED,
    /** It was connected before and is finding its way back. */
    RECONNECTING,
    /** The phone has no network; nothing can connect until it returns. */
    NO_NETWORK,
    /** The node process died and the service is about to start it again. */
    RESTARTING,
    /** The node keeps dying right after it starts. */
    FAILING;

    /** The state of the node's transports, read from GET /api/status. */
    public static final class Transports {
        /** At least one transport is ready. */
        public final boolean ready;
        /** What the most relevant transport says about itself ("bootstrapping 45%: …"). */
        public final String detail;

        /** Builds a state from plain values. */
        public Transports(boolean ready, String detail) {
            this.ready = ready;
            this.detail = detail == null ? "" : detail;
        }

        /**
         * Reads the body of GET /api/status.
         *
         * @throws IllegalArgumentException when it is not JSON
         */
        public static Transports parse(String statusJson) {
            Object root = Json.parse(statusJson);
            List<?> ts = Json.list(root, "transports");
            boolean ready = false;
            String detail = "";
            int rank = -1;
            for (Object t : ts) {
                String state = Json.str(t, "state");
                String d = Json.str(t, "detail");
                if (state.equals("ready")) {
                    ready = true;
                }
                // Tor's own words win; otherwise the busiest transport's.
                int r = state.equals("stopped") ? 0 : state.equals("ready") ? 1 : state.equals("failed") ? 2 : 3;
                if (Json.str(t, "name").equals("tor") && !state.equals("stopped")) {
                    r = 4;
                }
                if (r > rank && !d.isEmpty()) {
                    rank = r;
                    detail = d;
                }
            }
            return new Transports(ready, detail);
        }
    }

    /**
     * The phase of a node whose process is running.
     *
     * @param panelAnswers whether GET /api/status worked just now
     * @param t            its transports, or null when the panel does not answer
     * @param hasNetwork   whether Android reports a default network
     * @param wasConnected whether this service has seen the node connected before
     */
    public static Phase of(boolean panelAnswers, Transports t, boolean hasNetwork, boolean wasConnected) {
        if (!panelAnswers || t == null) {
            return STARTING;
        }
        if (t.ready && hasNetwork) {
            return CONNECTED;
        }
        if (!hasNetwork) {
            return NO_NETWORK;
        }
        return wasConnected ? RECONNECTING : CONNECTING;
    }
}
