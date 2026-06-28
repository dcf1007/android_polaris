package com.dcf1007.androidpolaris.camera;

import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

/**
 * Small UI organizer for the programmatic MainActivity layout.
 *
 * <p>MainActivity currently builds the interface directly in Java. This helper makes the existing
 * section headers collapsible, moves video-fit controls into the video-alignment section, and
 * updates camera wording to match the capability-query-first UVC lifecycle.</p>
 */
public final class MainInterfaceOrganizer {
    private static final String[] COLLAPSIBLE_SECTION_NAMES = {
            "ALIGNMENT", "VISIBILITY", "POLARIS ALIGNMENT", "STATUS", "READOUTS", "DEBUG LOG"
    };

    private MainInterfaceOrganizer() { }

    /** Applies static layout organization immediately after MainActivity has built its panels. */
    public static void organize(View root) {
        LinearLayout controlsColumn = findControlsColumn(root);
        if (controlsColumn == null) return;
        relabelOpenCameraButton(controlsColumn);
        moveVideoFitRowToAlignmentPanel(controlsColumn);
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
