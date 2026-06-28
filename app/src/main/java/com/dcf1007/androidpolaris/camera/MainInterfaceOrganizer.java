package com.dcf1007.androidpolaris.camera;

import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.Locale;

/**
 * Startup UI normalizer for MainActivity's programmatic layout.
 *
 * <p>MainActivity still owns the screen layout. This class performs one explicit, readable pass over
 * that layout after creation: it standardizes typography, moves the video-fit row to the video
 * alignment section, creates the disabled UVC hardware section that must be visible before query,
 * enables consistent collapsible category headers, and removes visible text that no longer matches
 * the current direct-libuvc/query-first behavior.</p>
 */
public final class MainInterfaceOrganizer {
    static final String HARDWARE_CONTROLS_TAG = "android-polaris-uvc-hardware-controls";

    private static final int COLOR_TEXT = Color.rgb(243, 245, 247);
    private static final int COLOR_MUTED = Color.rgb(174, 182, 194);
    private static final int HEADER_SIZE_SP = 13;
    private static final int BODY_SIZE_SP = 12;
    private static final int SMALL_SIZE_SP = 11;

    private static final String[] COLLAPSIBLE_HEADER_TITLES = {
            "ALIGNMENT", "VISIBILITY", "POLARIS ALIGNMENT", "STATUS", "READOUTS", "DEBUG LOG"
    };

    private MainInterfaceOrganizer() { }

    /** Runs once per activity render pass. The method is idempotent and safe to call again. */
    public static void organize(View root) {
        LinearLayout controlsColumn = findControlsColumn(root);
        if (controlsColumn == null) return;

        replaceObsoleteVisibleText(root);
        relabelOpenCameraButton(controlsColumn);
        moveVideoFitRowToAlignmentPanel(controlsColumn);
        ensureDisabledHardwareControlsAreVisible(controlsColumn);
        installConsistentCollapsibleHeaders(controlsColumn);
        standardizeTypography(root);
    }

    /** Returns the camera panel used by the live UVC controls. */
    public static LinearLayout findFirstPanelInScrollableControls(View root) {
        LinearLayout controlsColumn = findControlsColumn(root);
        if (controlsColumn == null) return null;
        for (int index = 0; index < controlsColumn.getChildCount(); index++) {
            View child = controlsColumn.getChildAt(index);
            if (child instanceof LinearLayout) return (LinearLayout) child;
        }
        return null;
    }

    private static LinearLayout findControlsColumn(View root) {
        ScrollView scrollView = findFirstScrollView(root);
        if (scrollView == null || scrollView.getChildCount() == 0) return null;
        View child = scrollView.getChildAt(0);
        return child instanceof LinearLayout ? (LinearLayout) child : null;
    }

    private static ScrollView findFirstScrollView(View view) {
        if (view instanceof ScrollView) return (ScrollView) view;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            ScrollView found = findFirstScrollView(group.getChildAt(index));
            if (found != null) return found;
        }
        return null;
    }

    private static void replaceObsoleteVisibleText(View root) {
        replaceText(root,
                "Open the USB OTG UVC camera, then use the alignment controls to match the video to the reticle. The reticle is native Canvas geometry generated from the full drawing.",
                "Open/query the USB UVC camera, select a stream mode, then start preview. Use video alignment controls to match the preview to the native reticle.");
        replaceText(root,
                "Native Android build. USB/UVC preview uses the AUSBC backend; the reticle is native Canvas geometry. No SVG, WebView or Camera2 preview path is used.",
                "Native Android build. USB/UVC preview uses direct libuvc. The reticle is native Canvas geometry. No WebView, SVG runtime, or Camera2 preview path is used.");
        replaceText(root, "Open USB UVC camera", "Open/query USB UVC camera");
        replaceText(root, "Press Open USB UVC camera to load the UVC backend and request USB permission.",
                "Open/query the UVC camera to list stream modes and hardware controls.");
    }

    private static void relabelOpenCameraButton(LinearLayout controlsColumn) {
        replaceText(controlsColumn, "Open USB UVC camera", "Open/query USB UVC camera");
    }

    private static void moveVideoFitRowToAlignmentPanel(LinearLayout controlsColumn) {
        LinearLayout cameraPanel = childLinearLayoutAt(controlsColumn, 0);
        LinearLayout alignmentPanel = childLinearLayoutAt(controlsColumn, 1);
        if (cameraPanel == null || alignmentPanel == null) return;

        View videoFitRow = findDirectChildContainingCameraFitControls(cameraPanel);
        if (videoFitRow == null || videoFitRow.getParent() != cameraPanel) return;

        cameraPanel.removeView(videoFitRow);
        int alignmentHeaderIndex = findHeaderIndex(alignmentPanel, "ALIGNMENT");
        alignmentPanel.addView(videoFitRow, alignmentHeaderIndex < 0 ? 0 : alignmentHeaderIndex + 1);
    }

    private static void ensureDisabledHardwareControlsAreVisible(LinearLayout controlsColumn) {
        LinearLayout cameraPanel = childLinearLayoutAt(controlsColumn, 0);
        if (cameraPanel == null || hasHardwareControlsPanel(cameraPanel)) return;
        cameraPanel.addView(createDisabledHardwareControlsPlaceholder(cameraPanel),
                Math.min(3, cameraPanel.getChildCount()), new LinearLayout.LayoutParams(-1, -2));
    }

    private static LinearLayout createDisabledHardwareControlsPlaceholder(View parentView) {
        LinearLayout panel = new LinearLayout(parentView.getContext());
        panel.setTag(HARDWARE_CONTROLS_TAG);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(0, dp(parentView, 8), 0, dp(parentView, 8));

        final TextView title = header(parentView, "UVC HARDWARE CONTROLS  ▲");
        panel.addView(title, new LinearLayout.LayoutParams(-1, -2));

        final LinearLayout body = new LinearLayout(parentView.getContext());
        body.setOrientation(LinearLayout.VERTICAL);
        panel.addView(body, new LinearLayout.LayoutParams(-1, -2));
        title.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                boolean collapsed = body.getVisibility() == View.VISIBLE;
                body.setVisibility(collapsed ? View.GONE : View.VISIBLE);
                title.setText(collapsed ? "UVC HARDWARE CONTROLS  ▼" : "UVC HARDWARE CONTROLS  ▲");
            }
        });

        body.addView(label(parentView, "Stream type"), new LinearLayout.LayoutParams(-1, -2));
        Spinner streamTypeSpinner = new Spinner(parentView.getContext());
        streamTypeSpinner.setEnabled(false);
        body.addView(streamTypeSpinner, new LinearLayout.LayoutParams(-1, -2));
        body.addView(label(parentView, "Resolution"), new LinearLayout.LayoutParams(-1, -2));
        Spinner resolutionSpinner = new Spinner(parentView.getContext());
        resolutionSpinner.setEnabled(false);
        body.addView(resolutionSpinner, new LinearLayout.LayoutParams(-1, -2));
        body.addView(label(parentView, "FPS"), new LinearLayout.LayoutParams(-1, -2));
        Spinner fpsSpinner = new Spinner(parentView.getContext());
        fpsSpinner.setEnabled(false);
        body.addView(fpsSpinner, new LinearLayout.LayoutParams(-1, -2));
        body.addView(disabledButton(parentView, "Start selected stream"), new LinearLayout.LayoutParams(-1, -2));
        body.addView(disabledSlider(parentView, "Brightness"));
        body.addView(disabledSlider(parentView, "Contrast"));
        body.addView(disabledSlider(parentView, "Gain"));
        body.addView(disabledSlider(parentView, "Exposure"));

        CheckBox autoExposure = new CheckBox(parentView.getContext());
        autoExposure.setText("Auto exposure");
        autoExposure.setEnabled(false);
        body.addView(autoExposure, new LinearLayout.LayoutParams(-1, -2));

        TextView summary = label(parentView, "Open/query the UVC camera to list stream modes and hardware controls.");
        summary.setTextColor(COLOR_MUTED);
        body.addView(summary, new LinearLayout.LayoutParams(-1, -2));
        return panel;
    }

    private static Button disabledButton(View parentView, String text) {
        Button button = new Button(parentView.getContext());
        button.setAllCaps(false);
        button.setText(text);
        button.setTextSize(BODY_SIZE_SP);
        button.setEnabled(false);
        return button;
    }

    private static LinearLayout disabledSlider(View parentView, String labelText) {
        LinearLayout group = new LinearLayout(parentView.getContext());
        group.setOrientation(LinearLayout.VERTICAL);

        LinearLayout row = new LinearLayout(parentView.getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(label(parentView, labelText), new LinearLayout.LayoutParams(0, -2, 1.0f));
        Button minus = disabledButton(parentView, "−");
        TextView value = label(parentView, "—");
        value.setGravity(Gravity.CENTER);
        Button plus = disabledButton(parentView, "+");
        row.addView(minus, new LinearLayout.LayoutParams(dp(parentView, 44), -2));
        row.addView(value, new LinearLayout.LayoutParams(dp(parentView, 70), -2));
        row.addView(plus, new LinearLayout.LayoutParams(dp(parentView, 44), -2));
        group.addView(row, new LinearLayout.LayoutParams(-1, -2));

        SeekBar seekBar = new SeekBar(parentView.getContext());
        seekBar.setMax(100);
        seekBar.setProgress(50);
        seekBar.setEnabled(false);
        group.addView(seekBar, new LinearLayout.LayoutParams(-1, -2));
        return group;
    }

    private static boolean hasHardwareControlsPanel(LinearLayout cameraPanel) {
        for (int index = 0; index < cameraPanel.getChildCount(); index++) {
            if (HARDWARE_CONTROLS_TAG.equals(cameraPanel.getChildAt(index).getTag())) return true;
        }
        return false;
    }

    private static void installConsistentCollapsibleHeaders(LinearLayout controlsColumn) {
        for (int panelIndex = 0; panelIndex < controlsColumn.getChildCount(); panelIndex++) {
            View child = controlsColumn.getChildAt(panelIndex);
            if (child instanceof LinearLayout) installHeadersInPanel((LinearLayout) child);
        }
    }

    private static void installHeadersInPanel(final LinearLayout panel) {
        for (int index = 0; index < panel.getChildCount(); index++) {
            View child = panel.getChildAt(index);
            if (!(child instanceof TextView)) continue;
            final TextView header = (TextView) child;
            final String title = normalizedHeaderText(header);
            if (!isCollapsibleHeader(title) || Boolean.TRUE.equals(header.getTag())) continue;
            header.setTag(Boolean.TRUE);
            applyHeaderStyle(header, title + "  ▲");
            header.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    boolean collapsed = !header.isSelected();
                    header.setSelected(collapsed);
                    header.setText(title + (collapsed ? "  ▼" : "  ▲"));
                    setSectionBodyVisible(panel, header, !collapsed);
                }
            });
        }
    }

    private static void setSectionBodyVisible(LinearLayout panel, TextView header, boolean visible) {
        int headerIndex = panel.indexOfChild(header);
        if (headerIndex < 0) return;
        for (int index = headerIndex + 1; index < panel.getChildCount(); index++) {
            View child = panel.getChildAt(index);
            if (child instanceof TextView && isCollapsibleHeader(normalizedHeaderText((TextView) child))) break;
            child.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    private static void standardizeTypography(View view) {
        if (view instanceof TextView) {
            TextView textView = (TextView) view;
            String normalized = normalizedHeaderText(textView);
            if (isCollapsibleHeader(normalized) || normalized.startsWith("UVC HARDWARE CONTROLS")) {
                applyHeaderStyle(textView, textView.getText());
            } else {
                textView.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
                if (textView.getTextSize() > 0) textView.setTextColor(textView.isEnabled() ? COLOR_TEXT : COLOR_MUTED);
            }
            if (view instanceof Button) ((Button) view).setAllCaps(false);
            if (view instanceof CompoundButton) ((CompoundButton) view).setTextSize(BODY_SIZE_SP);
        }
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) standardizeTypography(group.getChildAt(index));
    }

    private static void applyHeaderStyle(TextView textView, CharSequence text) {
        textView.setText(text);
        textView.setTextSize(HEADER_SIZE_SP);
        textView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        textView.setTextColor(COLOR_TEXT);
        textView.setPadding(0, dp(textView, 4), 0, dp(textView, 4));
    }

    private static boolean isCollapsibleHeader(String title) {
        for (String candidate : COLLAPSIBLE_HEADER_TITLES) if (candidate.equals(title)) return true;
        return false;
    }

    private static LinearLayout childLinearLayoutAt(LinearLayout parent, int index) {
        if (parent == null || index < 0 || index >= parent.getChildCount()) return null;
        View child = parent.getChildAt(index);
        return child instanceof LinearLayout ? (LinearLayout) child : null;
    }

    private static View findDirectChildContainingCameraFitControls(LinearLayout panel) {
        for (int index = 0; index < panel.getChildCount(); index++) {
            View child = panel.getChildAt(index);
            if (containsText(child, "Video fit")
                    && containsCheckBoxText(child, "Mirror video")
                    && containsCheckBoxText(child, "Lock width/height")) return child;
        }
        return null;
    }

    private static int findHeaderIndex(LinearLayout panel, String normalizedTitle) {
        for (int index = 0; index < panel.getChildCount(); index++) {
            View child = panel.getChildAt(index);
            if (child instanceof TextView && normalizedHeaderText((TextView) child).equals(normalizedTitle)) return index;
        }
        return -1;
    }

    private static String normalizedHeaderText(TextView textView) {
        String text = String.valueOf(textView.getText()).replace("▲", "").replace("▼", "").trim();
        return text.toUpperCase(Locale.US);
    }

    private static TextView header(View parentView, String text) {
        TextView textView = new TextView(parentView.getContext());
        applyHeaderStyle(textView, text);
        return textView;
    }

    private static TextView label(View parentView, String text) {
        TextView textView = new TextView(parentView.getContext());
        textView.setText(text);
        textView.setTextSize(SMALL_SIZE_SP);
        textView.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
        textView.setTextColor(COLOR_TEXT);
        textView.setPadding(0, dp(parentView, 2), 0, dp(parentView, 2));
        return textView;
    }

    private static int dp(View view, int value) {
        return Math.round(value * view.getContext().getResources().getDisplayMetrics().density);
    }

    private static boolean containsText(View view, String wantedText) {
        if (view instanceof TextView && wantedText.contentEquals(((TextView) view).getText())) return true;
        if (!(view instanceof ViewGroup)) return false;
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) if (containsText(group.getChildAt(index), wantedText)) return true;
        return false;
    }

    private static boolean containsCheckBoxText(View view, String wantedText) {
        if (view instanceof CheckBox && wantedText.contentEquals(((CheckBox) view).getText())) return true;
        if (!(view instanceof ViewGroup)) return false;
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) if (containsCheckBoxText(group.getChildAt(index), wantedText)) return true;
        return false;
    }

    private static void replaceText(View view, String from, String to) {
        if (view instanceof TextView && from.contentEquals(((TextView) view).getText())) ((TextView) view).setText(to);
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) replaceText(group.getChildAt(index), from, to);
    }
}
