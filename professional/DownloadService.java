package com.jack.downloader;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.text.TextUtils;

import com.yausername.ffmpeg.FFmpeg;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;

import kotlin.Unit;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class DownloadService extends Service {
    public static final String ACTION_START = "com.jack.downloader.START";
    public static final String ACTION_CANCEL = "com.jack.downloader.CANCEL";
    public static final String ACTION_STATUS = "com.jack.downloader.STATUS";
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_EXTENSION = "extension";
    public static final String EXTRA_HEIGHT = "height";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_PROGRESS = "progress";
    public static final String EXTRA_ACTIVE = "active";
    public static volatile boolean running = false;

    private static final String CHANNEL_PROGRESS = "jack_download_progress";
    private static final String CHANNEL_RESULT = "jack_download_result";
    private static final int NOTIF_PROGRESS = 1701;
    private static final int NOTIF_RESULT = 1702;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile String currentProcess;
    private volatile int progress;
    private volatile long lastShown;
    private NotificationManager notifications;

    @Override public void onCreate() {
        super.onCreate();
        notifications = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel active = new NotificationChannel(CHANNEL_PROGRESS, "عمليات التنزيل",
                    NotificationManager.IMPORTANCE_LOW);
            active.setDescription("حالة التنزيل ومدة تقدمه");
            NotificationChannel results = new NotificationChannel(CHANNEL_RESULT, "نتائج التنزيل",
                    NotificationManager.IMPORTANCE_DEFAULT);
            results.setDescription("اكتمل التنزيل أو فشل");
            notifications.createNotificationChannel(active);
            notifications.createNotificationChannel(results);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "" : intent.getAction();
        if (ACTION_CANCEL.equals(action)) {
            cancelled.set(true);
            String process = currentProcess;
            if (process != null) new Thread(() -> {
                try { YoutubeDL.getInstance().destroyProcessById(process); }
                catch (Exception ignored) { }
            }, "jack-download-cancel").start();
            updateState("جارٍ إلغاء التنزيل...", progress, true);
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action) || intent == null) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (running) return START_NOT_STICKY;

        String url = MainActivity.extractUrl(intent.getStringExtra(EXTRA_URL));
        if (url == null) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        String extension = intent.getStringExtra(EXTRA_EXTENSION);
        int height = intent.getIntExtra(EXTRA_HEIGHT, 1080);
        if (TextUtils.isEmpty(extension)) extension = "auto";
        final String requestedExtension = extension;
        running = true;
        cancelled.set(false);
        progress = 0;
        lastShown = 0;

        Notification first = buildProgressNotification("بدأ التنزيل • جارٍ التحضير", 0);
        try {
            if (Build.VERSION.SDK_INT >= 29)
                startForeground(NOTIF_PROGRESS, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else startForeground(NOTIF_PROGRESS, first);
        } catch (Exception ex) {
            running = false;
            updateState("لم يبدأ التنزيل: " + message(ex), 0, false);
            stopSelf();
            return START_NOT_STICKY;
        }
        updateState("بدأ التنزيل • يمكنك مغادرة التطبيق", 0, true);
        executor.execute(() -> runDownload(url, requestedExtension, height));
        return START_NOT_STICKY;
    }

    private Notification.Builder baseNotification(String channel) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, channel)
                : new Notification.Builder(this);
        Intent openApp = new Intent(this, MainActivity.class);
        openApp.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent open = PendingIntent.getActivity(this, 4, openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        builder.setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Jack Downloader")
                .setContentIntent(open)
                .setAutoCancel(false);
        return builder;
    }

    private Notification buildProgressNotification(String status, int pct) {
        Notification.Builder b = baseNotification(CHANNEL_PROGRESS)
                .setContentText(status)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setProgress(100, Math.max(0, Math.min(100, pct)), pct == 0);
        Intent cancel = new Intent(this, DownloadService.class);
        cancel.setAction(ACTION_CANCEL);
        PendingIntent pi = PendingIntent.getService(this, 5, cancel,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        b.addAction(android.R.drawable.ic_menu_close_clear_cancel, "إلغاء", pi);
        return b.build();
    }

    private void updateState(String status, int pct, boolean active) {
        getSharedPreferences("download_state", MODE_PRIVATE).edit()
                .putString("status", status).putInt("progress", pct)
                .putBoolean("active", active).apply();
        Intent message = new Intent(ACTION_STATUS);
        message.setPackage(getPackageName());
        message.putExtra(EXTRA_STATUS, status);
        message.putExtra(EXTRA_PROGRESS, pct);
        message.putExtra(EXTRA_ACTIVE, active);
        sendBroadcast(message);
    }

    private void showProgress(String status, int pct) {
        progress = pct;
        long now = SystemClock.elapsedRealtime();
        if (now - lastShown < 900 && pct != 100) return;
        lastShown = now;
        updateState(status, pct, true);
        try { notifications.notify(NOTIF_PROGRESS, buildProgressNotification(status, pct)); }
        catch (Exception ignored) { }
    }

    private void notifyResult(String title, String detail, boolean success) {
        Notification.Builder b = baseNotification(CHANNEL_RESULT)
                .setContentTitle(title)
                .setContentText(detail)
                .setAutoCancel(true)
                .setOngoing(false)
                .setCategory(Notification.CATEGORY_STATUS)
                .setSmallIcon(success ? android.R.drawable.stat_sys_download_done
                        : android.R.drawable.stat_notify_error);
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_DEFAULT);
        try { notifications.notify(NOTIF_RESULT, b.build()); }
        catch (Exception ignored) { }
    }

    private void runDownload(String url, String extension, int maxHeight) {
        File staging = null;
        String jobId = "jack_" + UUID.randomUUID().toString();
        boolean succeeded = false;
        String lastFailure = "لم ينجح التنزيل من المصدر المحدد";
        int attempts = isAudio(extension) ? 2 : 3;
        try {
            YoutubeDL.getInstance().init(getApplicationContext());
            FFmpeg.getInstance().init(getApplicationContext());
            if (cancelled.get()) return;

            File parent = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (parent == null) parent = getFilesDir();
            staging = new File(parent, jobId);
            if (!staging.mkdirs() && !staging.isDirectory())
                throw new IOException("تعذّر إنشاء مجلد التنزيل المؤقت");

            for (int attempt = 0; attempt < attempts && !cancelled.get(); attempt++) {
                File attemptDir = new File(staging, "attempt-" + (attempt + 1));
                if (!attemptDir.mkdirs() && !attemptDir.isDirectory())
                    throw new IOException("تعذّر تجهيز مساحة التنزيل");

                String processId = jobId + "_" + (attempt + 1);
                currentProcess = processId;
                progress = 0;
                try {
                    YoutubeDLRequest request = makeRequest(
                            url, extension, maxHeight, attemptDir, attempt);
                    showProgress(attempt == 0
                            ? "جارٍ تنزيل الفيديو أو الصوت..."
                            : "جارٍ تجربة طريقة تنزيل بديلة (" + (attempt + 1) + "/" + attempts + ")...", 0);

                    YoutubeDL.getInstance().execute(request, processId, (percent, etaSeconds, line) -> {
                        if (cancelled.get()) return Unit.INSTANCE;
                        int pct = Math.max(0, Math.min(100, Math.round(percent)));
                        long eta = Math.max(0L, etaSeconds);
                        showProgress(String.format(Locale.ROOT,
                                "جارٍ التنزيل %d%% • متبقٍ نحو %d ثانية", pct, eta), pct);
                        return Unit.INSTANCE;
                    });

                    if (cancelled.get()) return;

                    File[] downloaded = attemptDir.listFiles((folder, name) -> {
                        String n = name.toLowerCase(Locale.ROOT);
                        return !n.endsWith(".part") && !n.endsWith(".ytdl")
                                && !n.endsWith(".temp") && !n.endsWith(".json")
                                && !n.endsWith(".jpg") && !n.endsWith(".jpeg")
                                && !n.endsWith(".png") && !n.endsWith(".webp");
                    });
                    if (downloaded == null || downloaded.length == 0)
                        throw new IOException("لم ينتج ملف مكتمل من رابط الفيديو");

                    showProgress("اكتمل الاستلام • جارٍ حفظ الملف في الهاتف...", 99);
                    int saved = 0;
                    for (File file : downloaded) {
                        if (cancelled.get()) return;
                        if (file.isFile() && file.length() > 0) {
                            savePublicDownload(file);
                            saved++;
                        }
                    }
                    if (saved == 0) throw new IOException("لم يعثر على ملف نهائي صالح");
                    succeeded = true;
                    progress = 100;
                    getSharedPreferences("download_state", MODE_PRIVATE)
                            .edit().remove("last_error").apply();
                    String result = attempt > 0
                            ? "✓ اكتمل التنزيل بطريقة بديلة • تحقّق من صيغة الملف المحفوظ"
                            : "✓ اكتمل التنزيل • محفوظ في Download/Jack Downloader";
                    updateState(result, 100, false);
                    break;
                } catch (Exception problem) {
                    if (cancelled.get()) return;
                    lastFailure = friendlyFailure(problem);
                    if (attempt + 1 < attempts && canFallback(problem)) {
                        showProgress("تعذّرت الطريقة الحالية؛ أجرّب صيغة تنزيل أبسط...", 0);
                    } else {
                        break;
                    }
                } finally {
                    currentProcess = null;
                    deleteTree(attemptDir);
                }
            }
            if (!succeeded && !cancelled.get())
                throw new IOException(lastFailure);
        } catch (Exception ex) {
            if (!cancelled.get()) {
                String error = friendlyFailure(ex);
                getSharedPreferences("download_state", MODE_PRIVATE)
                        .edit().putString("last_error", error).apply();
                updateState("فشل التنزيل: " + error, progress, false);
            }
        } finally {
            if (staging != null) deleteTree(staging);
            currentProcess = null;
            boolean wasCancelled = cancelled.get();
            running = false;
            if (wasCancelled) updateState("تم إلغاء التنزيل", 0, false);
            try { stopForeground(STOP_FOREGROUND_REMOVE); }
            catch (Exception ignored) { }
            if (succeeded) {
                notifyResult("اكتمل التنزيل ✓", "افتح Download/Jack Downloader", true);
            } else if (!wasCancelled) {
                notifyResult("لم يكتمل التنزيل", "افتح التطبيق لمعرفة السبب المحدّد", false);
            }
            stopSelf();
        }
    }

    private YoutubeDLRequest makeRequest(String url, String extension,
            int maxHeight, File folder, int attempt) {
        YoutubeDLRequest request = new YoutubeDLRequest(url);
        request.addOption("--no-playlist");
        request.addOption("--no-mtime");
        request.addOption("--no-overwrites");
        request.addOption("--retries", 6);
        request.addOption("--fragment-retries", 6);
        request.addOption("--extractor-retries", 3);
        request.addOption("--socket-timeout", 25);
        request.addOption("-o",
                new File(folder, "%(title).100B [%(id)s].%(ext)s").getAbsolutePath());

        if (isAudio(extension)) {
            if (attempt == 0) {
                request.addOption("-f", "bestaudio/best");
                request.addOption("--extract-audio");
                request.addOption("--audio-format", extension);
                request.addOption("--audio-quality", "2");
            } else {
                // FFmpeg can fail on certain devices; preserve a playable audio
                // file rather than reject the download solely over conversion.
                request.addOption("-f", "bestaudio[ext=m4a]/bestaudio/best");
            }
            return request;
        }

        String bound = maxHeight > 0 ? "[height<=" + maxHeight + "]" : "";
        if ("auto".equals(extension)) {
            if (attempt == 0) {
                // MP4 video+M4A audio if available. No forced re-encoding.
                request.addOption("-f", "bv" + bound + "[ext=mp4]+ba[ext=m4a]/"
                        + "b" + bound + "[ext=mp4]/b" + bound + "/best");
                request.addOption("--merge-output-format", "mp4");
            } else if (attempt == 1) {
                // Many social sites expose a complete video+audio file,
                // which avoids the FFmpeg post-processing step entirely.
                request.addOption("-f", "b" + bound + "[ext=mp4]/b" + bound + "/best");
            } else {
                request.addOption("-f", "bv" + bound + "+ba/b" + bound + "/best");
            }
            return request;
        }

        if (attempt == 0) {
            if ("mp4".equals(extension)) {
                request.addOption("-f", "bv" + bound + "[ext=mp4]+ba[ext=m4a]/"
                        + "b" + bound + "[ext=mp4]/best[ext=mp4]");
                request.addOption("--merge-output-format", "mp4");
            } else {
                request.addOption("-f", "bv" + bound + "+ba/b" + bound + "/best");
                request.addOption("--merge-output-format", extension);
                request.addOption("--remux-video", extension);
            }
        } else if (attempt == 1) {
            request.addOption("-f", "b" + bound + "/best");
        } else {
            request.addOption("-f", "bv" + bound + "+ba/b" + bound + "/best");
        }
        return request;
    }

    private static boolean canFallback(Exception error) {
        if (error instanceof InterruptedException) return false;
        String m = String.valueOf(error.getMessage()).toLowerCase(Locale.ROOT);
        return !m.contains("login required")
                && !m.contains("sign in")
                && !m.contains("private video")
                && !m.contains("http error 403")
                && !m.contains("http error 429")
                && !m.contains("drm")
                && !m.contains("unsupported url")
                && !m.contains("permission denied")
                && !m.contains("no space left")
                && !m.contains("تعذّر حفظ");
    }

    private static String friendlyFailure(Exception exception) {
        String raw = exception.getMessage();
        if (TextUtils.isEmpty(raw)) raw = exception.getClass().getSimpleName();
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.contains("login required") || lower.contains("sign in")
                || lower.contains("authentication") || lower.contains("private video")
                || lower.contains("cookies")) {
            return "الموقع يطلب تسجيل الدخول أو أن الفيديو خاص. حاول رابطاً عاماً متاحاً.";
        }
        if (lower.contains("drm") || lower.contains("protected content")) {
            return "المحتوى محمي بنظام DRM ولا يمكن تنزيله بهذه الطريقة.";
        }
        if (lower.contains("403") || lower.contains("forbidden")) {
            return "رفض الموقع الوصول (403). قد يكون الرابط مقيداً أو يتطلب صلاحية.";
        }
        if (lower.contains("429") || lower.contains("too many requests")) {
            return "الموقع يقيّد كثرة الطلبات (429). جرّب لاحقاً.";
        }
        if (lower.contains("unsupported url") || lower.contains("unsupported site")) {
            return "هذا الرابط غير مدعوم حالياً. جرّب رابط المنشور الأصلي.";
        }
        if (lower.contains("requested format is not available")
                || lower.contains("no video formats")) {
            return "الجودة أو الصيغة غير متاحة لهذا الرابط. اختر «تلقائي» أو جودة أقل.";
        }
        if (lower.contains("ffmpeg") || lower.contains("postprocess")) {
            return "تعذّر دمج أو تحويل المقطع. جرّب الصيغة التلقائية أو M4A للصوت.";
        }
        if (lower.contains("no space left") || lower.contains("enospc")
                || lower.contains("مساحة") || lower.contains("تعذّر حفظ")) {
            return "مساحة الهاتف غير كافية أو تعذر الحفظ. حرّر مساحة وأعد المحاولة.";
        }
        if (lower.contains("timed out") || lower.contains("timeout")
                || lower.contains("connection reset") || lower.contains("network is unreachable")) {
            return "انقطع الاتصال أو انتهت المهلة. افحص الإنترنت وأعد المحاولة.";
        }
        if (lower.contains("http error 404")) return "لم يعد الفيديو متاحاً على الرابط (404).";
        String technical = raw;
        int idx = Math.max(technical.lastIndexOf("ERROR:"), technical.lastIndexOf("Error:"));
        if (idx >= 0) technical = technical.substring(idx);
        technical = technical.split("\\r?\\n")[0].trim();
        technical = technical.replaceAll("https?://\\S+", "[رابط مخفي]");
        technical = technical.replaceAll("\\s+", " ");
        if (technical.length() > 250) technical = technical.substring(0, 250);
        if (TextUtils.isEmpty(technical)) technical = "خطأ غير معروف";
        return technical + " • جرّب وضع «تلقائي» أو رابطاً عاماً آخر.";
    }

    private void savePublicDownload(File source) throws IOException {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, source.getName());
            values.put(MediaStore.Downloads.MIME_TYPE, mimeFor(source.getName()));
            values.put(MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/Jack Downloader");
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("تعذر إنشاء الملف في مجلد التنزيلات");
            boolean copied = false;
            try {
                try (InputStream input = new FileInputStream(source);
                     OutputStream output = getContentResolver().openOutputStream(uri)) {
                    if (output == null) throw new IOException("تعذر فتح الملف للكتابة");
                    copyStream(input, output);
                }
                values.clear();
                values.put(MediaStore.Downloads.IS_PENDING, 0);
                getContentResolver().update(uri, values, null, null);
                copied = true;
            } finally {
                if (!copied) getContentResolver().delete(uri, null, null);
            }
        } else {
            File folder = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), "Jack Downloader");
            if (!folder.exists() && !folder.mkdirs())
                throw new IOException("تعذر إنشاء مجلد التنزيلات");
            File output = findUniqueFile(folder, source.getName());
            boolean copied = false;
            try {
                try (InputStream input = new FileInputStream(source);
                     OutputStream stream = new FileOutputStream(output)) {
                    copyStream(input, stream);
                }
                copied = true;
            } finally {
                if (!copied) output.delete();
            }
            sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(output)));
        }
    }

    private void copyStream(InputStream input, OutputStream output) throws IOException {
        byte[] data = new byte[64 * 1024];
        int read;
        while ((read = input.read(data)) != -1) {
            if (cancelled.get()) throw new IOException("تم الإلغاء");
            output.write(data, 0, read);
        }
        output.flush();
    }

    private static File findUniqueFile(File folder, String name) {
        File file = new File(folder, name);
        int dot = name.lastIndexOf('.');
        String base = dot >= 0 ? name.substring(0, dot) : name;
        String suffix = dot >= 0 ? name.substring(dot) : "";
        int index = 1;
        while (file.exists()) file = new File(folder, base + " (" + (index++) + ")" + suffix);
        return file;
    }

    private static boolean isAudio(String extension) {
        return "mp3".equals(extension) || "m4a".equals(extension) ||
                "wav".equals(extension) || "flac".equals(extension) || "opus".equals(extension);
    }

    private static String mimeFor(String filename) {
        int dot = filename.lastIndexOf('.');
        String ext = (dot >= 0 ? filename.substring(dot + 1) : "").toLowerCase(Locale.ROOT);
        switch (ext) {
            case "mp4": return "video/mp4";
            case "mkv": return "video/x-matroska";
            case "webm": return "video/webm";
            case "mov": return "video/quicktime";
            case "mp3": return "audio/mpeg";
            case "m4a": return "audio/mp4";
            case "wav": return "audio/wav";
            case "flac": return "audio/flac";
            case "opus": return "audio/opus";
            default: return "application/octet-stream";
        }
    }

    private static void deleteTree(File file) {
        if (file.isDirectory()) {
            File[] child = file.listFiles();
            if (child != null) for (File one : child) deleteTree(one);
        }
        file.delete();
    }

    private static String message(Exception error) {
        String msg = error.getMessage();
        if (TextUtils.isEmpty(msg)) return error.getClass().getSimpleName();
        return msg.length() > 170 ? msg.substring(0, 170) : msg;
    }

    @Override public void onTimeout(int startId, int foregroundServiceType) {
        cancelled.set(true);
        String process = currentProcess;
        if (process != null) {
            new Thread(() -> {
                try { YoutubeDL.getInstance().destroyProcessById(process); }
                catch (Exception ignored) { }
            }).start();
        }
        running = false;
        updateState("انتهت مدة التنزيل المسموحة في الخلفية", progress, false);
        stopSelf();
    }

    @Override public void onDestroy() {
        executor.shutdownNow();
        running = false;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}