package com.huangguo.mobile;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.json.JSONArray;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class HotUpdateTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();
    private byte[] asset(String name) throws Exception {
        return HotUpdateManager.read(instrumentation.getContext().getAssets().open("hot/" + name), 12 * 1024 * 1024);
    }
    private HotUpdateManager manager() throws Exception {
        return new HotUpdateManager(context, new File(context.getCacheDir(), "hot-test-" + UUID.randomUUID()));
    }
    private void stage(HotUpdateManager manager, String name) throws Exception { manager.stage(asset(name + ".json"), asset(name + ".zip")); }
    private <T> T ui(Callable<T> operation) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> { try { result.set(operation.call()); } catch (Throwable error) { failure.set(error); } });
        if (failure.get() != null) throw new Exception(failure.get());
        return result.get();
    }
    private void await(Callable<Boolean> condition, String message) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 22_000;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition.call()) return;
            SystemClock.sleep(60);
        }
        fail(message);
    }
    private Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    private String javascript(WebView web, String expression) throws Exception {
        CountDownLatch done = new CountDownLatch(1); AtomicReference<String> value = new AtomicReference<>();
        ui(() -> { web.evaluateJavascript(expression, result -> { value.set(result); done.countDown(); }); return null; });
        assertTrue(done.await(5, TimeUnit.SECONDS)); return value.get();
    }

    @Test public void signedPackageActivatesAndPersists() throws Exception {
        HotUpdateManager manager = manager(); stage(manager, "good");
        assertEquals(101, manager.status().getLong("pending"));
        HotUpdateManager.Session trial = manager.beginSession();
        assertTrue(trial.trial); assertEquals("https://example.com", trial.site.origin);
        assertEquals(101, trial.revision);
        assertEquals("#e57373", trial.theme.getJSONObject("light").getString("--accent"));
        manager.markHealthy(trial);
        HotUpdateManager.Session again = manager.beginSession();
        assertFalse(again.trial); assertEquals(101, again.revision);
        assertEquals(200, manager.resource(again, "theme/dot.svg").getStatusCode());
        assertEquals(200, manager.resource(again, "app.html").getStatusCode());
    }

    @Test public void tamperedSignatureAndArchiveAreRejected() throws Exception {
        HotUpdateManager manager = manager();
        JSONObject envelope = new JSONObject(new String(asset("good.json"), StandardCharsets.UTF_8));
        JSONObject payload = new JSONObject(new String(android.util.Base64.decode(envelope.getString("payload"), 0), StandardCharsets.UTF_8));
        payload.put("revision", 900);
        envelope.put("payload", android.util.Base64.encodeToString(payload.toString().getBytes(StandardCharsets.UTF_8), 2));
        try { manager.verifyEnvelope(envelope.toString().getBytes(StandardCharsets.UTF_8)); fail("tampered payload accepted"); }
        catch (SecurityException expected) { }
        byte[] corrupted = asset("good.zip"); corrupted[corrupted.length / 2] ^= 1;
        try { manager.stage(asset("good.json"), corrupted); fail("corrupt archive accepted"); }
        catch (SecurityException expected) { }
        assertEquals(0, manager.status().getLong("revision"));
        assertEquals(0, manager.status().getLong("pending"));
    }

    @Test public void incompatibleAndEscapingPackagesAreRejected() throws Exception {
        HotUpdateManager manager = manager();
        try { stage(manager, "incompatible"); fail("incompatible package accepted"); }
        catch (IllegalArgumentException expected) { }
        try { stage(manager, "traversal"); fail("escaping archive accepted"); }
        catch (java.io.IOException expected) { }
        assertEquals(0, manager.status().getLong("pending"));
        assertFalse(new File(context.getCacheDir(), "escaped.js").exists());
    }

    @Test public void failedBootReturnsToPreviousAndBlocksReplay() throws Exception {
        HotUpdateManager manager = manager(); stage(manager, "good");
        HotUpdateManager.Session trial = manager.beginSession(); assertTrue(trial.trial);
        HotUpdateManager.Session recovered = manager.beginSession();
        assertEquals(0, recovered.revision); assertEquals(101, manager.status().getLong("blocked"));
        stage(manager, "good"); assertEquals(0, manager.status().getLong("pending"));
        stage(manager, "next");
        HotUpdateManager.Session next = manager.beginSession(); assertEquals(102, next.revision);
        manager.markHealthy(next);
        assertEquals(102, manager.beginSession().revision);
    }

    @Test public void invalidRulesDoNotDisplaceWorkingVersion() throws Exception {
        HotUpdateManager manager = manager(); stage(manager, "good");
        manager.markHealthy(manager.beginSession());
        try { stage(manager, "invalid-site"); fail("invalid selector accepted"); }
        catch (org.jsoup.select.Selector.SelectorParseException expected) { }
        assertEquals(101, manager.beginSession().revision);
        assertEquals(0, manager.status().getLong("pending"));
    }

    @Test public void siteRulesActuallyChangeParsingAndCoverAddresses() throws Exception {
        JSONObject patch = new JSONObject("{\"origin\":\"https://new.example.com\",\"routes\":{\"episode\":\"/drama/{id}/part/{episode}\"},"
                + "\"selectors\":{\"cards\":\".custom\",\"title\":\".name\",\"cover\":\"img\"},"
                + "\"attributes\":{\"id\":\"data-id\"},\"fields\":{\"content\":{\"videoSrc\":[\"stream.url\"]}},"
                + "\"cover\":{\"allowedHosts\":[\"img.new.example.com\"],\"hostRewrites\":{\"old.example.com\":\"img.new.example.com\"},\"mode\":\"plain\"}}");
        SiteProfile profile = new SiteProfile(context, patch);
        assertEquals("/drama/117/part/2", profile.route("episode", "id", "117", "episode", "2"));
        assertEquals("https://img.new.example.com/a.png", profile.coverUrl("https://old.example.com/a.png"));
        assertEquals("https://cdn.example.com/a.m3u8", profile.mapJson(new JSONObject("{\"stream\":{\"url\":\"https://cdn.example.com/a.m3u8\"}}"), "content").getString("videoSrc"));
        SiteRepository repository = new SiteRepository(context, profile);
        Method cards = SiteRepository.class.getDeclaredMethod("cards", Element.class, String.class); cards.setAccessible(true);
        JSONArray parsed = (JSONArray) cards.invoke(repository, Jsoup.parse("<article class='custom' data-id='117'><span class='name'>Fixture title</span><img data-src='https://img.new.example.com/a.png'></article>"), "");
        assertEquals(1, parsed.length()); assertEquals("Fixture title", parsed.getJSONObject(0).getString("title"));
        assertEquals("117", parsed.getJSONObject(0).getString("id"));
    }

    @Test public void webResourcesStayInsideTrustedOrigin() throws Exception {
        HotUpdateManager manager = manager(); HotUpdateManager.Session session = manager.beginSession();
        assertEquals(404, manager.resource(session, "../private.pem").getStatusCode());
        assertEquals(404, manager.resource(session, "%2e%2e/private.pem").getStatusCode());
        assertTrue(HotUpdateManager.trusted(Uri.parse(HotUpdateManager.UI_URL)));
        assertFalse(HotUpdateManager.trusted(Uri.parse("https://evil.example/assets/app.html")));
        assertFalse(HotUpdateManager.trusted(Uri.parse("https://appassets.androidplatform.net:444/assets/app.html")));
        assertTrue(manager.resource(session, "app.html").getResponseHeaders().get("Content-Security-Policy").contains("frame-src 'none'"));
    }

    @Test public void realWebViewConfirmsThemeAndRecoversBrokenUi() throws Exception {
        HotUpdateManager manager = HotUpdateManager.get(context); stage(manager, "good");
        // Test package has its own preferences and no user account or viewing data.
        context.getSharedPreferences("hot-updates", 0).edit().putBoolean("originMigrated", true).putBoolean("adultConfirmed", false).commit();
        AppStore store = new AppStore(context);
        store.recordProgress(new JSONObject().put("id", "334455").put("episode", 1).put("position", 10_000).put("duration", 40_000));
        MainActivity activity = (MainActivity) instrumentation.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            await(() -> ui(() -> (boolean) field(activity, "resourceReady")), "good hot UI never confirmed boot");
            WebView web = (WebView) ui(() -> field(activity, "webView"));
            assertEquals("\"#e57373\"", javascript(web, "getComputedStyle(document.getElementById('app')).getPropertyValue('--accent').trim()"));
            assertEquals(101, manager.status().getLong("revision"));
            assertFalse(((HotUpdateManager.Session) ui(() -> field(activity, "hotSession"))).trial && manager.status().getLong("blocked") == 101);
        } finally { ui(() -> { activity.finish(); return null; }); instrumentation.waitForIdleSync(); }
        stage(manager, "broken-ui");
        MainActivity broken = (MainActivity) instrumentation.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            await(() -> manager.status().getLong("blocked") == 106 && ui(() -> (boolean) field(broken, "resourceReady")), "broken UI did not recover");
            assertEquals(101, manager.status().getLong("revision"));
            assertEquals(10_000, store.getProgress("334455", 1).getLong("position"));
        } finally { ui(() -> { broken.finish(); return null; }); instrumentation.waitForIdleSync(); }
    }
}
