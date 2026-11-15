package io.solarae.sol.core;

/**
 * What the service does when the node process ends.
 *
 * The node runs with SOL_SUPERVISED set, so it never starts its own
 * replacement: it exits and leaves the restart to the service, which is the
 * only thing Android lets keep a process alive.
 *
 * The decision rests on who ended the node, not on the exit code. The node
 * exits 0 on SIGTERM, on {@code sol stop} and on a clean shutdown alike, so
 * a 0 says nothing about whether anybody asked. The service knows whether
 * it did (the user's Stop, its own teardown), and anything else is
 * unexpected and is restarted.
 */
public final class ExitPolicy {
    /** The node's "restart me" exit code (EX_TEMPFAIL, see cmd/sol/main.go). */
    public static final int EXIT_RESTART = 75;
    /** Consecutive early deaths after which the service stops trying (about ten minutes of backoff). */
    public static final int GIVE_UP_AFTER = 15;

    /** What happens next. */
    public enum Action {
        /** Start again at once: the node asked for it (Settings, "Restart"). */
        RESTART_NOW,
        /** Start again after the {@link Backoff} delay: it crashed or was killed. */
        RESTART_LATER,
        /** Leave it stopped: the service itself asked the node to stop. */
        STOP,
        /** Leave it stopped: it died young too many times in a row; the user can start it from the app. */
        GIVE_UP
    }

    private ExitPolicy() {}

    /**
     * @param exitCode the process's exit status (128 + signal when killed)
     * @param uptimeMs how long it ran
     * @param requested whether the service asked the node to stop
     * @param earlierFailures early deaths since the last healthy run, not counting this one
     */
    public static Action afterExit(int exitCode, long uptimeMs, boolean requested, int earlierFailures) {
        if (requested) {
            return Action.STOP;
        }
        if (exitCode == EXIT_RESTART) {
            return Action.RESTART_NOW;
        }
        int failures = uptimeMs >= Backoff.STABLE_MS ? 1 : earlierFailures + 1;
        return failures >= GIVE_UP_AFTER ? Action.GIVE_UP : Action.RESTART_LATER;
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
