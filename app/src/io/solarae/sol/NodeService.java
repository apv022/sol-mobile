package io.solarae.sol;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs this device's Sol node for as long as the user wants it running.
 *
 * The node is the same `sol` program as on a desktop, built for Android and
 * shipped in the APK as libsol.so next to Tor's own Android build
 * (libtor.so). Android only lets an app execute files from its native
 * library directory, which is why both are named like libraries.
 *
 * A foreground service (with its notification) is what keeps Android from
 * stopping the node when the app is not on screen. Nothing here needs the
 * VPN permission: see docs/DESIGN.md.
 */
public class NodeService extends Service {
    static final String ACTION_STOP = "io.solarae.sol.STOP";
    /** The panel's address. Sol's default panel port, bound to loopback only. */
    static final String PANEL = "http://127.0.0.1:8080";

    private static final String TAG = "Sol";
    private static final String CHANNEL = "node";
    private static final int NOTE_ID = 1;
    private static final long LOG_MAX = 2 << 20; // start a fresh sol.log above 2 MiB

    private Process node;
    private volatile boolean stopping;

    /** Where the node keeps its identity, config and log. */
    static File dataDir(android.content.Context c) {
        return new File(c.getFilesDir(), "sol");
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationChannel ch = new NotificationChannel(
                CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW);
        ch.setDescription(getString(R.string.channel_desc));
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTE_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTE_ID, notification());
        }
        if (node == null) {
            try {
                startNode();
            } catch (IOException e) {
                Log.e(TAG, "starting the node failed", e);
                stopSelf();
                return START_NOT_STICKY;
            }
        }
        // If Android kills the app, bring the node back when it can.
        return START_STICKY;
    }

    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, NodeService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.note_title))
                .setContentText(getString(R.string.note_text))
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, getString(R.string.note_stop), stop).build())
                .build();
    }

    private void startNode() throws IOException {
        File data = dataDir(this);
        if (!data.isDirectory() && !data.mkdirs()) {
            throw new IOException("can't create " + data);
        }
        File log = new File(data, "sol.log");
        if (log.length() > LOG_MAX) {
            log.delete();
        }
        String libs = getApplicationInfo().nativeLibraryDir;
        ProcessBuilder pb = new ProcessBuilder(libs + "/libsol.so", "run", "--data", data.getAbsolutePath());
        Map<String, String> env = pb.environment();
        // Sol looks for tor on PATH; on Android it is this one file.
        env.put("SOL_BIN_TOR", libs + "/libtor.so");
        env.put("HOME", getFilesDir().getAbsolutePath());
        env.put("TMPDIR", getCacheDir().getAbsolutePath());
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log));
        node = pb.start();
        final Process p = node;
        new Thread(() -> {
            try {
                int code = p.waitFor();
                Log.i(TAG, "the node exited with " + code);
            } catch (InterruptedException ignored) {
                return;
            }
            // The node ended by itself (a crash, or "Restart" in the panel):
            // stop the service so the notification doesn't claim it runs.
            if (!stopping) {
                stopSelf();
            }
        }, "sol-node-wait").start();
    }

    @Override
    public void onDestroy() {
        stopping = true;
        Process p = node;
        node = null;
        if (p != null) {
            p.destroy(); // SIGTERM: the node closes connections and stops tor
            try {
                if (!p.waitFor(6, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
            } catch (InterruptedException e) {
                p.destroyForcibly();
            }
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
