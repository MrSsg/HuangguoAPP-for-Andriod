package com.huangguo.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

final class UpdateManager {
    private static final String RELEASE_BASE = "https://github.com/MrSsg/HuangguoAPP-for-Andriod/releases/";
    private static final String MANIFEST_URL = RELEASE_BASE + "latest/download/android-update.json";
    private static final Pattern VERSION = Pattern.compile("\\d+\\.\\d+\\.\\d+");
    private static final long AUTO_INTERVAL_MS = 12 * 60 * 60 * 1000L;
    private static final long MANUAL_WINDOW_MS = 60 * 60 * 1000L;
    private static final int MANUAL_LIMIT = 5;

    interface Progress { void onPercent(int percent); }

    private final Context context;
    private final SharedPreferences preferences;
    private Candidate candidate;
    private boolean downloading;

    private static final class Candidate {
        String version;
        String url;
        String digest;
        long size;
        String notes;
    }

    UpdateManager(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences("updates", Context.MODE_PRIVATE);
    }

    synchronized JSONObject check(boolean manual) throws Exception {
        long now = System.currentTimeMillis();
        if (manual) {
            List<Long> checks = new ArrayList<>();
            for (String value : preferences.getString("manual_checks", "").split(",")) {
                try {
                    long timestamp = Long.parseLong(value);
                    if (timestamp <= now && now - timestamp < MANUAL_WINDOW_MS) checks.add(timestamp);
                } catch (NumberFormatException ignored) { }
            }
            if (checks.size() >= MANUAL_LIMIT) return status("throttled");
            checks.add(now);
            StringBuilder saved = new StringBuilder();
            for (long timestamp : checks) {
                if (saved.length() > 0) saved.append(',');
                saved.append(timestamp);
            }
            preferences.edit().putString("manual_checks", saved.toString()).apply();
        } else {
            long last = preferences.getLong("last_auto_check", 0);
            if (last > 0 && now >= last && now - last < AUTO_INTERVAL_MS) return status("skipped");
            preferences.edit().putLong("last_auto_check", now).apply();
        }

        JSONObject manifest = fetchManifest();
        if (manual) preferences.edit().putLong("last_auto_check", now).apply();
        PackageInfo installed = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        String localVersion = installed.versionName;
        if (manifest == null) {
            candidate = null;
            return status("unpublished");
        }
        long installedCode = Build.VERSION.SDK_INT >= 28 ? installed.getLongVersionCode() : installed.versionCode;
        if (manifest.optLong("versionCode") <= installedCode) {
            candidate = null;
            return status("current").put("version", localVersion);
        }
        String version = manifest.optString("versionName");
        String tag = manifest.optString("tag");
        String apk = manifest.optString("apk");
        String sha256 = manifest.optString("sha256");
        if (!VERSION.matcher(version).matches() || !tag.equals("v" + version)
                || !apk.equals("HuangGuo-Android-v" + version + "-release.apk")
                || !sha256.matches("[0-9a-fA-F]{64}") || manifest.optLong("size") <= 0) {
            throw new IllegalStateException("发布信息格式有误");
        }
        Candidate newest = new Candidate();
        newest.version = version;
        newest.url = RELEASE_BASE + "download/" + tag + "/" + apk;
        newest.digest = "sha256:" + sha256;
        newest.size = manifest.getLong("size");
        newest.notes = manifest.optString("notes");
        candidate = newest;
        return status("available")
                .put("version", newest.version)
                .put("currentVersion", localVersion)
                .put("notes", newest.notes)
                .put("size", newest.size);
    }

    synchronized File download(Progress progress) throws Exception {
        if (downloading) throw new IllegalStateException("更新包正在下载");
        if (candidate == null) throw new IllegalStateException("请先检查更新");
        if (!candidate.digest.matches("sha256:[0-9a-fA-F]{64}")) throw new IllegalStateException("发布包缺少 SHA-256 校验值");
        if (!candidate.url.startsWith(RELEASE_BASE + "download/")) {
            throw new IllegalStateException("更新包地址无效");
        }
        downloading = true;
        try {
            File directory = new File(context.getCacheDir(), "updates");
            if (!directory.exists() && !directory.mkdirs()) throw new IllegalStateException("无法创建下载目录");
            File apk = new File(directory, "HuangGuo-Android-v" + candidate.version + "-release.apk");
            if (apk.isFile() && matchesDigest(apk, candidate.digest)) {
                progress.onPercent(100);
                return apk;
            }
            File partial = new File(directory, apk.getName() + ".part");
            HttpURLConnection connection = open(candidate.url);
            try {
                long downloaded = 0;
                long total = candidate.size > 0 ? candidate.size : connection.getContentLengthLong();
                try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(partial)) {
                    byte[] buffer = new byte[64 * 1024];
                    int count;
                    int previous = -1;
                    while ((count = input.read(buffer)) != -1) {
                        output.write(buffer, 0, count);
                        downloaded += count;
                        int percent = total > 0 ? (int) Math.min(99, downloaded * 100 / total) : 0;
                        if (percent != previous) { progress.onPercent(percent); previous = percent; }
                    }
                }
                if (candidate.size > 0 && downloaded != candidate.size) throw new IllegalStateException("更新包下载不完整，请重试");
                if (!matchesDigest(partial, candidate.digest)) throw new IllegalStateException("更新包校验失败，请重试");
                if (apk.exists() && !apk.delete()) throw new IllegalStateException("无法替换旧下载包");
                if (!partial.renameTo(apk)) throw new IllegalStateException("无法保存更新包");
                progress.onPercent(100);
                return apk;
            } finally {
                connection.disconnect();
                if (partial.exists()) partial.delete();
            }
        } finally {
            downloading = false;
        }
    }

    private static JSONObject status(String value) throws Exception { return new JSONObject().put("status", value); }

    private static HttpURLConnection open(String address) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        connection.setRequestProperty("User-Agent", "HuangGuo-Android-Updater");
        connection.setRequestProperty("Accept", "application/json");
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            connection.disconnect();
            throw new IllegalStateException("GitHub 请求失败（" + code + "）");
        }
        return connection;
    }

    private static JSONObject fetchManifest() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(MANIFEST_URL).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        connection.setRequestProperty("User-Agent", "HuangGuo-Android-Updater");
        int code = connection.getResponseCode();
        if (code == 404) { connection.disconnect(); return null; }
        if (code < 200 || code >= 300) {
            connection.disconnect();
            throw new IllegalStateException("GitHub 请求失败（" + code + "）");
        }
        try (InputStream stream = connection.getInputStream()) {
            byte[] bytes = new byte[64 * 1024];
            int length = 0;
            while (true) {
                int count = stream.read(bytes, length, bytes.length - length);
                if (count < 0) break;
                length += count;
                if (length == bytes.length) throw new IllegalStateException("发布列表过大");
            }
            return new JSONObject(new String(bytes, 0, length, java.nio.charset.StandardCharsets.UTF_8));
        } finally {
            connection.disconnect();
        }
    }

    private static boolean matchesDigest(File file, String expected) throws Exception {
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) sha256.update(buffer, 0, count);
        }
        StringBuilder actual = new StringBuilder("sha256:");
        for (byte value : sha256.digest()) actual.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return actual.toString().equalsIgnoreCase(expected);
    }
}
