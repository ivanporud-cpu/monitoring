package com.production.monitoring;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

public class NewDayWorker extends Worker {

    public static final String PREFS_NAME = "monitoring_prefs";
    public static final String KEY_SERVER_URL = "server_url";
    public static final String KEY_LINES_CONFIG = "lines_config_json";
    public static final String KEY_NOTIFY_ENABLED = "notify_enabled";
    public static final String KEY_PENDING_DAYS = "pending_new_days";
    public static final String KEY_LAST_ALERT_MS = "last_new_day_alert_ms";
    public static final String KEY_YANDEX_TOKEN = "yandex_oauth_token";
    public static final String KEY_YANDEX_ENABLED = "yandex_disk_enabled";
    public static final String EXTRA_OPEN_LINE_IDX = "extra_open_line_idx";

    public static final String DEFAULT_YANDEX_TOKEN =
            "y0__wgBENuj48EGGKDzUCCm37zvGTDzmKv3CCTYq0IcMxZ4g_rmD135_SjlzCdA";
    public static final String YANDEX_API_BASE = "https://cloud-api.yandex.net/v1/disk/resources";

    public static final int SCAN_PORT = 5000;
    public static final int SOCKET_TIMEOUT_MS = 280;
    public static final int VERIFY_TIMEOUT_MS = 900;
    public static final String FALLBACK_SUBNET = "192.168.57.";

    public static final String CHANNEL_ID = "new_day_channel";
    public static final int NOTIFICATION_ID = 7701;
    private static final String FOLLOWUP_WORK_NAME = "new_day_followup_work";

    private static final String[] MONTHS_RU = {
            "январь", "февраль", "март", "апрель", "май", "июнь",
            "июль", "август", "сентябрь", "октябрь", "ноябрь", "декабрь"
    };

    private static final Pattern[] MONTH_RE = {
            Pattern.compile("январ"), Pattern.compile("феврал"), Pattern.compile("март"),
            Pattern.compile("апрел"), Pattern.compile("ма[йя]"), Pattern.compile("июн"),
            Pattern.compile("июл"), Pattern.compile("август"), Pattern.compile("сентябр"),
            Pattern.compile("октябр"), Pattern.compile("ноябр"), Pattern.compile("декабр")
    };

    public NewDayWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        if (!prefs.getBoolean(KEY_NOTIFY_ENABLED, true)) {
            return Result.success();
        }

        String configJson = prefs.getString(KEY_LINES_CONFIG, "");
        if (configJson == null || configJson.trim().isEmpty()) {
            return Result.success();
        }

        String serverUrl = null;
        JSONObject yandexCache = null;

        if (isWifiOrLanConnected(ctx)) {
            List<String> servers = findWorkingServers(ctx, prefs, false);
            if (!servers.isEmpty()) {
                serverUrl = servers.get(0);
            }
        }
        if (serverUrl == null && prefs.getBoolean(KEY_YANDEX_ENABLED, true)) {
            String token = prefs.getString(KEY_YANDEX_TOKEN, DEFAULT_YANDEX_TOKEN);
            if (token == null || token.trim().isEmpty()) {
                token = DEFAULT_YANDEX_TOKEN;
            }
            try {
                String raw = downloadYandexStringOrThrow("app:/cache.json", token.trim());
                if (raw != null && !raw.trim().isEmpty()) {
                    yandexCache = new JSONObject(raw);
                }
            } catch (Exception ignored) {
            }
        }

        if (serverUrl == null && yandexCache == null) {
            return Result.success();
        }

        try {
            JSONArray lines = new JSONArray(configJson);
            int configuredCount = 0;
            List<NewDayEvent> foundInRun = new ArrayList<>();

            for (int i = 0; i < lines.length(); i++) {
                JSONObject lineObj = lines.optJSONObject(i);
                if (lineObj == null) continue;

                int idx = lineObj.optInt("idx", i);
                String name = lineObj.optString("name", "").trim();
                String rawPath = lineObj.optString("path", "").trim();
                String pattern = lineObj.optString("pattern", "").trim();

                if (name.isEmpty()) continue;
                String basePath = !pattern.isEmpty() ? resolveMacros(pattern) : rawPath;
                if (basePath.isEmpty() && yandexCache == null) continue;

                configuredCount++;

                List<String> dayFolders;
                if (serverUrl != null) {
                    dayFolders = fetchCurrentDayFolders(serverUrl, basePath);
                } else {
                    dayFolders = extractDayFoldersFromYandexCache(yandexCache, name, basePath, pattern);
                }
                if (dayFolders == null) {
                    continue;
                }

                String knownKey = "known_days_" + idx;
                String baselineKey = "baseline_ready_" + idx;
                Set<String> knownSet = loadStringSet(prefs, knownKey);
                boolean baselineReady = prefs.getBoolean(baselineKey, false);

                if (!baselineReady) {
                    for (String f : dayFolders) {
                        knownSet.add(f.toLowerCase(Locale.ROOT));
                    }
                    saveStringSet(prefs, knownKey, knownSet);
                    prefs.edit().putBoolean(baselineKey, true).apply();
                    continue;
                }

                boolean updated = false;
                for (String f : dayFolders) {
                    String norm = f.toLowerCase(Locale.ROOT);
                    if (!knownSet.contains(norm)) {
                        knownSet.add(norm);
                        updated = true;
                        foundInRun.add(new NewDayEvent(idx, name, f, System.currentTimeMillis()));
                    }
                }
                if (updated) {
                    saveStringSet(prefs, knownKey, knownSet);
                }
            }

            if (!foundInRun.isEmpty()) {
                appendAndShowUnifiedNotification(ctx, prefs, foundInRun);

                Set<Integer> notifiedLines = new HashSet<>();
                JSONArray pending = loadPendingArray(prefs);
                for (int i = 0; i < pending.length(); i++) {
                    JSONObject o = pending.optJSONObject(i);
                    if (o != null) notifiedLines.add(o.optInt("lineIdx", -1));
                }
                if (configuredCount > 1 && notifiedLines.size() < configuredCount) {
                    scheduleFollowUpCheck(ctx);
                }
            }
        } catch (Exception ignored) {
        }

        return Result.success();
    }

    // ==================== ЯНДЕКС ДИСК: ЗАГРУЗКА И ВЫГРУЗКА ====================

    public static String downloadYandexStringOrThrow(String remotePath, String token) throws IOException {
        if (token == null || token.trim().isEmpty()) {
            throw new IOException("Не указан OAuth-токен Яндекс Диска");
        }
        String encodedPath = URLEncoder.encode(remotePath, "UTF-8").replace("%3A", ":").replace("%2F", "/");
        String metaUrl = YANDEX_API_BASE + "/download?path=" + encodedPath;

        String metaResp = httpGetString(metaUrl, "OAuth " + token.trim(), 12000, 15000);
        try {
            JSONObject meta = new JSONObject(metaResp);
            String href = meta.optString("href", "");
            if (href.isEmpty()) {
                throw new IOException("Яндекс Диск не вернул ссылку для скачивания");
            }
            byte[] rawBytes = httpGetBytes(href, null, 15000, 30000);
            if (rawBytes.length >= 2 && (rawBytes[0] & 0xFF) == 0x1F && (rawBytes[1] & 0xFF) == 0x8B) {
                rawBytes = gunzipBytes(rawBytes);
            }
            return new String(rawBytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Ошибка разбора ответа Яндекс Диска: " + e.getMessage(), e);
        }
    }

    public static void uploadYandexStringOrThrow(String remotePath, String content, String token) throws IOException {
        if (token == null || token.trim().isEmpty()) {
            throw new IOException("Не указан OAuth-токен Яндекс Диска");
        }
        String encodedPath = URLEncoder.encode(remotePath, "UTF-8").replace("%3A", ":").replace("%2F", "/");
        String metaUrl = YANDEX_API_BASE + "/upload?path=" + encodedPath + "&overwrite=true";

        String metaResp = httpGetString(metaUrl, "OAuth " + token.trim(), 12000, 15000);
        String href;
        try {
            JSONObject meta = new JSONObject(metaResp);
            href = meta.optString("href", "");
        } catch (Exception e) {
            throw new IOException("Ошибка ответа Яндекс Диска: " + e.getMessage(), e);
        }
        if (href.isEmpty()) {
            throw new IOException("Яндекс Диск не вернул ссылку для загрузки");
        }

        byte[] body = (content != null ? content : "").getBytes(StandardCharsets.UTF_8);
        HttpURLConnection conn = null;
        try {
            URL url = new URL(href);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("PUT");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setFixedLengthStreamingMode(body.length);

            try (OutputStream out = conn.getOutputStream()) {
                out.write(body);
            }
            int code = conn.getResponseCode();
            if (code != 200 && code != 201 && code != 202) {
                throw new IOException("Ошибка выгрузки на Яндекс Диск: HTTP " + code);
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String httpGetString(String urlStr, String authHeader, int connectTimeout, int readTimeout) throws IOException {
        byte[] bytes = httpGetBytes(urlStr, authHeader, connectTimeout, readTimeout);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static byte[] httpGetBytes(String urlStr, String authHeader, int connectTimeout, int readTimeout) throws IOException {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(connectTimeout);
            conn.setReadTimeout(readTimeout);
            if (authHeader != null && !authHeader.isEmpty()) {
                conn.setRequestProperty("Authorization", authHeader);
                conn.setRequestProperty("Accept", "application/json");
            }
            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            if (is != null) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) {
                    baos.write(buf, 0, n);
                }
                is.close();
            }
            if (code < 200 || code >= 300) {
                String errTxt = new String(baos.toByteArray(), StandardCharsets.UTF_8);
                throw new IOException("HTTP " + code + (errTxt.isEmpty() ? "" : ": " + errTxt));
            }
            return baos.toByteArray();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static byte[] gunzipBytes(byte[] compressed) throws IOException {
        try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(compressed));
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = gis.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            return baos.toByteArray();
        }
    }

    private List<String> extractDayFoldersFromYandexCache(JSONObject cacheJson, String lineName, String basePath, String pattern) {
        if (cacheJson == null) return null;
        JSONArray linesArr = cacheJson.optJSONArray("lines");
        if (linesArr == null || linesArr.length() == 0) return null;

        String normBase = normalizePathForCompare(basePath);
        String normLineName = lineName != null ? lineName.trim().toLowerCase(Locale.ROOT) : "";
        String lineFolderHint = extractLineFolderHint(basePath, pattern);

        List<String> matchedFolders = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (int i = 0; i < linesArr.length(); i++) {
            JSONObject entry = linesArr.optJSONObject(i);
            if (entry == null) continue;
            String entryDir = normalizePathForCompare(entry.optString("directory", ""));
            String entryName = entry.optString("name", "").trim().toLowerCase(Locale.ROOT);

            boolean matches = false;
            if (!normBase.isEmpty() && !entryDir.isEmpty()) {
                if (entryDir.equals(normBase) || entryDir.startsWith(normBase + "/") || normBase.startsWith(entryDir + "/")) {
                    matches = true;
                }
            }
            if (!matches && !lineFolderHint.isEmpty() && entryDir.contains("/" + lineFolderHint + "/")) {
                matches = true;
            }
            if (!matches && !normLineName.isEmpty() && (entryName.equals(normLineName) || entryName.contains(normLineName))) {
                matches = true;
            }

            if (matches) {
                JSONArray blocks = entry.optJSONArray("blocks");
                if (blocks != null) {
                    for (int j = 0; j < blocks.length(); j++) {
                        JSONObject b = blocks.optJSONObject(j);
                        if (b == null) continue;
                        String fn = b.optString("folder_name", "").trim();
                        if (!fn.isEmpty() && seen.add(fn.toLowerCase(Locale.ROOT))) {
                            matchedFolders.add(fn);
                        }
                    }
                }
            }
        }
        return matchedFolders.isEmpty() ? null : matchedFolders;
    }

    private String normalizePathForCompare(String p) {
        if (p == null) return "";
        return p.trim().replace('\\', '/').replaceAll("/+$", "").toLowerCase(Locale.ROOT);
    }

    private String extractLineFolderHint(String basePath, String pattern) {
        String candidate = normalizePathForCompare(basePath);
        if (candidate.isEmpty()) return "";
        String[] parts = candidate.split("/");
        for (int i = parts.length - 1; i >= 0; i--) {
            String seg = parts[i].trim();
            if (seg.isEmpty()) continue;
            if (getMonthIndexFromName(seg) >= 0) continue;
            if (seg.matches("^20\\d{2}$")) continue;
            return seg;
        }
        return "";
    }

    // ==================== ТИХИЙ АВТОПОИСК СЕРВЕРА С ПРОВЕРКОЙ «СВОЙ/ЧУЖОЙ» ====================

    public static boolean isWifiOrLanConnected(Context ctx) {
        if (ctx == null) {
            return getLocalSubnetPrefix() != null;
        }
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                Network[] networks = cm.getAllNetworks();
                if (networks != null) {
                    for (Network net : networks) {
                        NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                        if (caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                                || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) {
                            return true;
                        }
                    }
                    return false;
                }
            }
        } catch (Exception ignored) {
        }
        return getLocalSubnetPrefix() != null;
    }

    public static List<String> findWorkingServers(SharedPreferences prefs, boolean forceFullScan) {
        return findWorkingServers(null, prefs, forceFullScan);
    }

    public static List<String> findWorkingServers(Context ctx, SharedPreferences prefs, boolean forceFullScan) {
        if (ctx != null && !isWifiOrLanConnected(ctx)) {
            return Collections.emptyList();
        }
        String localPrefix = getLocalSubnetPrefix();
        if (localPrefix == null) {
            return Collections.emptyList();
        }

        String savedUrl = normalizeBaseUrl(prefs.getString(KEY_SERVER_URL, ""));
        if (!forceFullScan && !savedUrl.isEmpty()) {
            if (isMonitoringServer(savedUrl, VERIFY_TIMEOUT_MS)) {
                return Collections.singletonList(savedUrl);
            }
        }

        List<String> verified = scanAndVerifySubnet(localPrefix);
        if (verified.isEmpty() && !FALLBACK_SUBNET.equals(localPrefix)) {
            verified = scanAndVerifySubnet(FALLBACK_SUBNET);
        }

        if (verified.size() == 1) {
            String found = verified.get(0);
            if (!found.equals(savedUrl)) {
                prefs.edit().putString(KEY_SERVER_URL, found).apply();
            }
        }
        return verified;
    }

    public static String getLocalSubnetPrefix() {
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

    public static List<String> scanAndVerifySubnet(String prefix) {
        final List<String> openUrls = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(64);
        for (int i = 1; i <= 254; i++) {
            final String ip = prefix + i;
            pool.submit(() -> {
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(ip, SCAN_PORT), SOCKET_TIMEOUT_MS);
                    openUrls.add("http://" + ip + ":" + SCAN_PORT + "/");
                } catch (Exception ignored) {
                }
            });
        }
        pool.shutdown();
        try {
            pool.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }

        Collections.sort(openUrls);
        List<String> verified = new ArrayList<>();
        for (String candidate : openUrls) {
            if (isMonitoringServer(candidate, VERIFY_TIMEOUT_MS)) {
                verified.add(candidate);
            }
        }
        return verified;
    }

    public static boolean isMonitoringServer(String baseUrl, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            String normalized = normalizeBaseUrl(baseUrl);
            if (normalized.isEmpty()) return false;
            URL url = new URL(normalized + "api/files");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);

            if (conn.getResponseCode() != 200) return false;

            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            JSONObject json = new JSONObject(sb.toString());
            return json.has("items") || json.has("current_path") || json.has("error");
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public static String normalizeBaseUrl(String raw) {
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

    // ==================== УВЕДОМЛЕНИЯ О НОВЫХ СМЕНАХ ====================

    private static class NewDayEvent {
        final int lineIdx;
        final String lineName;
        final String folderName;
        final long ts;

        NewDayEvent(int lineIdx, String lineName, String folderName, long ts) {
            this.lineIdx = lineIdx;
            this.lineName = lineName;
            this.folderName = folderName;
            this.ts = ts;
        }
    }

    private void appendAndShowUnifiedNotification(Context ctx, SharedPreferences prefs, List<NewDayEvent> newEvents) {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(ctx, "android.permission.POST_NOTIFICATIONS")
                    != PackageManager.PERMISSION_GRANTED) {
                return;
            }
        }

        ensureNotificationChannel(ctx);

        JSONArray pending = loadPendingArray(prefs);
        boolean hadPrevious = pending.length() > 0;

        Set<String> dedup = new HashSet<>();
        for (int i = 0; i < pending.length(); i++) {
            JSONObject o = pending.optJSONObject(i);
            if (o != null) {
                dedup.add(o.optInt("lineIdx", -1) + "|" + o.optString("folderName", "").toLowerCase(Locale.ROOT));
            }
        }

        for (NewDayEvent ev : newEvents) {
            String key = ev.lineIdx + "|" + ev.folderName.toLowerCase(Locale.ROOT);
            if (dedup.contains(key)) continue;
            dedup.add(key);
            try {
                JSONObject o = new JSONObject();
                o.put("lineIdx", ev.lineIdx);
                o.put("lineName", ev.lineName);
                o.put("folderName", ev.folderName);
                o.put("ts", ev.ts);
                pending.put(o);
            } catch (Exception ignored) {
            }
        }

        prefs.edit().putString(KEY_PENDING_DAYS, pending.toString()).apply();

        int count = pending.length();
        if (count == 0) return;

        long now = System.currentTimeMillis();
        long lastAlertMs = prefs.getLong(KEY_LAST_ALERT_MS, 0L);
        boolean silentUpdate = hadPrevious && (now - lastAlertMs < 15 * 60 * 1000L);
        if (!silentUpdate) {
            prefs.edit().putLong(KEY_LAST_ALERT_MS, now).apply();
        }

        String title;
        String summaryText;
        NotificationCompat.InboxStyle inboxStyle = new NotificationCompat.InboxStyle();

        int firstLineIdx = -1;
        List<String> inlineParts = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            JSONObject o = pending.optJSONObject(i);
            if (o == null) continue;
            int lIdx = o.optInt("lineIdx", 0);
            String lName = o.optString("lineName", "Линия");
            String fName = o.optString("folderName", "");
            if (firstLineIdx < 0) firstLineIdx = lIdx;
            inlineParts.add(lName + ": " + fName);
            inboxStyle.addLine(lName + " — смена " + fName);
        }

        if (count == 1) {
            JSONObject single = pending.optJSONObject(0);
            String lName = single != null ? single.optString("lineName", "Линия") : "Линия";
            String fName = single != null ? single.optString("folderName", "") : "";
            title = "Новая смена: " + lName;
            summaryText = "Появилась смена " + fName;
        } else {
            title = "Новые смены на производстве (" + count + ")";
            summaryText = joinStrings(" • ", inlineParts);
            inboxStyle.setBigContentTitle(title);
        }

        Intent openIntent = new Intent(ctx, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (firstLineIdx >= 0) {
            openIntent.putExtra(EXTRA_OPEN_LINE_IDX, firstLineIdx);
        }

        PendingIntent contentPendingIntent = PendingIntent.getActivity(
                ctx,
                NOTIFICATION_ID,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent dismissIntent = new Intent(ctx, NotificationDismissReceiver.class);
        PendingIntent deletePendingIntent = PendingIntent.getBroadcast(
                ctx,
                NOTIFICATION_ID + 1,
                dismissIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_monitoring)
                .setColor(Color.parseColor("#E07A22"))
                .setContentTitle(title)
                .setContentText(summaryText)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setAutoCancel(true)
                .setOnlyAlertOnce(silentUpdate)
                .setContentIntent(contentPendingIntent)
                .setDeleteIntent(deletePendingIntent);

        if (count > 1) {
            builder.setStyle(inboxStyle);
        }

        NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, builder.build());
    }

    private void scheduleFollowUpCheck(Context ctx) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        OneTimeWorkRequest followUp = new OneTimeWorkRequest.Builder(NewDayWorker.class)
                .setInitialDelay(6, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build();

        WorkManager.getInstance(ctx).enqueueUniqueWork(
                FOLLOWUP_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                followUp
        );
    }

    private List<String> fetchCurrentDayFolders(String serverUrl, String basePath) {
        try {
            JSONObject info = requestFilesApi(serverUrl, basePath);
            if (info == null) return null;

            List<DirItem> dirs = extractDirs(info);
            String ownName = baseName(basePath);
            int ownMonthIdx = getMonthIndexFromName(ownName);

            List<DirItem> targetDirs = dirs;

            String parentPath = info.optString("parent_path", "");
            if (ownMonthIdx >= 0 && !parentPath.isEmpty()) {
                JSONObject parentInfo = requestFilesApi(serverUrl, parentPath);
                if (parentInfo != null) {
                    List<DirItem> parentDirs = extractDirs(parentInfo);
                    int bestMonthIdx = ownMonthIdx;
                    String bestMonthPath = basePath;
                    for (DirItem d : parentDirs) {
                        int mIdx = getMonthIndexFromName(d.name);
                        if (mIdx > bestMonthIdx) {
                            bestMonthIdx = mIdx;
                            bestMonthPath = d.path;
                        }
                    }
                    if (!bestMonthPath.equals(basePath)) {
                        JSONObject newerInfo = requestFilesApi(serverUrl, bestMonthPath);
                        if (newerInfo != null) {
                            targetDirs = extractDirs(newerInfo);
                        }
                    }
                }
            } else {
                int bestMonthIdx = -1;
                String bestMonthPath = null;
                for (DirItem d : dirs) {
                    int mIdx = getMonthIndexFromName(d.name);
                    if (mIdx > bestMonthIdx) {
                        bestMonthIdx = mIdx;
                        bestMonthPath = d.path;
                    }
                }
                if (bestMonthPath != null) {
                    JSONObject monthInfo = requestFilesApi(serverUrl, bestMonthPath);
                    if (monthInfo != null) {
                        targetDirs = extractDirs(monthInfo);
                    }
                }
            }

            List<String> result = new ArrayList<>();
            for (DirItem d : targetDirs) {
                if (!d.name.isEmpty()) {
                    result.add(d.name);
                }
            }
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    private static class DirItem {
        final String name;
        final String path;

        DirItem(String name, String path) {
            this.name = name;
            this.path = path;
        }
    }

    private List<DirItem> extractDirs(JSONObject info) {
        List<DirItem> list = new ArrayList<>();
        JSONArray items = info.optJSONArray("items");
        if (items == null) return list;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null && item.optBoolean("is_directory", false)) {
                String n = item.optString("name", "").trim();
                String p = item.optString("path", "").trim();
                if (!n.isEmpty() && !p.isEmpty()) {
                    list.add(new DirItem(n, p));
                }
            }
        }
        return list;
    }

    private JSONObject requestFilesApi(String serverUrl, String path) {
        HttpURLConnection conn = null;
        try {
            String encoded = URLEncoder.encode(path, "UTF-8");
            URL url = new URL(serverUrl + "api/files?path=" + encoded);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);

            if (conn.getResponseCode() != 200) return null;

            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            JSONObject json = new JSONObject(sb.toString());
            if (json.has("error") && !json.isNull("error")) return null;
            return json;
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String resolveMacros(String pattern) {
        Calendar cal = Calendar.getInstance();
        int year = cal.get(Calendar.YEAR);
        int monthIdx = cal.get(Calendar.MONTH);
        String monthName = MONTHS_RU[monthIdx];
        String monthNum = String.format(Locale.US, "%02d", monthIdx + 1);
        return pattern
                .replace("{YEAR}", String.valueOf(year))
                .replace("{MONTH}", monthName)
                .replace("{MONTH_NUM}", monthNum);
    }

    private int getMonthIndexFromName(String name) {
        if (name == null) return -1;
        String n = name.toLowerCase(Locale.ROOT).trim();
        if (n.matches("^\\d{1,2}[.\\-]\\d{1,2}.*") || n.matches(".*\\d{4}-\\d{2}-\\d{2}.*")) {
            return -1;
        }
        for (int i = 0; i < 12; i++) {
            Matcher m = MONTH_RE[i].matcher(n);
            if (m.find()) {
                int start = m.start();
                if (start == 0 || !Character.isLetter(n.charAt(start - 1))) {
                    return i;
                }
            }
        }
        Matcher num = Pattern.compile("^(\\d{1,2})(?!\\d)").matcher(n);
        if (num.find()) {
            try {
                int v = Integer.parseInt(num.group(1));
                if (v >= 1 && v <= 12) return v - 1;
            } catch (Exception ignored) {
            }
        }
        return -1;
    }

    private String baseName(String path) {
        if (path == null) return "";
        String[] parts = path.split("[\\\\/]");
        for (int i = parts.length - 1; i >= 0; i--) {
            if (!parts[i].trim().isEmpty()) return parts[i].trim();
        }
        return "";
    }

    private static JSONArray loadPendingArray(SharedPreferences prefs) {
        String raw = prefs.getString(KEY_PENDING_DAYS, "[]");
        try {
            return new JSONArray(raw);
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    public static Set<String> loadStringSet(SharedPreferences prefs, String key) {
        Set<String> set = new HashSet<>();
        String raw = prefs.getString(key, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                String v = arr.optString(i, "");
                if (!v.isEmpty()) set.add(v);
            }
        } catch (Exception ignored) {
        }
        return set;
    }

    public static void saveStringSet(SharedPreferences prefs, String key, Set<String> set) {
        List<String> list = new ArrayList<>(set);
        Collections.sort(list);
        JSONArray arr = new JSONArray();
        for (String s : list) arr.put(s);
        prefs.edit().putString(key, arr.toString()).apply();
    }

    public static void ensureNotificationChannel(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        "Новые смены на производстве",
                        NotificationManager.IMPORTANCE_HIGH
                );
                channel.setDescription("Уведомления о появлении новых смен на сервере и Яндекс Диске");
                nm.createNotificationChannel(channel);
            }
        }
    }

    public static void clearPendingNotifications(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit()
                .remove(KEY_PENDING_DAYS)
                .remove(KEY_LAST_ALERT_MS)
                .apply();
        NotificationManagerCompat.from(ctx).cancel(NOTIFICATION_ID);
    }

    private static String joinStrings(String delimiter, List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(delimiter);
            sb.append(items.get(i));
        }
        return sb.toString();
    }
}
