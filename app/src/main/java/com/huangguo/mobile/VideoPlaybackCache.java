package com.huangguo.mobile;

import android.content.Context;
import androidx.media3.common.C;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import java.io.File;

/** Shared by playback, the next episode and seek previews; never caches mutable playlists. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class VideoPlaybackCache {
    static final long MAX_BYTES = 128L * 1024 * 1024;
    private static SimpleCache cache;

    private static synchronized SimpleCache get(Context context) {
        if (cache == null) {
            Context app = context.getApplicationContext();
            cache = new SimpleCache(new File(app.getCacheDir(), "video-segments"),
                    new LeastRecentlyUsedCacheEvictor(MAX_BYTES), new StandaloneDatabaseProvider(app));
        }
        return cache;
    }

    static HlsMediaSource.Factory sources(Context context) {
        DataSource.Factory upstream = new DefaultDataSource.Factory(context,
                new DefaultHttpDataSource.Factory().setConnectTimeoutMs(20_000)
                        .setReadTimeoutMs(25_000).setUserAgent("Mozilla/5.0 (Linux; Android) HuangGuo/0.1"));
        CacheDataSource.Factory cached = new CacheDataSource.Factory().setCache(get(context))
                .setUpstreamDataSourceFactory(upstream).setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
        return new HlsMediaSource.Factory(type -> type == C.DATA_TYPE_MANIFEST
                ? upstream.createDataSource() : cached.createDataSource());
    }
}
