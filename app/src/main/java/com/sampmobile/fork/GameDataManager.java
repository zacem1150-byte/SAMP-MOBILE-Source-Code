package com.sampmobile.fork;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Enumeration;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Single-package game-data installer.
 *
 * Downloads ONE complete data package (files.zip, same source the
 * SA-MP Mobile Launcher uses) instead of per-file manifests. Completion is
 * version-pinned: once the configured data_version is installed and verified,
 * the user is never asked to download again.
 *
 * Data source: app.json -> data_url (MediaFire file page or direct link),
 * data_version, data_size, data_sentinel.
 */
public final class GameDataManager {
    // Direct link to the data package (GitHub release asset). Change DATA_VERSION
    // whenever you upload a new uksamp.zip so users re-download it.
    private static final String DATA_URL = "https://github.com/zacem1150-byte/launcher/releases/download/v1/uksamp.zip";
    private static final String DATA_VERSION = "1";
    private static final long   DATA_SIZE = 0;      // bytes; 0 = do not check size
    private static final String DATA_SENTINEL = "SAMP/main.scm"; // must exist after extract; "" = skip

    private static final String PREFS = "samp_game_data";
    private static final String KEY_DATA_VERSION = "data_version";
    private static final String MARKER = "SAMP/.data_ready";

    private static final int ACCENT = Color.rgb(240, 180, 41);
    private static final int CARD = Color.rgb(29, 35, 52);
    private static final int WHITE = Color.rgb(245, 247, 255);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    public interface ReadyCallback { void onReady(); }

    private static final class DataInfo {
        String url;
        String version;
        long size;
        String sentinel;
    }

    private GameDataManager() {}

    public static void checkAndPrepare(Activity activity, ReadyCallback callback) {
        showLoading(activity, "Checking game data", "Please wait...");
        IO.execute(() -> {
            try {
                DataInfo info = new DataInfo();
                info.url = DATA_URL;
                info.version = DATA_VERSION;
                info.size = DATA_SIZE;
                info.sentinel = DATA_SENTINEL;

                String installed = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
                        .getString(KEY_DATA_VERSION, "");
                if (info.version.equals(installed) && isInstalled(activity, info)) {
                    MAIN.post(() -> { dismissLoading(); if (!activity.isFinishing()) callback.onReady(); });
                    return;
                }
                final DataInfo fi = info;
                MAIN.post(() -> showDownloadOffer(activity, fi, callback));
            } catch (Exception e) {
                android.util.Log.e("GameDataManager", "Config check failed", e);
                MAIN.post(() -> showNetworkError(activity, e.getMessage(), callback));
            }
        });
    }

    /** Version-pinned completion check: marker + version + optional sentinel file. */
    private static boolean isInstalled(Activity a, DataInfo info) {
        try {
            File root = a.getExternalFilesDir(null);
            if (root == null) return false;
            File marker = new File(root, MARKER);
            if (!marker.isFile()) return false;
            if (info.sentinel != null && !info.sentinel.isEmpty()) {
                File s = new File(root, info.sentinel);
                if (!s.exists()) return false;
            }
            String[] list = root.list();
            return list != null && list.length > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void markInstalled(Activity a, DataInfo info) {
        try {
            File root = a.getExternalFilesDir(null);
            if (root == null) return;
            File marker = new File(root, MARKER);
            File parent = marker.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (OutputStream os = new FileOutputStream(marker)) {
                os.write(info.version.getBytes("UTF-8"));
            }
            a.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
                    .edit().putString(KEY_DATA_VERSION, info.version).apply();
        } catch (Exception e) {
            android.util.Log.e("GameDataManager", "markInstalled failed", e);
        }
    }

    /** Resolve a MediaFire file-page URL to a direct download URL. */
    private static String resolveDownloadUrl(String url) throws Exception {
        if (url.contains("mediafire.com/file/")) {
            String html = getText(url);
            Matcher m = Pattern.compile("https://download\\d*\\.mediafire\\.com/[^\\s\"'<>]+")
                    .matcher(html);
            if (m.find()) return m.group(0);
            throw new Exception("Could not find a direct download link on the MediaFire page.");
        }
        return url;
    }

    // ---------------------------------------------------------- UI: offer --
    private static volatile AlertDialog loadingDialog;

    private static void showLoading(Activity a, String title, String message) {
        if (a.isFinishing()) return;
        LinearLayout root = panel(a); root.setPadding(dp(a,24),dp(a,22),dp(a,24),dp(a,22));
        TextView h = text(a,title,21,true); TextView m = text(a,message,14,false); m.setTextColor(Color.LTGRAY);
        ProgressBar p = new ProgressBar(a); p.setIndeterminate(true);
        root.addView(h); addGap(a,root,8); root.addView(m); addGap(a,root,18); root.addView(p);
        AlertDialog d = new AlertDialog.Builder(a).setView(root).setCancelable(false).create();
        d.setOnShowListener(v -> styleWindow(a,d)); loadingDialog=d; d.show(); styleWindow(a,d);
    }

    private static void dismissLoading() {
        try { if (loadingDialog != null && loadingDialog.isShowing()) loadingDialog.dismiss(); }
        catch (Exception ignored) {}
        loadingDialog=null;
    }

    private static void showNetworkError(Activity a, String detail, ReadyCallback cb) {
        if (a.isFinishing()) return; dismissLoading(); setImmersive(a, false);
        new AlertDialog.Builder(a).setTitle("Could not load game data info")
                .setMessage("Failed to read the update config. Check your connection and try again.\n\n" + (detail == null ? "" : detail))
                .setCancelable(false).setPositiveButton("RETRY", (d,w) -> checkAndPrepare(a,cb))
                .setNegativeButton("EXIT", (d,w) -> a.finishAffinity()).show();
    }

    private static void showDownloadOffer(Activity a, DataInfo info, ReadyCallback cb) {
        if (a.isFinishing()) return; dismissLoading(); setImmersive(a, true);
        LinearLayout root=panel(a); root.setPadding(dp(a,22),dp(a,22),dp(a,22),dp(a,20));
        ImageView icon=iconView(a,R.drawable.ic_package,56);
        TextView title=text(a,"Download game data",22,true); title.setGravity(Gravity.CENTER);
        String sizeTxt = info.size > 0 ? " - " + fmtBytes(info.size) : "";
        TextView sub=text(a,"Full data package" + sizeTxt + "\nVersion " + info.version + " - one-time download.",14,false);
        sub.setTextColor(Color.LTGRAY); sub.setGravity(Gravity.CENTER);
        Button dl=button(a,"DOWNLOAD NOW");
        root.addView(icon); addGap(a,root,6); root.addView(title); addGap(a,root,8);
        root.addView(sub); addGap(a,root,18); root.addView(dl);
        AlertDialog d=new AlertDialog.Builder(a).setView(root).setCancelable(false).create(); d.show(); styleWindow(a,d);
        dl.setOnClickListener(v -> { d.dismiss(); startInstall(a, info, cb); });
    }

    // ---------------------------------------------------------- UI: progress
    private static final class ProgressUi {
        AlertDialog dialog;
        TextView phase, detail, speed;
        ProgressBar bar;
    }

    private static ProgressUi showProgress(Activity a, DataInfo info) {
        setImmersive(a, true);
        LinearLayout root=panel(a); root.setPadding(dp(a,22),dp(a,22),dp(a,24),dp(a,20));
        ImageView pic=iconView(a,R.drawable.ic_download,48);
        TextView title=text(a,"Preparing game data",21,true); title.setGravity(Gravity.CENTER);
        TextView phase=text(a,"Connecting...",14,false); phase.setTextColor(Color.LTGRAY);
        ProgressBar bar=new ProgressBar(a,null,android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000); bar.setProgress(0);
        bar.setProgressTintList(ColorStateList.valueOf(ACCENT));
        TextView detail=text(a,"",13,false); detail.setTextColor(Color.LTGRAY);
        TextView speed=text(a,"",13,false); speed.setTextColor(Color.LTGRAY);
        root.addView(pic); addGap(a,root,6); root.addView(title); addGap(a,root,10); root.addView(phase); addGap(a,root,12);
        root.addView(bar); addGap(a,root,8); root.addView(detail); addGap(a,root,2); root.addView(speed);
        AlertDialog d=new AlertDialog.Builder(a).setView(root).setCancelable(false).create();
        d.show(); styleWindow(a,d);
        ProgressUi ui=new ProgressUi(); ui.dialog=d; ui.phase=phase; ui.detail=detail; ui.speed=speed; ui.bar=bar;
        return ui;
    }

    // ---------------------------------------------------------- install ----
    private static void startInstall(Activity a, DataInfo info, ReadyCallback cb) {
        if (a.isFinishing()) return;
        ProgressUi ui = showProgress(a, info);
        IO.execute(() -> {
            File zipFile = null;
            try {
                postPhase(ui, "Resolving download link...", "");
                String directUrl = resolveDownloadUrl(info.url);
                android.util.Log.i("GameDataManager", "direct url resolved");

                File cacheDir = a.getExternalCacheDir();
                if (cacheDir == null) cacheDir = a.getCacheDir();
                zipFile = new File(cacheDir, "game_data_" + info.version + ".zip");
                if (zipFile.exists()) zipFile.delete();

                downloadWithProgress(directUrl, zipFile, info.size, ui);
                if (info.size > 0 && zipFile.length() != info.size)
                    throw new Exception("File size mismatch - the download may be corrupt. Try again.");

                postPhase(ui, "Extracting data...", "");
                extractWithProgress(a, zipFile, ui);

                File root = a.getExternalFilesDir(null);
                if (root == null) throw new Exception("Storage unavailable.");
                ensureGuiFont(root);
                if (info.sentinel != null && !info.sentinel.isEmpty()
                        && !new File(root, info.sentinel).exists())
                    throw new Exception("Data incomplete after extraction (sentinel missing).");

                markInstalled(a, info);
                try { if (zipFile.exists()) zipFile.delete(); } catch (Exception ignored) {}

                MAIN.post(() -> {
                    if (ui.dialog.isShowing()) ui.dialog.dismiss();
                    setImmersive(a, false);
                    Toast.makeText(a, "Game data ready", Toast.LENGTH_SHORT).show();
                    if (!a.isFinishing()) cb.onReady();
                });
            } catch (Exception e) {
                android.util.Log.e("GameDataManager", "Install failed", e);
                final File zf = zipFile;
                MAIN.post(() -> {
                    if (ui.dialog.isShowing()) ui.dialog.dismiss();
                    setImmersive(a, false);
                    if (a.isFinishing()) return;
                    new AlertDialog.Builder(a).setTitle("Download failed")
                            .setMessage(e.getMessage() == null ? "Please try again." : e.getMessage())
                            .setCancelable(false)
                            .setPositiveButton("RETRY", (x, w) -> startInstall(a, info, cb))
                            .setNegativeButton("EXIT", (x, w) -> a.finishAffinity())
                            .show();
                    try { if (zf != null && zf.exists()) zf.delete(); } catch (Exception ignored) {}
                });
            }
        });
    }

    /**
     * The native GUI loads SAMP/fonts/Arial.ttf (default in settings). The data
     * package ships its fonts in fonts/ instead, so copy one over if needed.
     */
    private static void ensureGuiFont(File root) {
        try {
            File dst = new File(root, "SAMP/fonts/Arial.ttf");
            if (dst.isFile() && dst.length() > 0) return;
            File src = new File(root, "fonts/arial_bold.ttf");
            if (!src.isFile()) return;
            File dir = dst.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            try (InputStream in = new BufferedInputStream(new java.io.FileInputStream(src));
                 OutputStream out = new BufferedOutputStream(new FileOutputStream(dst))) {
                byte[] b = new byte[64 * 1024]; int n;
                while ((n = in.read(b)) != -1) out.write(b, 0, n);
            }
        } catch (Exception e) {
            android.util.Log.e("GameDataManager", "ensureGuiFont failed", e);
        }
    }

    private static void downloadWithProgress(String url, File out, long expectedSize, ProgressUi ui) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000); c.setReadTimeout(60000);
        c.setRequestProperty("User-Agent", "SA-MP-Mobile");
        c.setInstanceFollowRedirects(true);
        int code = c.getResponseCode();
        if (code / 100 != 2) throw new Exception("Server refused the download (HTTP " + code + ").");
        long total = c.getContentLengthLong();
        if (total <= 0) total = expectedSize;

        final long totalF = total;
        postPhase(ui, "Downloading game data...", totalF > 0 ? "0 / " + fmtBytes(totalF) : "");
        long lastUi = 0, startNs = System.nanoTime();
        byte[] buf = new byte[128 * 1024];
        try (InputStream in = new BufferedInputStream(c.getInputStream());
             OutputStream fos = new BufferedOutputStream(new FileOutputStream(out))) {
            long done = 0; int n;
            while ((n = in.read(buf)) != -1) {
                fos.write(buf, 0, n);
                done += n;
                long now = System.currentTimeMillis();
                if (now - lastUi > 300) {
                    lastUi = now;
                    long elapsed = Math.max(1, (System.nanoTime() - startNs) / 1_000_000_000L);
                    long speed = done / elapsed;
                    final long d = done;
                    final int permille = totalF > 0 ? (int) Math.min(1000, (d * 1000) / totalF) : 0;
                    MAIN.post(() -> {
                        if (!ui.dialog.isShowing()) return;
                        ui.bar.setProgress(permille);
                        ui.detail.setText(fmtBytes(d) + (totalF > 0 ? " / " + fmtBytes(totalF) : "") + "  -  " + (permille / 10) + "%");
                        ui.speed.setText(fmtBytes(speed) + "/s");
                    });
                }
            }
        } finally {
            c.disconnect();
        }
        MAIN.post(() -> { if (ui.dialog.isShowing()) { ui.bar.setProgress(1000); ui.detail.setText("Download complete"); ui.speed.setText(""); } });
    }

    private static void extractWithProgress(Activity a, File zipFile, ProgressUi ui) throws Exception {
        File root = a.getExternalFilesDir(null);
        if (root == null) throw new Exception("Storage unavailable.");
        final String rootCanon = root.getCanonicalPath() + File.separator;
        try (ZipFile zip = new ZipFile(zipFile)) {
            int totalEntries = zip.size();
            if (totalEntries <= 0) throw new Exception("Zip file is empty or corrupt.");
            // Detect a single wrapping top-level folder (e.g. "files/...") and strip it.
            java.util.ArrayList<String> names = new java.util.ArrayList<>(totalEntries);
            Enumeration<? extends ZipEntry> scan = zip.entries();
            while (scan.hasMoreElements()) names.add(scan.nextElement().getName());
            final String stripPrefix = detectStripPrefix(names);
            if (!stripPrefix.isEmpty())
                android.util.Log.i("GameDataManager", "stripping zip prefix: " + stripPrefix);

            postPhase(ui, "Extracting data...", "0 / " + totalEntries + " files");
            Enumeration<? extends ZipEntry> en = zip.entries();
            int done = 0; long lastUi = 0;
            byte[] buf = new byte[128 * 1024];
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String name = e.getName();
                if (!stripPrefix.isEmpty()) {
                    if (!name.startsWith(stripPrefix)) continue;
                    name = name.substring(stripPrefix.length());
                }
                if (name.isEmpty()) { done++; continue; }
                File target = new File(root, name);
                // zip-slip protection
                String canon = target.getCanonicalPath();
                if (!canon.startsWith(rootCanon) && !canon.equals(root.getCanonicalPath()))
                    throw new Exception("Invalid zip entry: " + name);
                if (e.isDirectory()) {
                    target.mkdirs();
                } else {
                    File parent = target.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();
                    try (InputStream in = new BufferedInputStream(zip.getInputStream(e));
                         OutputStream os = new BufferedOutputStream(new FileOutputStream(target))) {
                        int n;
                        while ((n = in.read(buf)) != -1) os.write(buf, 0, n);
                    }
                }
                done++;
                long now = System.currentTimeMillis();
                if (now - lastUi > 300 || done == totalEntries) {
                    lastUi = now;
                    final int d = done; final String nm = name;
                    final int permille = (int) Math.min(1000, (d * 1000L) / totalEntries);
                    MAIN.post(() -> {
                        if (!ui.dialog.isShowing()) return;
                        ui.bar.setProgress(permille);
                        ui.detail.setText(d + " / " + totalEntries + " files  -  " + (permille / 10) + "%");
                        String shortName = nm.length() > 42 ? "..." + nm.substring(nm.length() - 39) : nm;
                        ui.speed.setText(shortName);
                    });
                }
            }
        }
        MAIN.post(() -> { if (ui.dialog.isShowing()) { ui.bar.setProgress(1000); ui.detail.setText("Extraction complete"); ui.speed.setText(""); } });
    }

    /** Returns e.g. "files/" when every entry lives under one top-level folder, else "". */
    private static String detectStripPrefix(java.util.List<String> names) {
        String firstSeg = null;
        for (String n : names) {
            int slash = n.indexOf('/');
            if (slash <= 0) return "";
            String seg = n.substring(0, slash + 1);
            if (firstSeg == null) firstSeg = seg;
            else if (!firstSeg.equals(seg)) return "";
        }
        return firstSeg == null ? "" : firstSeg;
    }

    private static void postPhase(ProgressUi ui, String phase, String detail) {
        MAIN.post(() -> {
            if (!ui.dialog.isShowing()) return;
            ui.phase.setText(phase);
            ui.detail.setText(detail);
            ui.speed.setText("");
        });
    }

    // ---------------------------------------------------------- helpers ----
    private static String fmtBytes(long n) {
        if (n < 1024) return n + " B";
        String[] u = {"B", "KB", "MB", "GB"};
        int i = 0; double v = n;
        while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
        return String.format(Locale.US, i == 0 ? "%.0f %s" : "%.2f %s", v, u[i]);
    }

    private static JSONObject getJson(String address) throws Exception {
        return new JSONObject(getText(address));
    }

    private static String getText(String address) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(15000); c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "SA-MP-Mobile");
        c.setInstanceFollowRedirects(true);
        try (InputStream in = new BufferedInputStream(c.getInputStream());
             BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
            StringBuilder b = new StringBuilder();
            char[] buf = new char[8192]; int n;
            while ((n = r.read(buf)) != -1) b.append(buf, 0, n);
            return b.toString();
        } finally {
            c.disconnect();
        }
    }

    private static LinearLayout panel(Activity a) { LinearLayout l=new LinearLayout(a); l.setOrientation(LinearLayout.VERTICAL); l.setGravity(Gravity.CENTER_HORIZONTAL); l.setBackground(shape(CARD,22)); return l; }
    private static TextView text(Activity a,String s,int size,boolean bold) { TextView t=new TextView(a); t.setText(s); t.setTextColor(WHITE); t.setTextSize(size); if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD); return t; }
    private static Button button(Activity a,String s) { Button b=new Button(a); b.setText(s); b.setTextColor(Color.rgb(20,24,31)); b.setTextSize(15); b.setAllCaps(false); b.setTypeface(Typeface.DEFAULT,Typeface.BOLD); b.setBackground(shape(ACCENT,14)); b.setPadding(dp(a,12),dp(a,10),dp(a,12),dp(a,10)); b.setMinHeight(dp(a,52)); return b; }
    private static ImageView iconView(Activity a,int resId,int sizeDp) {
        ImageView iv=new ImageView(a); iv.setImageResource(resId); iv.setColorFilter(ACCENT);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(a,sizeDp),dp(a,sizeDp));
        lp.gravity=Gravity.CENTER_HORIZONTAL; iv.setLayoutParams(lp); return iv;
    }
    private static void setImmersive(Activity a, boolean on) {
        if (a == null || a.isFinishing()) return;
        MAIN.post(() -> {
            try {
                android.view.Window w = a.getWindow();
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    android.view.WindowInsetsController c = w.getInsetsController();
                    if (c != null) {
                        if (on) { c.hide(android.view.WindowInsets.Type.navigationBars()); c.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE); }
                        else { c.show(android.view.WindowInsets.Type.navigationBars()); }
                    }
                } else {
                    android.view.View d = w.getDecorView();
                    if (on) d.setSystemUiVisibility(android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
                    else d.setSystemUiVisibility(android.view.View.SYSTEM_UI_FLAG_VISIBLE);
                }
            } catch (Exception ignored) {}
        });
    }
    private static GradientDrawable shape(int color,int radius) { GradientDrawable d=new GradientDrawable(); d.setColor(color); d.setCornerRadius(radius); return d; }
    private static void addGap(Activity a,LinearLayout l,int h) { View v=new View(a); l.addView(v,new LinearLayout.LayoutParams(1,dp(a,h))); }
    private static int dp(Activity a,int n) { return (int)(n*a.getResources().getDisplayMetrics().density+0.5f); }
    private static void styleWindow(Activity a,AlertDialog d) { if(d.getWindow()!=null){ d.getWindow().setBackgroundDrawableResource(android.R.color.transparent); d.getWindow().setLayout(Math.min(a.getResources().getDisplayMetrics().widthPixels-dp(a,40),dp(a,440)),-2); } }
}
