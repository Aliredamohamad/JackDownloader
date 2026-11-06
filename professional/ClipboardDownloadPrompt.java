package com.jack.downloader;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.net.URI;
import java.util.Locale;

/**
 * Consent-first download suggestion. Only reads clipboard while MainActivity
 * is resumed and has focus. Android 10+ disallows background clipboard access.
 * It never downloads or sends any copied link unless the user taps Download.
 */
public final class ClipboardDownloadPrompt implements ClipboardManager.OnPrimaryClipChangedListener {
    public interface Actions {
        void download(String url);
        void choose(String url);
    }

    private static final String PREFS = "clipboard_prompts";
    private static final String LAST_SEEN = "last_clip_signature";
    private static final long DISMISS_DELAY_MS = 11500L;

    private final Activity activity;
    private final Actions actions;
    private final ClipboardManager clipboard;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    private final FrameLayout root;
    private View banner;
    private boolean listening;
    private String lastLinkShown = "";

    private final Runnable dismissAction = this::dismiss;

    public ClipboardDownloadPrompt(Activity activity, Actions actions) {
        this.activity = activity;
        this.actions = actions;
        this.clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        this.prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.root = activity.findViewById(android.R.id.content);
    }

    public void resume() {
        if (clipboard != null && !listening) {
            clipboard.addPrimaryClipChangedListener(this);
            listening = true;
        }
        // Clipboard reads are allowed only when the activity holds focus.
        main.postDelayed(this::checkNow, 260L);
    }

    public void pause() {
        if (listening && clipboard != null) {
            clipboard.removePrimaryClipChangedListener(this);
            listening = false;
        }
        dismiss();
    }

    public void destroy() {
        pause();
        main.removeCallbacksAndMessages(null);
    }

    @Override public void onPrimaryClipChanged() {
        main.post(this::checkNow);
    }

    public void checkNow() {
        if (!listening || !activity.hasWindowFocus() || clipboard == null) return;
        try {
            if (!clipboard.hasPrimaryClip()) return;
            ClipData data = clipboard.getPrimaryClip();
            if (data == null || data.getItemCount() < 1) return;

            CharSequence value = data.getItemAt(0).coerceToText(activity);
            if (value == null) return;
            String url = validWebUrl(value.toString());
            if (url == null) return;

            long timestamp = 0L;
            if (Build.VERSION.SDK_INT >= 26) {
                ClipDescription desc = data.getDescription();
                if (desc != null) timestamp = desc.getTimestamp();
            }
            String signature = url + "|" + timestamp;
            if (signature.equals(prefs.getString(LAST_SEEN, ""))) return;

            prefs.edit().putString(LAST_SEEN, signature).apply();
            offer(url, false);
        } catch (SecurityException ignored) {
            // Clipboard access may be restricted by the system or device policy.
        } catch (Exception ignored) {
        }
    }

    public void offerFromShare(String url) {
        String safe = validWebUrl(url);
        if (safe == null) return;
        offer(safe, true);
    }

    private static String validWebUrl(String text) {
        String url = MainActivity.extractUrl(text);
        if (url == null) return null;
        try {
            URI u = new URI(url);
            String scheme = u.getScheme();
            if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) return null;
            if (TextUtils.isEmpty(u.getHost())) return null;
            return url;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void offer(String url, boolean shared) {
        if (root == null) return;
        if (url.equals(lastLinkShown) && banner != null) {
            resetExpiry();
            return;
        }
        lastLinkShown = url;
        dismiss();

        LinearLayout surface = new LinearLayout(activity);
        surface.setOrientation(LinearLayout.VERTICAL);
        surface.setPadding(dp(15), dp(12), dp(15), dp(12));
        surface.setBackground(background());
        surface.setElevation(dp(14));
        surface.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        TextView title = new TextView(activity);
        title.setText(shared ? "تم استقبال رابط • هل تريد تنزيله؟"
                : "تم نسخ رابط • هل تريد تنزيله؟");
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(Color.WHITE);
        title.setTextSize(15);
        surface.addView(title);

        TextView details = new TextView(activity);
        details.setText("رابط: " + shortHost(url) + "  •  لن يبدأ التنزيل دون موافقتك");
        details.setTextColor(Color.rgb(179, 202, 221));
        details.setTextSize(12);
        details.setMaxLines(2);
        details.setPadding(0, dp(3), 0, dp(10));
        surface.addView(details);

        LinearLayout buttons = new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        surface.addView(buttons);

        Button download = button("تنزيل الآن", true);
        download.setOnClickListener(v -> {
            dismiss();
            actions.download(url);
        });
        buttons.addView(download, weight());

        Button choose = button("اختيار الصيغة", false);
        choose.setOnClickListener(v -> {
            dismiss();
            actions.choose(url);
        });
        buttons.addView(choose, weight());

        Button close = button("✕", false);
        close.setContentDescription("إغلاق اقتراح التنزيل");
        close.setOnClickListener(v -> dismiss());
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(dp(44), dp(45));
        closeParams.leftMargin = dp(4);
        buttons.addView(close, closeParams);

        banner = surface;
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
        );
        params.setMargins(dp(12), 0, dp(12), dp(19));
        root.addView(surface, params);
        surface.setAlpha(0f);
        surface.setTranslationY(dp(75));
        surface.animate().alpha(1f).translationY(0).setDuration(230L).start();
        resetExpiry();
    }

    private void resetExpiry() {
        main.removeCallbacks(dismissAction);
        main.postDelayed(dismissAction, DISMISS_DELAY_MS);
    }

    public void dismiss() {
        main.removeCallbacks(dismissAction);
        View shown = banner;
        banner = null;
        if (shown == null) return;
        shown.animate().cancel();
        if (shown.getParent() instanceof ViewGroup) {
            ((ViewGroup) shown.getParent()).removeView(shown);
        }
    }

    private GradientDrawable background() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.rgb(17, 31, 51));
        g.setCornerRadius(dp(17));
        g.setStroke(dp(1), Color.rgb(94, 204, 202));
        return g;
    }

    private Button button(String text, boolean primary) {
        Button b = new Button(activity);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(12);
        b.setTextColor(primary ? Color.rgb(9, 27, 34) : Color.WHITE);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(11));
        bg.setColor(primary ? Color.rgb(105, 226, 202) : Color.rgb(41, 66, 93));
        b.setBackground(bg);
        b.setPadding(0, 0, 0, 0);
        TouchEffects.apply(b);
        return b;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(45), 1f);
        p.leftMargin = dp(4);
        return p;
    }

    private static String shortHost(String url) {
        try {
            String host = new URI(url).getHost();
            if (host == null) return "رابط ويب";
            if (host.startsWith("www.")) host = host.substring(4);
            return host.length() > 33 ? host.substring(0, 33) : host;
        } catch (Exception ignored) {
            return "رابط ويب";
        }
    }

    private int dp(int n) {
        return Math.round(n * activity.getResources().getDisplayMetrics().density);
    }
}
