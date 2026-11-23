package com.production.monitoring;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
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
import java.io.InputStreamReader;
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

public class NewDayWorker extends Worker {

    public static final String PREFS_NAME = "monitoring_prefs";
    public static final String KEY_SERVER_URL = "server_url";
    public static final String KEY_LINES_CONFIG = "lines_config_json";
    public static final String KEY_NOTIFY_ENABLED = "notify_enabled";
    public static final String KEY_PENDING_DAYS = "pending_new_days";
    public static final String KEY_LAST_ALERT_MS = "last_new_day_alert_ms";
    public static final String EXTRA_OPEN_LINE_IDX = "extra_open_line_idx";

    public static final int SCAN_PORT = 5000;
    public static final int SOCKET_TIMEOUT_MS = 450;
    public static final int VERIFY_TIMEOUT_MS = 1200;
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

        List<String> servers = findWorkingServers(prefs, false);
        if (servers.isEmpty()) {
            return Result.success();
        }
        String serverUrl = servers.get(0);

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
                if (basePath.isEmpty()) continue;

                configuredCount++;

                List<String> dayFolders = fetchCurrentDayFolders(serverUrl, basePath);
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

    // ==================== ТИХИЙ АВТОПОИСК СЕРВЕРА С ПРОВЕРКОЙ «СВОЙ/ЧУЖОЙ» ====================

    public static List<String> findWorkingServers(SharedPreferences prefs, boolean forceFullScan) {
        String savedUrl = normalizeBaseUrl(prefs.getString(KEY_SERVER_URL, ""));
        if (!forceFullScan && !savedUrl.isEmpty()) {
            if (isMonitoringServer(savedUrl, VERIFY_TIMEOUT_MS)) {
                return Collections.singletonList(savedUrl);
            }
        }

        String localPrefix = getLocalSubnetPrefix();
        String firstPrefix = (localPrefix != null) ? localPrefix : FALLBACK_SUBNET;

        List<String> verified = scanAndVerifySubnet(firstPrefix);
        if (verified.isEmpty() && !FALLBACK_SUBNET.equals(firstPrefix)) {
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
            pool.awaitTermination(12, TimeUnit.SECONDS);
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

    // ==================== УВЕДОМЛЕНИЯ О НОВЫХ ДНЯХ ====================

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
            inboxStyle.addLine(lName + " — " + fName);
        }

        if (count == 1) {
            JSONObject single = pending.optJSONObject(0);
            String lName = single != null ? single.optString("lineName", "Линия") : "Линия";
            String fName = single != null ? single.optString("folderName", "") : "";
            title = "Новый день: " + lName;
            summaryText = "Появилась папка " + fName;
        } else {
            title = "Новые дни на производстве (" + count + ")";
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
                .setSmallIcon(android.R.drawable.ic_dialog_info)
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
                        "Новые дни на производстве",
                        NotificationManager.IMPORTANCE_HIGH
                );
                channel.setDescription("Уведомления о появлении новых папок смен на сервере");
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
