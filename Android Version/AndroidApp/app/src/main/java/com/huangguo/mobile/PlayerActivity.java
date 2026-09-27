package com.huangguo.mobile;

import android.app.Activity;
import android.animation.ValueAnimator;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.datasource.DefaultHttpDataSource;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PlayerActivity extends Activity {
    private static final int YELLOW = Color.rgb(255, 212, 91);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newFixedThreadPool(3);
    private final Map<String, JSONObject> episodeCache = new HashMap<>();
    private final Map<String, Bitmap> posterCache = new HashMap<>();
    private SiteRepository repository;
    private AppStore store;
    private ExoPlayer player;
    private ExoPlayer previewPlayer;
    private FrameLayout root;
    private FrameLayout mediaLayer;
    private ShimmerView skeleton;
    private ImageView poster;
    private ImageView incoming;
    private ProgressBar loading;
    private TextView title;
    private TextView episodeText;
    private TextView timeText;
    private TextView message;
    private ImageButton playButton;
    private ImageButton centerToggle;
    private SeekBar seekBar;
    private SeekBar landscapeSeek;
    private SeekBar volumeSeek;
    private LinearLayout previewBubble;
    private PlayerView previewView;
    private ProgressBar previewLoading;
    private TextView previewTime;
    private TextView previewHint;
    private ImageButton volumeButton;
    private LinearLayout controls;
    private FrameLayout miniProgress;
    private View miniProgressFill;
    private float miniProgressFraction;
    private boolean controlsShown = true;
    private int controlTransitionToken;
    private LinearLayout episodePanel;
    private TextView episodeTrigger;
    private TextView speedTrigger;
    private View speedScrim;
    private LinearLayout speedPanel;
    private JSONArray queue;
    private JSONArray episodes;
    private JSONObject currentItem;
    private String group;
    private String currentId;
    private int currentEpisode;
    private int queueIndex;
    private int loadToken;
    private long lastSaved;
    private boolean seeking;
    private String currentSource;
    private long previewTarget;
    private long previewBucket = -1;
    private long lastPreviewRequest;
    private boolean previewSeekScheduled;
    private final Runnable previewSeek = () -> {
        previewSeekScheduled = false;
        if (!seeking || currentSource == null || player == null || player.getDuration() <= 0) return;
        long bucket = Math.min(player.getDuration(), (previewTarget / 5000) * 5000);
        if (bucket == previewBucket) return;
        previewBucket = bucket;
        lastPreviewRequest = SystemClock.uptimeMillis();
        if (previewPlayer == null) ensurePreviewPlayer();
        else previewPlayer.seekTo(bucket);
    };
    private static final int GESTURE_PENDING = 0;
    private static final int GESTURE_VERTICAL = 1;
    private static final int GESTURE_HORIZONTAL = 2;
    private static final int GESTURE_HOLD = 3;
    private static final float[] SPEEDS = {0.5f, 0.75f, 1f, 1.25f, 2f, 3f};
    private float manualSpeed = 1f;
    private boolean speedLocked;
    private boolean speedHoldActive;
    private boolean holdToggled;
    private int gestureMode = GESTURE_PENDING;
    private float touchStartX;
    private float touchStartY;
    private float dragY;
    private boolean dragging;
    private boolean doubleTapCandidate;
    private long gestureSeekStart;
    private long gestureSeekTarget;
    private long lastTapAt;
    private float lastTapX;
    private float lastTapY;
    private final Runnable singleTap = () -> {
        if (controlsShown) concealControls();
        else revealControls();
    };
    private final Runnable longPress = () -> {
        if (!dragging || gestureMode != GESTURE_PENDING) return;
        gestureMode = GESTURE_HOLD;
        lastTapAt = 0;
        holdToggled = false;
        if (player == null || !player.isPlaying()) return;
        if (!speedLocked) {
            speedHoldActive = true;
            applyPlaybackSpeed(2f);
        }
        revealControls();
    };
    private boolean episodesOpen;
    private int sourceRetryCount;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (player != null && player.getDuration() > 0) {
                long position = player.getCurrentPosition();
                long duration = player.getDuration();
                if (!seeking) seekBar.setProgress((int) Math.min(1000, position * 1000 / duration));
                if (!seeking && landscapeSeek != null) landscapeSeek.setProgress((int) Math.min(1000, position * 1000 / duration));
                updateMiniProgress(position, duration);
                timeText.setText(clock(position) + " / " + clock(duration));
                if (System.currentTimeMillis() - lastSaved > 5000) saveProgress(false);
            }
            handler.postDelayed(this, 500);
        }
    };

    private static final class Selection {
        final JSONObject item;
        final int index;
        final int episode;
        Selection(JSONObject item, int index, int episode) {
            this.item = item; this.index = index; this.episode = episode;
        }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        repository = new SiteRepository(this);
        store = new AppStore(this);
        try {
            JSONObject payload = new JSONObject(getIntent().getStringExtra("payload"));
            currentItem = payload.optJSONObject("item");
            if (currentItem == null) throw new IllegalArgumentException("没有影片信息");
            group = payload.optString("group");
            queue = payload.optJSONArray("queue");
            if (queue == null) queue = new JSONArray();
            episodes = payload.optJSONArray("episodes");
            if (episodes == null && payload.optJSONObject("detail") != null) {
                episodes = payload.optJSONObject("detail").optJSONArray("episodes");
            }
            if (episodes == null) episodes = new JSONArray();
            queueIndex = payload.optInt("index", 0);
            currentId = currentItem.optString("id");
            currentEpisode = payload.optInt("episode", 1);
        } catch (Exception error) { finish(); return; }

        buildUi();
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(20_000).setReadTimeoutMs(25_000)
                .setUserAgent("Mozilla/5.0 (Linux; Android) HuangGuo/0.1");
        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(this).setDataSourceFactory(http)).build();
        ((PlayerView) mediaLayer.getChildAt(0)).setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    loading.setVisibility(View.GONE);
                    message.setVisibility(View.GONE);
                } else if (state == Player.STATE_BUFFERING) loading.setVisibility(View.VISIBLE);
                else if (state == Player.STATE_ENDED) {
                    saveProgress(true);
                    if (!isAiQueue()) {
                        Selection next = adjacent(1);
                        if (next != null) switchTo(next);
                    }
                }
            }
            @Override public void onRenderedFirstFrame() {
                poster.setVisibility(View.GONE);
                skeleton.setVisibility(View.GONE);
                loading.setVisibility(View.GONE);
                sourceRetryCount = 0;
            }
            @Override public void onIsPlayingChanged(boolean playing) {
                playButton.setImageResource(playing ? R.drawable.player_pause : R.drawable.player_play);
                centerToggle.setImageResource(playing ? R.drawable.player_pause : R.drawable.player_play);
                revealControls();
            }
            @Override public void onVideoSizeChanged(androidx.media3.common.VideoSize size) {
                ((PlayerView) mediaLayer.getChildAt(0)).setResizeMode(
                        size.width > 0 && size.height > 0 && (double) size.width / size.height < 0.9
                                ? AspectRatioFrameLayout.RESIZE_MODE_ZOOM : AspectRatioFrameLayout.RESIZE_MODE_FIT);
            }
            @Override public void onPlayerError(PlaybackException error) {
                if (hasTimeout(error) && sourceRetryCount < 2) {
                    int attempt = ++sourceRetryCount;
                    long position = player.getCurrentPosition();
                    loading.setVisibility(View.VISIBLE);
                    message.setVisibility(View.GONE);
                    Log.w("HuangGuo", "Transient HLS timeout, retry " + attempt);
                    handler.postDelayed(() -> {
                        if (player == null || isFinishing()) return;
                        player.prepare();
                        player.seekTo(position);
                        player.play();
                    }, 700L * attempt);
                    return;
                }
                loading.setVisibility(View.GONE);
                message.setText("播放失败，点击重试");
                message.setVisibility(View.VISIBLE);
                message.setOnClickListener(view -> loadSelection(false));
                Log.e("HuangGuo", "Player error", error);
            }
        });
        loadSelection(true);
        handler.post(tick);
    }

    private int dp(float value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }

    private GradientDrawable background(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private TextView label(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private ImageButton icon(int resource, String description) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(resource);
        button.setColorFilter(Color.WHITE);
        button.setContentDescription(description);
        button.setBackground(background(0x7A000000, 18));
        button.setPadding(dp(8), dp(8), dp(8), dp(8));
        return button;
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);
        mediaLayer = new FrameLayout(this);
        PlayerView view = new PlayerView(this);
        view.setUseController(false);
        mediaLayer.addView(view, new FrameLayout.LayoutParams(-1, -1));
        skeleton = new ShimmerView(this);
        mediaLayer.addView(skeleton, new FrameLayout.LayoutParams(-1, -1));
        poster = new ImageView(this);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setVisibility(View.INVISIBLE);
        mediaLayer.addView(poster, new FrameLayout.LayoutParams(-1, -1));
        root.addView(mediaLayer, new FrameLayout.LayoutParams(-1, -1));
        incoming = new ImageView(this);
        incoming.setScaleType(ImageView.ScaleType.CENTER_CROP);
        incoming.setBackgroundColor(0xFF242321);
        incoming.setVisibility(View.GONE);
        root.addView(incoming, new FrameLayout.LayoutParams(-1, -1));

        View gesture = new View(this);
        root.addView(gesture, new FrameLayout.LayoutParams(-1, -1));
        gesture.setOnTouchListener((target, event) -> handleGesture(event));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(4), dp(12), dp(16), dp(10));
        ImageButton back = icon(R.drawable.player_back, "返回详情");
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(dp(38), dp(38)));
        title = label("", 16, Color.WHITE);
        title.setTypeface(null, Typeface.BOLD);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, dp(42), 1);
        titleParams.leftMargin = dp(12);
        top.addView(title, titleParams);
        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(-1, dp(70), Gravity.TOP);
        topParams.topMargin = dp(24);
        root.addView(top, topParams);

        loading = new ProgressBar(this);
        loading.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(YELLOW));
        root.addView(loading, new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER));
        centerToggle = icon(R.drawable.player_play, "播放或暂停");
        GradientDrawable centerBackground = background(0xA8101114, 34);
        centerBackground.setStroke(dp(1), 0x99FFFFFF);
        centerToggle.setBackground(centerBackground);
        centerToggle.setElevation(dp(10));
        centerToggle.setVisibility(View.GONE);
        centerToggle.setOnClickListener(buttonView -> {
            if (player != null) player.setPlayWhenReady(!player.getPlayWhenReady());
            revealControls();
        });
        root.addView(centerToggle, new FrameLayout.LayoutParams(dp(62), dp(62), Gravity.CENTER));
        message = label("", 14, Color.WHITE);
        message.setGravity(Gravity.CENTER);
        message.setBackground(background(0xCC16191B, 10));
        message.setPadding(dp(16), dp(10), dp(16), dp(10));
        message.setVisibility(View.GONE);
        root.addView(message, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setGravity(Gravity.CENTER_HORIZONTAL);
        controls.setPadding(dp(15), dp(38), dp(15), dp(16));
        controls.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xDD000000, 0x00000000}));
        episodeText = label("", 13, 0xFFFFD45B);
        episodeText.setVisibility(View.GONE);
        LinearLayout timeline = new LinearLayout(this);
        timeline.setGravity(Gravity.CENTER_VERTICAL);
        seekBar = new SeekBar(this);
        seekBar.setMax(1000);
        seekBar.setProgressTintList(android.content.res.ColorStateList.valueOf(YELLOW));
        seekBar.setThumbTintList(android.content.res.ColorStateList.valueOf(YELLOW));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar bar) { startSeekPreview(bar); }
            @Override public void onStopTrackingTouch(SeekBar bar) { finishSeekPreview(bar); }
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser && seeking) updateSeekPreview(bar, progress);
            }
        });
        timeline.addView(seekBar, new LinearLayout.LayoutParams(-1, dp(34)));
        timeText = label("0:00 / 0:00", 11, Color.WHITE);
        controls.addView(timeline, new LinearLayout.LayoutParams(-1, dp(37)));
        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        buttons.setPadding(dp(7), 0, dp(7), 0);
        GradientDrawable remote = background(0xF2292720, 12);
        remote.setStroke(dp(1), 0xFF796D53);
        buttons.setBackground(remote);
        buttons.setElevation(dp(12));
        playButton = icon(R.drawable.player_play, "播放或暂停");
        playButton.setBackgroundColor(Color.TRANSPARENT);
        playButton.setOnClickListener(v -> { if (player != null) player.setPlayWhenReady(!player.getPlayWhenReady()); revealControls(); });
        buttons.addView(playButton, new LinearLayout.LayoutParams(dp(38), dp(38)));
        ImageButton next = icon(R.drawable.player_next, "下一集");
        next.setBackgroundColor(Color.TRANSPARENT);
        next.setOnClickListener(v -> { Selection item = adjacent(1); if (item != null) animateSwitch(item, 1); else toast("已经是最后一集"); });
        LinearLayout.LayoutParams nextParams = new LinearLayout.LayoutParams(dp(38), dp(38));
        nextParams.leftMargin = dp(3);
        buttons.addView(next, nextParams);
        LinearLayout.LayoutParams timeParams = new LinearLayout.LayoutParams(dp(77), dp(38));
        timeParams.leftMargin = dp(4);
        timeText.setTextSize(10);
        timeText.setGravity(Gravity.CENTER);
        buttons.addView(timeText, timeParams);
        landscapeSeek = new SeekBar(this);
        landscapeSeek.setMax(1000);
        landscapeSeek.setProgressTintList(android.content.res.ColorStateList.valueOf(YELLOW));
        landscapeSeek.setThumbTintList(android.content.res.ColorStateList.valueOf(YELLOW));
        landscapeSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar bar) { startSeekPreview(bar); }
            @Override public void onStopTrackingTouch(SeekBar bar) { finishSeekPreview(bar); }
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser && seeking) updateSeekPreview(bar, progress);
            }
        });
        buttons.addView(landscapeSeek, new LinearLayout.LayoutParams(dp(90), dp(38)));
        volumeButton = icon(R.drawable.player_volume, "静音");
        volumeButton.setBackgroundColor(Color.TRANSPARENT);
        volumeButton.setOnClickListener(v -> {
            if (player == null) return;
            boolean mute = player.getVolume() > 0;
            player.setVolume(mute ? 0 : Math.max(.2f, volumeSeek.getProgress() / 100f));
            volumeButton.setImageResource(mute ? R.drawable.player_mute : R.drawable.player_volume);
        });
        buttons.addView(volumeButton, new LinearLayout.LayoutParams(dp(34), dp(38)));
        volumeSeek = new SeekBar(this);
        volumeSeek.setMax(100);
        volumeSeek.setProgress(100);
        volumeSeek.setProgressTintList(android.content.res.ColorStateList.valueOf(YELLOW));
        volumeSeek.setThumbTintList(android.content.res.ColorStateList.valueOf(YELLOW));
        volumeSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser && player != null) {
                    player.setVolume(progress / 100f);
                    volumeButton.setImageResource(progress == 0 ? R.drawable.player_mute : R.drawable.player_volume);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { revealControls(); }
            @Override public void onStopTrackingTouch(SeekBar bar) { revealControls(); }
        });
        buttons.addView(volumeSeek, new LinearLayout.LayoutParams(dp(68), dp(38)));
        ImageButton fullscreen = icon(R.drawable.player_fullscreen, "全屏");
        fullscreen.setBackgroundColor(Color.TRANSPARENT);
        fullscreen.setOnClickListener(v -> {
            boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
            setRequestedOrientation(landscape ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            fullscreen.setImageResource(landscape ? R.drawable.player_fullscreen : R.drawable.player_shrink);
        });
        buttons.addView(fullscreen, new LinearLayout.LayoutParams(dp(36), dp(38)));
        speedTrigger = label("1×", 12, 0xFFF5D782);
        speedTrigger.setGravity(Gravity.CENTER);
        speedTrigger.setBackgroundColor(Color.TRANSPARENT);
        speedTrigger.setContentDescription("播放倍速");
        speedTrigger.setOnClickListener(v -> showSpeedPanel());
        buttons.addView(speedTrigger, new LinearLayout.LayoutParams(dp(46), dp(38)));
        episodeTrigger = label("选集", 13, 0xFFF5D782);
        episodeTrigger.setGravity(Gravity.CENTER);
        episodeTrigger.setBackgroundColor(Color.TRANSPARENT);
        episodeTrigger.setOnClickListener(v -> showEpisodes());
        buttons.addView(episodeTrigger, new LinearLayout.LayoutParams(dp(49), dp(38)));
        controls.addView(buttons, new LinearLayout.LayoutParams(-2, dp(52)));
        root.addView(controls, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        buildSeekPreview();
        miniProgress = new FrameLayout(this);
        miniProgress.setVisibility(View.GONE);
        miniProgressFill = new View(this);
        miniProgressFill.setBackgroundColor(YELLOW);
        miniProgress.addView(miniProgressFill, new FrameLayout.LayoutParams(0, -1, Gravity.LEFT));
        FrameLayout.LayoutParams miniParams = new FrameLayout.LayoutParams(-1, dp(3), Gravity.BOTTOM);
        miniParams.leftMargin = dp(5);
        miniParams.rightMargin = dp(5);
        miniParams.bottomMargin = dp(14);
        root.addView(miniProgress, miniParams);
        updateControlLayout();
    }

    private static String speedLabel(float speed) {
        return (speed == (int) speed ? String.valueOf((int) speed) : String.valueOf(speed)) + "×";
    }

    private void applyPlaybackSpeed(float speed) {
        if (player != null) player.setPlaybackSpeed(speed);
        if (speedTrigger != null) speedTrigger.setText(speedLocked ? "2×锁" : speedLabel(speed));
    }

    private void closeSpeedPanel() {
        if (speedPanel == null) return;
        root.removeView(speedPanel);
        root.removeView(speedScrim);
        speedPanel = null;
        speedScrim = null;
        revealControls();
    }

    private void showSpeedPanel() {
        if (speedPanel != null) { closeSpeedPanel(); return; }
        revealControls();
        handler.removeCallbacks(hideControls);
        speedScrim = new View(this);
        speedScrim.setOnClickListener(v -> closeSpeedPanel());
        root.addView(speedScrim, new FrameLayout.LayoutParams(-1, -1));
        speedPanel = new LinearLayout(this);
        speedPanel.setOrientation(LinearLayout.VERTICAL);
        speedPanel.setPadding(dp(10), dp(9), dp(10), dp(10));
        GradientDrawable background = background(0xF326292A, 13);
        background.setStroke(dp(1), 0xFF796D53);
        speedPanel.setBackground(background);
        speedPanel.setElevation(dp(18));
        TextView heading = label("播放速度", 13, Color.WHITE);
        heading.setGravity(Gravity.CENTER);
        speedPanel.addView(heading, new LinearLayout.LayoutParams(-1, dp(28)));
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(3);
        for (float speed : SPEEDS) {
            TextView option = label(speedLabel(speed), 13, speed == manualSpeed ? Color.BLACK : Color.WHITE);
            option.setGravity(Gravity.CENTER);
            option.setBackground(background(speed == manualSpeed ? YELLOW : 0xFF3B4042, 8));
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = dp(67);
            params.height = dp(39);
            params.setMargins(dp(2), dp(2), dp(2), dp(2));
            grid.addView(option, params);
            option.setOnClickListener(v -> {
                manualSpeed = speed;
                speedLocked = false;
                speedHoldActive = false;
                applyPlaybackSpeed(speed);
                closeSpeedPanel();
            });
        }
        speedPanel.addView(grid);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        params.bottomMargin = dp(90);
        root.addView(speedPanel, params);
    }

    private void buildSeekPreview() {
        previewBubble = new LinearLayout(this);
        previewBubble.setOrientation(LinearLayout.VERTICAL);
        previewBubble.setPadding(dp(5), dp(5), dp(5), dp(3));
        GradientDrawable bubble = background(0xF2222528, 11);
        bubble.setStroke(dp(1), 0xFF8D7A4E);
        previewBubble.setBackground(bubble);
        previewBubble.setElevation(dp(16));
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(0xFF151719);
        previewView = new PlayerView(this);
        previewView.setUseController(false);
        previewView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        frame.addView(previewView, new FrameLayout.LayoutParams(-1, -1));
        previewHint = label("暂无法预览", 11, 0xFFD4D0C6);
        previewHint.setGravity(Gravity.CENTER);
        previewHint.setVisibility(View.GONE);
        frame.addView(previewHint, new FrameLayout.LayoutParams(-1, -1));
        previewLoading = new ProgressBar(this);
        previewLoading.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(YELLOW));
        frame.addView(previewLoading, new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER));
        previewBubble.addView(frame, new LinearLayout.LayoutParams(-1, 0, 1));
        previewTime = label("0:00", 12, Color.WHITE);
        previewTime.setGravity(Gravity.CENTER);
        previewBubble.addView(previewTime, new LinearLayout.LayoutParams(-1, dp(22)));
        previewBubble.setVisibility(View.GONE);
        root.addView(previewBubble, new FrameLayout.LayoutParams(dp(136), dp(105), Gravity.TOP | Gravity.LEFT));
    }

    private void startSeekPreview(SeekBar bar) {
        seeking = true;
        revealControls();
        handler.removeCallbacks(hideControls);
        previewHint.setVisibility(View.GONE);
        previewLoading.setVisibility(View.VISIBLE);
        updateSeekPreview(bar, bar.getProgress());
    }

    private void updateSeekPreview(SeekBar bar, int progress) {
        if (player == null || player.getDuration() <= 0) return;
        previewTarget = player.getDuration() * progress / 1000;
        previewTime.setText(clock(previewTarget));
        int landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE ? 1 : 0;
        int width = dp(landscape == 1 ? 160 : 136);
        int height = dp(landscape == 1 ? 112 : 105);
        int[] barLocation = new int[2];
        int[] rootLocation = new int[2];
        bar.getLocationOnScreen(barLocation);
        root.getLocationOnScreen(rootLocation);
        int usable = Math.max(1, bar.getWidth() - bar.getPaddingLeft() - bar.getPaddingRight());
        int thumbX = barLocation[0] - rootLocation[0] + bar.getPaddingLeft() + usable * progress / 1000;
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) previewBubble.getLayoutParams();
        params.width = width;
        params.height = height;
        params.leftMargin = Math.max(dp(8), Math.min(root.getWidth() - width - dp(8), thumbX - width / 2));
        params.topMargin = Math.max(dp(72), barLocation[1] - rootLocation[1] - height - dp(12));
        previewBubble.setLayoutParams(params);
        previewBubble.setVisibility(View.VISIBLE);
        if (currentSource == null || previewSeekScheduled) return;
        long elapsed = SystemClock.uptimeMillis() - lastPreviewRequest;
        long delay = lastPreviewRequest == 0 ? 120 : Math.max(0, 350 - elapsed);
        previewSeekScheduled = true;
        handler.postDelayed(previewSeek, delay);
    }

    private void ensurePreviewPlayer() {
        if (previewPlayer != null) return;
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(20_000).setReadTimeoutMs(25_000)
                .setUserAgent("Mozilla/5.0 (Linux; Android) HuangGuo/0.1");
        previewPlayer = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(this).setDataSourceFactory(http)).build();
        previewPlayer.setTrackSelectionParameters(previewPlayer.getTrackSelectionParameters().buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build());
        previewPlayer.setSeekParameters(SeekParameters.CLOSEST_SYNC);
        previewPlayer.setVolume(0f);
        previewView.setPlayer(previewPlayer);
        previewPlayer.addListener(new Player.Listener() {
            @Override public void onRenderedFirstFrame() {
                previewLoading.setVisibility(View.GONE);
                previewHint.setVisibility(View.GONE);
            }
            @Override public void onPlayerError(PlaybackException error) {
                previewLoading.setVisibility(View.GONE);
                previewHint.setText("暂无法预览");
                previewHint.setVisibility(View.VISIBLE);
                Log.w("HuangGuo", "Seek preview unavailable", error);
            }
        });
        previewPlayer.setMediaItem(new MediaItem.Builder().setUri(currentSource)
                .setMimeType(MimeTypes.APPLICATION_M3U8).build());
        previewPlayer.pause();
        previewPlayer.seekTo(previewBucket);
        previewPlayer.prepare();
    }

    private void finishSeekPreview(SeekBar bar) {
        stopSeekPreview();
        if (player != null && player.getDuration() > 0) player.seekTo(player.getDuration() * bar.getProgress() / 1000);
        saveProgress(false);
        revealControls();
    }

    private void stopSeekPreview() {
        seeking = false;
        handler.removeCallbacks(previewSeek);
        previewSeekScheduled = false;
        previewBubble.setVisibility(View.GONE);
        previewView.setPlayer(null);
        if (previewPlayer != null) { previewPlayer.release(); previewPlayer = null; }
        previewBucket = -1;
        lastPreviewRequest = 0;
    }

    private static String clock(long millis) {
        long seconds = Math.max(0, millis / 1000);
        return seconds / 60 + ":" + String.format(java.util.Locale.ROOT, "%02d", seconds % 60);
    }

    private static boolean hasTimeout(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.net.SocketTimeoutException) return true;
        }
        return false;
    }

    private boolean isAiQueue() { return "ai-huanlian".equals(group) || "ai-mogai".equals(group); }
    private String cacheKey(String id, int episode) { return id + ":" + episode; }

    private Selection adjacent(int direction) {
        if (isAiQueue()) {
            int index = queueIndex + direction;
            JSONObject item = queue.optJSONObject(index);
            return item == null ? null : new Selection(item, index, 1);
        }
        int currentIndex = -1;
        for (int i = 0; i < episodes.length(); i++) {
            if (episodes.optJSONObject(i).optInt("number") == currentEpisode) { currentIndex = i; break; }
        }
        if (currentIndex < 0) return null;
        JSONObject next = episodes.optJSONObject(currentIndex + direction);
        return next == null ? null : new Selection(currentItem, queueIndex, next.optInt("number"));
    }

    private void loadPoster(JSONObject item, ImageView target) {
        String url = item.optString("cover");
        if (url.isEmpty()) return;
        target.setTag(url);
        Bitmap cached;
        synchronized (posterCache) { cached = posterCache.get(url); }
        if (cached != null) {
            target.setImageBitmap(cached);
            if (target == poster) { target.setVisibility(View.VISIBLE); skeleton.setVisibility(View.GONE); }
            return;
        }
        worker.execute(() -> {
            try {
                String data = repository.coverData(url);
                byte[] bytes = Base64.decode(data.substring(data.indexOf(',') + 1), Base64.DEFAULT);
                Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bitmap == null) return;
                synchronized (posterCache) { posterCache.put(url, bitmap); }
                runOnUiThread(() -> {
                    if (!isFinishing() && url.equals(target.getTag())) {
                        target.setImageBitmap(bitmap);
                        if (target == poster) { target.setVisibility(View.VISIBLE); skeleton.setVisibility(View.GONE); }
                    }
                });
            } catch (Exception ignored) { }
        });
    }

    private void loadSelection(boolean initial) {
        final int token = ++loadToken;
        stopSeekPreview();
        currentSource = null;
        String id = currentItem.optString("id");
        currentId = id;
        title.setText(currentItem.optString("title") + " · 第 " + currentEpisode + " 集");
        episodeText.setText(isAiQueue() ? "AI 作品" : "第 " + currentEpisode + " 集");
        skeleton.setVisibility(View.VISIBLE);
        poster.setVisibility(View.INVISIBLE);
        poster.setImageDrawable(null);
        loading.setVisibility(View.VISIBLE);
        centerToggle.setVisibility(View.GONE);
        message.setVisibility(View.GONE);
        sourceRetryCount = 0;
        loadPoster(currentItem, poster);
        player.stop();
        player.clearMediaItems();
        worker.execute(() -> {
            try {
                JSONObject data;
                synchronized (episodeCache) { data = episodeCache.get(cacheKey(id, currentEpisode)); }
                if (data == null) data = repository.episode(id, currentEpisode);
                JSONObject ready = data;
                runOnUiThread(() -> {
                    if (token != loadToken || isFinishing()) return;
                    currentSource = ready.optString("source");
                    MediaItem item = new MediaItem.Builder().setUri(ready.optString("source"))
                            .setMimeType(MimeTypes.APPLICATION_M3U8).build();
                    player.setMediaItem(item);
                    applyPlaybackSpeed(speedLocked ? 2f : manualSpeed);
                    JSONObject progress = store.getProgress(id, currentEpisode);
                    if (initial && progress != null && !progress.optBoolean("completed")) {
                        long position = progress.optLong("position");
                        long duration = progress.optLong("duration");
                        if (position < duration - 3000) player.seekTo(position);
                    }
                    player.prepare();
                    player.play();
                    prefetchAdjacent();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (token != loadToken) return;
                    loading.setVisibility(View.GONE);
                    message.setText("加载失败，点击重试");
                    message.setVisibility(View.VISIBLE);
                    message.setOnClickListener(view -> loadSelection(false));
                });
                Log.e("HuangGuo", "Episode failed", error);
            }
        });
    }

    private void prefetchAdjacent() {
        for (int direction : new int[]{1, -1}) {
            Selection selection = adjacent(direction);
            if (selection == null) continue;
            String id = selection.item.optString("id");
            String key = cacheKey(id, selection.episode);
            loadPoster(selection.item, incoming);
            worker.execute(() -> {
                try {
                    synchronized (episodeCache) { if (episodeCache.containsKey(key)) return; }
                    JSONObject data = repository.episode(id, selection.episode);
                    synchronized (episodeCache) { episodeCache.put(key, data); }
                } catch (Exception ignored) { }
            });
        }
    }

    private void switchTo(Selection selection) {
        closeSpeedPanel();
        if (episodesOpen) closeEpisodes();
        saveProgress(false);
        boolean wasAi = isAiQueue();
        int oldIndex = queueIndex;
        currentItem = selection.item;
        queueIndex = selection.index;
        currentEpisode = selection.episode;
        mediaLayer.setTranslationY(0);
        incoming.setTranslationY(0);
        incoming.setVisibility(View.GONE);
        loadSelection(false);
        if (wasAi && oldIndex != queueIndex) toast(oldIndex < queueIndex ? "跳转至下一部作品" : "返回上一部作品");
    }

    private void animateSwitch(Selection selection, int direction) {
        if (dragging) return;
        int height = Math.max(1, root.getHeight());
        incoming.setImageDrawable(null);
        incoming.setVisibility(View.VISIBLE);
        loadPoster(selection.item, incoming);
        incoming.setTranslationY(direction > 0 ? height : -height);
        player.pause();
        mediaLayer.animate().translationY(direction > 0 ? -height : height).setDuration(290).start();
        incoming.animate().translationY(0).setDuration(290).withEndAction(() -> switchTo(selection)).start();
    }

    private boolean handleGesture(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchStartX = event.getX();
                touchStartY = event.getY();
                dragY = 0;
                dragging = true;
                gestureMode = GESTURE_PENDING;
                doubleTapCandidate = SystemClock.uptimeMillis() - lastTapAt < ViewConfiguration.getDoubleTapTimeout()
                        && Math.abs(touchStartX - lastTapX) < dp(72) && Math.abs(touchStartY - lastTapY) < dp(72);
                if (doubleTapCandidate) handler.removeCallbacks(singleTap);
                handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging) return true;
                float dx = event.getX() - touchStartX;
                float dy = event.getY() - touchStartY;
                if (gestureMode == GESTURE_PENDING) {
                    int slop = Math.max(dp(10), ViewConfiguration.get(this).getScaledTouchSlop());
                    if (Math.hypot(dx, dy) < slop) return true;
                    handler.removeCallbacks(longPress);
                    lastTapAt = 0;
                    gestureMode = Math.abs(dx) > Math.abs(dy) * 1.15f ? GESTURE_HORIZONTAL : GESTURE_VERTICAL;
                    if (gestureMode == GESTURE_HORIZONTAL && player != null && player.getDuration() > 0) {
                        gestureSeekStart = player.getCurrentPosition();
                        seeking = true;
                        revealControls();
                        handler.removeCallbacks(hideControls);
                    }
                }
                if (gestureMode == GESTURE_HOLD) {
                    if (!holdToggled && dy > dp(64) && player != null && player.isPlaying()) {
                        holdToggled = true;
                        speedLocked = !speedLocked;
                        speedHoldActive = false;
                        applyPlaybackSpeed(speedLocked ? 2f : manualSpeed);
                        root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                        toast(speedLocked ? "已锁定 2×" : "已恢复 " + speedLabel(manualSpeed));
                    }
                    return true;
                }
                if (gestureMode == GESTURE_HORIZONTAL) {
                    if (player == null || player.getDuration() <= 0) return true;
                    long duration = player.getDuration();
                    long range = duration / 5;
                    gestureSeekTarget = Math.max(0, Math.min(duration,
                            gestureSeekStart + Math.round(dx * range / Math.max(1, root.getWidth()))));
                    int progress = (int) Math.min(1000, gestureSeekTarget * 1000 / duration);
                    SeekBar bar = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE
                            ? landscapeSeek : seekBar;
                    seekBar.setProgress(progress);
                    landscapeSeek.setProgress(progress);
                    updateSeekPreview(bar, progress);
                    return true;
                }
                dragY = Math.max(-root.getHeight(), Math.min(root.getHeight(), event.getY() - touchStartY));
                int direction = dragY < 0 ? 1 : -1;
                Selection candidate = adjacent(direction);
                mediaLayer.setTranslationY(dragY);
                if (candidate != null) {
                    if (incoming.getVisibility() != View.VISIBLE) {
                        incoming.setImageDrawable(null);
                        incoming.setVisibility(View.VISIBLE);
                        loadPoster(candidate.item, incoming);
                    }
                    incoming.setTranslationY((direction > 0 ? root.getHeight() : -root.getHeight()) + dragY);
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!dragging) return true;
                dragging = false;
                handler.removeCallbacks(longPress);
                boolean cancelled = event.getActionMasked() == MotionEvent.ACTION_CANCEL;
                if (gestureMode == GESTURE_HOLD) {
                    gestureMode = GESTURE_PENDING;
                    if (speedHoldActive && !speedLocked) applyPlaybackSpeed(manualSpeed);
                    speedHoldActive = false;
                    revealControls();
                    return true;
                }
                if (gestureMode == GESTURE_HORIZONTAL) {
                    gestureMode = GESTURE_PENDING;
                    stopSeekPreview();
                    if (!cancelled && player != null && player.getDuration() > 0) {
                        player.seekTo(gestureSeekTarget);
                        saveProgress(false);
                    }
                    revealControls();
                    return true;
                }
                if (gestureMode == GESTURE_PENDING) {
                    if (cancelled) return true;
                    if (doubleTapCandidate) {
                        lastTapAt = 0;
                        if (episodesOpen) closeEpisodes();
                        if (player != null) player.setPlayWhenReady(!player.getPlayWhenReady());
                        revealControls();
                    } else {
                        lastTapAt = SystemClock.uptimeMillis();
                        lastTapX = event.getX();
                        lastTapY = event.getY();
                        handler.postDelayed(singleTap, ViewConfiguration.getDoubleTapTimeout());
                    }
                    return true;
                }
                gestureMode = GESTURE_PENDING;
                int moveDirection = dragY < 0 ? 1 : -1;
                Selection next = adjacent(moveDirection);
                if (!cancelled && Math.abs(dragY) > Math.max(dp(76), root.getHeight() * 0.12f) && next != null) {
                    int height = root.getHeight();
                    player.pause();
                    mediaLayer.animate().translationY(moveDirection > 0 ? -height : height).setDuration(260).start();
                    incoming.animate().translationY(0).setDuration(260).withEndAction(() -> switchTo(next)).start();
                } else {
                    mediaLayer.animate().translationY(0).setDuration(220).start();
                    incoming.animate().translationY(moveDirection > 0 ? root.getHeight() : -root.getHeight())
                            .setDuration(220).withEndAction(() -> incoming.setVisibility(View.GONE)).start();
                    if (!cancelled && Math.abs(dragY) > dp(76) && next == null)
                        toast(moveDirection > 0 ? "已经是最后一集" : "已经是第一集");
                }
                return true;
        }
        return true;
    }

    private void showEpisodes() {
        if (episodesOpen) { closeEpisodes(); return; }
        if (episodes.length() == 0 || isAiQueue()) { toast("当前作品没有选集"); return; }
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(18), dp(18), dp(18));
        GradientDrawable background = background(0xF7242320, 12);
        background.setStroke(dp(1), 0xFF796D53);
        panel.setBackground(background);
        panel.setElevation(dp(18));
        TextView heading = label("选集", 20, Color.WHITE);
        heading.setTypeface(null, Typeface.BOLD);
        panel.addView(heading, new LinearLayout.LayoutParams(-1, dp(36)));
        TextView current = label("当前第 " + currentEpisode + " 集", 12, 0xFFC9C4B8);
        panel.addView(current, new LinearLayout.LayoutParams(-1, dp(30)));
        ScrollView scroll = new ScrollView(this);
        android.widget.GridLayout grid = new android.widget.GridLayout(this);
        grid.setColumnCount(4);
        for (int i = 0; i < episodes.length(); i++) {
            JSONObject item = episodes.optJSONObject(i);
            if (item == null) continue;
            int number = item.optInt("number");
            TextView button = label(String.valueOf(number), 14, number == currentEpisode ? Color.BLACK : Color.WHITE);
            button.setGravity(Gravity.CENTER);
            button.setBackground(background(number == currentEpisode ? YELLOW : 0xFF373D40, 10));
            android.widget.GridLayout.LayoutParams params = new android.widget.GridLayout.LayoutParams();
            params.width = dp(52); params.height = dp(46); params.setMargins(dp(3), dp(3), dp(3), dp(3));
            grid.addView(button, params);
            button.setOnClickListener(v -> { closeEpisodes(); switchTo(new Selection(currentItem, queueIndex, number)); });
        }
        scroll.addView(grid);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, dp(260)));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(286), -2, Gravity.BOTTOM | Gravity.RIGHT);
        params.bottomMargin = dp(88);
        params.rightMargin = dp(13);
        root.addView(panel, params);
        episodePanel = panel;
        episodesOpen = true;
        episodeTrigger.setText("收起");
        panel.setPivotX(dp(286));
        panel.setPivotY(dp(380));
        panel.setAlpha(0);
        panel.setScaleX(.72f);
        panel.setScaleY(.12f);
        panel.setTranslationY(dp(10));
        panel.animate().alpha(1).scaleX(1).scaleY(1).translationY(0).setDuration(300)
                .setInterpolator(new android.view.animation.PathInterpolator(.2f, .8f, .2f, 1f)).start();
        revealControls();
    }

    private void closeEpisodes() {
        if (!episodesOpen || episodePanel == null) return;
        LinearLayout closing = episodePanel;
        episodePanel = null;
        episodesOpen = false;
        episodeTrigger.setText("选集");
        closing.animate().alpha(0).scaleX(.72f).scaleY(.12f).translationY(dp(10)).setDuration(220)
                .withEndAction(() -> root.removeView(closing)).start();
        revealControls();
    }

    private void updateControlLayout() {
        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        if (landscapeSeek != null) landscapeSeek.setVisibility(landscape ? View.VISIBLE : View.GONE);
        if (volumeButton != null) volumeButton.setVisibility(landscape ? View.VISIBLE : View.GONE);
        if (volumeSeek != null) volumeSeek.setVisibility(landscape ? View.VISIBLE : View.GONE);
        if (seekBar != null) ((View) seekBar.getParent()).setVisibility(landscape ? View.GONE : View.VISIBLE);
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        closeSpeedPanel();
        if (seeking) stopSeekPreview();
        updateControlLayout();
    }

    private void updateMiniProgress(long position, long duration) {
        miniProgressFraction = Math.max(0f, Math.min(1f, (float) position / duration));
        updateMiniProgressFill();
    }

    private void updateMiniProgressFill() {
        if (miniProgress == null || miniProgress.getWidth() == 0) return;
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) miniProgressFill.getLayoutParams();
        int width = Math.round(miniProgress.getWidth() * miniProgressFraction);
        if (params.width != width) { params.width = width; miniProgressFill.setLayoutParams(params); }
    }

    private SeekBar visibleSeekBar() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE
                ? landscapeSeek : seekBar;
    }

    private float[] miniToSeekGeometry(SeekBar target) {
        int[] miniPoint = new int[2];
        int[] seekPoint = new int[2];
        miniProgress.getLocationOnScreen(miniPoint);
        target.getLocationOnScreen(seekPoint);
        float miniCenterX = miniPoint[0] + miniProgress.getWidth() / 2f;
        float miniCenterY = miniPoint[1] + miniProgress.getHeight() / 2f;
        float seekCenterX = seekPoint[0] + target.getWidth() / 2f;
        float seekCenterY = seekPoint[1] + target.getHeight() / 2f;
        float trackWidth = target.getWidth() - target.getPaddingLeft() - target.getPaddingRight();
        return new float[]{seekCenterX - miniCenterX, seekCenterY - miniCenterY,
                Math.max(.08f, trackWidth / Math.max(1f, miniProgress.getWidth()))};
    }

    private void resetMiniTransform() {
        miniProgress.setTranslationX(0);
        miniProgress.setTranslationY(0);
        miniProgress.setScaleX(1);
        miniProgress.setScaleY(1);
    }

    private void showControlsTransition() {
        controlsShown = true;
        int token = ++controlTransitionToken;
        controls.animate().cancel();
        miniProgress.animate().cancel();
        controls.setVisibility(View.VISIBLE);
        controls.setAlpha(0);
        SeekBar target = visibleSeekBar();
        target.setAlpha(0);
        miniProgress.setVisibility(View.VISIBLE);
        resetMiniTransform();
        miniProgress.post(() -> {
            if (token != controlTransitionToken || !controlsShown) return;
            updateMiniProgressFill();
            if (!ValueAnimator.areAnimatorsEnabled()) {
                controls.setAlpha(1);
                target.setAlpha(1);
                miniProgress.setVisibility(View.GONE);
                return;
            }
            float[] geometry = miniToSeekGeometry(target);
            controls.animate().alpha(1).setDuration(230).start();
            miniProgress.animate().translationX(geometry[0]).translationY(geometry[1])
                    .scaleX(geometry[2]).scaleY(1.5f).setDuration(230)
                    .setInterpolator(new PathInterpolator(.2f, .75f, .2f, 1f))
                    .withEndAction(() -> {
                        if (token != controlTransitionToken || !controlsShown) return;
                        miniProgress.setVisibility(View.GONE);
                        resetMiniTransform();
                        target.setAlpha(1);
                    }).start();
        });
    }

    private void concealControls() {
        if (!controlsShown) return;
        controlsShown = false;
        int token = ++controlTransitionToken;
        handler.removeCallbacks(hideControls);
        handler.removeCallbacks(hideCenterToggle);
        centerToggle.setVisibility(View.GONE);
        controls.animate().cancel();
        miniProgress.animate().cancel();
        controls.setVisibility(View.VISIBLE);
        controls.setAlpha(1);
        SeekBar target = visibleSeekBar();
        target.setAlpha(0);
        miniProgress.setVisibility(View.VISIBLE);
        miniProgress.post(() -> {
            if (token != controlTransitionToken || controlsShown) return;
            updateMiniProgressFill();
            float[] geometry = miniToSeekGeometry(target);
            miniProgress.setTranslationX(geometry[0]);
            miniProgress.setTranslationY(geometry[1]);
            miniProgress.setScaleX(geometry[2]);
            miniProgress.setScaleY(1.5f);
            if (!ValueAnimator.areAnimatorsEnabled()) {
                resetMiniTransform();
                controls.setVisibility(View.INVISIBLE);
                target.setAlpha(1);
                return;
            }
            controls.animate().alpha(0).setDuration(180).start();
            miniProgress.animate().translationX(0).translationY(0).scaleX(1).scaleY(1)
                    .setDuration(220).setInterpolator(new PathInterpolator(.2f, .75f, .2f, 1f))
                    .withEndAction(() -> {
                        if (token != controlTransitionToken || controlsShown) return;
                        controls.setVisibility(View.INVISIBLE);
                        controls.setAlpha(1);
                        target.setAlpha(1);
                    }).start();
        });
    }

    private void revealControls() {
        if (controls == null) return;
        if (!controlsShown) showControlsTransition();
        else controls.setVisibility(View.VISIBLE);
        if (centerToggle != null && loading.getVisibility() != View.VISIBLE && player != null && !player.isPlaying()) {
            centerToggle.setVisibility(View.VISIBLE);
            handler.removeCallbacks(hideCenterToggle);
            handler.postDelayed(hideCenterToggle, 3000);
        } else if (centerToggle != null) centerToggle.setVisibility(View.GONE);
        handler.removeCallbacks(hideControls);
        if (!episodesOpen && !seeking && gestureMode != GESTURE_HOLD && speedPanel == null)
            handler.postDelayed(hideControls, 3000);
    }

    private final Runnable hideControls = () -> {
        if (controls != null && !seeking && gestureMode != GESTURE_HOLD && speedPanel == null)
            concealControls();
    };
    private final Runnable hideCenterToggle = () -> { if (centerToggle != null) centerToggle.setVisibility(View.GONE); };

    private void toast(String text) {
        LinearLayout bubble = new LinearLayout(this);
        bubble.setGravity(Gravity.CENTER_VERTICAL);
        bubble.setPadding(dp(13), dp(9), dp(13), dp(9));
        GradientDrawable box = background(0xF42A2D30, 8);
        box.setStroke(dp(1), 0xFF52585A);
        bubble.setBackground(box);
        View dot = new View(this);
        dot.setBackground(background(YELLOW, 4));
        bubble.addView(dot, new LinearLayout.LayoutParams(dp(8), dp(8)));
        TextView label = label(text, 13, Color.WHITE);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(-2, -2);
        labelParams.leftMargin = dp(8);
        bubble.addView(label, labelParams);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        params.topMargin = dp(32);
        root.addView(bubble, params);
        bubble.setAlpha(0);
        bubble.setTranslationY(-dp(12));
        bubble.animate().alpha(1).translationY(0).setDuration(220).start();
        handler.postDelayed(() -> bubble.animate().alpha(0).translationY(-dp(12)).setDuration(220)
                .withEndAction(() -> root.removeView(bubble)).start(), 2800);
    }

    private void saveProgress(boolean completed) {
        if (player == null || currentId == null || player.getDuration() <= 0) return;
        try {
            JSONObject value = new JSONObject();
            value.put("id", currentId);
            value.put("episode", currentEpisode);
            value.put("title", currentItem.optString("title"));
            value.put("cover", currentItem.optString("cover"));
            value.put("position", player.getCurrentPosition());
            value.put("duration", player.getDuration());
            value.put("completed", completed || player.getPlaybackState() == Player.STATE_ENDED);
            store.recordProgress(value);
            lastSaved = System.currentTimeMillis();
        } catch (Exception ignored) { }
    }

    @Override protected void onPause() {
        handler.removeCallbacks(longPress);
        handler.removeCallbacks(singleTap);
        dragging = false;
        gestureMode = GESTURE_PENDING;
        if (speedHoldActive && !speedLocked) applyPlaybackSpeed(manualSpeed);
        speedHoldActive = false;
        closeSpeedPanel();
        stopSeekPreview();
        if (player != null) { saveProgress(false); player.pause(); }
        super.onPause();
    }

    @Override public void onBackPressed() {
        if (speedPanel != null) closeSpeedPanel();
        else if (episodesOpen) closeEpisodes();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        if (player != null) player.release();
        super.onDestroy();
    }
}
