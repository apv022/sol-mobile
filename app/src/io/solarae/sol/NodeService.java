package io.solarae.sol;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import io.solarae.sol.core.Backoff;
import io.solarae.sol.core.ExitPolicy;
import io.solarae.sol.core.NetDebounce;
import io.solarae.sol.core.Phase;
import io.solarae.sol.core.PortPicker;
import io.solarae.sol.core.RotatingLog;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Runs this device's Sol node for as long as the user wants it running, and
 * keeps it running.
 *
 * The node is the same `sol` program as on a desktop, built for Android and
 * shipped in the APK as libsol.so next to Tor's own Android build
 * (libtor.so). Android only lets an app execute files from its native
 * library directory, which is why both are named like libraries.
 *
 * A foreground service (with its notification) keeps Android from stopping
 * the node when the app is not on screen. This class is also its supervisor:
 * <ul>
 *   <li>if the node exits unexpectedly it is started again, with a growing
 *       delay ({@link Backoff}, {@link ExitPolicy}); only the Stop button
 *       (or `sol stop`) leaves it stopped;</li>
 *   <li>when the phone changes network it runs `libsol.so network-changed`
 *       so Tor rebuilds its circuits instead of waiting for timeouts;</li>
 *   <li>the node's output goes to a size-capped log;</li>
 *   <li>the notification says what is true: starting, connecting, running,
 *       reconnecting, no network, restarting, failing.</li>
 * </ul>
 * Nothing here needs the VPN permission: see docs/DESIGN.md.
 *
 * <h2>API for the activity</h2>
 * <ul>
 *   <li>{@link #panelPort(Context)} / {@link #panelUrl(Context)}: the panel's
 *       loopback port. It is 8080 unless that was taken; the last choice is
 *       kept in SharedPreferences "sol", key "panel_port", and a
 *       {@link #ACTION_STATUS} broadcast follows every change.</li>
 *   <li>{@link #phase()} and {@link #detail()}: the latest {@link Phase}.</li>
 *   <li>{@link #ACTION_STATUS}: package-restricted broadcast with extras
 *       {@link #EXTRA_PHASE} (a Phase name), {@link #EXTRA_PORT} and
 *       {@link #EXTRA_DETAIL}, sent when the phase, port or detail changes.</li>
 *   <li>{@link #logFile(Context)}: the node's log; read it with
 *       {@link RotatingLog#tail(File, int)}.</li>
 *   <li>{@link #notificationsAllowed(Context)},
 *       {@link #batteryExempt(Context)} and
 *       {@link #batteryExemptionIntent(Context)}.</li>
 * </ul>
 */
public class NodeService extends Service {
    /** Stops the node and the service. Sent by the notification's Stop button. */
    static final String ACTION_STOP = "io.solarae.sol.STOP";
    /** Broadcast (limited to this app) when the phase, port or detail changes. */
    public static final String ACTION_STATUS = "io.solarae.sol.STATUS";
    /** String extra of {@link #ACTION_STATUS}: a {@link Phase} name. */
    public static final String EXTRA_PHASE = "phase";
    /** Int extra of {@link #ACTION_STATUS}: the panel's port. */
    public static final String EXTRA_PORT = "port";
    /** String extra of {@link #ACTION_STATUS}: what Tor says it is doing, may be empty. */
    public static final String EXTRA_DETAIL = "detail";
    /** The panel's address on Sol's default port. Use {@link #panelUrl(Context)} instead: the port can differ. */
    @Deprecated
    static final String PANEL = "http://127.0.0.1:8080";

    private static final String TAG = "Sol";
    private static final String CHANNEL = "node";
    private static final int NOTE_ID = 1;
    private static final String PREFS = "sol";
    private static final String PREF_PORT = "panel_port";
    private static final long LOG_MAX = 1 << 20; // two files of 1 MiB at most
    private static final long POLL_MS = 5_000;
    private static final long SETTLE_MS = 3_000;
    private static final long MIN_GAP_MS = 30_000;
    private static final int FAILING_AFTER = 3;

    private static volatile Phase phase = Phase.STOPPED;
    private static volatile String detail = "";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private final Backoff backoff = new Backoff();
    private final NetDebounce net = new NetDebounce(SETTLE_MS, MIN_GAP_MS); // main thread only
    private final ScheduledExecutorService aux = new ScheduledThreadPoolExecutor(2);

    private RotatingLog log;
    private Thread supervisor;
    private volatile Process node;
    private volatile boolean stopping;
    private volatile boolean hasNetwork = true;
    private volatile boolean wasConnected;
    private volatile int port;
    private ScheduledFuture<?> poller;
    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback netCallback;
    private BroadcastReceiver idleReceiver;
    private Runnable netTimer;
    private Phase shown;

    // ---- the API for the activity ----

    /** Where the node keeps its identity, config and log. */
    static File dataDir(Context c) {
        return new File(c.getFilesDir(), "sol");
    }

    /** The node's log (the previous one is {@code sol.log.1}). Private to the app. */
    public static File logFile(Context c) {
        return new File(dataDir(c), "sol.log");
    }

    /** The panel's port: the one the node was last started with, or 8080 before the first start. */
    public static int panelPort(Context c) {
        return c.getSharedPreferences(PREFS, MODE_PRIVATE).getInt(PREF_PORT, PortPicker.DEFAULT);
    }

    /** {@code http://127.0.0.1:<panelPort>}, no trailing slash. */
    public static String panelUrl(Context c) {
        return "http://127.0.0.1:" + panelPort(c);
    }

    /** The latest phase; {@link Phase#STOPPED} when the service is not running. */
    public static Phase phase() {
        return phase;
    }

    /** What Tor says it is doing ("bootstrapping 45%..."), or "". */
    public static String detail() {
        return detail;
    }

    /**
     * Whether the status notification may be shown. The service runs either
     * way, but without this permission (Android 13+) the user sees nothing
     * and may think the node is off. Ask with
     * {@code requestPermissions(POST_NOTIFICATIONS)}.
     */
    public static boolean notificationsAllowed(Context c) {
        if (Build.VERSION.SDK_INT >= 33
                && c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        return c.getSystemService(NotificationManager.class).areNotificationsEnabled();
    }

    /** Whether Android already lets the app run without battery optimisation. */
    public static boolean batteryExempt(Context c) {
        return c.getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(c.getPackageName());
    }

    /**
     * The intent that asks the user to exempt Sol from battery optimisation,
     * which stops Doze from cutting the node's network while the screen is
     * off. Start it with startActivity only after the user agreed to an
     * explanation; it opens a system dialog. Falls back to the system list
     * when the direct dialog is not available.
     */
    public static Intent batteryExemptionIntent(Context c) {
        Intent direct = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + c.getPackageName()));
        if (direct.resolveActivity(c.getPackageManager()) != null) {
            return direct;
        }
        return new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
    }

    // ---- lifecycle ----

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationChannel ch = new NotificationChannel(
                CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW);
        ch.setDescription(getString(R.string.channel_desc));
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
        File data = dataDir(this);
        data.mkdirs();
        log = new RotatingLog(logFile(this), LOG_MAX);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            log.line("[app] stopped by the user");
            stopSelf();
            return START_NOT_STICKY;
        }
        // Must come first: Android gives a few seconds after startForegroundService.
        try {
            Notification n = notification(Phase.STARTING);
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTE_ID, n);
            }
        } catch (RuntimeException e) {
            // Android 12+ refuses a foreground start from the background (for
            // example a sticky restart); the user opening the app starts it again.
            Log.e(TAG, "cannot run in the foreground", e);
            log.line("[app] cannot run in the foreground: " + e);
            stopSelf();
            return START_NOT_STICKY;
        }
        synchronized (lock) {
            if (supervisor == null) {
                stopping = false;
                shown = null;
                wasConnected = false;
                backoff.reset();
                setPhase(Phase.STARTING, "");
                registerNetwork();
                supervisor = new Thread(this::supervise, "sol-supervisor");
                supervisor.start();
                poller = aux.scheduleWithFixedDelay(this::poll, POLL_MS, POLL_MS, TimeUnit.MILLISECONDS);
            }
        }
        // After Android kills the app, bring the node back when it can
        // (intent is null then). A user Stop returned START_NOT_STICKY above.
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopping = true;
        main.removeCallbacksAndMessages(null);
        unregisterNetwork();
        if (poller != null) {
            poller.cancel(false);
        }
        aux.shutdownNow();
        Thread s;
        synchronized (lock) {
            s = supervisor;
            supervisor = null;
        }
        if (s != null) {
            s.interrupt();
        }
        stopProcess(node);
        if (s != null) {
            try {
                s.join(8000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        setPhase(Phase.STOPPED, "");
        log.line("[app] service stopped");
        log.close();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---- supervision ----

    /** Starts the node, waits for it to exit, decides what next. Runs until stopped. */
    private void supervise() {
        while (!stopping) {
            long began = SystemClock.elapsedRealtime();
            int code;
            try {
                reclaim();
                code = runOnce();
            } catch (IOException e) {
                log.line("[app] could not start the node: " + e.getMessage());
                code = -1;
            } catch (InterruptedException e) {
                return;
            }
            if (stopping) {
                return;
            }
            long up = SystemClock.elapsedRealtime() - began;
            ExitPolicy.Action a = ExitPolicy.afterExit(code, up);
            log.line("[app] the node " + ExitPolicy.describe(code) + " after " + up / 1000 + " s");
            if (a == ExitPolicy.Action.STOP) {
                log.line("[app] the node was stopped on its own request; stopping the service");
                stopSelf();
                return;
            }
            long wait = 0;
            if (a == ExitPolicy.Action.RESTART_NOW) {
                backoff.reset();
            } else {
                wait = backoff.next(up);
            }
            setPhase(backoff.failures() >= FAILING_AFTER ? Phase.FAILING : Phase.RESTARTING, "");
            if (wait > 0) {
                log.line("[app] starting it again in " + wait / 1000 + " s");
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    /** Runs the node once; returns its exit status. */
    private int runOnce() throws IOException, InterruptedException {
        File data = dataDir(this);
        if (!data.isDirectory() && !data.mkdirs()) {
            throw new IOException("can't create the data directory");
        }
        int p = pickPort();
        ProcessBuilder pb = command("run", "--data", data.getAbsolutePath(), "--panel", "127.0.0.1:" + p);
        // Without this the node answers "Restart" in the panel by starting a
        // replacement of its own, outside this service. It exits 75 instead.
        pb.environment().put("SOL_SUPERVISED", "1");
        pb.redirectErrorStream(true);
        log.line("[app] starting the node, panel on port " + p);
        setPhase(Phase.STARTING, "");
        Process proc = pb.start();
        node = proc;
        if (stopping) { // onDestroy ran between the check and the assignment
            stopProcess(proc);
        }
        Thread pump = new Thread(() -> pump(proc.getInputStream()), "sol-node-output");
        pump.start();
        int code;
        try {
            code = proc.waitFor();
        } finally {
            node = null;
        }
        pump.join(2000);
        return code;
    }

    /** The libsol.so command line with the environment `run` uses (data dir, tor, HOME). */
    private ProcessBuilder command(String... args) {
        String libs = getApplicationInfo().nativeLibraryDir;
        List<String> cmd = new ArrayList<>();
        cmd.add(libs + "/libsol.so");
        Collections.addAll(cmd, args);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        Map<String, String> env = pb.environment();
        // Sol looks for tor on PATH; on Android it is this one file.
        env.put("SOL_BIN_TOR", libs + "/libtor.so");
        env.put("HOME", getFilesDir().getAbsolutePath());
        env.put("TMPDIR", getCacheDir().getAbsolutePath());
        return pb;
    }

    /** Copies the node's output into the capped log. */
    private void pump(InputStream in) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String l;
            while ((l = r.readLine()) != null) {
                log.line(l);
            }
        } catch (IOException ignored) {
            // the process ended
        }
    }

    /** Picks the panel port: the last one if free, else a nearby one. Saves and announces it. */
    private int pickPort() throws IOException {
        int p = PortPicker.pick(panelPort(this));
        port = p;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(PREF_PORT, p).apply();
        broadcast();
        return p;
    }

    /**
     * Makes room for a new node. A node left over from a killed service still
     * owns the control socket and the panel port: it is asked to stop. A
     * socket nobody answers on is a stale lock left by a kill, and is
     * removed. Never deletes the socket of a node that answers.
     */
    private void reclaim() throws IOException, InterruptedException {
        File sock = new File(dataDir(this), "control.sock");
        if (!sock.exists()) {
            return;
        }
        if (answers(sock)) {
            log.line("[app] a node from an earlier run is still alive; asking it to stop");
            ProcessBuilder pb = command("stop", "--data", dataDir(this).getAbsolutePath());
            pb.redirectErrorStream(true);
            runQuietly(pb, 15);
            for (int i = 0; i < 20 && answers(sock); i++) {
                Thread.sleep(500);
            }
            if (answers(sock)) {
                throw new IOException("an earlier node is still running and will not stop");
            }
        }
        if (sock.exists() && sock.delete()) {
            log.line("[app] removed a stale control socket");
        }
    }

    /** Whether something is listening on a Unix socket file. */
    private static boolean answers(File sock) {
        try (LocalSocket s = new LocalSocket()) {
            s.connect(new LocalSocketAddress(sock.getAbsolutePath(), LocalSocketAddress.Namespace.FILESYSTEM));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** SIGTERM (the node closes connections and stops tor), then SIGKILL after a few seconds. */
    private static void stopProcess(Process p) {
        if (p == null) {
            return;
        }
        p.destroy();
        try {
            if (!p.waitFor(6, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    /** Runs a short helper command, logs its output, never throws. Returns whether it exited 0. */
    private boolean runQuietly(ProcessBuilder pb, int timeoutSec) {
        try {
            Process p = pb.start();
            Thread t = new Thread(() -> pump(p.getInputStream()), "sol-helper-output");
            t.start();
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                log.line("[app] the helper command timed out");
                return false;
            }
            t.join(1000);
            return p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            log.line("[app] could not run the helper command: " + e.getMessage());
            return false;
        }
    }

    // ---- network changes ----

    private void registerNetwork() {
        cm = getSystemService(ConnectivityManager.class);
        netCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network n) {
                report(cm.getNetworkCapabilities(n), cm.getLinkProperties(n) == null ? null
                        : cm.getLinkProperties(n).getLinkAddresses(), n);
            }

            @Override
            public void onLinkPropertiesChanged(Network n, android.net.LinkProperties lp) {
                report(cm.getNetworkCapabilities(n), lp.getLinkAddresses(), n);
            }

            @Override
            public void onCapabilitiesChanged(Network n, NetworkCapabilities nc) {
                android.net.LinkProperties lp = cm.getLinkProperties(n);
                report(nc, lp == null ? null : lp.getLinkAddresses(), n);
            }

            @Override
            public void onLost(Network n) {
                reportSignature("");
            }
        };
        try {
            cm.registerDefaultNetworkCallback(netCallback, main);
        } catch (RuntimeException e) {
            log.line("[app] cannot watch the network: " + e);
            netCallback = null;
        }
        // Leaving Doze: Android had cut the app's network, so the node's
        // connections are probably dead although the network looks the same.
        idleReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                PowerManager pm = c.getSystemService(PowerManager.class);
                if (!pm.isDeviceIdleMode()) {
                    schedule(net.force(SystemClock.elapsedRealtime()));
                }
            }
        };
        IntentFilter idle = new IntentFilter(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(idleReceiver, idle, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(idleReceiver, idle);
        }
    }

    private void unregisterNetwork() {
        if (netCallback != null && cm != null) {
            try {
                cm.unregisterNetworkCallback(netCallback);
            } catch (RuntimeException ignored) {
                // not registered
            }
        }
        if (idleReceiver != null) {
            try {
                unregisterReceiver(idleReceiver);
            } catch (RuntimeException ignored) {
                // not registered
            }
        }
    }

    /**
     * Identifies the network by its handle, kind and IPv4 addresses. IPv6
     * addresses are left out: Android rotates the privacy ones every few
     * hours, which is not a network change.
     */
    private void report(NetworkCapabilities nc, List<LinkAddress> addrs, Network n) {
        StringBuilder sb = new StringBuilder().append(n.getNetworkHandle());
        if (nc != null) {
            for (int t : new int[] {NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.TRANSPORT_CELLULAR,
                    NetworkCapabilities.TRANSPORT_ETHERNET, NetworkCapabilities.TRANSPORT_VPN}) {
                if (nc.hasTransport(t)) {
                    sb.append('/').append(t);
                }
            }
        }
        if (addrs != null) {
            List<String> v4 = new ArrayList<>();
            for (LinkAddress a : addrs) {
                if (a.getAddress() instanceof java.net.Inet4Address) {
                    v4.add(a.getAddress().getHostAddress());
                }
            }
            Collections.sort(v4);
            sb.append('/').append(v4);
        }
        reportSignature(sb.toString());
    }

    private void reportSignature(String sig) {
        hasNetwork = !sig.isEmpty();
        schedule(net.report(sig, SystemClock.elapsedRealtime()));
    }

    /** Arms the debounce timer (main thread). {@code delay} is from NetDebounce: -1 means nothing to do. */
    private void schedule(long delay) {
        if (delay < 0 || stopping) {
            return;
        }
        if (netTimer != null) {
            main.removeCallbacks(netTimer);
        }
        netTimer = () -> {
            long again = net.fire(SystemClock.elapsedRealtime());
            if (again == 0) {
                reconnect();
            } else if (again > 0) {
                schedule(again);
            }
        };
        main.postDelayed(netTimer, delay);
    }

    /** Tells the running node the network changed. A node without the subcommand just fails; that is logged. */
    private void reconnect() {
        if (node == null || stopping) {
            return; // not running: the fresh node starts on the new network anyway
        }
        log.line("[app] the network changed; telling the node to reconnect");
        try {
            aux.execute(() -> {
                ProcessBuilder pb = command("network-changed", "--data", dataDir(this).getAbsolutePath());
                pb.redirectErrorStream(true);
                if (!runQuietly(pb, 20)) {
                    log.line("[app] network-changed did not succeed (older node, or it is not up yet)");
                }
            });
        } catch (RuntimeException e) {
            // the executor was shut down: the service is stopping
        }
    }

    // ---- status and notification ----

    /** Asks the node's panel what it is doing and updates the phase. Runs every few seconds. */
    private void poll() {
        try {
            if (stopping || node == null) {
                return;
            }
            Phase.Transports t = null;
            try {
                t = Phase.Transports.parse(get("http://127.0.0.1:" + port + "/api/status"));
            } catch (IOException | IllegalArgumentException e) {
                // not up yet: STARTING
            }
            Phase p = Phase.of(t != null, t, hasNetwork, wasConnected);
            if (p == Phase.CONNECTED) {
                wasConnected = true;
            }
            if (node != null && !stopping) {
                setPhase(p, t == null ? "" : t.detail);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "status poll failed", e);
        }
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(2000);
        c.setReadTimeout(3000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0 && b.size() < 256 * 1024) {
                b.write(buf, 0, n);
            }
            return b.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private synchronized void setPhase(Phase p, String d) {
        boolean changed = p != phase || !d.equals(detail);
        phase = p;
        detail = d;
        if (!changed) {
            return;
        }
        broadcast();
        if (p != shown && p != Phase.STOPPED) {
            shown = p;
            getSystemService(NotificationManager.class).notify(NOTE_ID, notification(p));
        }
    }

    private void broadcast() {
        sendBroadcast(new Intent(ACTION_STATUS).setPackage(getPackageName())
                .putExtra(EXTRA_PHASE, phase.name())
                .putExtra(EXTRA_PORT, port == 0 ? panelPort(this) : port)
                .putExtra(EXTRA_DETAIL, detail));
    }

    private Notification notification(Phase p) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, NodeService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        int title, text;
        switch (p) {
            case CONNECTING: title = R.string.note_title_connecting; text = R.string.note_text_connecting; break;
            case CONNECTED: title = R.string.note_title_connected; text = R.string.note_text; break;
            case RECONNECTING: title = R.string.note_title_reconnecting; text = R.string.note_text_reconnecting; break;
            case NO_NETWORK: title = R.string.note_title_offline; text = R.string.note_text_offline; break;
            case RESTARTING: title = R.string.note_title_restarting; text = R.string.note_text_restarting; break;
            case FAILING: title = R.string.note_title_failing; text = R.string.note_text_failing; break;
            case STOPPED: title = R.string.note_title_stopped; text = R.string.note_text_stopped; break;
            default: title = R.string.note_title_starting; text = R.string.note_text_starting; break;
        }
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(title))
                .setContentText(getString(text))
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(null, getString(R.string.note_stop), stop).build());
        if (Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return b.build();
    }
}
