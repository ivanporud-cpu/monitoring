package com.production.monitoring;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
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

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {

    private static final String PREFS_NAME = "monitoring_prefs";
    private static final String KEY_SERVER_URL = "server_url";

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
            showServerUrlDialog(true);
        } else {
            loadWithBaseUrl(normalizeUrl(savedUrl));
        }
    }

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
        // loadDataWithBaseURL задаёт origin = baseUrl:
        // все относительные /api/files и /api/scan-directory идут на сервер напрямую без CORS-ошибок
        webView.loadDataWithBaseURL(baseUrl, cachedHtml, "text/html", "UTF-8", baseUrl);
    }

    private void showServerUrlDialog(boolean isFirstRun) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad / 2, pad, 0);

        TextView hint = new TextView(this);
        hint.setText("Укажите адрес сервера мониторинга в локальной сети (например, http://192.168.1.10:5000):");
        layout.addView(hint);

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("http://192.168.1.10:5000");
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
                    String normalized = normalizeUrl(entered);
                    prefs.edit().putString(KEY_SERVER_URL, normalized).apply();
                    loadWithBaseUrl(normalized);
                    Toast.makeText(this, "Сервер: " + displayUrl(normalized), Toast.LENGTH_SHORT).show();
                });

        if (!isFirstRun) {
            builder.setNegativeButton("Отмена", null);
        } else {
            builder.setNegativeButton("Позже", null);
        }
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
                prefs.edit().putString(KEY_SERVER_URL, normalized).apply();
                loadWithBaseUrl(normalized);
                Toast.makeText(MainActivity.this, "Подключение к " + displayUrl(normalized), Toast.LENGTH_SHORT).show();
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
