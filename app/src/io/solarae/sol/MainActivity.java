package io.solarae.sol;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayDeque;

/**
 * The whole UI: Sol's own web panel, served by the node on this device's
 * loopback address, shown in a WebView. There is one panel codebase for
 * every platform; this class only starts the node, waits for it, and
 * decides which links stay in the app.
 */
public class MainActivity extends Activity {
    private static final int PICK_FILES = 1;
    private static final long WAIT_MS = 60_000;

    private WebView web;
    private ValueCallback<Uri[]> filePicker;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean destroyed;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        web = new WebView(this);
        web.setBackgroundColor(0xff0d1117);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true); // the panel is a JS app, and only our own loopback page is ever loaded
        s.setDomStorageEnabled(true); // the panel's look is saved in localStorage
        s.setSupportMultipleWindows(true); // so "Open" (window.open) reaches onCreateWindow
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return leaveApp(r.getUrl());
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
                        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try {
                    startActivityForResult(pick, PICK_FILES);
                } catch (ActivityNotFoundException e) {
                    filePicker = null;
                    return false;
                }
                return true;
            }
        });

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 0);
        }
        start();
    }

    /** Starts the node (if it isn't running) and shows the panel once it answers. */
    private void start() {
        message("Starting Sol…", "The first start takes a few seconds.");
        startForegroundService(new Intent(this, NodeService.class));
        new Thread(() -> {
            long deadline = System.currentTimeMillis() + WAIT_MS;
            while (!destroyed && System.currentTimeMillis() < deadline) {
                if (panelAnswers()) {
                    main.post(() -> web.loadUrl(NodeService.PANEL + "/"));
                    return;
                }
                try {
                    Thread.sleep(400);
                } catch (InterruptedException e) {
                    return;
                }
            }
            String log = logTail(12);
            main.post(() -> message("Sol didn't start", log.isEmpty() ? "No log was written." : log));
        }, "sol-wait").start();
    }

    private static boolean panelAnswers() {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(NodeService.PANEL + "/api/status").openConnection();
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

    /** The last lines of the node's log, for the "didn't start" screen. */
    private String logTail(int n) {
        ArrayDeque<String> last = new ArrayDeque<>();
        try (BufferedReader r = new BufferedReader(new FileReader(new File(NodeService.dataDir(this), "sol.log")))) {
            for (String line; (line = r.readLine()) != null; ) {
                last.addLast(line);
                if (last.size() > n) {
                    last.removeFirst();
                }
            }
        } catch (Exception ignored) {
            // no log yet
        }
        return String.join("\n", last);
    }

    /** A plain status page in the panel's colours. The text is escaped. */
    private void message(String title, String body) {
        String html = "<html><body style='background:#0d1117;color:#c9d1d9;font:15px monospace;padding:24px'>"
                + "<h3 style='color:#ff8c2b'>" + esc(title) + "</h3><pre style='white-space:pre-wrap'>" + esc(body)
                + "</pre></body></html>";
        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Only the panel itself stays in the WebView; everything else opens outside. */
    private boolean leaveApp(Uri u) {
        Uri panel = Uri.parse(NodeService.PANEL);
        boolean isPanel = "http".equals(u.getScheme()) && panel.getHost().equals(u.getHost())
                && panel.getPort() == u.getPort();
        if (isPanel) {
            return false;
        }
        openOutside(u);
        return true;
    }

    private void openOutside(Uri u) {
        String scheme = u.getScheme();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return; // about:blank and friends
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, u));
        } catch (ActivityNotFoundException ignored) {
            // no browser installed
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        if (request != PICK_FILES || filePicker == null) {
            return;
        }
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
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed(); // the node keeps running in its service
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        web.destroy();
        super.onDestroy();
    }
}
