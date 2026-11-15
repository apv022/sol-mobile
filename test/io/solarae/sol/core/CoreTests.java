package io.solarae.sol.core;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.util.List;

/**
 * Tests for the Android-free classes in core/. JUnit is not part of the
 * toolchain, so this is a plain main(): each test is a method, a failed
 * check throws, and the exit status is the number of failures. Run it with
 * {@code ./build.sh test}.
 */
public final class CoreTests {
    private static int failed;

    public static void main(String[] args) throws Exception {
        run("backoff doubles and caps", CoreTests::backoff);
        run("backoff resets after a stable run", CoreTests::backoffReset);
        run("exit policy", CoreTests::exitPolicy);
        run("debounce ignores the first report", CoreTests::debounceFirst);
        run("debounce waits for the network to settle", CoreTests::debounceSettle);
        run("debounce flapping back cancels", CoreTests::debounceFlap);
        run("debounce min gap defers, not drops", CoreTests::debounceGap);
        run("debounce offline then back", CoreTests::debounceOffline);
        run("debounce force after doze", CoreTests::debounceForce);
        run("phase from facts", CoreTests::phase);
        run("transports parse", CoreTests::transports);
        run("port picker", CoreTests::ports);
        run("rotating log", CoreTests::rotatingLog);
        run("log tail and lastLines", CoreTests::tail);
        run("json", CoreTests::json);
        run("pair link", CoreTests::pairLink);
        run("file names", CoreTests::fileNames);
        System.out.println(failed == 0 ? "all passed" : failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }

    interface T { void run() throws Exception; }

    private static void run(String name, T t) {
        try {
            t.run();
            System.out.println("ok    " + name);
        } catch (Throwable e) {
            failed++;
            System.out.println("FAIL  " + name + ": " + e);
            StackTraceElement[] st = e.getStackTrace();
            if (st.length > 0) {
                System.out.println("        at " + st[0]);
            }
        }
    }

    private static void eq(Object want, Object got) {
        if (want == null ? got != null : !want.equals(got)) {
            throw new AssertionError("want " + want + ", got " + got);
        }
    }

    private static void yes(boolean b, String what) {
        if (!b) {
            throw new AssertionError(what);
        }
    }

    static void backoff() {
        Backoff b = new Backoff();
        eq(1000L, b.next(0));
        eq(2000L, b.next(0));
        eq(4000L, b.next(100));
        long last = 0;
        for (int i = 0; i < 10; i++) {
            last = b.next(0);
        }
        eq(Backoff.MAX_MS, last);
        eq(13, b.failures());
    }

    static void backoffReset() {
        Backoff b = new Backoff();
        b.next(0);
        b.next(0);
        eq(1000L, b.next(Backoff.STABLE_MS));
        eq(1, b.failures());
        b.next(0);
        b.reset();
        eq(0, b.failures());
        eq(1000L, b.next(0));
    }

    static void exitPolicy() {
        eq(ExitPolicy.Action.RESTART_NOW, ExitPolicy.afterExit(75, 10));
        eq(ExitPolicy.Action.STOP, ExitPolicy.afterExit(0, 60_000));
        eq(ExitPolicy.Action.RESTART_LATER, ExitPolicy.afterExit(0, 100));
        eq(ExitPolicy.Action.RESTART_LATER, ExitPolicy.afterExit(137, 600_000));
        eq(ExitPolicy.Action.RESTART_LATER, ExitPolicy.afterExit(1, 600_000));
        yes(ExitPolicy.describe(137).contains("SIGKILL"), "137");
        yes(ExitPolicy.describe(143).contains("15"), "143");
        yes(ExitPolicy.describe(2).contains("2"), "2");
    }

    static void debounceFirst() {
        NetDebounce d = new NetDebounce(3000, 30000);
        eq(-1L, d.report("wifi", 0));
        eq(-1L, d.report("wifi", 100));
        eq(-1L, d.fire(5000));
    }

    static void debounceSettle() {
        NetDebounce d = new NetDebounce(3000, 30000);
        d.report("wifi", 0);
        eq(3000L, d.report("cell", 1000));
        eq(2000L, d.fire(2000)); // too early: wait the rest
        eq(0L, d.fire(4000));
        eq(-1L, d.fire(4001)); // acted once only
    }

    static void debounceFlap() {
        NetDebounce d = new NetDebounce(3000, 30000);
        d.report("wifi", 0);
        d.report("cell", 1000);
        eq(-1L, d.report("wifi", 2000)); // back before it settled
        eq(-1L, d.fire(5000));
    }

    static void debounceGap() {
        NetDebounce d = new NetDebounce(1000, 30000);
        d.report("a", 0);
        d.report("b", 100);
        eq(0L, d.fire(1100));
        long wait = d.report("c", 2000); // inside the gap
        eq(29100L, wait); // acts when the gap ends (1100 + 30000 - 2000)
        eq(0L, d.fire(31100));
    }

    static void debounceOffline() {
        NetDebounce d = new NetDebounce(1000, 0);
        d.report("wifi", 0);
        eq(-1L, d.report("", 100));
        yes(d.offline(), "offline");
        // The same network returning after a gap counts as a change.
        yes(d.report("wifi", 200) >= 0, "back online is a change");
        eq(0L, d.fire(1200));
        yes(!d.offline(), "online");
    }

    static void debounceForce() {
        NetDebounce d = new NetDebounce(3000, 0);
        d.report("wifi", 0);
        eq(0L, d.force(10_000));
        eq(0L, d.fire(10_000));
        d.report("", 20_000);
        eq(-1L, d.force(30_000)); // no network, nothing to do
    }

    static void phase() {
        Phase.Transports ready = new Phase.Transports(true, "");
        Phase.Transports not = new Phase.Transports(false, "bootstrapping 10%");
        eq(Phase.STARTING, Phase.of(false, null, true, false));
        eq(Phase.CONNECTED, Phase.of(true, ready, true, false));
        eq(Phase.CONNECTING, Phase.of(true, not, true, false));
        eq(Phase.RECONNECTING, Phase.of(true, not, true, true));
        eq(Phase.NO_NETWORK, Phase.of(true, ready, false, true));
        eq(Phase.NO_NETWORK, Phase.of(true, not, false, false));
    }

    static void transports() {
        String j = "{\"id\":\"x\",\"transports\":[{\"name\":\"clearnet\",\"state\":\"stopped\",\"detail\":\"off\"},"
                + "{\"name\":\"tor\",\"state\":\"starting\",\"detail\":\"bootstrapping 45%\"}]}";
        Phase.Transports t = Phase.Transports.parse(j);
        yes(!t.ready, "not ready");
        eq("bootstrapping 45%", t.detail);
        yes(Phase.Transports.parse("{\"transports\":[{\"name\":\"tor\",\"state\":\"ready\"}]}").ready, "ready");
        yes(!Phase.Transports.parse("{}").ready, "no transports");
        try {
            Phase.Transports.parse("<html>");
            throw new AssertionError("should throw");
        } catch (IllegalArgumentException expected) {
            // good
        }
    }

    static void ports() throws IOException {
        int p = PortPicker.pick(PortPicker.DEFAULT);
        yes(p > 0, "port");
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getByAddress(new byte[] {127, 0, 0, 1}))) {
            int taken = s.getLocalPort();
            yes(!PortPicker.free(taken), "taken port is not free");
            int got = PortPicker.pick(taken);
            yes(got != taken, "picked a different port");
            yes(PortPicker.free(got) || got > 0, "usable");
        }
        yes(PortPicker.pick(5) > 0, "bad preference falls back");
    }

    static File tmp() throws IOException {
        File d = Files.createTempDirectory("soltest").toFile();
        d.deleteOnExit();
        return d;
    }

    static void rotatingLog() throws IOException {
        File d = tmp();
        File f = new File(d, "sol.log");
        RotatingLog l = new RotatingLog(f, 100);
        for (int i = 0; i < 100; i++) {
            l.line("line " + i + " xxxxxxxxxxxxxxxxxxxx");
        }
        l.close();
        yes(f.length() <= 100, "current file capped: " + f.length());
        File old = new File(f.getPath() + ".1");
        yes(old.exists() && old.length() <= 100, "old file kept and capped");
        String t = l.tail(1000);
        yes(t.contains("line 99"), "newest line kept");
        yes(!t.contains("line 0 "), "oldest dropped");
        l.line("after close");
        yes(l.tail(50).contains("after close"), "reopens");
        for (File x : d.listFiles()) {
            x.delete();
        }
        d.delete();
    }

    static void tail() throws IOException {
        File d = tmp();
        File f = new File(d, "a.log");
        Files.write(f.toPath(), "one\ntwo\nthree\nfour\n".getBytes("UTF-8"));
        eq("three\nfour\n", RotatingLog.tail(f, 12)); // starts on a line boundary
        eq("three\nfour", RotatingLog.lastLines("one\ntwo\nthree\nfour\n", 2));
        eq("a", RotatingLog.lastLines("a\n", 5));
        eq("", RotatingLog.lastLines("", 3));
        eq("", RotatingLog.tail(new File(d, "missing"), 10));
        f.delete();
        d.delete();
    }

    static void json() {
        Object o = Json.parse("{\"a\":[1,2,{\"b\":\"c\\u00e9\\n\"}],\"t\":true,\"n\":null}");
        eq(3, Json.list(o, "a").size());
        eq("cé\n", Json.str(((List<?>) Json.get(o, "a")).get(2), "b"));
        eq("", Json.str(o, "missing"));
        eq(0, Json.list(o, "missing").size());
        for (String bad : new String[] {"", "{", "[1,]x", "nul", "{\"a\":}"}) {
            try {
                Json.parse(bad);
                throw new AssertionError("accepted " + bad);
            } catch (IllegalArgumentException expected) {
                // good
            }
        }
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            deep.append('[');
        }
        try {
            Json.parse(deep.toString());
            throw new AssertionError("accepted deep nesting");
        } catch (IllegalArgumentException expected) {
            // good: no stack overflow
        }
    }

    static void pairLink() {
        String ok = "sol://pair?node=abc&name=Phone&s=secret&tor=x.onion";
        eq(null, PairLink.problem(ok));
        yes(PairLink.problem(null) != null, "null");
        yes(PairLink.problem("https://x") != null, "scheme");
        yes(PairLink.problem("sol://pair?node=a") != null, "no secret");
        yes(PairLink.problem(ok + "#x") != null, "fragment");
        yes(PairLink.problem(ok + " x") != null, "space");
        yes(PairLink.problem(ok + "\"><script>") != null, "quotes");
        StringBuilder big = new StringBuilder(ok);
        while (big.length() <= PairLink.MAX_LENGTH) {
            big.append("&x=1");
        }
        yes(PairLink.problem(big.toString()) != null, "too long");
        String f = PairLink.fragment(ok);
        yes(f.startsWith("pair=sol%3A%2F%2Fpair%3F"), f);
        yes(!f.contains("+") && !f.contains("&"), "encoded");
    }

    static void fileNames() {
        yes(!FileNames.safe("../../etc/passwd", "x").contains("/"), "traversal");
        yes(!FileNames.safe("a/b\\c:d", "f").matches(".*[/\\\\:].*"), "separators");
        yes(!FileNames.safe(".hidden", "f").startsWith("."), "hidden");
        eq("f", FileNames.safe("", "f"));
        eq("f", FileNames.safe(null, "f"));
        yes(FileNames.safe(new String(new char[500]).replace('\0', 'a') + ".txt", "f").endsWith(".txt"), "ext kept");
        yes(FileNames.safe(new String(new char[500]).replace('\0', 'a'), "f").length() <= 255, "short");
        eq("x y.txt", FileNames.fromContentDisposition("attachment; filename=\"x y.txt\""));
        eq("x y.txt", FileNames.fromContentDisposition("attachment; filename*=UTF-8''x%20y.txt"));
        eq(null, FileNames.fromContentDisposition("attachment"));
        eq(null, FileNames.fromContentDisposition(null));
    }
}
