package com.dcf1007.androidpolaris.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

/** Shared native dark-theme styling for the programmatic Android UI. */
final class UiStyle {
    static final int BACKGROUND = Color.rgb(15, 17, 21);
    static final int STAGE_BACKGROUND = Color.rgb(9, 10, 13);
    static final int PANEL = Color.rgb(24, 27, 34);
    static final int PANEL_2 = Color.rgb(32, 36, 45);
    static final int TEXT = Color.rgb(243, 245, 247);
    static final int MUTED = Color.rgb(174, 182, 194);
    static final int BORDER = Color.rgb(52, 58, 70);
    static final int ACCENT = Color.rgb(134, 183, 255);
    static final int ACCENT_TEXT = Color.rgb(6, 16, 31);
    static final int DANGER = Color.rgb(255, 138, 138);
    static final int DISABLED_FILL = Color.rgb(58, 63, 73);
    static final int DISABLED_TEXT = Color.rgb(154, 163, 176);

    private UiStyle() { }

    static TextView text(Context context, String value, int sp, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(sp);
        view.setLineSpacing(0.0f, 1.08f);
        view.setGravity(Gravity.START);
        view.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        return view;
    }

    static TextView label(Context context, String value) {
        TextView view = text(context, value, 13, MUTED, false);
        view.setPadding(dp(context, 2), dp(context, 4), dp(context, 2), dp(context, 2));
        return view;
    }

    static TextView note(Context context, String value) {
        TextView view = text(context, value, 12, MUTED, false);
        view.setPadding(0, dp(context, 4), 0, dp(context, 8));
        return view;
    }

    static TextView textArea(Context context, String value, boolean monospace) {
        TextView view = text(context, value, 12, value == null || value.isEmpty() ? MUTED : TEXT, false);
        view.setPadding(dp(context, 10), dp(context, 9), dp(context, 10), dp(context, 9));
        view.setBackground(roundedBackground(context, Color.rgb(17, 20, 26), BORDER, 10));
        if (monospace) view.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
        return view;
    }

    static Button button(Context context, String value, boolean primary, boolean danger) {
        Button button = new Button(context);
        button.setAllCaps(false);
        button.setText(value);
        button.setTextSize(15);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(context, 42));
        button.setPadding(dp(context, 10), dp(context, 8), dp(context, 10), dp(context, 8));
        if (primary) {
            button.setTextColor(ACCENT_TEXT);
            button.setBackground(roundedBackground(context, ACCENT, Color.TRANSPARENT, 10));
        } else if (danger) {
            button.setTextColor(DANGER);
            button.setBackground(roundedBackground(context, Color.TRANSPARENT, Color.argb(120, 255, 138, 138), 10));
        } else {
            button.setTextColor(TEXT);
            button.setBackground(roundedBackground(context, PANEL_2, BORDER, 10));
        }
        return button;
    }

    static void applyPrimaryButtonState(Context context, Button button, boolean enabled) {
        button.setEnabled(enabled);
        button.setTextColor(enabled ? ACCENT_TEXT : DISABLED_TEXT);
        button.setBackground(roundedBackground(context, enabled ? ACCENT : DISABLED_FILL, Color.TRANSPARENT, 10));
    }

    static void styleEditText(Context context, EditText editText) {
        editText.setSingleLine(true);
        editText.setTextColor(TEXT);
        editText.setHintTextColor(Color.rgb(128, 132, 140));
        editText.setTextSize(15);
        editText.setPadding(dp(context, 10), dp(context, 8), dp(context, 10), dp(context, 8));
        editText.setBackground(roundedBackground(context, PANEL_2, BORDER, 10));
    }

    static GradientDrawable roundedBackground(Context context, int fillColor, int strokeColor, int cornerDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(fillColor);
        drawable.setCornerRadius(dp(context, cornerDp));
        if (strokeColor != Color.TRANSPARENT) drawable.setStroke(dp(context, 1), strokeColor);
        return drawable;
    }

    static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
