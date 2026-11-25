package com.production.monitoring;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {

    private static final String PREFS_NAME = "monitoring_prefs";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_APP_THEME = "app_theme";
    private static final String GITHUB_LATEST_RELEASE_API =
            "https://api.github.com/repos/ivanporud-cpu/monitoring/releases/latest";
    private static final String PERIODIC_WORK_NAME = "new_day_periodic_check";
    private static final int REQ_POST_NOTIFICATIONS = 2001;
    private static final int REQ_CAMERA_PERMISSION = 2002;
    private String pendingScanTarget = "global";

    private FrameLayout rootContainer;
    private WebView webView;
    private WebView visualizerWebView;
    private boolean visualizerPageReady = false;
    private volatile String pendingVisualizerPayload = "";

    private SharedPreferences prefs;
    private String cachedHtml;
    private String cachedVisualizerHtml;
    private String activeBaseUrl = "";
    private long lastBackPressMs = 0L;
    private int pendingOpenLineIdx = -1;

    private final AtomicBoolean isAutoDiscovering = new AtomicBoolean(false);
    private volatile long lastAutoDiscoveryMs = 0L;
    private final ConcurrentHashMap<String, String> asyncResults = new ConcurrentHashMap<>();

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean startDark = "dark".equals(prefs.getString(KEY_APP_THEME, "light"));
        int startBgColor = Color.parseColor(startDark ? "#0f1626" : "#F2F2F2");

        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);

        // Инициализируем токен Яндекс Диска по умолчанию, если он ещё не сохранён
        if (!prefs.contains(NewDayWorker.KEY_YANDEX_TOKEN)) {
            prefs.edit()
                    .putString(NewDayWorker.KEY_YANDEX_TOKEN, NewDayWorker.DEFAULT_YANDEX_TOKEN)
                    .putBoolean(NewDayWorker.KEY_YANDEX_ENABLED, true)
                    .apply();
        }

        handleNotificationIntent(getIntent());
        NewDayWorker.ensureNotificationChannel(this);
        NewDayWorker.clearPendingNotifications(this);
        requestNotificationPermissionIfNeeded();
        scheduleBackgroundNewDayCheck();

        rootContainer = new FrameLayout(this);
        rootContainer.setBackgroundColor(startBgColor);
        setContentView(rootContainer);

        webView = new WebView(this);
        webView.setBackgroundColor(startBgColor);
        applySystemBarsTheme(startDark);
        configureWebViewSettings(webView);
        rootContainer.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        webView.addJavascriptInterface(new AndroidBridge(), "AndroidApp");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return false;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsAlert(WebView view, String url, String message, final JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Загрузка производства")
                        .setMessage(message)
                        .setPositiveButton("ОК", (d, w) -> result.confirm())
                        .setOnCancelListener(d -> result.cancel())
                        .show();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, final JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Загрузка производства")
                        .setMessage(message)
                        .setPositiveButton("Да", (d, w) -> result.confirm())
                        .setNegativeButton("Отмена", (d, w) -> result.cancel())
                        .setOnCancelListener(d -> result.cancel())
                        .show();
                return true;
            }
        });

        cachedHtml = readAssetHtml("index.html");

        String savedUrl = normalizeUrl(prefs.getString(KEY_SERVER_URL, ""));
        if (savedUrl.isEmpty()) {
            loadWithBaseUrl("http://localhost/");
        } else {
            loadWithBaseUrl(savedUrl);
        }

        // Тихая фоновая проверка и автопоиск сервера без всплывающих окон
        silentDiscoverServer(false);
        checkForAppUpdate(false);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebViewSettings(WebView wv) {
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
    }

    // ==================== ВСТРОЕННЫЙ ВИЗУАЛИЗАТОР ЧЕРТЕЖЕЙ ====================

    private void showVisualizerOverlay(final String payloadJson) {
        pendingVisualizerPayload = payloadJson != null ? payloadJson : "";

        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;

            if (visualizerWebView == null) {
                if (cachedVisualizerHtml == null) {
                    cachedVisualizerHtml = readAssetHtml("frame_visualizer-v7.html");
                }
                visualizerWebView = new WebView(MainActivity.this);
                boolean visDark = "dark".equals(prefs.getString(KEY_APP_THEME, "light"));
                visualizerWebView.setBackgroundColor(Color.parseColor(visDark ? "#0d1117" : "#F2F2F2"));
                configureWebViewSettings(visualizerWebView);
                visualizerWebView.addJavascriptInterface(new VisualizerBridge(), "AndroidVisualizer");

                visualizerWebView.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(WebView view, String url) {
                        super.onPageFinished(view, url);
                        visualizerPageReady = true;
                        view.evaluateJavascript(
                                "if (window.loadExternalCsvData && window.AndroidVisualizer) {" +
                                        "var p = window.AndroidVisualizer.getPayload();" +
                                        "if (p) window.loadExternalCsvData(p);" +
                                        "}",
                                null
                        );
                    }
                });
                visualizerWebView.setWebChromeClient(new WebChromeClient());

                rootContainer.addView(visualizerWebView, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                ));

                String base = (activeBaseUrl != null && !activeBaseUrl.isEmpty()) ? activeBaseUrl : "http://localhost/";
                visualizerPageReady = false;
                visualizerWebView.loadDataWithBaseURL(base, cachedVisualizerHtml, "text/html", "UTF-8", base);
            } else {
                visualizerWebView.setVisibility(View.VISIBLE);
                visualizerWebView.bringToFront();
                if (visualizerPageReady) {
                    visualizerWebView.evaluateJavascript(
                            "if (window.loadExternalCsvData && window.AndroidVisualizer) {" +
                                    "var p = window.AndroidVisualizer.getPayload();" +
                                    "if (p) window.loadExternalCsvData(p);" +
                                    "}",
                            null
                    );
                }
            }
        });
    }

    private void hideVisualizerOverlay() {
        runOnUiThread(() -> {
            if (visualizerWebView != null) {
                visualizerWebView.setVisibility(View.GONE);
            }
        });
    }

    private class VisualizerBridge {
        @JavascriptInterface
        public String getPayload() {
            return pendingVisualizerPayload;
        }

        @JavascriptInterface
        public void close() {
            hideVisualizerOverlay();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleNotificationIntent(intent);
        NewDayWorker.clearPendingNotifications(this);
        hideVisualizerOverlay();
        if (webView != null && pendingOpenLineIdx >= 0) {
            final int idx = pendingOpenLineIdx;
            pendingOpenLineIdx = -1;
            webView.evaluateJavascript(
                    "if (typeof isFolderConfigured === 'function' && isFolderConfigured(" + idx + ")) {" +
                            "activeIndex = " + idx + "; currentDirectory = folderPaths[" + idx + "]; updateActiveDisplay();" +
                            "if (blocksByFolder[" + idx + "]) { currentBlocks = blocksByFolder[" + idx + "]; displayBlocks(currentBlocks); prepareChartDataFromBlocks(currentBlocks); }" +
                            "if (typeof refreshAllFolders === 'function') refreshAllFolders();" +
                            "}",
                    null
            );
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        NewDayWorker.clearPendingNotifications(this);
    }

    private void handleNotificationIntent(Intent intent) {
        if (intent != null && intent.hasExtra(NewDayWorker.EXTRA_OPEN_LINE_IDX)) {
            pendingOpenLineIdx = intent.getIntExtra(NewDayWorker.EXTRA_OPEN_LINE_IDX, -1);
            intent.removeExtra(NewDayWorker.EXTRA_OPEN_LINE_IDX);
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS")
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                        this,
                        new String[]{"android.permission.POST_NOTIFICATIONS"},
                        REQ_POST_NOTIFICATIONS
                );
            }
        }
    }

    // ==================== СКАНЕР ШТРИХ-КОДОВ ====================

    private void launchBarcodeScanner(final String target) {
        pendingScanTarget = (target != null && !target.isEmpty()) ? target : "global";
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (ContextCompat.checkSelfPermission(MainActivity.this, android.Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                        MainActivity.this,
                        new String[]{android.Manifest.permission.CAMERA},
                        REQ_CAMERA_PERMISSION
                );
                return;
            }
            openZxingScanner();
        });
    }

    private void openZxingScanner() {
        IntentIntegrator integrator = new IntentIntegrator(this);
        integrator.setDesiredBarcodeFormats(IntentIntegrator.ALL_CODE_TYPES);
        integrator.setPrompt("Наведите на правый штрих-код (напр. C1/18467/8)\nКнопки громкости — фонарик");
        integrator.setBeepEnabled(true);
        integrator.setOrientationLocked(false);
        integrator.initiateScan();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA_PERMISSION) {
            if (grantResults != null && grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openZxingScanner();
            } else {
                Toast.makeText(this, "Нужен доступ к камере для сканирования штрих-кода", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (result != null) {
            String contents = result.getContents();
            if (contents != null && !contents.trim().isEmpty() && webView != null) {
                final String safeTarget = JSONObject.quote(pendingScanTarget != null ? pendingScanTarget : "global");
                final String safeCode = JSONObject.quote(contents.trim());
                runOnUiThread(() -> webView.evaluateJavascript(
                        "if (window.__onBarcodeScanned) window.__onBarcodeScanned(" + safeTarget + ", " + safeCode + ");",
                        null
                ));
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void scheduleBackgroundNewDayCheck() {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        PeriodicWorkRequest periodicRequest = new PeriodicWorkRequest.Builder(
                NewDayWorker.class,
                15,
                TimeUnit.MINUTES
        )
                .setConstraints(constraints)
                .build();

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                periodicRequest
        );
    }

    // ==================== ТИХИЙ АВТОПОИСК СЕРВЕРА ====================

    private void silentDiscoverServer(final boolean forceFullScan) {
        if (!NewDayWorker.isWifiOrLanConnected(this)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!forceFullScan && (now - lastAutoDiscoveryMs < 15000L)) {
            return;
        }
        if (!isAutoDiscovering.compareAndSet(false, true)) {
            return;
        }
        lastAutoDiscoveryMs = now;

        new Thread(() -> {
            try {
                List<String> found = NewDayWorker.findWorkingServers(MainActivity.this, prefs, forceFullScan);
                if (found == null || found.isEmpty()) {
                    return;
                }

                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;

                    if (found.size() == 1) {
                        String targetUrl = normalizeUrl(found.get(0));
                        if (!targetUrl.equals(activeBaseUrl)) {
                            prefs.edit().putString(KEY_SERVER_URL, targetUrl).apply();
                            loadWithBaseUrl(targetUrl);
                            Toast.makeText(MainActivity.this,
                                    "Сервер найден: " + displayUrl(targetUrl), Toast.LENGTH_SHORT).show();
                        }
                    } else if (found.size() > 1) {
                        showServerSelectionDialog(found, false);
                    }
                });
            } finally {
                isAutoDiscovering.set(false);
            }
        }).start();
    }

    // ==================== АВТООБНОВЛЕНИЕ ЧЕРЕЗ GITHUB RELEASES ====================

    private void applySystemBarsTheme(final boolean isDark) {
        try {
            Window window = getWindow();
            int barColor = Color.parseColor(isDark ? "#0f1626" : "#F2F2F2");
            window.setStatusBarColor(barColor);
            window.setNavigationBarColor(barColor);

            WindowInsetsControllerCompat controller =
                    WindowCompat.getInsetsController(window, window.getDecorView());
            if (controller != null) {
                controller.setAppearanceLightStatusBars(!isDark);
                controller.setAppearanceLightNavigationBars(!isDark);
            }
            if (rootContainer != null) {
                rootContainer.setBackgroundColor(barColor);
            }
            if (webView != null) {
                webView.setBackgroundColor(barColor);
            }
            if (visualizerWebView != null) {
                visualizerWebView.setBackgroundColor(Color.parseColor(isDark ? "#0d1117" : "#F2F2F2"));
            }
        } catch (Exception ignored) {
        }
    }

    private int getCurrentVersionCode() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return (int) pi.getLongVersionCode();
            }
            return pi.versionCode;
        } catch (Exception e) {
            return 1;
        }
    }

    private String getCurrentVersionName() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            if (pi.versionName != null && !pi.versionName.trim().isEmpty()) {
                return pi.versionName.trim();
            }
        } catch (Exception ignored) {
        }
        return "1." + getCurrentVersionCode();
    }

    private void checkForAppUpdate(final boolean manual) {
        if (manual) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "Проверка обновлений...", Toast.LENGTH_SHORT).show());
        }
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(GITHUB_LATEST_RELEASE_API);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(6000);
                conn.setRequestProperty("Accept", "application/vnd.github+json");
                conn.setRequestProperty("User-Agent", "ProductionMonitoringApp");

                int code = conn.getResponseCode();
                if (code != 200) {
                    if (manual) {
                        runOnUiThread(() -> Toast.makeText(MainActivity.this,
                                "Не удалось проверить обновления (HTTP " + code + ")", Toast.LENGTH_LONG).show());
                    }
                    return;
                }

                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                }

                JSONObject json = new JSONObject(sb.toString());
                String tagName = json.optString("tag_name", "");
                int remoteBuild = parseBuildNumber(tagName);
                int localBuild = getCurrentVersionCode();

                if (remoteBuild <= localBuild) {
                    if (manual) {
                        final String curVer = getCurrentVersionName();
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            new AlertDialog.Builder(MainActivity.this)
                                    .setTitle("Обновлений нет")
                                    .setMessage("У вас установлена актуальная версия приложения: v" + curVer + " (сборка #" + localBuild + ").")
                                    .setPositiveButton("ОК", null)
                                    .show();
                        });
                    }
                    return;
                }

                String apkUrl = null;
                JSONArray assets = json.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject asset = assets.getJSONObject(i);
                        String name = asset.optString("name", "");
                        if (name.toLowerCase(Locale.US).endsWith(".apk")) {
                            apkUrl = asset.optString("browser_download_url", null);
                            break;
                        }
                    }
                }
                if (apkUrl == null || apkUrl.isEmpty()) {
                    if (manual) {
                        runOnUiThread(() -> Toast.makeText(MainActivity.this,
                                "В релизе " + tagName + " ещё нет APK-файла", Toast.LENGTH_LONG).show());
                    }
                    return;
                }

                final String finalApkUrl = apkUrl;
                final String finalTag = tagName;
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("Доступно обновление " + finalTag)
                            .setMessage("Текущая версия: v" + getCurrentVersionName() + "
Новая версия: " + finalTag + "

Скачать и установить сейчас?")
                            .setPositiveButton("Обновить", (d, w) -> downloadAndInstallApk(finalApkUrl))
                            .setNegativeButton("Позже", null)
                            .show();
                });
            } catch (Exception e) {
                if (manual) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this,
                            "Нет связи с GitHub для проверки обновлений", Toast.LENGTH_LONG).show());
                }
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    private int parseBuildNumber(String tag) {
        if (tag == null) return 0;
        int dot = tag.lastIndexOf('.');
        String numPart = (dot >= 0) ? tag.substring(dot + 1) : tag.replaceAll("[^0-9]", "");
        try {
            return Integer.parseInt(numPart.replaceAll("[^0-9]", ""));
        } catch (Exception e) {
            return 0;
        }
    }

    private void downloadAndInstallApk(final String apkUrl) {
        final AlertDialog progressDialog = new AlertDialog.Builder(this)
                .setTitle("Загрузка обновления")
                .setMessage("Скачивание APK с GitHub...")
                .setCancelable(false)
                .create();
        progressDialog.show();

        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(apkUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.connect();

                File apkFile = new File(getCacheDir(), "update.apk");
                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(apkFile)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) != -1) {
                        out.write(buf, 0, len);
                    }
                }

                runOnUiThread(() -> {
                    try { progressDialog.dismiss(); } catch (Exception ignored) {}
                    Uri apkUri = FileProvider.getUriForFile(
                            MainActivity.this,
                            getPackageName() + ".fileprovider",
                            apkFile
                    );
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    try { progressDialog.dismiss(); } catch (Exception ignored) {}
                    Toast.makeText(MainActivity.this, "Ошибка загрузки: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    // ==================== ПОДКЛЮЧЕНИЕ И РУЧНОЙ ВЫБОР ====================

    private String readAssetHtml(String assetName) {
        StringBuilder sb = new StringBuilder();
        try (InputStream is = getAssets().open(assetName);
             BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (Exception e) {
            return "<html><body style='background:#111;color:#fff;font-family:sans-serif;padding:24px;'>Ошибка загрузки " + assetName + ": " + e.getMessage() + "</body></html>";
        }
        return sb.toString();
    }

    private String normalizeUrl(String raw) {
        return NewDayWorker.normalizeBaseUrl(raw);
    }

    private String displayUrl(String raw) {
        String u = normalizeUrl(raw);
        if (u.endsWith("/")) {
            return u.substring(0, u.length() - 1);
        }
        return u;
    }

    private void loadWithBaseUrl(String baseUrl) {
        activeBaseUrl = baseUrl;
        webView.loadDataWithBaseURL(baseUrl, cachedHtml, "text/html", "UTF-8", baseUrl);
    }

    private void applyAndConnect(String rawUrl) {
        String normalized = normalizeUrl(rawUrl);
        prefs.edit().putString(KEY_SERVER_URL, normalized).apply();
        loadWithBaseUrl(normalized);
        Toast.makeText(this, "Подключено: " + displayUrl(normalized), Toast.LENGTH_SHORT).show();
    }

    private void manualScanNetworkForServer(final boolean isFirstRun) {
        if (!NewDayWorker.isWifiOrLanConnected(this)) {
            Toast.makeText(this, "Wi-Fi отключён — подключение к локальному серверу невозможно", Toast.LENGTH_LONG).show();
            showServerUrlDialog(isFirstRun);
            return;
        }
        final String localPrefix = NewDayWorker.getLocalSubnetPrefix();
        final String firstPrefix = (localPrefix != null) ? localPrefix : NewDayWorker.FALLBACK_SUBNET;

        final AlertDialog progressDialog = new AlertDialog.Builder(this)
                .setTitle("Поиск сервера")
                .setMessage("Поиск сервера мониторинга в " + firstPrefix + "1–254...")
                .setCancelable(false)
                .create();
        progressDialog.show();

        new Thread(() -> {
            List<String> foundUrls = NewDayWorker.scanAndVerifySubnet(firstPrefix);

            if (foundUrls.isEmpty() && !NewDayWorker.FALLBACK_SUBNET.equals(firstPrefix)) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        progressDialog.setMessage("Поиск сервера в " + NewDayWorker.FALLBACK_SUBNET + "1–254...");
                    }
                });
                foundUrls = NewDayWorker.scanAndVerifySubnet(NewDayWorker.FALLBACK_SUBNET);
            }

            final List<String> result = foundUrls;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                try { progressDialog.dismiss(); } catch (Exception ignored) {}

                if (result.size() == 1) {
                    applyAndConnect(result.get(0));
                } else if (result.size() > 1) {
                    showServerSelectionDialog(result, isFirstRun);
                } else {
                    Toast.makeText(MainActivity.this,
                            "Сервер мониторинга на порту " + NewDayWorker.SCAN_PORT + " не найден", Toast.LENGTH_LONG).show();
                    showServerUrlDialog(isFirstRun);
                }
            });
        }).start();
    }

    private void showServerSelectionDialog(final List<String> urls, final boolean isFirstRun) {
        final String[] items = new String[urls.size()];
        for (int i = 0; i < urls.size(); i++) {
            items[i] = displayUrl(urls.get(i));
        }
        new AlertDialog.Builder(this)
                .setTitle("Выберите сервер (порт " + NewDayWorker.SCAN_PORT + ")")
                .setItems(items, (dialog, which) -> applyAndConnect(urls.get(which)))
                .setNeutralButton("Ввести вручную", (dialog, which) -> showServerUrlDialog(isFirstRun))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void showServerUrlDialog(boolean isFirstRun) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad / 2, pad, 0);

        TextView hint = new TextView(this);
        hint.setText("Укажите адрес сервера мониторинга (например, http://192.168.57.107:5000):");
        layout.addView(hint);

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("http://192.168.57.107:5000");
        String current = prefs.getString(KEY_SERVER_URL, "");
        if (current != null && !current.isEmpty()) {
            input.setText(displayUrl(current));
            input.setSelection(input.getText().length());
        }
        layout.addView(input);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("Подключение к серверу")
                .setView(layout)
                .setPositiveButton("Подключиться", (dialog, which) -> {
                    String entered = input.getText().toString().trim();
                    if (entered.isEmpty()) {
                        Toast.makeText(this, "Адрес сервера не указан", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    applyAndConnect(entered);
                })
                .setNeutralButton("Найти в сети", (dialog, which) -> manualScanNetworkForServer(isFirstRun));

        builder.setNegativeButton(isFirstRun ? "Позже" : "Отмена", null);
        builder.show();
    }

    private void dispatchAsyncCallback(final String callbackId, final boolean success, final String payloadOrError) {
        asyncResults.put(callbackId, payloadOrError != null ? payloadOrError : "");
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed() || webView == null) return;
            String safeId = JSONObject.quote(callbackId);
            webView.evaluateJavascript(
                    "if (window.__onAndroidAsyncResult) window.__onAndroidAsyncResult(" + safeId + ", " + success + ");",
                    null
            );
        });
    }

    private class AndroidBridge {
        @JavascriptInterface
        public String getServerUrl() {
            return displayUrl(prefs.getString(KEY_SERVER_URL, ""));
        }

        @JavascriptInterface
        public void setServerUrl(final String url) {
            runOnUiThread(() -> {
                String normalized = normalizeUrl(url);
                if (normalized.isEmpty()) {
                    Toast.makeText(MainActivity.this, "Введите корректный адрес сервера", Toast.LENGTH_SHORT).show();
                    return;
                }
                applyAndConnect(normalized);
            });
        }

        @JavascriptInterface
        public void openServerDialog() {
            runOnUiThread(() -> showServerUrlDialog(false));
        }

        @JavascriptInterface
        public boolean isWifiConnected() {
            return NewDayWorker.isWifiOrLanConnected(MainActivity.this);
        }

        @JavascriptInterface
        public void syncTheme(final String theme) {
            final boolean isDark = "dark".equals(theme);
            prefs.edit().putString(KEY_APP_THEME, isDark ? "dark" : "light").apply();
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    applySystemBarsTheme(isDark);
                }
            });
        }

        @JavascriptInterface
        public String getAppVersion() {
            return "v" + getCurrentVersionName() + " (сборка #" + getCurrentVersionCode() + ")";
        }

        @JavascriptInterface
        public void checkAppUpdateManual() {
            checkForAppUpdate(true);
        }

        @JavascriptInterface
        public void onServerUnreachable() {
            if (NewDayWorker.isWifiOrLanConnected(MainActivity.this)) {
                silentDiscoverServer(true);
            }
        }

        @JavascriptInterface
        public int getPendingOpenLine() {
            int val = pendingOpenLineIdx;
            pendingOpenLineIdx = -1;
            return val;
        }

        @JavascriptInterface
        public void openVisualizer(final String payloadJson) {
            showVisualizerOverlay(payloadJson);
        }

        @JavascriptInterface
        public void startBarcodeScan(final String target) {
            launchBarcodeScanner(target);
        }

        @JavascriptInterface
        public String getYandexToken() {
            String t = prefs.getString(NewDayWorker.KEY_YANDEX_TOKEN, NewDayWorker.DEFAULT_YANDEX_TOKEN);
            return (t == null || t.trim().isEmpty()) ? NewDayWorker.DEFAULT_YANDEX_TOKEN : t.trim();
        }

        @JavascriptInterface
        public boolean isYandexEnabled() {
            return prefs.getBoolean(NewDayWorker.KEY_YANDEX_ENABLED, true);
        }

        @JavascriptInterface
        public void syncYandexConfig(final String token, final boolean enabled) {
            String cleanToken = (token != null) ? token.trim() : "";
            prefs.edit()
                    .putString(NewDayWorker.KEY_YANDEX_TOKEN, cleanToken.isEmpty() ? NewDayWorker.DEFAULT_YANDEX_TOKEN : cleanToken)
                    .putBoolean(NewDayWorker.KEY_YANDEX_ENABLED, enabled)
                    .apply();
        }

        @JavascriptInterface
        public void yandexDownloadAsync(final String remotePath, final String token, final String callbackId) {
            final String effectiveToken = (token != null && !token.trim().isEmpty())
                    ? token.trim()
                    : getYandexToken();
            new Thread(() -> {
                try {
                    String data = NewDayWorker.downloadYandexStringOrThrow(remotePath, effectiveToken);
                    dispatchAsyncCallback(callbackId, true, data);
                } catch (Exception e) {
                    dispatchAsyncCallback(callbackId, false, e.getMessage() != null ? e.getMessage() : "Ошибка загрузки");
                }
            }).start();
        }

        @JavascriptInterface
        public void yandexUploadAsync(final String remotePath, final String content, final String token, final String callbackId) {
            final String effectiveToken = (token != null && !token.trim().isEmpty())
                    ? token.trim()
                    : getYandexToken();
            new Thread(() -> {
                try {
                    NewDayWorker.uploadYandexStringOrThrow(remotePath, content, effectiveToken);
                    dispatchAsyncCallback(callbackId, true, "OK");
                } catch (Exception e) {
                    dispatchAsyncCallback(callbackId, false, e.getMessage() != null ? e.getMessage() : "Ошибка выгрузки");
                }
            }).start();
        }

        @JavascriptInterface
        public String consumeAsyncResult(final String callbackId) {
            String res = asyncResults.remove(callbackId);
            return res != null ? res : "";
        }

        @JavascriptInterface
        public void syncLinesConfig(final String linesJson, final boolean notifyEnabled, final boolean resetAll) {
            try {
                SharedPreferences.Editor ed = prefs.edit();
                ed.putString(NewDayWorker.KEY_LINES_CONFIG, linesJson != null ? linesJson : "[]");
                ed.putBoolean(NewDayWorker.KEY_NOTIFY_ENABLED, notifyEnabled);

                if (resetAll) {
                    for (int i = 0; i < 3; i++) {
                        ed.remove("known_days_" + i);
                        ed.putBoolean("baseline_ready_" + i, false);
                    }
                    ed.remove(NewDayWorker.KEY_PENDING_DAYS);
                } else if (linesJson != null) {
                    JSONArray arr = new JSONArray(linesJson);
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.optJSONObject(i);
                        if (o == null) continue;
                        int idx = o.optInt("idx", i);
                        String name = o.optString("name", "").trim();
                        String path = o.optString("path", "").trim();
                        String pattern = o.optString("pattern", "").trim();
                        if (name.isEmpty() || (path.isEmpty() && pattern.isEmpty())) {
                            ed.remove("known_days_" + idx);
                            ed.putBoolean("baseline_ready_" + idx, false);
                        }
                    }
                }
                ed.apply();
            } catch (Exception ignored) {
            }
        }

        @JavascriptInterface
        public void syncKnownFolders(final int lineIdx, final String foldersJson) {
            if (lineIdx < 0 || lineIdx >= 3 || foldersJson == null) return;
            try {
                JSONArray arr = new JSONArray(foldersJson);
                Set<String> known = NewDayWorker.loadStringSet(prefs, "known_days_" + lineIdx);
                for (int i = 0; i < arr.length(); i++) {
                    String name = arr.optString(i, "").trim().toLowerCase(Locale.ROOT);
                    if (!name.isEmpty()) {
                        known.add(name);
                    }
                }
                NewDayWorker.saveStringSet(prefs, "known_days_" + lineIdx, known);
                prefs.edit().putBoolean("baseline_ready_" + lineIdx, true).apply();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (visualizerWebView != null && visualizerWebView.getVisibility() == View.VISIBLE) {
            hideVisualizerOverlay();
            return;
        }
        if (webView != null) {
            webView.evaluateJavascript(
                    "(window.closeTopModalIfOpen && window.closeTopModalIfOpen()) ? '1' : '0'",
                    val -> {
                        if ("\"1\"".equals(val) || "1".equals(val)) {
                            return;
                        }
                        handleBackExitPress();
                    }
            );
            return;
        }
        handleBackExitPress();
    }

    private void handleBackExitPress() {
        long now = System.currentTimeMillis();
        if (now - lastBackPressMs < 2000L) {
            super.onBackPressed();
        } else {
            lastBackPressMs = now;
            Toast.makeText(this, "Нажмите «Назад» ещё раз для выхода", Toast.LENGTH_SHORT).show();
        }
    }
}
