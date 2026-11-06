package com.jack.downloader;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.content.res.ColorStateList;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.mapper.VideoInfo;
import com.yausername.ffmpeg.FFmpeg;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final int REQ_STORAGE = 61;
    private static final int REQ_MEDIA = 62;
    private static final int REQ_NOTIFY = 63;
    private static final int REQ_PICK = 64;
    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s<>\"']+", Pattern.CASE_INSENSITIVE);

    private static final String[] FORMAT_CHOICES = {
            "تلقائي • MP4 متوافق", "MP4 • فيديو", "MKV • فيديو", "WEBM • فيديو",
            "MOV • فيديو", "MP3 • صوت", "M4A • صوت", "WAV • صوت",
            "FLAC • صوت", "OPUS • صوت"
    };
    private static final String[] EXTENSIONS = {
            "auto", "mp4", "mkv", "webm", "mov", "mp3", "m4a", "wav", "flac", "opus"
    };
    private static final String[] QUALITY_CHOICES = {
            "تلقائي • جودة متوازنة 1080p", "أعلى جودة متاحة", "4K • 2160p",
            "2K • 1440p", "Full HD • 1080p", "HD • 720p",
            "480p", "360p", "240p"
    };
    private static final int[] HEIGHTS = {1080, 0, 2160, 1440, 1080, 720, 480, 360, 240};

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private boolean ready = false;
    private boolean analyzing = false;
    private EditText urlInput;
    private Spinner formatSpinner;
    private Spinner qualitySpinner;
    private TextView titleText;
    private TextView statusText;
    private ProgressBar progressBar;
    private Button analyzeButton;
    private Button downloadButton;
    private Button cancelButton;
    private TextView clipboardLabel;
    private TextView mediaSummary;
    private LinearLayout mediaRows;
    private EditText mediaSearch;
    private final ArrayList<PhoneMedia> cachedMedia = new ArrayList<>();
    private final ArrayList<PhoneMedia> displayedMedia = new ArrayList<>();
    private String mediaFilter = "all";
    private boolean mediaOpened;
    private String lastSeenClipboard = "";
    private ClipboardDownloadPrompt clipboardPrompt;
    private Button lastErrorButton;
    private Button engineUpdateButton;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String status = intent.getStringExtra(DownloadService.EXTRA_STATUS);
            int progress = intent.getIntExtra(DownloadService.EXTRA_PROGRESS, 0);
            boolean active = intent.getBooleanExtra(DownloadService.EXTRA_ACTIVE, false);
            showTransfer(status, progress, active);
        }
    };

    @Override protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        setContentView(R.layout.activity_main);
        urlInput = findViewById(R.id.editUrl);
        formatSpinner = findViewById(R.id.spinnerFormat);
        qualitySpinner = findViewById(R.id.spinnerQuality);
        titleText = findViewById(R.id.textTitle);
        statusText = findViewById(R.id.textStatus);
        progressBar = findViewById(R.id.progressDownload);
        analyzeButton = findViewById(R.id.btnAnalyze);
        downloadButton = findViewById(R.id.btnDownload);
        cancelButton = findViewById(R.id.btnCancel);

        configureSpinner(formatSpinner, FORMAT_CHOICES);
        configureSpinner(qualitySpinner, QUALITY_CHOICES);
        analyzeButton.setEnabled(false);
        downloadButton.setEnabled(false);

        findViewById(R.id.btnPaste).setOnClickListener(v -> pasteLink(true));
        analyzeButton.setOnClickListener(v -> analyzeLink());
        downloadButton.setOnClickListener(v -> startDownload());
        cancelButton.setOnClickListener(v -> cancelDownload());
        findViewById(R.id.btnOpenDownloads).setOnClickListener(v -> showPhoneMedia());
        addProfessionalPanels();
        addDownloadDiagnostics();
        TouchEffects.installRecursively(findViewById(R.id.scrollRoot));
        animateFirstAppearance();
        clipboardPrompt = new ClipboardDownloadPrompt(this, new ClipboardDownloadPrompt.Actions() {
            @Override public void download(String link) {
                urlInput.setText(link);
                if (ready && !analyzing && !DownloadService.running) {
                    startDownload();
                } else {
                    statusText.setText("الرابط جاهز • اضغط «تنزيل» عند جاهزية المحرّك");
                }
            }

            @Override public void choose(String link) {
                urlInput.setText(link);
                statusText.setText("اختر الصيغة والجودة المطلوبة ثم اضغط «تنزيل»");
                ScrollView root = findViewById(R.id.scrollRoot);
                root.smoothScrollTo(0, dp(190));
            }
        });
        handleIncomingIntent(getIntent());

        initializeAndRefreshEngine(false);
    }

    private void initializeAndRefreshEngine(boolean manual) {
        if (DownloadService.running) {
            if (manual) toast("انتظر اكتمال التنزيل قبل تحديث المحرك");
            // The service already owns the initialized engine; keep the new
            // Activity ready for the next download after the service finishes.
            ready = true;
            updateActions();
            return;
        }
        if (analyzing) {
            toast("انتظر اكتمال تحليل الرابط");
            return;
        }
        ready = false;
        updateActions();
        statusText.setText(manual ? "جارٍ تحديث محرك التنزيل..."
                : "جارٍ تجهيز محرك التنزيل وفحص التحديثات...");
        if (engineUpdateButton != null) engineUpdateButton.setEnabled(false);
        executor.execute(() -> {
            String engineStatus = "محرك التنزيل جاهز • تحسين الاعتمادية مفعّل";
            boolean successfulInit = false;
            try {
                YoutubeDL.getInstance().init(getApplicationContext());
                FFmpeg.getInstance().init(getApplicationContext());
                successfulInit = true;

                android.content.SharedPreferences prefs =
                        getSharedPreferences("yt_dlp_updates", MODE_PRIVATE);
                long now = System.currentTimeMillis();
                long lastCheck = prefs.getLong("checked_at", 0L);
                long fourDays = 4L * 24L * 60L * 60L * 1000L;
                if (manual || lastCheck <= 0 || now - lastCheck > fourDays) {
                    prefs.edit().putLong("checked_at", now).apply();
                    try {
                        YoutubeDL.UpdateStatus updated = YoutubeDL.getInstance()
                                .updateYoutubeDL(getApplicationContext(), YoutubeDL.UpdateChannel._STABLE);
                        if (updated != null) engineStatus = "محرك التنزيل محدّث وجاهز • " + updated;
                    } catch (Throwable updateError) {
                        String cause = updateError.getMessage();
                        if (cause == null || cause.isEmpty()) {
                            cause = updateError.getClass().getSimpleName();
                        }
                        engineStatus = "تعذر فحص التحديث؛ المحرك الموجود متاح. " + cause;
                    }
                }
            } catch (Exception setupError) {
                engineStatus = "تعذر تجهيز محرك التنزيل: " + message(setupError);
            }
            final boolean ok = successfulInit;
            final String summary = engineStatus;
            runOnUiThread(() -> {
                ready = ok;
                if (!DownloadService.running) statusText.setText(summary);
                if (engineUpdateButton != null) engineUpdateButton.setEnabled(true);
                updateActions();
            });
        });
    }

    private void addDownloadDiagnostics() {
        if (!(statusText.getParent() instanceof LinearLayout)) return;
        LinearLayout card = (LinearLayout) statusText.getParent();
        int at = card.indexOfChild(statusText) + 1;
        engineUpdateButton = new Button(this);
        engineUpdateButton.setText("تحديث محرك تنزيل الفيديو");
        engineUpdateButton.setAllCaps(false);
        engineUpdateButton.setOnClickListener(v -> initializeAndRefreshEngine(true));
        LinearLayout.LayoutParams first = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(45));
        first.topMargin = dp(8);
        card.addView(engineUpdateButton, at, first);

        lastErrorButton = new Button(this);
        lastErrorButton.setText("نسخ تفاصيل آخر فشل");
        lastErrorButton.setAllCaps(false);
        lastErrorButton.setOnClickListener(v -> {
            String error = getSharedPreferences("download_state", MODE_PRIVATE)
                    .getString("last_error", "");
            if (error == null || error.isEmpty()) {
                toast("لم يسجل أي خطأ حتى الآن");
                return;
            }
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("Jack Downloader Error", error));
                toast("تم نسخ سبب الفشل لإرساله وإصلاحه");
            }
        });
        LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(45));
        second.topMargin = dp(6);
        card.addView(lastErrorButton, at + 1, second);
        refreshErrorButton();
    }

    private void refreshErrorButton() {
        if (lastErrorButton == null) return;
        String error = getSharedPreferences("download_state", MODE_PRIVATE)
                .getString("last_error", "");
        lastErrorButton.setVisibility(error == null || error.isEmpty()
                ? View.GONE : View.VISIBLE);
    }

    private void configureSpinner(Spinner spinner, String[] entries) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.spinner_item, entries);
        adapter.setDropDownViewResource(R.layout.spinner_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(0);
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(DownloadService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(statusReceiver, filter);
    }

    @Override protected void onResume() {
        super.onResume();
        android.content.SharedPreferences state = getSharedPreferences("download_state", MODE_PRIVATE);
        if (DownloadService.running) {
            showTransfer(state.getString("status", "جارٍ التنزيل..."), state.getInt("progress", 0), true);
        } else {
            String lastError = state.getString("last_error", "");
            if (!lastError.isEmpty()) {
                statusText.setText("آخر فشل: " + lastError);
            }
            updateActions();
        }
        refreshErrorButton();
        urlInput.postDelayed(() -> pasteLink(false), 180);
        if (clipboardPrompt != null) clipboardPrompt.resume();
        if (mediaOpened && hasMediaPermission()) loadPhoneMedia();
    }

    @Override protected void onPause() {
        if (clipboardPrompt != null) clipboardPrompt.pause();
        super.onPause();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && clipboardPrompt != null) {
            urlInput.postDelayed(() -> clipboardPrompt.checkNow(), 180);
        }
    }

    @Override protected void onStop() {
        try { unregisterReceiver(statusReceiver); } catch (Exception ignored) { }
        super.onStop();
    }

    @Override protected void onDestroy() {
        if (clipboardPrompt != null) clipboardPrompt.destroy();
        executor.shutdown();
        super.onDestroy();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        String value = null;
        if (Intent.ACTION_SEND.equals(intent.getAction())) value = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null)
            value = intent.getData().toString();
        String link = extractUrl(value);
        if (link != null) {
            urlInput.setText(link);
            lastSeenClipboard = link;
            if (clipboardLabel != null) clipboardLabel.setText("✓ تم استقبال الرابط من المشاركة");
            if (clipboardPrompt != null) clipboardPrompt.offerFromShare(link);
        }
    }

    private void pasteLink(boolean manual) {
        try {
            ClipboardManager manager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (manager == null || !manager.hasPrimaryClip()) {
                if (manual) toast("لا يوجد رابط في الحافظة");
                return;
            }
            ClipData clip = manager.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return;
            CharSequence text = clip.getItemAt(0).coerceToText(this);
            String link = extractUrl(text == null ? null : text.toString());
            if (link == null) {
                if (manual) toast("المنسوخ لا يحتوي رابطاً صالحاً");
                return;
            }
            String current = urlInput.getText().toString().trim();
            if (manual || (!link.equals(lastSeenClipboard) && !link.equals(current))) {
                urlInput.setText(link);
                if (clipboardLabel != null) clipboardLabel.setText("✓ تم اكتشاف رابط من الحافظة تلقائياً");
                if (manual) toast("تم لصق الرابط");
            }
            lastSeenClipboard = link;
        } catch (Exception e) {
            if (manual) toast("تعذرت قراءة الحافظة");
        }
    }

    static String extractUrl(String text) {
        if (text == null) return null;
        Matcher m = URL_PATTERN.matcher(text.trim());
        if (!m.find()) return null;
        String result = m.group();
        while (result.endsWith(".") || result.endsWith(",") || result.endsWith(")")
                || result.endsWith("،") || result.endsWith("؛")) {
            result = result.substring(0, result.length() - 1);
        }
        Uri uri = Uri.parse(result);
        if (TextUtils.isEmpty(uri.getHost())) return null;
        return result;
    }

    private void analyzeLink() {
        String url = extractUrl(urlInput.getText().toString());
        if (url == null) { toast("ألصق رابط فيديو أو صوت أولاً"); return; }
        if (!ready || analyzing || DownloadService.running) return;
        analyzing = true;
        updateActions();
        statusText.setText("جارٍ تحليل الرابط...");
        executor.execute(() -> {
            try {
                VideoInfo info = YoutubeDL.getInstance().getInfo(url);
                runOnUiThread(() -> {
                    titleText.setText(TextUtils.isEmpty(info.getTitle()) ? "رابط مدعوم ✓" : info.getTitle());
                    statusText.setText("اختيار ذكي جاهز • يمكنك تغيير الصيغة أو الدقة");
                });
            } catch (Exception ex) {
                runOnUiThread(() -> statusText.setText("لم ينجح التحليل: " + message(ex)));
            } finally {
                runOnUiThread(() -> { analyzing = false; updateActions(); });
            }
        });
    }

    private void startDownload() {
        String link = extractUrl(urlInput.getText().toString());
        if (link == null) { toast("الصق رابطاً أولاً"); return; }
        if (!ready || analyzing || DownloadService.running) {
            toast("المحرّك مشغول حالياً");
            return;
        }
        if (Build.VERSION.SDK_INT < 29 &&
                checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                !getPreferences(MODE_PRIVATE).getBoolean("asked_notifications", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("asked_notifications", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
            return;
        }
        beginDownload(link);
    }

    private void beginDownload(String link) {
        if (DownloadService.running) return;
        Intent intent = new Intent(this, DownloadService.class);
        intent.setAction(DownloadService.ACTION_START);
        intent.putExtra(DownloadService.EXTRA_URL, link);
        intent.putExtra(DownloadService.EXTRA_EXTENSION, EXTENSIONS[formatSpinner.getSelectedItemPosition()]);
        intent.putExtra(DownloadService.EXTRA_HEIGHT, HEIGHTS[qualitySpinner.getSelectedItemPosition()]);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
            else startService(intent);
            showTransfer("بدء التنزيل...", 0, true);
        } catch (Exception e) {
            showTransfer("تعذر بدء التنزيل: " + message(e), 0, false);
        }
    }

    private void cancelDownload() {
        if (!DownloadService.running) return;
        Intent intent = new Intent(this, DownloadService.class);
        intent.setAction(DownloadService.ACTION_CANCEL);
        startService(intent);
        statusText.setText("جارٍ إلغاء التنزيل...");
    }

    private void showTransfer(String message, int progress, boolean active) {
        if (!TextUtils.isEmpty(message)) statusText.setText(message);
        progressBar.setProgress(Math.max(0, Math.min(100, progress)));
        cancelButton.setEnabled(active);
        refreshErrorButton();
        updateActions();
    }

    private void updateActions() {
        boolean idle = ready && !analyzing && !DownloadService.running;
        analyzeButton.setEnabled(idle);
        downloadButton.setEnabled(idle);
        cancelButton.setEnabled(DownloadService.running);
    }

    private void addProfessionalPanels() {
        ScrollView scroll = findViewById(R.id.scrollRoot);
        LinearLayout root = (LinearLayout) scroll.getChildAt(0);
        clipboardLabel = new TextView(this);
        clipboardLabel.setText("● الكشف عن الرابط المنسوخ عند فتح التطبيق");
        clipboardLabel.setTextColor(Color.rgb(105, 226, 202));
        clipboardLabel.setTextSize(13);
        clipboardLabel.setPadding(dp(4), dp(12), dp(4), dp(5));
        root.addView(clipboardLabel, 3);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(17), dp(17), dp(17), dp(17));
        GradientDrawable surface = new GradientDrawable();
        surface.setColor(Color.rgb(20, 32, 54));
        surface.setCornerRadius(dp(17));
        surface.setStroke(dp(1), Color.rgb(47, 70, 103));
        card.setBackground(surface);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.setMargins(0, dp(16), 0, 0);
        card.setLayoutParams(cp);

        TextView header = new TextView(this);
        header.setText("مكتبة الفيديو والصوت");
        header.setTextColor(Color.WHITE);
        header.setTypeface(null, Typeface.BOLD);
        header.setTextSize(18);
        card.addView(header);

        TextView note = new TextView(this);
        note.setText("تصفّح الملفات الموجودة في هاتفك فقط؛ لا يرفع التطبيق وسائطك لأي خادم");
        note.setTextColor(Color.rgb(161, 179, 203));
        note.setTextSize(13);
        note.setPadding(0, dp(7), 0, dp(9));
        card.addView(note);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button show = new Button(this);
        show.setText("عرض وسائط الهاتف");
        show.setAllCaps(false);
        show.setOnClickListener(v -> showPhoneMedia());
        actions.addView(show, new LinearLayout.LayoutParams(0, dp(52), 1));
        Button pick = new Button(this);
        pick.setText("اختيار ملف");
        pick.setAllCaps(false);
        pick.setOnClickListener(v -> pickMedia());
        actions.addView(pick, new LinearLayout.LayoutParams(0, dp(52), 1));
        card.addView(actions);

        mediaSummary = new TextView(this);
        mediaSummary.setText("امنح إذن الوسائط لعرض الملفات في التطبيق");
        mediaSummary.setTextColor(Color.rgb(161, 179, 203));
        mediaSummary.setPadding(0, dp(8), 0, dp(5));
        card.addView(mediaSummary);

        mediaSearch = new EditText(this);
        mediaSearch.setSingleLine(true);
        mediaSearch.setHint("ابحث عن اسم فيديو أو مقطع صوتي...");
        mediaSearch.setTextColor(Color.WHITE);
        mediaSearch.setHintTextColor(Color.rgb(144, 164, 189));
        mediaSearch.setTextSize(14);
        mediaSearch.setPadding(dp(13), dp(7), dp(13), dp(7));
        mediaSearch.setBackground(roundInput());
        LinearLayout.LayoutParams searchLayout = new LinearLayout.LayoutParams(-1, dp(47));
        searchLayout.setMargins(0, dp(12), 0, dp(10));
        card.addView(mediaSearch, searchLayout);
        mediaSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                renderMedia();
            }
            @Override public void afterTextChanged(Editable editable) { }
        });

        LinearLayout filters = new LinearLayout(this);
        filters.setOrientation(LinearLayout.HORIZONTAL);
        addFilterButton(filters, "الكل", "all");
        addFilterButton(filters, "الفيديو", "video");
        addFilterButton(filters, "الصوتيات", "audio");
        card.addView(filters);

        mediaRows = new LinearLayout(this);
        mediaRows.setOrientation(LinearLayout.VERTICAL);
        card.addView(mediaRows);
        root.addView(card, root.getChildCount() - 1);
        card.setAlpha(0f);
        card.setTranslationY(dp(18));
        card.animate().alpha(1f).translationY(0).setDuration(420).start();
    }

    private void animateFirstAppearance() {
        ScrollView scroll = findViewById(R.id.scrollRoot);
        View root = scroll.getChildAt(0);
        root.setAlpha(0.72f);
        root.animate().alpha(1f).setDuration(360).start();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private GradientDrawable roundInput() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(13, 27, 45));
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), Color.rgb(51, 84, 110));
        return bg;
    }

    private void addFilterButton(LinearLayout parent, String text, String filter) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(12);
        button.setBackground(roundInput());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(47), 1);
        params.setMargins(dp(2), 0, dp(2), 0);
        parent.addView(button, params);
        button.setOnClickListener(v -> {
            mediaFilter = filter;
            for (int i = 0; i < parent.getChildCount(); i++) {
                Button b = (Button) parent.getChildAt(i);
                boolean active = b == button;
                b.setTextColor(active ? Color.rgb(105, 226, 202) : Color.WHITE);
                b.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
            }
            renderMedia();
        });
        if ("all".equals(filter)) {
            button.setTextColor(Color.rgb(105, 226, 202));
            button.setTypeface(null, Typeface.BOLD);
        }
        TouchEffects.apply(button);
    }

    private boolean hasMediaPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            boolean video = checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED;
            boolean audio = checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED;
            boolean partial = Build.VERSION.SDK_INT >= 34 &&
                    checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED;
            return video || audio || partial;
        }
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void showPhoneMedia() {
        mediaOpened = true;
        if (!hasMediaPermission()) {
            if (Build.VERSION.SDK_INT >= 34)
                requestPermissions(new String[]{Manifest.permission.READ_MEDIA_VIDEO,
                        Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED}, REQ_MEDIA);
            else if (Build.VERSION.SDK_INT >= 33)
                requestPermissions(new String[]{Manifest.permission.READ_MEDIA_VIDEO,
                        Manifest.permission.READ_MEDIA_AUDIO}, REQ_MEDIA);
            else
                requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_MEDIA);
            return;
        }
        loadPhoneMedia();
    }

    private void loadPhoneMedia() {
        mediaSummary.setText("جارٍ تحميل قائمة الوسائط...");
        mediaRows.removeAllViews();
        executor.execute(() -> {
            ArrayList<PhoneMedia> files = new ArrayList<>();
            queryMedia(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, files);
            queryMedia(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, false, files);
            files.sort((a, b) -> Long.compare(b.date, a.date));
            runOnUiThread(() -> {
                cachedMedia.clear();
                cachedMedia.addAll(files);
                renderMedia();
            });
        });
    }

    private void renderMedia() {
        if (mediaRows == null || mediaSummary == null) return;
        mediaRows.removeAllViews();
        displayedMedia.clear();
        String term = mediaSearch == null ? "" :
                mediaSearch.getText().toString().trim().toLowerCase(Locale.ROOT);
        for (PhoneMedia file : cachedMedia) {
            if ("video".equals(mediaFilter) && !file.video) continue;
            if ("audio".equals(mediaFilter) && file.video) continue;
            if (!term.isEmpty() && !file.name.toLowerCase(Locale.ROOT).contains(term)) continue;
            displayedMedia.add(file);
        }
        mediaSummary.setText("تم العثور على " + displayedMedia.size()
                + " ملف • اضغط على ملف لتشغيله داخل التطبيق");
        if (displayedMedia.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("لا توجد ملفات ضمن البحث الحالي • أو اختر ملفاً يدوياً");
            empty.setTextColor(Color.rgb(161,179,203));
            empty.setPadding(dp(5), dp(18), dp(5), dp(14));
            mediaRows.addView(empty);
        }
        for (int i = 0; i < Math.min(75, displayedMedia.size()); i++) {
            addMediaRow(displayedMedia.get(i));
        }
    }

    private void queryMedia(Uri collection, boolean video, List<PhoneMedia> files) {
        String[] columns = {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_ADDED};
        try (Cursor cursor = getContentResolver().query(collection, columns, null, null,
                MediaStore.MediaColumns.DATE_ADDED + " DESC")) {
            if (cursor == null) return;
            int idIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
            int nameIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME);
            int sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE);
            int dateIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED);
            while (cursor.moveToNext() && cursor.getPosition() < 120) {
                long id = cursor.getLong(idIdx);
                String name = cursor.getString(nameIdx);
                long size = cursor.getLong(sizeIdx);
                long date = cursor.getLong(dateIdx);
                Uri fileUri = Uri.withAppendedPath(collection, Long.toString(id));
                files.add(new PhoneMedia(fileUri, name == null ? "ملف بدون اسم" : name, size, date, video));
            }
        } catch (Exception ignored) {
            // When the user grants only some media, display only what is accessible.
        }
    }

    private static class PhoneMedia {
        final Uri uri;
        final String name;
        final long bytes;
        final long date;
        final boolean video;
        PhoneMedia(Uri uri, String name, long bytes, long date, boolean video) {
            this.uri = uri; this.name = name; this.bytes = bytes; this.date = date; this.video = video;
        }
    }

    private void addMediaRow(PhoneMedia file) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(9), 0, dp(9));
        TextView title = new TextView(this);
        title.setText((file.video ? "🎬  " : "🎵  ") + file.name + "\n"
                + String.format(Locale.ROOT, "%.1f MB", file.bytes / 1048576.0));
        title.setTextColor(Color.WHITE);
        title.setTextSize(12);
        title.setMaxLines(3);
        row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button open = new Button(this);
        open.setText("تشغيل ▶");
        open.setAllCaps(false);
        open.setOnClickListener(v -> openMedia(file.uri, file.video ? "video/*" : "audio/*"));
        TouchEffects.apply(open);
        row.addView(open, new LinearLayout.LayoutParams(dp(93), dp(48)));
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.argb(85,105,226,202)),
                new ColorDrawable(Color.TRANSPARENT), null));
        row.setOnClickListener(v -> openMedia(file.uri, file.video ? "video/*" : "audio/*"));
        mediaRows.addView(row);
        View separator = new View(this);
        separator.setBackgroundColor(Color.rgb(49, 68, 93));
        mediaRows.addView(separator, new LinearLayout.LayoutParams(-1, dp(1)));
    }

    private void openMedia(Uri uri, String mimeType) {
        try {
            Intent player = new Intent(this, PlayerActivity.class);
            player.putExtra(PlayerActivity.EXTRA_URI, uri.toString());
            boolean isVideo = mimeType != null && mimeType.startsWith("video/");
            player.putExtra(PlayerActivity.EXTRA_VIDEO, isVideo);
            player.putExtra(PlayerActivity.EXTRA_NAME, resolveMediaName(uri));
            player.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            int selected = -1;
            ArrayList<String> uris = new ArrayList<>();
            ArrayList<String> names = new ArrayList<>();
            ArrayList<String> kinds = new ArrayList<>();
            for (int i = 0; i < Math.min(75, displayedMedia.size()); i++) {
                PhoneMedia item = displayedMedia.get(i);
                if (uri.equals(item.uri)) selected = i;
                uris.add(item.uri.toString());
                names.add(item.name);
                kinds.add(item.video ? "video" : "audio");
            }
            if (selected >= 0) {
                player.putStringArrayListExtra(PlayerActivity.EXTRA_URIS, uris);
                player.putStringArrayListExtra(PlayerActivity.EXTRA_NAMES, names);
                player.putStringArrayListExtra(PlayerActivity.EXTRA_KINDS, kinds);
                player.putExtra(PlayerActivity.EXTRA_INDEX, selected);
            }
            startActivity(player);
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        } catch (Exception e) {
            toast("تعذر فتح المشغّل: " + message(e));
        }
    }

    private String resolveMediaName(Uri uri) {
        for (PhoneMedia item : displayedMedia) {
            if (uri.equals(item.uri)) return item.name;
        }
        try (Cursor cursor = getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0) return cursor.getString(column);
            }
        } catch (Exception ignored) { }
        return "ملف الوسائط";
    }

    private void pickMedia() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"video/*", "audio/*"});
            startActivityForResult(i, REQ_PICK);
        } catch (Exception e) { toast("تعذر فتح منتقي الملفات"); }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && data != null && data.getData() != null) {
            String mime = getContentResolver().getType(data.getData());
            String mediaName = resolveMediaName(data.getData()).toLowerCase(Locale.ROOT);
            if (mime == null) {
                mime = mediaName.matches(".*\\.(mp4|mkv|webm|mov|avi|3gp)$") ? "video/*" : "audio/*";
            }
            openMedia(data.getData(), mime);
        }
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == REQ_MEDIA) {
            if (hasMediaPermission()) loadPhoneMedia();
            else {
                mediaSummary.setText("لم يتم منح إذن كامل للمكتبة. يمكنك استخدام «اختيار ملف» دون إذن شامل.");
                toast("يمكن اختيار الملفات يدوياً");
            }
        } else if (code == REQ_STORAGE || code == REQ_NOTIFY) {
            String link = extractUrl(urlInput.getText().toString());
            if (link != null) {
                if (code == REQ_STORAGE && (results.length == 0 || results[0] != PackageManager.PERMISSION_GRANTED)) {
                    toast("التخزين مطلوب لحفظ الملفات على أندرويد القديم");
                } else beginDownload(link);
            }
        }
    }

    private static String message(Exception e) {
        String msg = e.getMessage();
        if (TextUtils.isEmpty(msg)) return e.getClass().getSimpleName();
        return msg.length() > 170 ? msg.substring(0, 170) : msg;
    }

    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); }
}