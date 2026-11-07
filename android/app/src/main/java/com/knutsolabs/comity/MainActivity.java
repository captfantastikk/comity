package com.knutsolabs.comity;

import android.Manifest;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.window.OnBackInvokedDispatcher;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_MIC = 7;
    private static final String DARK = "#12171B", LIGHT = "#F3F6F4";
    private static WeakReference<MainActivity> current = new WeakReference<>(null);

    private final ExecutorService io = Executors.newCachedThreadPool();
    private FrameLayout root;
    private WebView web;

    /** Runs a line of JavaScript in the open Comity screen, if there is one. */
    static void sendToPage(String js) {
        MainActivity a = current.get();
        if (a != null) a.runOnUiThread(() -> { if (a.web != null) a.web.evaluateJavascript(js, null); });
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        current = new WeakReference<>(this);

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor(DARK));
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor(DARK));
        root.addView(web, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets b = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.ime());
            v.setPadding(b.left, b.top, b.right, b.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        setBars(false);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(true);
        web.addJavascriptInterface(new Bridge(), "ComityNative");
        web.loadUrl("file:///android_asset/index.html");

        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        }
    }

    @Override protected void onDestroy() {
        if (current.get() == this) current = new WeakReference<>(null);
        if (web != null) { web.destroy(); web = null; }
        io.shutdown();
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() { handleBack(); }

    /** Lets the page go back a screen; leaves the app when the page is already on its first screen. */
    private void handleBack() {
        if (web == null) { finish(); return; }
        web.evaluateJavascript("(window.comityBack && window.comityBack()) ? 1 : 0", v -> {
            if (!"1".equals(v)) moveTaskToBack(true);
        });
    }

    private void setBars(boolean light) {
        int color = Color.parseColor(light ? LIGHT : DARK);
        root.setBackgroundColor(color);
        if (web != null) web.setBackgroundColor(color);
        WindowInsetsControllerCompat c = WindowCompat.getInsetsController(getWindow(), root);
        c.setAppearanceLightStatusBars(light);
        c.setAppearanceLightNavigationBars(light);
    }

    private void askForMic() {
        List<String> need = new ArrayList<>();
        need.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        requestPermissions(need.toArray(new String[0]), REQ_MIC);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_MIC) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) RecorderService.start(this);
        else sendToPage("window.onRecError && window.onRecError('mic')");
    }

    private static String readAll(InputStream in) throws Exception {
        try (InputStream i = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = i.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    /** Only files Comity recorded itself can be sent or deleted. */
    private File ownAudio(String path) {
        if (path == null || path.isEmpty()) return null;
        try {
            File f = new File(path).getCanonicalFile();
            File dir = Recorder.audioDir(this).getCanonicalFile();
            return dir.equals(f.getParentFile()) ? f : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Called from the page's JavaScript. */
    private final class Bridge {
        @JavascriptInterface public void startRecording() {
            runOnUiThread(() -> {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) RecorderService.start(MainActivity.this);
                else askForMic();
            });
        }

        @JavascriptInterface public void pauseRecording() { Recorder.pause(); RecorderService.refresh(MainActivity.this); }

        @JavascriptInterface public void resumeRecording() { Recorder.resume(); RecorderService.refresh(MainActivity.this); }

        @JavascriptInterface public String stopRecording() {
            String result = Recorder.finish();
            RecorderService.stop(MainActivity.this);
            return result;
        }

        @JavascriptInterface public String recState() { return Recorder.stateJson(); }

        /** A recording Android ended on its own (for example, the phone ran out of battery mid-way). */
        @JavascriptInterface public String takeOrphan() {
            SharedPreferences sp = getSharedPreferences("comity", MODE_PRIVATE);
            String o = sp.getString("orphan", "");
            if (!o.isEmpty()) sp.edit().remove("orphan").apply();
            return o;
        }

        @JavascriptInterface public void deleteFile(String path) {
            File f = ownAudio(path);
            if (f != null) //noinspection ResultOfMethodCallIgnored
                f.delete();
        }

        /** Removes leftover audio except the recording in progress and the one still waiting to be finished. */
        @JavascriptInterface public void cleanupAudio(String keepPath) {
            File[] files = Recorder.audioDir(MainActivity.this).listFiles();
            if (files == null) return;
            String recording = Recorder.currentPath();
            for (File f : files) {
                String p = f.getAbsolutePath();
                if (!p.equals(keepPath) && !p.equals(recording)) //noinspection ResultOfMethodCallIgnored
                    f.delete();
            }
        }

        @JavascriptInterface public void keepAwake(boolean on) {
            runOnUiThread(() -> {
                if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }

        @JavascriptInterface public void setLook(boolean light) { runOnUiThread(() -> setBars(light)); }

        /** Sends a web request for the page; the answer comes back through window.__nativeHttpDone. */
        @JavascriptInterface public void http(String id, String method, String url, String headersJson, String body, String filePath) {
            io.execute(() -> {
                int status;
                String text;
                HttpURLConnection c = null;
                try {
                    if (!url.startsWith("https://")) throw new IllegalArgumentException("Only secure addresses are allowed.");
                    c = (HttpURLConnection) new URL(url).openConnection();
                    c.setRequestMethod(method);
                    c.setConnectTimeout(30000);
                    c.setReadTimeout(300000);
                    JSONObject h = new JSONObject(headersJson == null || headersJson.isEmpty() ? "{}" : headersJson);
                    for (Iterator<String> it = h.keys(); it.hasNext(); ) {
                        String k = it.next();
                        c.setRequestProperty(k, h.getString(k));
                    }
                    File f = ownAudio(filePath);
                    if (filePath != null && !filePath.isEmpty() && f == null) throw new IllegalArgumentException("The recording file is missing.");
                    if (f != null) {
                        c.setDoOutput(true);
                        c.setFixedLengthStreamingMode(f.length());
                        try (InputStream in = new FileInputStream(f); OutputStream out = c.getOutputStream()) {
                            byte[] buf = new byte[65536];
                            int n;
                            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        }
                    } else if (body != null && !body.isEmpty()) {
                        byte[] b = body.getBytes(StandardCharsets.UTF_8);
                        c.setDoOutput(true);
                        c.setFixedLengthStreamingMode(b.length);
                        try (OutputStream out = c.getOutputStream()) { out.write(b); }
                    }
                    status = c.getResponseCode();
                    InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
                    text = in == null ? "" : readAll(in);
                } catch (Exception e) {
                    status = 0;
                    text = String.valueOf(e.getMessage());
                } finally {
                    if (c != null) c.disconnect();
                }
                sendToPage("window.__nativeHttpDone(" + JSONObject.quote(id) + "," + status + "," + JSONObject.quote(text) + ")");
            });
        }
    }
}
