package com.jack.downloader;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.TextUtils.TruncateAt;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.VideoView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Locale;

/** First-party local video/audio player. No external player required. */
public class PlayerActivity extends Activity {
    public static final String EXTRA_URI = "jack.player.uri";
    public static final String EXTRA_NAME = "jack.player.name";
    public static final String EXTRA_VIDEO = "jack.player.video";
    public static final String EXTRA_URIS = "jack.player.uris";
    public static final String EXTRA_NAMES = "jack.player.names";
    public static final String EXTRA_KINDS = "jack.player.kinds";
    public static final String EXTRA_INDEX = "jack.player.index";

    private final ArrayList<String> uris = new ArrayList<>();
    private final ArrayList<String> names = new ArrayList<>();
    private final ArrayList<String> kinds = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final float[] speeds = {0.75f, 1.0f, 1.25f, 1.5f, 2.0f};
    private int speedIndex = 1;
    private int index = 0;
    private boolean prepared;
    private boolean repeat = false;
    private boolean fullScreen = false;
    private boolean dragging = false;
    private boolean videoMode = true;
    private MediaPlayer audio;
    private MediaPlayer videoEngine;
    private VideoView videoView;
    private FrameLayout stage;
    private TextView audioArt;
    private TextView fileName;
    private TextView fileInfo;
    private TextView currentTime;
    private TextView totalTime;
    private TextView playbackStatus;
    private SeekBar seek;
    private Button playButton;
    private Button repeatButton;
    private Button speedButton;
    private Button pipButton;
    private Button fullscreenButton;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            refreshProgress();
            handler.postDelayed(this, 550);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(8, 14, 28));
        getWindow().setNavigationBarColor(Color.rgb(8, 14, 28));
        loadPlaylist();
        buildInterface();
        playIndex(index);
        handler.post(ticker);
    }

    private void loadPlaylist() {
        Intent intent = getIntent();
        ArrayList<String> allUris = intent.getStringArrayListExtra(EXTRA_URIS);
        ArrayList<String> allNames = intent.getStringArrayListExtra(EXTRA_NAMES);
        ArrayList<String> allKinds = intent.getStringArrayListExtra(EXTRA_KINDS);
        if (allUris != null && !allUris.isEmpty() && allNames != null
                && allKinds != null && allUris.size() == allNames.size()
                && allUris.size() == allKinds.size()) {
            uris.addAll(allUris);
            names.addAll(allNames);
            kinds.addAll(allKinds);
            index = Math.max(0, Math.min(uris.size() - 1, intent.getIntExtra(EXTRA_INDEX, 0)));
        } else {
            String uri = intent.getStringExtra(EXTRA_URI);
            if (uri != null) {
                uris.add(uri);
                String name = intent.getStringExtra(EXTRA_NAME);
                names.add(TextUtils.isEmpty(name) ? "ملف وسائط" : name);
                kinds.add(intent.getBooleanExtra(EXTRA_VIDEO, false) ? "video" : "audio");
            }
        }
        if (uris.isEmpty()) {
            Toast.makeText(this, "لا يوجد ملف قابل للتشغيل", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void buildInterface() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(8, 14, 28));
        LinearLayout root = new LinearLayout(this);
        root.setPadding(dp(16), dp(16), dp(16), dp(22));
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        scroll.addView(root);
        setContentView(scroll);

        LinearLayout header = horizontal();
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button close = button("رجوع", false);
        close.setOnClickListener(v -> finish());
        header.addView(close, new LinearLayout.LayoutParams(dp(80), dp(46)));

        TextView appTitle = label("مشغّل Jack", 19, Color.WHITE, true);
        appTitle.setGravity(Gravity.CENTER);
        header.addView(appTitle, new LinearLayout.LayoutParams(0, dp(48), 1f));

        Button share = button("مشاركة", false);
        share.setOnClickListener(v -> shareFile());
        header.addView(share, new LinearLayout.LayoutParams(dp(88), dp(46)));
        root.addView(header);

        TextView sub = label("فيديو وصوت • داخل التطبيق", 12, rgb("#75DDCE"), false);
        sub.setPadding(dp(6), dp(9), dp(6), dp(15));
        root.addView(sub);

        stage = new FrameLayout(this);
        stage.setBackground(round(rgb("#0C1930"), 20, rgb("#36516C")));
        stage.setClipToOutline(true);
        LinearLayout.LayoutParams stageParams = new LinearLayout.LayoutParams(-1, dp(232));
        root.addView(stage, stageParams);

        videoView = new VideoView(this);
        videoView.setBackgroundColor(Color.BLACK);
        videoView.setKeepScreenOn(true);
        stage.addView(videoView, new FrameLayout.LayoutParams(-1, -1));

        audioArt = new TextView(this);
        audioArt.setText("♫");
        audioArt.setTextSize(94);
        audioArt.setGravity(Gravity.CENTER);
        audioArt.setTextColor(rgb("#76E3D4"));
        audioArt.setBackground(round(rgb("#162841"), 22, rgb("#335B72")));
        stage.addView(audioArt, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout infoCard = new LinearLayout(this);
        infoCard.setPadding(dp(17), dp(15), dp(17), dp(15));
        infoCard.setOrientation(LinearLayout.VERTICAL);
        infoCard.setBackground(round(rgb("#152239"), 17, rgb("#2C4262")));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
        cardParams.topMargin = dp(15);
        root.addView(infoCard, cardParams);

        fileName = label("", 19, Color.WHITE, true);
        fileName.setSingleLine(true);
        fileName.setEllipsize(TruncateAt.MARQUEE);
        fileName.setMarqueeRepeatLimit(-1);
        fileName.setSelected(true);
        infoCard.addView(fileName);

        fileInfo = label("", 12, rgb("#9BAFCB"), false);
        fileInfo.setPadding(0, dp(8), 0, 0);
        infoCard.addView(fileInfo);

        playbackStatus = label("جارٍ تجهيز المشغّل...", 13, rgb("#73DDCD"), false);
        playbackStatus.setPadding(0, dp(12), 0, 0);
        infoCard.addView(playbackStatus);

        seek = new SeekBar(this);
        seek.setMax(1000);
        seek.setProgressTintList(ColorStateList.valueOf(rgb("#6DE4D3")));
        seek.setThumbTintList(ColorStateList.valueOf(rgb("#6DE4D3")));
        LinearLayout.LayoutParams seekParams = new LinearLayout.LayoutParams(-1, dp(46));
        seekParams.topMargin = dp(11);
        root.addView(seek, seekParams);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar bar) { dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                dragging = false;
                long duration = getDuration();
                if (duration > 0) seekTo((int) (duration * bar.getProgress() / 1000L));
            }
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (fromUser && getDuration() > 0)
                    currentTime.setText(clock(getDuration() * value / 1000L));
            }
        });

        LinearLayout times = horizontal();
        times.setGravity(Gravity.CENTER_VERTICAL);
        currentTime = label("00:00", 13, rgb("#BED2E6"), false);
        totalTime = label("00:00", 13, rgb("#BED2E6"), false);
        times.addView(totalTime);
        TextView spacer = label("  /  ", 13, rgb("#647F9B"), false);
        times.addView(spacer);
        times.addView(currentTime);
        root.addView(times);

        LinearLayout controls = horizontal();
        controls.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams controlsParams = new LinearLayout.LayoutParams(-1, dp(68));
        controlsParams.topMargin = dp(10);
        root.addView(controls, controlsParams);

        Button next = button("⏭", false);
        next.setContentDescription("المقطع التالي");
        next.setTextSize(23);
        next.setOnClickListener(v -> changeTrack(1));
        controls.addView(next, weighted());

        Button forward = button("+10", false);
        forward.setContentDescription("تقديم عشر ثوانٍ");
        forward.setOnClickListener(v -> jump(10000));
        controls.addView(forward, weighted());

        playButton = button("▶", true);
        playButton.setContentDescription("تشغيل أو إيقاف مؤقت");
        playButton.setTextSize(25);
        playButton.setOnClickListener(v -> togglePlayback());
        controls.addView(playButton, weighted());

        Button backward = button("−10", false);
        backward.setContentDescription("رجوع عشر ثوانٍ");
        backward.setOnClickListener(v -> jump(-10000));
        controls.addView(backward, weighted());

        Button previous = button("⏮", false);
        previous.setContentDescription("المقطع السابق");
        previous.setTextSize(23);
        previous.setOnClickListener(v -> changeTrack(-1));
        controls.addView(previous, weighted());

        LinearLayout options = horizontal();
        options.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams optionsParams = new LinearLayout.LayoutParams(-1, dp(58));
        optionsParams.topMargin = dp(8);
        root.addView(options, optionsParams);

        speedButton = button("السرعة 1×", false);
        speedButton.setOnClickListener(v -> cycleSpeed());
        options.addView(speedButton, weighted());

        repeatButton = button("تكرار: لا", false);
        repeatButton.setOnClickListener(v -> {
            repeat = !repeat;
            repeatButton.setText(repeat ? "تكرار: نعم" : "تكرار: لا");
        });
        options.addView(repeatButton, weighted());

        fullscreenButton = button("ملء الشاشة", false);
        fullscreenButton.setOnClickListener(v -> toggleFullscreen());
        options.addView(fullscreenButton, weighted());

        pipButton = button("نافذة عائمة", false);
        pipButton.setOnClickListener(v -> showPictureInPicture());
        if (Build.VERSION.SDK_INT >= 26
                && getPackageManager().hasSystemFeature("android.software.picture_in_picture")) {
            LinearLayout.LayoutParams pipParams = new LinearLayout.LayoutParams(-1, dp(46));
            pipParams.topMargin = dp(13);
            root.addView(pipButton, pipParams);
        } else {
            pipButton.setVisibility(View.GONE);
        }

        TextView footer = label(
                "يمكنك تقديم المقطع أو إرجاعه 10 ثوانٍ، تغيير سرعة التشغيل، التكرار، ومشاركة الملف. تعتمد الصيغ القابلة للتشغيل على جهازك.",
                12, rgb("#91A8C2"), false);
        footer.setPadding(dp(4), dp(19), dp(4), dp(16));
        root.addView(footer);

        TouchEffects.installRecursively(root);
    }

    private void playIndex(int selected) {
        if (uris.isEmpty() || selected < 0 || selected >= uris.size()) return;
        if (audio != null) {
            try { audio.reset(); audio.release(); } catch (Exception ignored) { }
            audio = null;
        }
        if (videoView != null) videoView.stopPlayback();
        videoEngine = null;
        prepared = false;
        index = selected;
        speedIndex = 1;
        speedButton.setText("السرعة 1×");
        seek.setProgress(0);
        currentTime.setText("00:00");
        totalTime.setText("00:00");

        Uri uri = Uri.parse(uris.get(index));
        String scheme = uri.getScheme();
        if (!"content".equalsIgnoreCase(scheme) && !"file".equalsIgnoreCase(scheme)) {
            error("يسمح المشغّل بالوسائط المحلية فقط");
            return;
        }

        videoMode = "video".equals(kinds.get(index));
        fileName.setText(names.get(index));
        fileInfo.setText((videoMode ? "🎬 فيديو" : "🎵 ملف صوتي")
                + "  •  " + (index + 1) + " / " + uris.size());
        playbackStatus.setText("جارٍ فتح الوسائط...");
        playButton.setText("▶");
        videoView.setVisibility(videoMode ? View.VISIBLE : View.GONE);
        audioArt.setVisibility(videoMode ? View.GONE : View.VISIBLE);
        fullscreenButton.setEnabled(videoMode);
        pipButton.setVisibility(videoMode && Build.VERSION.SDK_INT >= 26
                && getPackageManager().hasSystemFeature("android.software.picture_in_picture")
                ? View.VISIBLE : View.GONE);

        try {
            if (videoMode) {
                videoView.setOnPreparedListener(mp -> {
                    videoEngine = mp;
                    prepared = true;
                    videoEngine.setLooping(repeat);
                    videoView.start();
                    playbackStatus.setText("جاهز • تشغيل الفيديو");
                    refreshProgress();
                });
                videoView.setOnCompletionListener(mp -> onComplete());
                videoView.setOnErrorListener((mp, what, extra) -> {
                    error("تعذر تشغيل الفيديو أو أن صيغته غير مدعومة");
                    return true;
                });
                videoView.setVideoURI(uri);
                videoView.requestFocus();
            } else {
                MediaPlayer engine = new MediaPlayer();
                audio = engine;
                engine.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build());
                engine.setDataSource(this, uri);
                engine.setLooping(repeat);
                engine.setOnPreparedListener(mp -> {
                    if (mp != audio) return;
                    prepared = true;
                    mp.start();
                    playbackStatus.setText("جاهز • تشغيل الصوت");
                    refreshProgress();
                });
                engine.setOnCompletionListener(mp -> onComplete());
                engine.setOnErrorListener((mp, what, extra) -> {
                    error("تعذر تشغيل الصوت أو أن صيغته غير مدعومة");
                    return true;
                });
                engine.prepareAsync();
            }
        } catch (Exception ex) {
            error("تعذر فتح الملف: " + shortMessage(ex));
        }
    }

    private void onComplete() {
        if (repeat) {
            seekTo(0);
            resume();
        } else if (index + 1 < uris.size()) {
            playIndex(index + 1);
        } else {
            playbackStatus.setText("اكتمل التشغيل");
            playButton.setText("▶");
        }
    }

    private void refreshProgress() {
        if (!prepared || dragging) return;
        int duration = getDuration();
        int position = getPosition();
        totalTime.setText(clock(duration));
        currentTime.setText(clock(position));
        if (duration > 0) seek.setProgress((int) (1000L * position / duration));
        playButton.setText(isPlaying() ? "❚❚" : "▶");
    }

    private int getDuration() {
        try { return Math.max(0, videoMode ? videoView.getDuration() : (audio == null ? 0 : audio.getDuration())); }
        catch (Exception e) { return 0; }
    }

    private int getPosition() {
        try { return Math.max(0, videoMode ? videoView.getCurrentPosition() : (audio == null ? 0 : audio.getCurrentPosition())); }
        catch (Exception e) { return 0; }
    }

    private boolean isPlaying() {
        try { return prepared && (videoMode ? videoView.isPlaying() : audio != null && audio.isPlaying()); }
        catch (Exception e) { return false; }
    }

    private void seekTo(int timeMs) {
        if (!prepared) return;
        int clamped = Math.max(0, Math.min(getDuration(), timeMs));
        try {
            if (videoMode) videoView.seekTo(clamped);
            else if (audio != null) audio.seekTo(clamped);
        } catch (Exception ignored) { }
        refreshProgress();
    }

    private void jump(int byMs) { seekTo(getPosition() + byMs); }

    private void changeTrack(int delta) {
        int newIndex = index + delta;
        if (newIndex < 0 || newIndex >= uris.size()) {
            Toast.makeText(this, delta > 0 ? "هذا آخر ملف" : "هذا أول ملف", Toast.LENGTH_SHORT).show();
            return;
        }
        playIndex(newIndex);
    }

    private void resume() {
        if (!prepared) return;
        try {
            if (videoMode) videoView.start();
            else if (audio != null) audio.start();
            refreshProgress();
        } catch (Exception ex) { error("تعذر استئناف التشغيل"); }
    }

    private void pause() {
        if (!prepared) return;
        try {
            if (videoMode) videoView.pause();
            else if (audio != null) audio.pause();
            refreshProgress();
        } catch (Exception ignored) { }
    }

    private void togglePlayback() {
        if (!prepared) return;
        if (isPlaying()) pause();
        else resume();
    }

    private void cycleSpeed() {
        speedIndex = (speedIndex + 1) % speeds.length;
        float value = speeds[speedIndex];
        speedButton.setText("السرعة " + value + "×");
        if (!prepared) return;
        boolean playing = isPlaying();
        try {
            MediaPlayer mp = videoMode ? videoEngine : audio;
            if (mp != null) {
                PlaybackParams params = mp.getPlaybackParams();
                params.setSpeed(value);
                mp.setPlaybackParams(params);
                if (!playing) pause();
            }
        } catch (Exception ex) {
            Toast.makeText(this, "هذا الجهاز لا يدعم هذه السرعة", Toast.LENGTH_SHORT).show();
        }
    }

    private void toggleFullscreen() {
        if (!videoMode) return;
        fullScreen = !fullScreen;
        fullscreenButton.setText(fullScreen ? "تصغير" : "ملء الشاشة");
        getWindow().getDecorView().setSystemUiVisibility(fullScreen
                ? (View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                   | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
                : View.SYSTEM_UI_FLAG_VISIBLE);
        getWindow().setFlags(fullScreen ? WindowManager.LayoutParams.FLAG_FULLSCREEN : 0,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setRequestedOrientation(fullScreen ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        adjustStage();
    }

    private void adjustStage() {
        if (stage == null) return;
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) stage.getLayoutParams();
        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        params.height = (fullScreen && landscape)
                ? Math.max(dp(230), getResources().getDisplayMetrics().heightPixels - dp(85))
                : dp(232);
        stage.setLayoutParams(params);
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        adjustStage();
    }

    private void showPictureInPicture() {
        if (!videoMode || Build.VERSION.SDK_INT < 26) return;
        try { enterPictureInPictureMode(new PictureInPictureParams.Builder().build()); }
        catch (Exception e) { Toast.makeText(this, "النافذة العائمة غير متاحة", Toast.LENGTH_SHORT).show(); }
    }

    private void shareFile() {
        if (uris.isEmpty()) return;
        Uri uri = Uri.parse(uris.get(index));
        try {
            Intent sharing = new Intent(Intent.ACTION_SEND);
            sharing.setType(videoMode ? "video/*" : "audio/*");
            sharing.putExtra(Intent.EXTRA_STREAM, uri);
            sharing.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(sharing, "مشاركة الملف"));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "لا يوجد تطبيق للمشاركة", Toast.LENGTH_SHORT).show();
        }
    }

    private void error(String message) {
        prepared = false;
        playbackStatus.setText(message);
        playButton.setText("▶");
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private static String shortMessage(Exception e) {
        String m = e.getMessage();
        if (m == null) return e.getClass().getSimpleName();
        return m.length() > 100 ? m.substring(0, 100) : m;
    }

    @Override protected void onPause() {
        if (!(Build.VERSION.SDK_INT >= 26 && isInPictureInPictureMode())) pause();
        super.onPause();
    }

    @Override public void onBackPressed() {
        if (fullScreen) toggleFullscreen();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (audio != null) {
            try { audio.reset(); audio.release(); } catch (Exception ignored) { }
            audio = null;
        }
        if (videoView != null) videoView.stopPlayback();
        super.onDestroy();
    }

    private Button button(String caption, boolean primary) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(caption);
        b.setTextSize(13);
        b.setTextColor(primary ? rgb("#061A21") : Color.WHITE);
        b.setBackground(round(primary ? rgb("#66E2CF") : rgb("#20334C"),
                14, primary ? rgb("#91FFE5") : rgb("#36546E")));
        b.setPadding(dp(2), 0, dp(2), 0);
        TouchEffects.apply(b);
        return b;
    }

    private GradientDrawable round(int color, int radius, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        d.setStroke(dp(1), stroke);
        return d;
    }

    private TextView label(String text, int size, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(size);
        v.setTextColor(color);
        if (bold) v.setTypeface(null, Typeface.BOLD);
        return v;
    }

    private static int rgb(String text) { return Color.parseColor(text); }

    private LinearLayout horizontal() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(52), 1f);
        p.setMargins(dp(2), 0, dp(2), 0);
        return p;
    }

    private String clock(long ms) {
        long total = Math.max(0L, ms / 1000);
        long hours = total / 3600;
        long mins = (total % 3600) / 60;
        long secs = total % 60;
        return hours > 0
                ? String.format(Locale.ROOT, "%02d:%02d:%02d", hours, mins, secs)
                : String.format(Locale.ROOT, "%02d:%02d", mins, secs);
    }

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
}
