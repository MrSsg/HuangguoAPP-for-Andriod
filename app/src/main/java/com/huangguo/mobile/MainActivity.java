package com.huangguo.mobile;

import android.app.Activity;
import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.JsResult;
import com.parse.ParseUser;
import android.window.BackEvent;
import android.window.OnBackAnimationCallback;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.widget.FrameLayout;
import androidx.core.content.FileProvider;

import org.json.JSONObject;
import java.io.File;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String TAG = "HuangGuo";
    private static final int AVATAR_PICKER = 7101;
    private final ExecutorService requests = Executors.newFixedThreadPool(4);
    private WebView webView;
    private FrameLayout root;
    private SiteRepository repository;
    private AppStore store;
    private AccountService account;
    private UpdateManager updates;
    private File pendingUpdateApk;
    private OnBackInvokedCallback backCallback;
    private boolean backRegistered;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        repository = new SiteRepository(this);
        store = new AppStore(this);
        account = new AccountService(this, store);
        try {
            ParseUser current = ParseUser.getCurrentUser();
            store.activateUser(current == null ? "" : current.getObjectId());
        } catch (Exception error) { Log.w(TAG, "无法恢复账号数据", error); }
        updates = new UpdateManager(this);
        if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
        root = new FrameLayout(this);
        webView = new WebView(this);
        webView.setBackgroundColor(Color.TRANSPARENT);
        root.addView(webView, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars());
                root.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            } else {
                root.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        applyTheme();
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setAllowFileAccess(true);
        webView.getSettings().setAllowFileAccessFromFileURLs(false);
        webView.getSettings().setAllowUniversalAccessFromFileURLs(false);
        webView.getSettings().setMediaPlaybackRequiresUserGesture(true);
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !"file".equals(request.getUrl().getScheme());
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(ConsoleMessage message) {
                Log.d(TAG, message.message() + " @" + message.lineNumber());
                return true;
            }
            @Override public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                new android.app.AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setPositiveButton("清空", (dialog, which) -> result.confirm())
                        .setNegativeButton("取消", (dialog, which) -> result.cancel())
                        .setOnCancelListener(dialog -> result.cancel())
                        .show();
                return true;
            }
        });
        webView.addJavascriptInterface(new Bridge(), "AndroidHost");
        webView.loadUrl("file:///android_asset/app.html");
    }

    private boolean dark() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    private void applyTheme() {
        boolean dark = dark();
        int background = Color.parseColor(dark ? "#171B1D" : "#F5F2E9");
        root.setBackgroundColor(background);
        getWindow().setStatusBarColor(background);
        getWindow().setNavigationBarColor(background);
        int flags = dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        getWindow().getDecorView().setSystemUiVisibility(flags);
        if (webView != null) webView.evaluateJavascript("window.hgTheme && window.hgTheme(" + dark + ")", null);
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        applyTheme();
    }

    @Override protected void onResume() {
        super.onResume();
        if (webView != null) webView.evaluateJavascript("window.hgResume && window.hgResume()", null);
        if (account != null && ParseUser.getCurrentUser() != null) requests.execute(() -> {
            try {
                account.sync();
                runOnUiThread(() -> webView.evaluateJavascript("window.hgCloudSynced && window.hgCloudSynced()", null));
            } catch (Exception error) { Log.w(TAG, "云端同步稍后重试", error); }
        });
        if (pendingUpdateApk != null) {
            File apk = pendingUpdateApk;
            pendingUpdateApk = null;
            if (getPackageManager().canRequestPackageInstalls()) openUpdateInstaller(apk);
            else webView.evaluateJavascript("window.hgUpdateInstallPermissionDenied && window.hgUpdateInstallPermissionDenied()", null);
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != AVATAR_PICKER || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri selected = data.getData();
        requests.execute(() -> {
            try {
                JSONObject profile = account.setAvatar(selected);
                runOnUiThread(() -> webView.evaluateJavascript("window.hgAvatarUpdated && window.hgAvatarUpdated(" + profile + ")", null));
            } catch (Exception error) {
                String script = "window.hgAvatarError && window.hgAvatarError(" + JSONObject.quote(error.getMessage() == null ? "头像保存失败" : error.getMessage()) + ")";
                runOnUiThread(() -> webView.evaluateJavascript(script, null));
            }
        });
    }

    private void installUpdate(File apk) {
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            pendingUpdateApk = apk;
            Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName()));
            startActivity(settings);
            return;
        }
        openUpdateInstaller(apk);
    }

    private void openUpdateInstaller(File apk) {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".updates", apk);
            Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
            intent.setData(uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (ActivityNotFoundException | IllegalArgumentException error) {
            Log.e(TAG, "无法打开系统安装程序", error);
            webView.evaluateJavascript("window.hgUpdateInstallError && window.hgUpdateInstallError()", null);
        }
    }

    private void updateBackRegistration(boolean enabled) {
        if (Build.VERSION.SDK_INT < 33 || enabled == backRegistered) return;
        if (enabled) {
            if (Build.VERSION.SDK_INT >= 34) {
                backCallback = new OnBackAnimationCallback() {
                    @Override public void onBackStarted(BackEvent event) {
                        webView.evaluateJavascript("window.hgPredictiveBackStart && window.hgPredictiveBackStart(" +
                                (event.getSwipeEdge() == BackEvent.EDGE_RIGHT) + ")", null);
                    }

                    @Override public void onBackProgressed(BackEvent event) {
                        webView.evaluateJavascript("window.hgPredictiveBackProgress && window.hgPredictiveBackProgress(" +
                                event.getProgress() + ")", null);
                    }

                    @Override public void onBackCancelled() {
                        webView.evaluateJavascript("window.hgPredictiveBackCancel && window.hgPredictiveBackCancel()", null);
                    }

                    @Override public void onBackInvoked() { handleBack(true); }
                };
            } else backCallback = () -> handleBack(false);
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
        } else {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
            backCallback = null;
        }
        backRegistered = enabled;
    }

    private void handleBack(boolean predictive) {
        if (webView == null) { finish(); return; }
        String script = predictive
                ? "window.hgPredictiveBackCommit ? window.hgPredictiveBackCommit() : (window.hgBack ? window.hgBack() : false)"
                : "window.hgBack ? window.hgBack() : false";
        webView.evaluateJavascript(script, result -> {
            if (!"true".equals(result) && !moveTaskToBack(true)) finish();
        });
    }

    @Override public void onBackPressed() {
        handleBack(false);
    }

    @Override protected void onDestroy() {
        if (Build.VERSION.SDK_INT >= 33 && backRegistered) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        }
        requests.shutdownNow();
        if (webView != null) {
            webView.removeJavascriptInterface("AndroidHost");
            webView.destroy();
        }
        super.onDestroy();
    }

    private Object dispatch(String action, JSONObject args) throws Exception {
        switch (action) {
            case "theme": return dark();
            case "account": return account.profile();
            case "register": return account.register(args.optString("username"), args.optString("password"));
            case "login": return account.login(args.optString("username"), args.optString("password"));
            case "change-password": return account.changePassword(args.optString("oldPassword"), args.optString("newPassword"));
            case "logout": account.logout(); return account.profile();
            case "sync": return account.sync();
            case "choose-avatar": {
                if (ParseUser.getCurrentUser() == null) throw new IllegalStateException("请先登录");
                runOnUiThread(() -> {
                    Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    picker.addCategory(Intent.CATEGORY_OPENABLE);
                    picker.setType("image/*");
                    startActivityForResult(picker, AVATAR_PICKER);
                });
                return true;
            }
            case "app-version": return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            case "cached-update": return updates.cached();
            case "check-update": return updates.check(args.optBoolean("manual"));
            case "download-update": {
                File apk = updates.download(percent -> runOnUiThread(() ->
                        webView.evaluateJavascript("window.hgUpdateProgress && window.hgUpdateProgress(" + percent + ")", null)));
                runOnUiThread(() -> installUpdate(apk));
                return true;
            }
            case "cached": return store.cached(args.optString("key"));
            case "home": {
                JSONObject result = repository.home();
                store.saveCache("home", result);
                return result;
            }
            case "category": {
                String id = args.optString("id");
                int page = args.optInt("page", 1);
                JSONObject result = repository.category(id, page);
                store.saveCache("category:" + id + ":" + page, result);
                return result;
            }
            case "tags": {
                JSONObject result = repository.tags();
                store.saveCache("tags", result);
                return result;
            }
            case "tag-list": {
                String slug = args.optString("slug");
                int page = args.optInt("page", 1);
                JSONObject result = repository.tagListing(slug, page);
                store.saveCache("tag:" + slug + ":" + page, result);
                return result;
            }
            case "recommend":
            case "newest": {
                int page = args.optInt("page", 1);
                JSONObject result = repository.recommend(page, "newest".equals(action));
                store.saveCache(action + ":" + page, result);
                return result;
            }
            case "search": {
                String term = args.optString("query").trim();
                JSONObject result = repository.search(term);
                store.saveCache("search:" + term, result);
                return result;
            }
            case "detail": {
                String id = args.optString("id");
                JSONObject result = repository.detail(id);
                store.saveCache("detail:" + id, result);
                return result;
            }
            case "episode": return repository.episode(args.optString("id"), args.optInt("episode", 1));
            case "cover": return repository.coverData(args.optString("url"));
            case "library": return store.library();
            case "bookmark": {
                boolean saved = store.toggleBookmark(args);
                if (ParseUser.getCurrentUser() != null) requests.execute(() -> {
                    try { account.sync(); } catch (Exception error) { Log.w(TAG, "收藏稍后同步", error); }
                });
                return saved;
            }
            case "bookmark-state": return store.hasBookmark(args.optString("id"));
            case "get-progress": return store.getProgress(args.optString("id"), args.optInt("episode", 1));
            case "clear-history": store.clearHistory(); if (ParseUser.getCurrentUser() != null) requests.execute(() -> { try { account.sync(); } catch (Exception ignored) { } }); return true;
            case "clear-bookmarks": store.clearBookmarks(); if (ParseUser.getCurrentUser() != null) requests.execute(() -> { try { account.sync(); } catch (Exception ignored) { } }); return true;
            case "clear-content-cache": store.clearCache(); repository.clearCovers(); return true;
            case "quit": runOnUiThread(this::finish); return true;
            case "open-player": {
                Intent intent = new Intent(this, PlayerActivity.class);
                intent.putExtra("payload", args.toString());
                runOnUiThread(() -> startActivity(intent));
                return true;
            }
            default: throw new IllegalArgumentException("未知请求");
        }
    }

    private final class Bridge {
        @JavascriptInterface public void setBackEnabled(boolean enabled) {
            runOnUiThread(() -> updateBackRegistration(enabled));
        }

        @JavascriptInterface public void request(String requestId, String action, String argsText) {
            requests.execute(() -> {
                try {
                    JSONObject args = new JSONObject(argsText);
                    Object result = dispatch(action, args);
                    String data;
                    if (result == null) data = "null";
                    else if (result instanceof JSONObject) data = result.toString();
                    else if (result instanceof Boolean || result instanceof Number) data = result.toString();
                    else data = JSONObject.quote(String.valueOf(result));
                    String script = "window.NativeCallbacks.resolve(" + JSONObject.quote(requestId) + "," + data + ")";
                    String authUserId = ("login".equals(action) || "register".equals(action)) && result instanceof JSONObject
                            ? ((JSONObject) result).optString("userId") : "";
                    runOnUiThread(() -> {
                        webView.evaluateJavascript(script, null);
                        if (!authUserId.isEmpty()) refreshAccountAfterAuth(authUserId);
                    });
                } catch (Exception error) {
                    Log.e(TAG, action + " failed", error);
                    String script = "window.NativeCallbacks.reject(" + JSONObject.quote(requestId) + "," + JSONObject.quote(error.getMessage() == null ? "请求失败" : error.getMessage()) + ")";
                    runOnUiThread(() -> webView.evaluateJavascript(script, null));
                }
            });
        }
    }

    private void refreshAccountAfterAuth(String userId) {
        requests.execute(() -> {
            try {
                account.sync();
                runOnUiThread(() -> webView.evaluateJavascript("window.hgCloudSynced && window.hgCloudSynced()", null));
            } catch (Exception error) { Log.w(TAG, "登录后片单稍后同步", error); }
            try {
                JSONObject refreshed = account.profile();
                if (!userId.equals(refreshed.optString("userId"))) return;
                String script = "window.hgAccountRefreshed && window.hgAccountRefreshed(" + refreshed + ")";
                runOnUiThread(() -> webView.evaluateJavascript(script, null));
            } catch (Exception error) { Log.w(TAG, "登录后账号资料稍后刷新", error); }
        });
    }
}
