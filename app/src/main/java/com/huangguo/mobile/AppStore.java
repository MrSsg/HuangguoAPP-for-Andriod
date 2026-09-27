package com.huangguo.mobile;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

final class AppStore {
    private static final int HISTORY_LIMIT = 50;
    private final SharedPreferences preferences;

    AppStore(Context context) {
        preferences = context.getSharedPreferences("huangguo-data", Context.MODE_PRIVATE);
    }

    private String scoped(String name) {
        String user = preferences.getString("activeUserId", "");
        return user.isEmpty() ? name : "user:" + user + ":" + name;
    }

    private JSONObject readObject(String key) {
        try { return new JSONObject(preferences.getString(key, "{}")); }
        catch (Exception ignored) { return new JSONObject(); }
    }

    synchronized void activateUser(String userId) throws Exception {
        String current = preferences.getString("activeUserId", "");
        if (current.equals(userId)) return;
        SharedPreferences.Editor editor = preferences.edit();
        if (current.isEmpty() && !userId.isEmpty()) {
            String prefix = "user:" + userId + ":";
            editor.putString(prefix + "bookmarks", mergeRecords(readObject("bookmarks"), readObject(prefix + "bookmarks"), "addedAt").toString());
            editor.putString(prefix + "progress", mergeRecords(readObject("progress"), readObject(prefix + "progress"), "updatedAt").toString());
            editor.remove("bookmarks").remove("progress").remove("bookmarkDeleted").remove("historyDeleted");
        }
        editor.putString("activeUserId", userId).apply();
    }

    private static JSONObject mergeRecords(JSONObject first, JSONObject second, String timestamp) throws Exception {
        JSONObject merged = new JSONObject(second.toString());
        for (java.util.Iterator<String> keys = first.keys(); keys.hasNext();) {
            String key = keys.next();
            JSONObject incoming = first.optJSONObject(key);
            JSONObject existing = merged.optJSONObject(key);
            if (incoming != null && (existing == null || incoming.optLong(timestamp) > existing.optLong(timestamp))) merged.put(key, incoming);
        }
        return merged;
    }

    synchronized JSONObject cached(String key) {
        try {
            String value = preferences.getString("cache:" + key, null);
            return value == null ? null : new JSONObject(value);
        } catch (Exception ignored) { return null; }
    }

    synchronized void saveCache(String key, JSONObject data) throws Exception {
        JSONObject entry = new JSONObject().put("at", System.currentTimeMillis()).put("data", data);
        preferences.edit().putString("cache:" + key, entry.toString()).apply();
    }

    synchronized void clearCache() {
        SharedPreferences.Editor editor = preferences.edit();
        for (String key : preferences.getAll().keySet()) if (key.startsWith("cache:")) editor.remove(key);
        editor.apply();
    }

    private JSONObject object(String key) {
        return readObject(scoped(key));
    }

    synchronized boolean toggleBookmark(JSONObject item) throws Exception {
        String id = item.optString("id");
        if (!id.matches("\\d+")) throw new IllegalArgumentException("无效剧集");
        JSONObject all = object("bookmarks");
        JSONObject deleted = object("bookmarkDeleted");
        if (all.has(id)) {
            all.remove(id);
            deleted.put(id, System.currentTimeMillis());
            preferences.edit().putString(scoped("bookmarks"), all.toString())
                    .putString(scoped("bookmarkDeleted"), deleted.toString()).apply();
            return false;
        }
        JSONObject saved = new JSONObject();
        saved.put("id", id);
        saved.put("title", item.optString("title"));
        saved.put("cover", item.optString("cover"));
        saved.put("addedAt", System.currentTimeMillis());
        all.put(id, saved);
        deleted.remove(id);
        preferences.edit().putString(scoped("bookmarks"), all.toString())
                .putString(scoped("bookmarkDeleted"), deleted.toString()).apply();
        return true;
    }

    synchronized boolean hasBookmark(String id) {
        return object("bookmarks").has(id);
    }

    synchronized JSONObject getProgress(String id, int episode) {
        return object("progress").optJSONObject(id + ":" + episode);
    }

    synchronized void recordProgress(JSONObject input) throws Exception {
        String id = input.optString("id");
        int episode = input.optInt("episode", 1);
        long position = input.optLong("position");
        long duration = input.optLong("duration");
        if (!id.matches("\\d+") || episode < 1 || duration <= 0) return;
        JSONObject all = object("progress");
        JSONObject item = new JSONObject();
        item.put("id", id);
        item.put("episode", episode);
        item.put("title", input.optString("title"));
        item.put("cover", input.optString("cover"));
        item.put("position", Math.max(0, Math.min(position, duration)));
        item.put("duration", duration);
        item.put("completed", input.optBoolean("completed"));
        item.put("updatedAt", System.currentTimeMillis());
        all.put(id + ":" + episode, item);
        JSONObject deleted = object("historyDeleted");
        deleted.remove(id);
        for (String removed : trimProgress(all)) deleted.put(removed, System.currentTimeMillis());
        preferences.edit().putString(scoped("progress"), all.toString())
                .putString(scoped("historyDeleted"), deleted.toString()).apply();
    }

    private static Set<String> trimProgress(JSONObject all) {
        Map<String, Long> latest = new HashMap<>();
        for (java.util.Iterator<String> keys = all.keys(); keys.hasNext();) {
            JSONObject item = all.optJSONObject(keys.next());
            if (item == null) continue;
            String id = item.optString("id");
            latest.put(id, Math.max(latest.getOrDefault(id, 0L), item.optLong("updatedAt")));
        }
        if (latest.size() <= HISTORY_LIMIT) return new HashSet<>();
        ArrayList<String> ids = new ArrayList<>(latest.keySet());
        ids.sort((left, right) -> Long.compare(latest.get(right), latest.get(left)));
        Set<String> kept = new HashSet<>(ids.subList(0, HISTORY_LIMIT));
        ArrayList<String> removed = new ArrayList<>();
        Set<String> removedIds = new HashSet<>();
        for (java.util.Iterator<String> keys = all.keys(); keys.hasNext();) {
            String key = keys.next();
            JSONObject item = all.optJSONObject(key);
            if (item == null || !kept.contains(item.optString("id"))) {
                removed.add(key);
                if (item != null) removedIds.add(item.optString("id"));
            }
        }
        for (String key : removed) all.remove(key);
        return removedIds;
    }

    synchronized JSONObject library() throws Exception {
        JSONObject result = new JSONObject();
        JSONObject bookmarks = object("bookmarks");
        JSONObject progress = object("progress");
        Set<String> removed = trimProgress(progress);
        if (!removed.isEmpty()) {
            JSONObject deleted = object("historyDeleted");
            for (String id : removed) deleted.put(id, System.currentTimeMillis());
            preferences.edit().putString(scoped("progress"), progress.toString())
                    .putString(scoped("historyDeleted"), deleted.toString()).apply();
        }
        ArrayList<JSONObject> saved = new ArrayList<>();
        Map<String, JSONObject> latestByWork = new HashMap<>();
        for (java.util.Iterator<String> keys = bookmarks.keys(); keys.hasNext();) {
            String key = keys.next();
            JSONObject item = bookmarks.optJSONObject(key);
            if (item != null) saved.add(item);
        }
        for (java.util.Iterator<String> keys = progress.keys(); keys.hasNext();) {
            String key = keys.next();
            JSONObject item = progress.optJSONObject(key);
            if (item == null || (!item.optBoolean("completed") && item.optLong("position") <= 5000)) continue;
            String id = item.optString("id");
            JSONObject previous = latestByWork.get(id);
            if (previous == null || item.optLong("updatedAt") > previous.optLong("updatedAt")) latestByWork.put(id, item);
        }
        ArrayList<JSONObject> recent = new ArrayList<>(latestByWork.values());
        saved.sort(Comparator.comparingLong((JSONObject value) -> value.optLong("addedAt")).reversed());
        recent.sort(Comparator.comparingLong((JSONObject value) -> value.optLong("updatedAt")).reversed());
        JSONArray bookmarkArray = new JSONArray();
        JSONArray progressArray = new JSONArray();
        for (JSONObject item : saved) bookmarkArray.put(item);
        for (JSONObject item : recent) progressArray.put(item);
        result.put("bookmarks", bookmarkArray);
        result.put("progress", progressArray);
        return result;
    }

    synchronized JSONObject cloudSnapshot() throws Exception {
        JSONObject library = library();
        JSONObject bookmarks = new JSONObject();
        JSONObject history = new JSONObject();
        JSONArray saved = library.getJSONArray("bookmarks");
        JSONArray recent = library.getJSONArray("progress");
        for (int i = 0; i < saved.length(); i++) {
            JSONObject item = saved.optJSONObject(i);
            if (item != null) bookmarks.put(item.optString("id"), item);
        }
        for (int i = 0; i < recent.length(); i++) {
            JSONObject item = recent.optJSONObject(i);
            if (item != null) history.put(item.optString("id"), item);
        }
        return new JSONObject().put("bookmarks", bookmarks).put("history", history)
                .put("bookmarkDeleted", object("bookmarkDeleted"))
                .put("historyDeleted", object("historyDeleted"));
    }

    synchronized void applyCloudSnapshot(JSONObject merged) throws Exception {
        JSONObject bookmarks = merged.optJSONObject("bookmarks");
        JSONObject history = merged.optJSONObject("history");
        JSONObject historyDeleted = merged.optJSONObject("historyDeleted");
        JSONObject progress = object("progress");
        if (historyDeleted != null) {
            ArrayList<String> removed = new ArrayList<>();
            for (java.util.Iterator<String> keys = progress.keys(); keys.hasNext();) {
                String key = keys.next();
                JSONObject item = progress.optJSONObject(key);
                if (item != null && historyDeleted.optLong(item.optString("id")) >= item.optLong("updatedAt")) removed.add(key);
            }
            for (String key : removed) progress.remove(key);
        }
        if (history != null) for (java.util.Iterator<String> keys = history.keys(); keys.hasNext();) {
            String id = keys.next();
            JSONObject item = history.optJSONObject(id);
            if (item == null) continue;
            String key = id + ":" + item.optInt("episode", 1);
            JSONObject existing = progress.optJSONObject(key);
            if (existing == null || item.optLong("updatedAt") >= existing.optLong("updatedAt")) progress.put(key, item);
        }
        trimProgress(progress);
        preferences.edit()
                .putString(scoped("bookmarks"), bookmarks == null ? "{}" : bookmarks.toString())
                .putString(scoped("progress"), progress.toString())
                .putString(scoped("bookmarkDeleted"), merged.optJSONObject("bookmarkDeleted") == null ? "{}" : merged.getJSONObject("bookmarkDeleted").toString())
                .putString(scoped("historyDeleted"), historyDeleted == null ? "{}" : historyDeleted.toString())
                .apply();
    }

    synchronized void clearHistory() {
        JSONObject progress = object("progress");
        JSONObject deleted = object("historyDeleted");
        long now = System.currentTimeMillis();
        for (java.util.Iterator<String> keys = progress.keys(); keys.hasNext();) {
            JSONObject item = progress.optJSONObject(keys.next());
            if (item != null) try { deleted.put(item.optString("id"), now); } catch (Exception ignored) { }
        }
        preferences.edit().remove(scoped("progress")).putString(scoped("historyDeleted"), deleted.toString()).apply();
    }

    synchronized void clearBookmarks() {
        JSONObject bookmarks = object("bookmarks");
        JSONObject deleted = object("bookmarkDeleted");
        long now = System.currentTimeMillis();
        for (java.util.Iterator<String> keys = bookmarks.keys(); keys.hasNext();) {
            try { deleted.put(keys.next(), now); } catch (Exception ignored) { }
        }
        preferences.edit().remove(scoped("bookmarks")).putString(scoped("bookmarkDeleted"), deleted.toString()).apply();
    }
}
