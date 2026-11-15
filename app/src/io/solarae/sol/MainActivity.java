package io.solarae.sol;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.Message;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import io.solarae.sol.core.FileNames;
import io.solarae.sol.core.PairLink;
import io.solarae.sol.core.Phase;
import io.solarae.sol.core.RotatingLog;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * The whole UI: Sol's own web panel, served by the node on this device's
 * loopback address, shown in a WebView. There is one panel codebase for
 * every platform; this class starts the node, shows a native screen while
 * it comes up (or fails to), keeps navigation inside the panel, and saves
 * downloads. Everything the WebView may reach is loopback on the panel's
 * port; any other http(s) link opens in the phone's browser.
 *
 * <p>Invariants: pairing links are secrets, so they are never logged and
 * never saved in instance state; a {@code sol://pair} link only pre-fills
 * the panel's join screen, the user confirms there. The WebView gets no
 * JavaScript interface.
 */
public class MainActivity extends Activity {
    private static final int PICK_FILES = 1;
    private static final int SAVE_FILE = 2;
    private static final int NOTIF_PERM = 3;
    private static final long WAIT_MS = 90_000;
    private static final String PREFS = "ui";
    private static final String PREF_BATTERY_OFFERED = "battery_offered";
    private static final String PREF_NOTIF_ASKED = "notif_asked";
    private static final String STATE_WEB = "web";

    private enum Screen { LOADING, PANEL, ERROR, LOG }

    private final Handler main = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private WebView web;
    private LinearLayout loading;
    private LinearLayout error;
    private LinearLayout logView;
    private TextView loadingDetail;
    private TextView logText;
    private Screen screen = Screen.LOADING;
    private Screen beforeLog = Screen.LOADING;

    private ValueCallback<Uri[]> filePicker;
    private Bundle webState;          // WebView history restored after process death
    private int loadedPort;           // the port the WebView currently shows, 0 = none
    private String pendingPair;       // a checked sol://pair link waiting for the panel; a secret
    private int waitGen;              // bumped to cancel an older wait thread
    private boolean permissionPending;
    private String saveUrl;           // a download waiting for the user to pick where to save it
    private volatile boolean destroyed;

    private final BroadcastReceiver status = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            onStatus(i.getIntExtra(NodeService.EXTRA_PORT, 0));
        }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (saved != null) {
            webState = saved.getBundle(STATE_WEB);
        }
        root = new FrameLayout(this);
        root.setBackgroundColor(getColor(R.color.win_bg));
        setContentView(root);
        edgeToEdge();

        loading = buildLoading();
        error = buildError();
        logView = buildLog();
        root.addView(loading);
        root.addView(error);
        root.addView(logView);
        newWebView();
        show(Screen.LOADING);

        handleLink(getIntent());
        if (Build.VERSION.SDK_INT >= 33 && !prefs().getBoolean(PREF_NOTIF_ASKED, false)
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            prefs().edit().putBoolean(PREF_NOTIF_ASKED, true).apply();
            permissionPending = true;
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, NOTIF_PERM);
        }
        startNode();
    }

    // ---- window ----

    /** Draws behind the bars and pads the root by the bars and the keyboard. */
    private void edgeToEdge() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        root.setOnApplyWindowInsetsListener((v, in) -> {
            int l, t, r, b;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = in.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout());
                android.graphics.Insets ime = in.getInsets(WindowInsets.Type.ime());
                l = bars.left;
                t = bars.top;
                r = bars.right;
                b = Math.max(bars.bottom, ime.bottom);
            } else {
                l = in.getSystemWindowInsetLeft();
                t = in.getSystemWindowInsetTop();
                r = in.getSystemWindowInsetRight();
                b = in.getSystemWindowInsetBottom(); // includes the keyboard
            }
            v.setPadding(l, t, r, b);
            return in;
        });
        root.requestApplyInsets();
    }

    // ---- native screens ----

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER);
        l.setPadding(dp(24), dp(24), dp(24), dp(24));
        l.setBackgroundColor(getColor(R.color.win_bg));
        l.setVisibility(View.GONE);
        return l;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        if (bold) {
            t.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return t;
    }

    private LinearLayout buildLoading() {
        LinearLayout l = column();
        l.addView(new ProgressBar(this));
        TextView title = text(getString(R.string.loading_title), 20, getColor(R.color.win_fg), true);
        title.setPadding(0, dp(16), 0, dp(8));
        l.addView(title);
        loadingDetail = text(getString(R.string.loading_wait), 14, getColor(R.color.win_muted), false);
        l.addView(loadingDetail);
        return l;
    }

    private LinearLayout buildError() {
        LinearLayout l = column();
        TextView title = text(getString(R.string.error_title), 20, getColor(R.color.accent), true);
        title.setPadding(0, 0, 0, dp(8));
        l.addView(title);
        l.addView(text(getString(R.string.error_text), 14, getColor(R.color.win_muted), false));
        Button restart = new Button(this);
        restart.setText(R.string.restart_node);
        restart.setOnClickListener(v -> restartNode());
        Button log = new Button(this);
        log.setText(R.string.view_log);
        log.setOnClickListener(v -> showLog());
        l.addView(restart);
        l.addView(log);
        return l;
    }

    private LinearLayout buildLog() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackgroundColor(getColor(R.color.win_bg));
        l.setVisibility(View.GONE);
        TextView title = text(getString(R.string.log_title), 18, getColor(R.color.win_fg), true);
        title.setPadding(dp(16), dp(12), dp(16), dp(8));
        title.setGravity(Gravity.START);
        l.addView(title);
        ScrollView sv = new ScrollView(this);
        logText = new TextView(this);
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setTextSize(11);
        logText.setTextColor(getColor(R.color.win_fg));
        logText.setTextIsSelectable(true);
        logText.setPadding(dp(16), 0, dp(16), 0);
        sv.addView(logText);
        l.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.END);
        Button share = new Button(this);
        share.setText(R.string.share);
        share.setOnClickListener(v -> {
            Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, logText.getText().toString());
            startActivity(Intent.createChooser(send, getString(R.string.share)));
        });
        Button close = new Button(this);
        close.setText(R.string.close);
        close.setOnClickListener(v -> show(beforeLog));
        row.addView(share);
        row.addView(close);
        l.addView(row);
        return l;
    }

    private void show(Screen s) {
        screen = s;
        loading.setVisibility(s == Screen.LOADING ? View.VISIBLE : View.GONE);
        error.setVisibility(s == Screen.ERROR ? View.VISIBLE : View.GONE);
        logView.setVisibility(s == Screen.LOG ? View.VISIBLE : View.GONE);
        if (web != null) {
            web.setVisibility(s == Screen.PANEL ? View.VISIBLE : View.INVISIBLE);
        }
    }

    private void showLog() {
        beforeLog = screen == Screen.LOG ? beforeLog : screen;
        String t = RotatingLog.tail(NodeService.logFile(this), 32 * 1024);
        logText.setText(t == null || t.isEmpty() ? getString(R.string.log_empty) : t);
        show(Screen.LOG);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ---- node lifecycle ----

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    /**
     * Brings the node back if the service has stopped (the node exited on its
     * own, or the user pressed Stop in the notification) while the app was
     * away. Opening the app means the user wants Sol running. Without this the
     * panel keeps saying "reconnects by itself" about a node that is gone.
     */
    private void ensureNode() {
        if (nodeAskedAt != 0 && NodeService.phase() == Phase.STOPPED
                && SystemClock.elapsedRealtime() - nodeAskedAt > 3_000) {
            startNode();
        }
    }

    private long nodeAskedAt; // elapsedRealtime of the last startNode(); 0 before the first

    private void startNode() {
        nodeAskedAt = SystemClock.elapsedRealtime();
        try {
            startForegroundService(new Intent(this, NodeService.class));
        } catch (RuntimeException e) {
            // Android refused a foreground start from here; the error screen offers a retry.
        }
        awaitPanel();
    }

    private void restartNode() {
        show(Screen.LOADING);
        loadedPort = 0;
        startService(new Intent(this, NodeService.class).setAction(NodeService.ACTION_STOP));
        main.postDelayed(this::startNode, 1500);
    }

    /** Polls the panel until it answers, then loads it; the error screen after WAIT_MS. */
    private void awaitPanel() {
        final int gen = ++waitGen;
        if (screen != Screen.LOG) {
            show(Screen.LOADING);
        }
        updateDetail();
        new Thread(() -> {
            long deadline = System.currentTimeMillis() + WAIT_MS;
            while (!destroyed && gen == waitGen && System.currentTimeMillis() < deadline) {
                int port = NodeService.panelPort(this);
                if (panelAnswers(port)) {
                    main.post(() -> {
                        if (gen == waitGen && !destroyed) {
                            loadPanel(port);
                        }
                    });
                    return;
                }
                try {
                    Thread.sleep(400);
                } catch (InterruptedException e) {
                    return;
                }
            }
            if (!destroyed && gen == waitGen) {
                main.post(() -> {
                    if (gen == waitGen && screen != Screen.LOG) {
                        show(Screen.ERROR);
                    }
                });
            }
        }, "sol-wait").start();
    }

    private void updateDetail() {
        Phase p = NodeService.phase();
        String d = NodeService.detail();
        String line;
        switch (p) {
            case NO_NETWORK: line = getString(R.string.note_text_offline); break;
            case RESTARTING: line = getString(R.string.note_text_restarting); break;
            case FAILING: line = getString(R.string.note_text_failing); break;
            case STOPPED: line = getString(R.string.note_text_stopped); break;
            default: line = getString(R.string.loading_wait);
        }
        loadingDetail.setText(d == null || d.isEmpty() ? line : line + "\n" + d);
    }

    /** The service reported a change: refresh the text, and re-point the WebView if the port moved. */
    private void onStatus(int port) {
        if (destroyed) {
            return;
        }
        if (screen == Screen.LOADING) {
            updateDetail();
        }
        if (port != 0 && loadedPort != 0 && port != loadedPort) {
            loadedPort = 0;
            awaitPanel();
        }
    }

    private static boolean panelAnswers(int port) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/api/status").openConnection();
            c.setConnectTimeout(800);
            c.setReadTimeout(1500);
            try {
                return c.getResponseCode() == 200;
            } finally {
                c.disconnect();
            }
        } catch (Exception e) {
            return false;
        }
    }

    private void loadPanel(int port) {
        loadedPort = port;
        String base = "http://127.0.0.1:" + port;
        String link = pendingPair;
        pendingPair = null;
        if (link != null) {
            web.loadUrl(base + "/#" + PairLink.fragment(link));
        } else if (webState != null) {
            Bundle b = webState;
            webState = null;
            if (web.restoreState(b) == null) {
                web.loadUrl(base + "/");
            }
        } else {
            web.loadUrl(base + "/");
        }
        show(Screen.PANEL);
        maybeOfferBattery();
    }

    // ---- WebView ----

    private void newWebView() {
        web = new WebView(this);
        web.setBackgroundColor(getColor(R.color.win_bg));
        web.setVisibility(View.INVISIBLE);
        root.addView(web, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true); // the panel is a JS app, and only our own loopback page is ever loaded
        s.setDomStorageEnabled(true); // the panel's look is saved in localStorage
        s.setSupportMultipleWindows(true); // so "Open" (window.open) reaches onCreateWindow
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setSafeBrowsingEnabled(false); // it would send loopback URLs to Google
        // The panel reads this to know it runs in the app.
        s.setUserAgentString(s.getUserAgentString() + " SolAndroid/" + versionName());

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                if (isPanel(r.getUrl())) {
                    return false;
                }
                openOutside(r.getUrl());
                return true;
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                // The node went away under the panel (restart): wait for it instead of a browser error page.
                if (r.isForMainFrame() && isPanel(r.getUrl()) && screen == Screen.PANEL) {
                    loadedPort = 0;
                    awaitPanel();
                }
            }

            @Override
            public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
                if (v != web) {
                    return true;
                }
                // The renderer crashed or was killed for memory. The app survives: build a new view.
                root.removeView(web);
                web.destroy();
                loadedPort = 0;
                newWebView();
                awaitPanel();
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            // The panel opens a peer's app in a new tab. Here that means the
            // phone's browser: the app is on 127.0.0.1:<port> of this phone.
            @Override
            public boolean onCreateWindow(WebView v, boolean dialog, boolean userGesture, Message result) {
                WebView tmp = new WebView(MainActivity.this);
                tmp.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView t, WebResourceRequest r) {
                        openOutside(r.getUrl());
                        t.destroy();
                        return true;
                    }
                });
                ((WebView.WebViewTransport) result.obj).setWebView(tmp);
                result.sendToTarget();
                return true;
            }

            // "Send a file" and "Drop files here".
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (filePicker != null) {
                    filePicker.onReceiveValue(null);
                }
                filePicker = cb;
                Intent pick = new Intent(Intent.ACTION_GET_CONTENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("*/*")
                        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, p.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                try {
                    startActivityForResult(pick, PICK_FILES);
                } catch (ActivityNotFoundException e) {
                    filePicker = null;
                    return false;
                }
                return true;
            }
        });
        // The panel's Download links are plain http://127.0.0.1:<port>/blob/<hash>
        // (no blob: URLs), so a native HTTP fetch of the same URL works.
        web.setDownloadListener((url, ua, cd, mime, len) -> {
            Uri u = Uri.parse(url);
            if (!isPanel(u)) {
                openOutside(u);
                return;
            }
            String raw = FileNames.fromContentDisposition(cd);
            if (raw == null || raw.isEmpty()) {
                raw = URLUtil.guessFileName(url, cd, mime);
            }
            String name = FileNames.safe(raw, "download");
            String type = mime == null || mime.isEmpty() ? "application/octet-stream" : mime;
            saveUrl = url;
            try {
                startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType(type)
                        .putExtra(Intent.EXTRA_TITLE, name), SAVE_FILE);
            } catch (ActivityNotFoundException e) {
                saveUrl = null;
                toast(getString(R.string.save_failed));
            }
        });
    }

    private String versionName() {
        try {
            String v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            return v == null ? "0" : v.replaceAll("[^A-Za-z0-9._+-]", "");
        } catch (PackageManager.NameNotFoundException e) {
            return "0";
        }
    }

    /** Only the panel (http, 127.0.0.1, this node's port) stays in the WebView. */
    private boolean isPanel(Uri u) {
        int port = loadedPort != 0 ? loadedPort : NodeService.panelPort(this);
        return "http".equals(u.getScheme()) && "127.0.0.1".equals(u.getHost()) && u.getPort() == port;
    }

    private void openOutside(Uri u) {
        String scheme = u.getScheme();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return; // about:blank, javascript:, intent: and friends never leave the app
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, u));
        } catch (ActivityNotFoundException ignored) {
            // no browser installed
        }
    }

    // ---- sol://pair links ----

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleLink(intent);
        ensureNode();
    }

    /** Checks a sol://pair link and opens the panel's join screen with it. Never pairs by itself. */
    private void handleLink(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction()) || intent.getData() == null) {
            return;
        }
        String link = intent.getDataString();
        intent.setData(null); // do not replay the secret on rotation or recreation
        if (link == null || link.length() > PairLink.MAX_LENGTH || PairLink.problem(link) != null) {
            toast(getString(R.string.bad_link)); // never echo the link
            return;
        }
        if (loadedPort != 0 && screen != Screen.ERROR) {
            web.loadUrl("http://127.0.0.1:" + loadedPort + "/#" + PairLink.fragment(link));
            show(Screen.PANEL);
        } else {
            pendingPair = link;
        }
    }

    // ---- first run ----

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == NOTIF_PERM) {
            permissionPending = false;
            if (loadedPort != 0) {
                maybeOfferBattery();
            }
        }
    }

    /** Once, with an honest explanation, never silently. */
    private void maybeOfferBattery() {
        if (permissionPending || destroyed || prefs().getBoolean(PREF_BATTERY_OFFERED, false)
                || NodeService.batteryExempt(this)) {
            return;
        }
        prefs().edit().putBoolean(PREF_BATTERY_OFFERED, true).apply();
        new AlertDialog.Builder(this)
                .setTitle(R.string.battery_title)
                .setMessage(R.string.battery_text)
                .setPositiveButton(R.string.battery_yes, (d, w) -> {
                    try {
                        startActivity(NodeService.batteryExemptionIntent(this));
                    } catch (ActivityNotFoundException ignored) {
                        // no settings screen for it on this phone
                    }
                })
                .setNegativeButton(R.string.battery_no, null)
                .show();
    }

    // ---- files ----

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        if (request == PICK_FILES && filePicker != null) {
            Uri[] picked = null;
            if (result == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    picked = new Uri[data.getClipData().getItemCount()];
                    for (int i = 0; i < picked.length; i++) {
                        picked[i] = data.getClipData().getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    picked = new Uri[] {data.getData()};
                }
            }
            filePicker.onReceiveValue(picked);
            filePicker = null;
        } else if (request == SAVE_FILE) {
            String url = saveUrl;
            saveUrl = null;
            if (result == RESULT_OK && data != null && data.getData() != null && url != null) {
                save(url, data.getData(), CookieManager.getInstance().getCookie(url));
            }
        }
    }

    /** Streams a loopback download to the document the user chose. */
    private void save(String url, Uri dest, String cookie) {
        final Context app = getApplicationContext();
        new Thread(() -> {
            boolean ok = false;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(3000);
                if (cookie != null) {
                    c.setRequestProperty("Cookie", cookie);
                }
                try (InputStream in = c.getInputStream();
                     OutputStream out = app.getContentResolver().openOutputStream(dest)) {
                    byte[] buf = new byte[64 * 1024];
                    for (int n; (n = in.read(buf)) > 0; ) {
                        out.write(buf, 0, n);
                    }
                    ok = true;
                } finally {
                    c.disconnect();
                }
            } catch (Exception ignored) {
                // reported below; the URL and exception text are not shown
            }
            String name = FileNames.safe(dest.getLastPathSegment(), "file");
            String msg = ok ? app.getString(R.string.saved, name) : app.getString(R.string.save_failed);
            main.post(() -> Toast.makeText(app, msg, Toast.LENGTH_LONG).show());
        }, "sol-save").start();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ---- lifecycle ----

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter f = new IntentFilter(NodeService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(status, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(status, f);
        }
        ensureNode();
        // The service may have restarted on another port while we were away.
        if (loadedPort != 0 && NodeService.panelPort(this) != loadedPort) {
            onStatus(NodeService.panelPort(this));
        }
    }

    @Override
    protected void onStop() {
        unregisterReceiver(status);
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (loadedPort != 0) {
            Bundle b = new Bundle();
            if (web.saveState(b) != null) {
                out.putBundle(STATE_WEB, b);
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (screen == Screen.LOG) {
            show(beforeLog);
        } else if (screen == Screen.PANEL && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed(); // the node keeps running in its service
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        waitGen++;
        main.removeCallbacksAndMessages(null);
        web.destroy();
        super.onDestroy();
    }
}
