package com.huangguo.mobile;

import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.LoadControl;

/** A standby player becomes a normal player without rebuilding its decoder or buffers. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class EpisodeLoadControl extends DefaultLoadControl {
    static final long STANDBY_BUFFER_US = 5_000_000L;
    static final int STANDBY_BYTES = 4 * 1024 * 1024;
    private volatile boolean standby;

    EpisodeLoadControl(boolean standby) { this.standby = standby; }
    void promote() { standby = false; }

    @Override public boolean shouldContinueLoading(LoadControl.Parameters parameters) {
        if (standby && (parameters.bufferedDurationUs >= STANDBY_BUFFER_US
                || getAllocator(parameters.playerId).getTotalBytesAllocated() >= STANDBY_BYTES)) return false;
        return super.shouldContinueLoading(parameters);
    }

    @Override public boolean shouldStartPlayback(LoadControl.Parameters parameters) {
        if (standby && parameters.bufferedDurationUs >= 500_000) return true;
        return super.shouldStartPlayback(parameters);
    }
}
