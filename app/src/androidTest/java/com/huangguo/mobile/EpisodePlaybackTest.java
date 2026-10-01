package com.huangguo.mobile;

import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import android.app.Instrumentation;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.common.Player;
import androidx.media3.ui.PlayerView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Callable;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class EpisodePlaybackTest {
    private PlayerActivity activity;
    private Instrumentation getInstrumentation() { return InstrumentationRegistry.getInstrumentation(); }

    @Before public void setUp() throws Exception {
        File dir = new File(getInstrumentation().getTargetContext().getCacheDir(), "playback-fixture");
        dir.mkdirs();
        for (String name : getInstrumentation().getContext().getAssets().list("playback")) {
            try (InputStream input = getInstrumentation().getContext().getAssets().open("playback/" + name);
                    FileOutputStream output = new FileOutputStream(new File(dir, name))) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            }
        }
        JSONArray episodes = new JSONArray();
        for (int i = 1; i <= 4; i++) episodes.put(new JSONObject().put("number", i));
        JSONObject payload = new JSONObject().put("item", new JSONObject().put("id", "998877")
                .put("title", "Playback test")).put("episodes", episodes).put("episode", 1);
        Intent intent = new Intent(getInstrumentation().getTargetContext(), PlayerActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("payload", payload.toString())
                .putExtra("qa-source", Uri.fromFile(new File(dir, "test.m3u8")).toString());
        activity = (PlayerActivity) getInstrumentation().startActivitySync(intent);
        waitFor(() -> (boolean) field("activeFirstFrame"), "current first frame");
    }

    @After public void tearDown() throws Exception {
        if (activity != null) ui(() -> { activity.finish(); return null; });
        getInstrumentation().waitForIdleSync();
    }

    private Object field(String name) throws Exception {
        Field field = PlayerActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(activity);
    }

    private Object call(String name, Class<?>[] types, Object... args) throws Exception {
        Method method = PlayerActivity.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(activity, args);
    }

    private <T> T ui(Callable<T> action) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        getInstrumentation().runOnMainSync(() -> {
            try { result.set(action.call()); } catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() != null) throw new Exception(failure.get());
        return result.get();
    }

    private void waitFor(Callable<Boolean> condition, String description) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 15_000;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (ui(condition)) return;
            SystemClock.sleep(50);
        }
        fail("Timed out: " + description);
    }

    private ExoPlayer player() throws Exception { return (ExoPlayer) field("player"); }
    private void switchBy(int direction) throws Exception {
        Object selection = call("adjacent", new Class<?>[]{int.class}, direction);
        assertNotNull(selection);
        call("switchTo", new Class<?>[]{selection.getClass()}, selection);
    }

    @Test public void testPreparedPlayerReuseAndPreviousResume() throws Exception {
        ui(() -> { player().seekTo(8_000); return null; });
        waitFor(() -> player().getPlaybackState() == Player.STATE_READY
                && player().getCurrentPosition() >= 8_000, "seek before leaving");
        waitFor(() -> (boolean) field("standbyFirstFrame"), "prepared next frame");
        ExoPlayer prepared = ui(() -> (ExoPlayer) field("standbyPlayer"));
        ui(() -> {
            assertFalse(prepared.getPlayWhenReady());
            assertEquals(0f, prepared.getVolume(), 0f);
            assertTrue(prepared.getBufferedPosition() - prepared.getCurrentPosition() <= 8_000);
            return null;
        });
        ui(() -> { switchBy(1); return null; });
        ui(() -> {
            assertSame(prepared, player());
            assertSame(prepared, ((PlayerView) field("activeView")).getPlayer());
            assertTrue((boolean) field("activeFirstFrame"));
            assertTrue(player().getVolume() > 0f);
            return null;
        });
        waitFor(() -> player().getBufferedPosition() - player().getCurrentPosition() > 10_000,
                "promoted player continues normal buffering");
        ui(() -> { switchBy(-1); return null; });
        waitFor(() -> (boolean) field("activeFirstFrame") && player().getPlaybackState() == Player.STATE_READY,
                "return to previous episode");
        ui(() -> { assertEquals(1, field("currentEpisode"));
            assertTrue(player().getCurrentPosition() >= 8_000); return null; });
    }

    @Test public void testRapidSwitchKeepsOnlyLatestSelection() throws Exception {
        ui(() -> { switchBy(1); switchBy(1); switchBy(1); return null; });
        waitFor(() -> (boolean) field("activeFirstFrame") && player().getPlaybackState() == Player.STATE_READY,
                "latest episode starts");
        ui(() -> {
            assertEquals(4, field("currentEpisode"));
            assertNull(field("standbyPlayer"));
            assertTrue(((String) field("currentSource")).endsWith("?episode=4"));
            assertEquals("998877:4", player().getCurrentMediaItem().mediaId);
            return null;
        });
    }

    @Test public void testBufferingAndSeekingReleaseStandby() throws Exception {
        waitFor(() -> (boolean) field("standbyFirstFrame"), "prepared next frame");
        ui(() -> {
            call("startSeekPreview", new Class<?>[]{android.widget.SeekBar.class}, field("seekBar"));
            assertNull(field("standbyPlayer"));
            call("stopSeekPreview", new Class<?>[]{});
            player().seekTo(20_000);
            assertEquals(Player.STATE_BUFFERING, player().getPlaybackState());
            assertNull(field("standbyPlayer"));
            return null;
        });
        waitFor(() -> (boolean) field("standbyFirstFrame"), "preparation resumes after buffering");
    }

    @Test public void testUnfinishedEpisodeNearEndStillResumes() throws Exception {
        ui(() -> {
            AppStore store = (AppStore) field("store");
            store.recordProgress(new JSONObject().put("id", "112233").put("episode", 1)
                    .put("position", 39_000).put("duration", 40_000).put("completed", false));
            assertEquals(39_000L, call("resumePosition", new Class<?>[]{String.class, int.class}, "112233", 1));
            store.recordProgress(new JSONObject().put("id", "112233").put("episode", 1)
                    .put("position", 40_000).put("duration", 40_000).put("completed", true));
            assertEquals(0L, call("resumePosition", new Class<?>[]{String.class, int.class}, "112233", 1));
            return null;
        });
    }

    @Test public void testSegmentsCachedButPlaylistRefreshed() throws Exception {
        Method get = VideoPlaybackCache.class.getDeclaredMethod("get", android.content.Context.class);
        get.setAccessible(true);
        androidx.media3.datasource.cache.SimpleCache cache =
                (androidx.media3.datasource.cache.SimpleCache) get.invoke(null, activity);
        assertTrue(cache.getCacheSpace() > 0);
        assertTrue(cache.getCacheSpace() <= VideoPlaybackCache.MAX_BYTES);
        assertTrue(cache.getKeys().stream().anyMatch(key -> key.endsWith(".ts")));
        assertFalse(cache.getKeys().stream().anyMatch(key -> key.contains(".m3u8")));
    }

    @Test public void testDestroyReleasesBackgroundWorkAndPlayers() throws Exception {
        waitFor(() -> (boolean) field("standbyFirstFrame"), "prepared next frame");
        ui(() -> { activity.finish(); return null; });
        waitFor(() -> (boolean) field("destroyed"), "activity destroyed");
        ui(() -> {
            assertNull(field("standbyPlayer"));
            assertNull(field("previewPlayer"));
            assertTrue(((java.util.concurrent.ExecutorService) field("prefetchWorker")).isShutdown());
            assertTrue(((java.util.concurrent.ExecutorService) field("worker")).isShutdown());
            return null;
        });
        activity = null;
    }
}
