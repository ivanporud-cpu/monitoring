package com.production.monitoring;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {

    private static final String PREFS_NAME = "monitoring_prefs";
    private static final String KEY_SERVER_URL = "server_url";
    private static final int SCAN_PORT = 5000;
    private static final int CONNECT_TIMEOUT_MS = 500;
    private static final String FALLBACK_SUBNET = "192.168.57.";
    private static final String GITHUB_LATEST_RELEASE_API =
            "https://api.github.com/repos/ivanporud-cpu/monitoring/releases/latest";

    private WebView webView;
    private SharedPreferences prefs;
    private String cachedHtml;
    private long lastBackPressMs = 0L;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(Color.parseColor("#0f1626"));
        window.setNavigationBarColor(Color.parseColor("#0f1626"));

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        webView = new WebView(this);
        webView.setBackgroundColor(Color.parseColor("#0f1626"));
        setContentView(webView);

        WebSettings s = webView.getSettings();
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

        String savedUrl = prefs.getString(KEY_SERVER_URL, "");
        if (savedUrl == null || savedUrl.trim().isEmpty()) {
            loadWithBaseUrl("http://localhost/");
            scanNetworkForServer(true);
        } else {
            loadWithBaseUrl(normalizeUrl(savedUrl));
        }

        checkForAppUpdate();
    }

    // ==================== АВТООБНОВЛЕНИЕ ЧЕРЕЗ GITHUB RELEASES ====================

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

    private void checkForAppUpdate() {
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

                if (conn.getResponseCode() != 200) return;

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

                if (remoteBuild <= localBuild) return;

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
                if (apkUrl == null || apkUrl.isEmpty()) return;

                final String finalApkUrl = apkUrl;
                final String finalTag = tagName;
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("Доступно обновление " + finalTag)
                            .setMessage("Вышла новая версия приложения. Скачать и установить сейчас?")
                            .setPositiveButton("Обновить", (d, w) -> downloadAndInstallApk(finalApkUrl))
                            .setNegativeButton("Позже", null)
                            .show();
                });
            } catch (Exception ignored) {
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

    // ==================== ПОИСК СЕРВЕРА И ПОДКЛЮЧЕНИЕ ====================

    private String readAssetHtml(String assetName) {
        StringBuilder sb = new StringBuilder();
        try (InputStream is = getAssets().open(assetName);
             BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (Exception e) {
            return "<html><body style='background:#111;color:#fff;font-family:sans-serif;padding:24px;'>Ошибка загрузки index.html: " + e.getMessage() + "</body></html>";
        }
        return sb.toString();
    }

    private String normalizeUrl(String raw) {
        if (raw == null) return "";
        String u = raw.trim();
        if (u.isEmpty()) return "";
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            u = "http://" + u;
        }
        if (!u.endsWith("/")) {
            u = u + "/";
        }
        return u;
    }

    private String displayUrl(String raw) {
        String u = normalizeUrl(raw);
        if (u.endsWith("/")) {
            return u.substring(0, u.length() - 1);
        }
        return u;
    }

    private void loadWithBaseUrl(String baseUrl) {
        webView.loadDataWithBaseURL(baseUrl, cachedHtml, "text/html", "UTF-8", baseUrl);
    }

    private void applyAndConnect(String rawUrl) {
        String normalized = normalizeUrl(rawUrl);
        prefs.edit().putString(KEY_SERVER_URL, normalized).apply();
        loadWithBaseUrl(normalized);
        Toast.makeText(this, "Подключено: " + displayUrl(normalized), Toast.LENGTH_SHORT).show();
    }

    private String getLocalSubnetPrefix() {
        String fallbackPrefix = null;
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface nif = interfaces.nextElement();
                if (!nif.isUp() || nif.isLoopback()) continue;
                String name = nif.getName() == null ? "" : nif.getName().toLowerCase(Locale.US);
                if (name.startsWith("tun") || name.startsWith("ppp") || name.startsWith("rmnet")
                        || name.startsWith("ccmni") || name.startsWith("dummy")) {
                    continue;
                }
                Enumeration<InetAddress> addrs = nif.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && addr.isSiteLocalAddress()) {
                        String host = addr.getHostAddress();
                        if (host != null) {
                            int lastDot = host.lastIndexOf('.');
                            if (lastDot > 0) {
                                String prefix = host.substring(0, lastDot + 1);
                                if (name.startsWith("wlan") || name.startsWith("wifi")) {
                                    return prefix;
                                }
                                if (fallbackPrefix == null) {
                                    fallbackPrefix = prefix;
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return fallbackPrefix;
    }

    private List<String> scanSubnet(String prefix) {
        final List<String> found = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(64);
        for (int i = 1; i <= 254; i++) {
            final String ip = prefix + i;
            pool.submit(() -> {
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(ip, SCAN_PORT), CONNECT_TIMEOUT_MS);
                    found.add("http://" + ip + ":" + SCAN_PORT);
                } catch (Exception ignored) {
                }
            });
        }
        pool.shutdown();
        try {
            pool.awaitTermination(12, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        Collections.sort(found);
        return found;
    }

    private void scanNetworkForServer(final boolean isFirstRun) {
        final String localPrefix = getLocalSubnetPrefix();
        final String firstPrefix = (localPrefix != null) ? localPrefix : FALLBACK_SUBNET;

        final AlertDialog progressDialog = new AlertDialog.Builder(this)
                .setTitle("Поиск сервера")
                .setMessage("Сканирование " + firstPrefix + "1–254 (порт " + SCAN_PORT + ")...")
                .setCancelable(false)
                .create();
        progressDialog.show();

        new Thread(() -> {
            List<String> foundUrls = scanSubnet(firstPrefix);

            if (foundUrls.isEmpty() && !FALLBACK_SUBNET.equals(firstPrefix)) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        progressDialog.setMessage("Сканирование " + FALLBACK_SUBNET + "1–254 (порт " + SCAN_PORT + ")...");
                    }
                });
                foundUrls = scanSubnet(FALLBACK_SUBNET);
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
                            "Серверы на порту " + SCAN_PORT + " не найдены", Toast.LENGTH_LONG).show();
                    showServerUrlDialog(isFirstRun);
                }
            });
        }).start();
    }

    private void showServerSelectionDialog(final List<String> urls, final boolean isFirstRun) {
        final String[] items = urls.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle("Выберите сервер (порт " + SCAN_PORT + ")")
                .setItems(items, (dialog, which) -> applyAndConnect(items[which]))
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
                .setNeutralButton("Найти в сети", (dialog, which) -> scanNetworkForServer(isFirstRun));

        builder.setNegativeButton(isFirstRun ? "Позже" : "Отмена", null);
        builder.show();
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
    }

    @Override
    public void onBackPressed() {
        long now = System.currentTimeMillis();
        if (now - lastBackPressMs < 2000L) {
            super.onBackPressed();
        } else {
            lastBackPressMs = now;
            Toast.makeText(this, "Нажмите «Назад» ещё раз для выхода", Toast.LENGTH_SHORT).show();
        }
    }
}
