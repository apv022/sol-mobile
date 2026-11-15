package io.solarae.sol.core;

/**
 * What the service does when the node process ends without the service
 * having stopped it.
 *
 * The node runs with SOL_SUPERVISED set, so it never starts its own
 * replacement: it exits and leaves the restart to the service, which is the
 * only thing Android lets keep a process alive.
 */
public final class ExitPolicy {
    /** The node's "restart me" exit code (EX_TEMPFAIL, see cmd/sol/main.go). */
    public static final int EXIT_RESTART = 75;
    /** A clean exit this soon after starting is a failed start, not a stop. */
    public static final long QUICK_EXIT_MS = 5_000;

    /** The three things that can happen next. */
    public enum Action {
        /** Start again at once: the node asked for it (Settings, "Restart"). */
        RESTART_NOW,
        /** Start again after the {@link Backoff} delay: it crashed or was killed. */
        RESTART_LATER,
        /** Leave it stopped: it was told to stop over its control socket. */
        STOP
    }

    private ExitPolicy() {}

    /**
     * @param exitCode the process's exit status (128 + signal when killed)
     * @param uptimeMs how long it ran
     */
    public static Action afterExit(int exitCode, long uptimeMs) {
        if (exitCode == EXIT_RESTART) {
            return Action.RESTART_NOW;
        }
        if (exitCode == 0 && uptimeMs >= QUICK_EXIT_MS) {
            return Action.STOP;
        }
        return Action.RESTART_LATER;
    }

    /** A short description of an exit status for the log. */
    public static String describe(int exitCode) {
        if (exitCode == EXIT_RESTART) {
            return "asked to be restarted";
        }
        if (exitCode == 0) {
            return "exited";
        }
        if (exitCode == 137) {
            return "was killed (SIGKILL: Android or the user ended the process)";
        }
        if (exitCode > 128 && exitCode < 160) {
            return "was ended by signal " + (exitCode - 128);
        }
        return "exited with status " + exitCode;
    }
}
