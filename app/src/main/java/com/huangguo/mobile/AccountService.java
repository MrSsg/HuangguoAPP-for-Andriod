package com.huangguo.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.util.Base64;

import com.parse.ParseACL;
import com.parse.ParseException;
import com.parse.ParseFile;
import com.parse.ParseObject;
import com.parse.ParseQuery;
import com.parse.ParseUser;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class AccountService {
    private final Context context;
    private final AppStore store;
    private long lastProfileRefresh;

    AccountService(Context context, AppStore store) {
        this.context = context.getApplicationContext();
        this.store = store;
    }

    synchronized JSONObject profile() throws Exception {
        return profile(true);
    }

    private JSONObject profile(boolean refreshRemote) throws Exception {
        ParseUser user = ParseUser.getCurrentUser();
        if (user == null) {
            store.activateUser("");
            return new JSONObject().put("loggedIn", false);
        }
        if (refreshRemote && System.currentTimeMillis() - lastProfileRefresh > 5 * 60_000) try {
            user.fetch();
            lastProfileRefresh = System.currentTimeMillis();
        } catch (Exception ignored) { /* 离线时使用本地账号资料 */ }
        store.activateUser(user.getObjectId());
        JSONObject result = new JSONObject().put("loggedIn", true)
                .put("username", user.getUsername()).put("userId", user.getObjectId());
        File avatar = new File(new File(context.getFilesDir(), "avatars"), user.getObjectId() + ".jpg");
        SharedPreferences markers = context.getSharedPreferences("huangguo-avatars", Context.MODE_PRIVATE);
        ParseFile remote = user.getParseFile("avatar");
        if (remote != null) {
            String remoteName = remote.getName();
            if (refreshRemote && (!avatar.isFile() || !remoteName.equals(markers.getString(user.getObjectId(), "")))) try {
                byte[] data = remote.getData();
                avatar.getParentFile().mkdirs();
                try (FileOutputStream output = new FileOutputStream(avatar)) { output.write(data); }
                markers.edit().putString(user.getObjectId(), remoteName).apply();
            } catch (Exception ignored) { /* 离线时先显示默认头像 */ }
        }
        if (avatar.isFile()) {
            byte[] data = java.nio.file.Files.readAllBytes(avatar.toPath());
            result.put("avatar", "data:image/jpeg;base64," + Base64.encodeToString(data, Base64.NO_WRAP));
        }
        return result;
    }

    synchronized JSONObject register(String username, String password) throws Exception {
        validate(username, password);
        ParseUser user = new ParseUser();
        user.setUsername(username);
        user.setPassword(password);
        try { user.signUp(); }
        catch (ParseException error) {
            if (error.getCode() == 119) throw new IllegalStateException("当前无法注册，请联系管理员检查账号创建权限");
            throw error;
        }
        store.activateUser(user.getObjectId());
        return profile(false);
    }

    synchronized JSONObject login(String username, String password) throws Exception {
        validate(username, password);
        ParseUser user;
        try {
            user = ParseUser.logIn(username, password);
        } catch (ParseException error) {
            if (error.getCode() == 101) throw new IllegalArgumentException("账号不存在或密码错误，请检查后重试");
            throw error;
        }
        store.activateUser(user.getObjectId());
        return profile(false);
    }

    synchronized JSONObject changePassword(String oldPassword, String newPassword) throws Exception {
        ParseUser current = ParseUser.getCurrentUser();
        if (current == null) throw new IllegalStateException("请先登录账号");
        if (oldPassword == null || oldPassword.isEmpty()) throw new IllegalArgumentException("请输入旧密码");
        if (newPassword == null || newPassword.length() < 8) throw new IllegalArgumentException("新密码至少需要 8 位");
        if (newPassword.equals(oldPassword)) throw new IllegalArgumentException("新密码不能与旧密码相同");
        String username = current.getUsername();
        ParseUser verified;
        try {
            verified = ParseUser.logIn(username, oldPassword);
        } catch (ParseException error) {
            if (error.getCode() == 101) throw new IllegalArgumentException("旧密码不正确");
            throw error;
        }
        if (!current.getObjectId().equals(verified.getObjectId()))
            throw new IllegalStateException("账号验证失败，请重新登录");
        verified.setPassword(newPassword);
        verified.save();
        ParseUser.logIn(username, newPassword);
        return profile();
    }

    private static void validate(String username, String password) {
        if (username == null || !username.matches("[A-Za-z0-9_]{3,24}"))
            throw new IllegalArgumentException("账号需为 3–24 位字母、数字或下划线");
        if (password == null || password.length() < 8)
            throw new IllegalArgumentException("密码至少需要 8 位");
    }

    synchronized void logout() throws Exception {
        if (ParseUser.getCurrentUser() != null) try { sync(); } catch (Exception ignored) { }
        ParseUser.logOut();
        store.activateUser("");
    }

    synchronized JSONObject sync() throws Exception {
        ParseUser user = ParseUser.getCurrentUser();
        if (user == null) return new JSONObject().put("loggedIn", false);
        store.activateUser(user.getObjectId());
        JSONObject local = store.cloudSnapshot();
        ParseObject record = null;
        try {
            ParseQuery<ParseObject> query = ParseQuery.getQuery("UserLibrary");
            query.whereEqualTo("owner", user);
            query.orderByDescending("updatedAt");
            query.setLimit(1);
            List<ParseObject> rows = query.find();
            if (!rows.isEmpty()) record = rows.get(0);
        } catch (ParseException error) {
            if (error.getCode() != 101) throw error;
        }
        String payload = record == null ? null : record.getString("payload");
        JSONObject remote = new JSONObject(payload == null || payload.isEmpty() ? "{}" : payload);
        JSONObject merged = merge(local, remote);
        store.applyCloudSnapshot(merged);
        if (record == null || !merged.toString().equals(remote.toString())) {
            if (record == null) record = new ParseObject("UserLibrary");
            record.put("owner", user);
            record.put("payload", merged.toString());
            record.setACL(new ParseACL(user));
            record.save();
        }
        return new JSONObject().put("loggedIn", true).put("synced", true);
    }

    private static JSONObject merge(JSONObject local, JSONObject remote) throws Exception {
        JSONObject result = new JSONObject();
        mergeGroup(result, "bookmarks", "bookmarkDeleted", "addedAt", local, remote);
        mergeGroup(result, "history", "historyDeleted", "updatedAt", local, remote);
        JSONObject history = result.getJSONObject("history");
        ArrayList<String> ids = new ArrayList<>();
        for (java.util.Iterator<String> keys = history.keys(); keys.hasNext();) ids.add(keys.next());
        ids.sort((a, b) -> Long.compare(history.optJSONObject(b).optLong("updatedAt"), history.optJSONObject(a).optLong("updatedAt")));
        JSONObject deleted = result.getJSONObject("historyDeleted");
        for (int i = 50; i < ids.size(); i++) {
            String id = ids.get(i);
            deleted.put(id, Math.max(System.currentTimeMillis(), history.optJSONObject(id).optLong("updatedAt") + 1));
            history.remove(id);
        }
        return result;
    }

    private static void mergeGroup(JSONObject result, String liveKey, String deleteKey, String timestamp,
                                   JSONObject local, JSONObject remote) throws Exception {
        JSONObject localLive = local.optJSONObject(liveKey), remoteLive = remote.optJSONObject(liveKey);
        JSONObject localDeleted = local.optJSONObject(deleteKey), remoteDeleted = remote.optJSONObject(deleteKey);
        if (localLive == null) localLive = new JSONObject();
        if (remoteLive == null) remoteLive = new JSONObject();
        if (localDeleted == null) localDeleted = new JSONObject();
        if (remoteDeleted == null) remoteDeleted = new JSONObject();
        JSONObject live = new JSONObject(), deleted = new JSONObject();
        Set<String> ids = new HashSet<>();
        for (JSONObject group : new JSONObject[]{localLive, remoteLive, localDeleted, remoteDeleted})
            for (java.util.Iterator<String> keys = group.keys(); keys.hasNext();) ids.add(keys.next());
        for (String id : ids) {
            JSONObject left = localLive.optJSONObject(id), right = remoteLive.optJSONObject(id);
            JSONObject newest = right == null || (left != null && left.optLong(timestamp) > right.optLong(timestamp)) ? left : right;
            long liveAt = newest == null ? 0 : newest.optLong(timestamp);
            long deletedAt = Math.max(localDeleted.optLong(id), remoteDeleted.optLong(id));
            if (deletedAt >= liveAt && deletedAt > 0) deleted.put(id, deletedAt);
            else if (newest != null) live.put(id, newest);
        }
        result.put(liveKey, live).put(deleteKey, deleted);
    }

    synchronized JSONObject setAvatar(Uri uri) throws Exception {
        ParseUser user = ParseUser.getCurrentUser();
        if (user == null) throw new IllegalStateException("请先登录");
        byte[] bytes = cropAvatar(uri);
        ParseFile avatar = new ParseFile("avatar.jpg", bytes);
        avatar.save();
        user.put("avatar", avatar);
        user.save();
        File local = new File(new File(context.getFilesDir(), "avatars"), user.getObjectId() + ".jpg");
        local.getParentFile().mkdirs();
        try (FileOutputStream output = new FileOutputStream(local)) { output.write(bytes); }
        context.getSharedPreferences("huangguo-avatars", Context.MODE_PRIVATE)
                .edit().putString(user.getObjectId(), avatar.getName()).apply();
        return profile();
    }

    private byte[] cropAvatar(Uri uri) throws Exception {
        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            orientation = new ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (Exception ignored) { }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try (InputStream input = context.getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(input, null, options); }
        int sample = 1;
        while (Math.max(options.outWidth, options.outHeight) / sample > 768) sample *= 2;
        options.inJustDecodeBounds = false;
        options.inSampleSize = sample;
        Bitmap source;
        try (InputStream input = context.getContentResolver().openInputStream(uri)) { source = BitmapFactory.decodeStream(input, null, options); }
        if (source == null) throw new IllegalArgumentException("无法读取这张图片");
        int rotation = orientation == ExifInterface.ORIENTATION_ROTATE_90 ? 90 :
                orientation == ExifInterface.ORIENTATION_ROTATE_180 ? 180 :
                        orientation == ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
        if (rotation != 0) {
            Matrix matrix = new Matrix();
            matrix.postRotate(rotation);
            Bitmap rotated = Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
            source.recycle();
            source = rotated;
        }
        int side = Math.min(source.getWidth(), source.getHeight());
        Bitmap square = Bitmap.createBitmap(source, (source.getWidth() - side) / 2, (source.getHeight() - side) / 2, side, side);
        Bitmap resized = Bitmap.createScaledBitmap(square, 256, 256, true);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        resized.compress(Bitmap.CompressFormat.JPEG, 85, output);
        if (resized != square) resized.recycle();
        if (square != source) square.recycle();
        source.recycle();
        return output.toByteArray();
    }
}
