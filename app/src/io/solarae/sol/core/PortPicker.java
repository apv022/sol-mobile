package io.solarae.sol.core;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;

/**
 * Picks the loopback port for the node's panel.
 *
 * Sol's usual panel port is 8080, which other apps on a phone like too
 * (local web servers, proxies, development tools). The panel keeps its look
 * in the WebView's storage, which is tied to the port, so the port should
 * not change without need: the last port used is tried first, then the
 * ones just above it, then any free port.
 */
public final class PortPicker {
    /** Sol's default panel port. */
    public static final int DEFAULT = 8080;
    private static final int NEARBY = 20;

    private PortPicker() {}

    /**
     * A loopback port that was free a moment ago.
     *
     * @param preferred the port to use if it is free
     * @throws IOException when no port can be bound at all
     */
    public static int pick(int preferred) throws IOException {
        if (preferred < 1024 || preferred > 65535 - NEARBY) {
            preferred = DEFAULT;
        }
        for (int p = preferred; p <= preferred + NEARBY; p++) {
            if (free(p)) {
                return p;
            }
        }
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        }
    }

    /** Whether 127.0.0.1:{@code port} can be bound right now. */
    public static boolean free(int port) {
        try (ServerSocket s = new ServerSocket()) {
            // Without SO_REUSEADDR a port in TIME_WAIT from the node that
            // just exited would look taken; the node itself binds with it.
            s.setReuseAddress(true);
            s.bind(new java.net.InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), port), 1);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
