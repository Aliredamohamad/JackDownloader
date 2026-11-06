package com.jack.downloader;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

/** Shared press feedback: visible ripple, light spring and subtle haptic. */
public final class TouchEffects {
    private TouchEffects() { }

    public static void installRecursively(View view) {
        if (view instanceof Button) {
            apply(view);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                installRecursively(group.getChildAt(i));
            }
        }
    }

    public static void apply(View button) {
        if (button == null || Boolean.TRUE.equals(button.getTag(R.id.touch_effect_tag))) return;
        button.setTag(R.id.touch_effect_tag, true);
        try {
            Drawable source = button.getBackground();
            if (source != null) {
                Drawable content = source.getConstantState() != null
                        ? source.getConstantState().newDrawable().mutate() : source.mutate();
                GradientDrawable mask = new GradientDrawable();
                mask.setColor(Color.WHITE);
                mask.setCornerRadius(28f * button.getResources().getDisplayMetrics().density);
                button.setBackground(new RippleDrawable(
                        ColorStateList.valueOf(Color.argb(78, 255, 255, 255)),
                        content, mask));
            }
        } catch (Exception ignored) { }

        button.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (v.isEnabled()) {
                        v.animate().cancel();
                        v.animate().scaleX(0.955f).scaleY(0.955f).setDuration(95).start();
                        v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.animate().cancel();
                    v.animate().scaleX(1f).scaleY(1f).setDuration(170).start();
                    break;
                default: break;
            }
            return false;
        });
    }
}
