package com.dcf1007.androidpolaris.camera;

import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.Locale;

/**
 * Startup organizer for the programmatic MainActivity layout.
 *
 * <p>This keeps the static app layout consistent at launch: the camera button wording is updated,
 * video-fit controls move into video alignment, collapsible section headers are enabled, and a
 * disabled UVC hardware-controls section is visible before any camera has been queried.</p>
 */
public final class MainInterfaceOrganizer {
    static final String HARDWARE_CONTROLS_TAG = "android-polaris-uvc-hardware-controls";

    private static final String[] COLLAPSIBLE_SECTION_NAMES = {
            "ALIGNMENT", "VISIBILITY", "POLARIS ALIGNMENT", "STATUS", "READOUTS", "DEBUG LOG"
    };

    private MainInterfaceOrganizer() { }

    public static void organize(View root) {
        LinearLayout controlsColumn = findControlsColumn(root);
        if (controlsColumn == null) return;
        relabelOpenCameraButton(controlsColumn);
        moveVideoFitRowToAlignmentPanel(controlsColumn);
        ensureDisabledHardwareControlsAreVisible(controlsColumn);
        installCollapsibleSectionHeaders(controlsColumn);
    }

    public static LinearLayout findFirstPanelInScrollableControls(View root) {
        LinearLayout controlsColumn = findControlsColumn(root);
        if (controlsColumn == null) return null;
        for (int i = 0; i < controlsColumn.getChildCount(); i++) {
            View child = controlsColumn.getChildAt(i);
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
        for (int i = 0; i < group.getChildCount(); i++) {
            ScrollView found = findFirstScrollView(group.getChildAt(i));
            if (found != null) return found;
        }
        return null;
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
        int insertionIndex = findSectionHeaderIndex(alignmentPanel, "ALIGNMENT");
        alignmentPanel.addView(videoFitRow, insertionIndex < 0 ? 0 : insertionIndex + 1);
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

        TextView title = text(parentView, "UVC hardware controls  ▲", 13, true);
        panel.addView(title, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout body = new LinearLayout(parentView.getContext());
        body.setOrientation(LinearLayout.VERTICAL);
        panel.addView(body, new LinearLayout.LayoutParams(-1, -2));

        body.addView(text(parentView, "Stream mode", 11, false), new LinearLayout.LayoutParams(-1, -2));
        Spinner streamSpinner = new Spinner(parentView.getContext());
        streamSpinner.setEnabled(false);
        body.addView(streamSpinner, new LinearLayout.LayoutParams(-1, -2));

        Button startButton = new Button(parentView.getContext());
        startButton.setAllCaps(false);
        startButton.setText("Start selected stream");
        startButton.setTextSize(12);
        startButton.setEnabled(false);
        body.addView(startButton, new LinearLayout.LayoutParams(-1, -2));

        body.addView(disabledSlider(parentView, "Brightness"));
        body.addView(disabledSlider(parentView, "Contrast"));
        body.addView(disabledSlider(parentView, "Gain"));
        body.addView(disabledSlider(parentView, "Exposure"));

        CheckBox autoExposure = new CheckBox(parentView.getContext());
        autoExposure.setText("Auto exposure");
        autoExposure.setTextColor(Color.rgb(243, 245, 247));
        autoExposure.setTextSize(12);
        autoExposure.setEnabled(false);
        body.addView(autoExposure, new LinearLayout.LayoutParams(-1, -2));

        TextView summary = text(parentView, "Open/query the UVC camera to list stream modes and controls.", 11, false);
        summary.setTextColor(Color.rgb(180, 190, 203));
        body.addView(summary, new LinearLayout.LayoutParams(-1, -2));
        return panel;
    }

    private static LinearLayout disabledSlider(View parentView, String label) {
        LinearLayout group = new LinearLayout(parentView.getContext());
        group.setOrientation(LinearLayout.VERTICAL);

        LinearLayout row = new LinearLayout(parentView.getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(text(parentView, label, 11, false), new LinearLayout.LayoutParams(0, -2, 1.0f));
        TextView value = text(parentView, "—", 11, false);
        value.setGravity(Gravity.END);
        row.addView(value, new LinearLayout.LayoutParams(0, -2, 1.0f));
        group.addView(row, new LinearLayout.LayoutParams(-1, -2));

        SeekBar seekBar = new SeekBar(parentView.getContext());
        seekBar.setMax(100);
        seekBar.setProgress(50);
        seekBar.setEnabled(false);
        group.addView(seekBar, new LinearLayout.LayoutParams(-1, -2));
        return group;
    }

    private static boolean hasHardwareControlsPanel(LinearLayout cameraPanel) {
        for (int i = 0; i < cameraPanel.getChildCount(); i++) {
            if (HARDWARE_CONTROLS_TAG.equals(cameraPanel.getChildAt(i).getTag())) return true;
        }
        return false;
    }

    static void removeHardwareControlsPlaceholder(LinearLayout cameraPanel) {
        for (int i = cameraPanel.getChildCount() - 1; i >= 0; i--) {
            if (HARDWARE_CONTROLS_TAG.equals(cameraPanel.getChildAt(i).getTag())) cameraPanel.removeViewAt(i);
        }
    }

    private static LinearLayout childLinearLayoutAt(LinearLayout parent, int index) {
        if (parent == null || index < 0 || index >= parent.getChildCount()) return null;
        View child = parent.getChildAt(index);
        return child instanceof LinearLayout ? (LinearLayout) child : null;
    }

    private static View findDirectChildContainingCameraFitControls(LinearLayout panel) {
        for (int i = 0; i < panel.getChildCount(); i++) {
            View child = panel.getChildAt(i);
            if (containsText(child, "Video fit")
                    && containsCheckBoxText(child, "Mirror video")
                    && containsCheckBoxText(child, "Lock width/height")) {
                return child;
            }
        }
        return null;
    }

    private static int findSectionHeaderIndex(LinearLayout panel, String normalizedTitle) {
        for (int i = 0; i < panel.getChildCount(); i++) {
            View child = panel.getChildAt(i);
            if (child instanceof TextView && normalizedHeaderText((TextView) child).equals(normalizedTitle)) return i;
        }
        return -1;
    }

    private static void installCollapsibleSectionHeaders(LinearLayout controlsColumn) {
        for (int panelIndex = 0; panelIndex < controlsColumn.getChildCount(); panelIndex++) {
            View child = controlsColumn.getChildAt(panelIndex);
            if (child instanceof LinearLayout) installHeadersInPanel((LinearLayout) child);
        }
    }

    private static void installHeadersInPanel(final LinearLayout panel) {
        for (int i = 0; i < panel.getChildCount(); i++) {
            View child = panel.getChildAt(i);
            if (!(child instanceof TextView)) continue;
            final TextView header = (TextView) child;
            final String title = normalizedHeaderText(header);
            if (!isCollapsibleTitle(title)) continue;
            if (Boolean.TRUE.equals(header.getTag())) continue;
            header.setTag(Boolean.TRUE);
            header.setText(title + "  ▲");
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
        for (int i = headerIndex + 1; i < panel.getChildCount(); i++) {
            View child = panel.getChildAt(i);
            if (child instanceof TextView && isCollapsibleTitle(normalizedHeaderText((TextView) child))) break;
            child.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    private static boolean isCollapsibleTitle(String title) {
        for (String candidate : COLLAPSIBLE_SECTION_NAMES) {
            if (candidate.equals(title)) return true;
        }
        return false;
    }

    private static String normalizedHeaderText(TextView textView) {
        String text = String.valueOf(textView.getText()).replace("▲", "").replace("▼", "").trim();
        return text.toUpperCase(Locale.US);
    }

    private static TextView text(View parentView, String text, int sizeSp, boolean bold) {
        TextView textView = new TextView(parentView.getContext());
        textView.setText(text);
        textView.setTextSize(sizeSp);
        textView.setTextColor(Color.rgb(243, 245, 247));
        textView.setPadding(0, dp(parentView, 2), 0, dp(parentView, 2));
        if (bold) textView.setTypeface(textView.getTypeface(), android.graphics.Typeface.BOLD);
        return textView;
    }

    private static int dp(View view, int value) {
        return Math.round(value * view.getContext().getResources().getDisplayMetrics().density);
    }

    private static boolean containsText(View view, String wantedText) {
        if (view instanceof TextView && wantedText.contentEquals(((TextView) view).getText())) return true;
        if (!(view instanceof ViewGroup)) return false;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) if (containsText(group.getChildAt(i), wantedText)) return true;
        return false;
    }

    private static boolean containsCheckBoxText(View view, String wantedText) {
        if (view instanceof CheckBox && wantedText.contentEquals(((CheckBox) view).getText())) return true;
        if (!(view instanceof ViewGroup)) return false;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) if (containsCheckBoxText(group.getChildAt(i), wantedText)) return true;
        return false;
    }

    private static void replaceText(View view, String from, String to) {
        if (view instanceof TextView && from.contentEquals(((TextView) view).getText())) {
            ((TextView) view).setText(to);
            return;
        }
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) replaceText(group.getChildAt(i), from, to);
    }
}
