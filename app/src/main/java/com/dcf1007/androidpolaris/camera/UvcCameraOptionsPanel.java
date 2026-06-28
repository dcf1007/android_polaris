package com.dcf1007.androidpolaris.camera;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * UI binding for queried UVC capabilities.
 *
 * <p>The controller queries the camera and publishes a UvcCapabilities snapshot.
 * This panel is inserted into the existing camera panel under the USB buttons,
 * so it behaves like the rest of the interface instead of floating above the reticle.</p>
 */
final class UvcCameraOptionsPanel {
    private static final int MIN_SAFE_STREAM_PIXELS = 320 * 240;
    private static final int HIGH_STREAM_PIXELS = 1280 * 720;

    private final Context context;
    private final UvcPreviewController controller;

    private LinearLayout panel;
    private TextView titleView;
    private TextView capabilitySummaryView;
    private Spinner streamModeSpinner;
    private SeekBar brightnessSeekBar;
    private SeekBar contrastSeekBar;
    private SeekBar gainSeekBar;
    private SeekBar exposureSeekBar;
    private TextView brightnessValueView;
    private TextView contrastValueView;
    private TextView gainValueView;
    private TextView exposureValueView;
    private CheckBox autoExposureCheckBox;
    private boolean collapsed;
    private boolean binding;
    private boolean lastCameraOpen;
    private boolean lastExposureSupported;
    private List<UvcPreviewController.StreamMode> streamModes = new ArrayList<>();
    private UvcPreviewController.StreamMode activeStreamMode;

    UvcCameraOptionsPanel(Context context, UvcPreviewController controller) {
        this.context = context;
        this.controller = controller;
        attachInsideMainCameraPanel();
    }

    void destroy() {
        if (panel == null) return;
        ViewGroup parent = (ViewGroup) panel.getParent();
        if (parent != null) parent.removeView(panel);
        panel = null;
    }

    void update(UvcPreviewController.UvcCapabilities capabilities) {
        if (panel == null || capabilities == null) return;

        binding = true;
        lastCameraOpen = capabilities.cameraOpen;
        lastExposureSupported = capabilities.exposureSupported;
        activeStreamMode = capabilities.activeStreamMode;

        List<UvcPreviewController.StreamMode> queriedModes = new ArrayList<>(capabilities.streamModes);
        if (!sameModeList(streamModes, queriedModes)) {
            streamModes = queriedModes;
            ArrayAdapter<UvcPreviewController.StreamMode> adapter = new ArrayAdapter<>(
                    context, android.R.layout.simple_spinner_item, streamModes);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            streamModeSpinner.setAdapter(adapter);
        }
        streamModeSpinner.setEnabled(capabilities.cameraOpen && !streamModes.isEmpty());
        if (activeStreamMode != null) {
            int activeIndex = indexOfEquivalentMode(activeStreamMode);
            if (activeIndex >= 0 && streamModeSpinner.getSelectedItemPosition() != activeIndex) {
                streamModeSpinner.setSelection(activeIndex, false);
            }
        }

        brightnessSeekBar.setEnabled(capabilities.cameraOpen && capabilities.brightnessSupported);
        contrastSeekBar.setEnabled(capabilities.cameraOpen && capabilities.contrastSupported);
        gainSeekBar.setEnabled(capabilities.cameraOpen && capabilities.gainSupported);
        autoExposureCheckBox.setEnabled(capabilities.cameraOpen && capabilities.autoExposureSupported);
        autoExposureCheckBox.setChecked(capabilities.autoExposureEnabled);
        exposureSeekBar.setProgress(clamp(capabilities.exposurePercent, 0, 100));
        updateExposureSliderEnabledState(capabilities.autoExposureEnabled);

        updateValueLabels();
        capabilitySummaryView.setText(buildCapabilitySummary(capabilities));
        binding = false;
    }

    private void attachInsideMainCameraPanel() {
        if (!(context instanceof Activity)) return;
        FrameLayout activityContent = ((Activity) context).findViewById(android.R.id.content);
        LinearLayout mainCameraPanel = findFirstPanelInScrollableControls(activityContent);
        if (mainCameraPanel == null) return;

        panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(0, dp(8), 0, dp(8));

        titleView = text("UVC hardware controls  ▲", 13, true);
        titleView.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                collapsed = !collapsed;
                updateCollapsedState();
            }
        });
        panel.addView(titleView, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setTag("uvc-options-body");
        panel.addView(body, new LinearLayout.LayoutParams(-1, -2));

        body.addView(text("Stream mode", 11, false), new LinearLayout.LayoutParams(-1, -2));
        streamModeSpinner = new Spinner(context);
        streamModeSpinner.setEnabled(false);
        body.addView(streamModeSpinner, new LinearLayout.LayoutParams(-1, -2));

        brightnessSeekBar = addSlider(body, "Brightness", brightnessValueView = rightValue());
        contrastSeekBar = addSlider(body, "Contrast", contrastValueView = rightValue());
        gainSeekBar = addSlider(body, "Gain", gainValueView = rightValue());
        exposureSeekBar = addSlider(body, "Exposure", exposureValueView = rightValue());

        autoExposureCheckBox = new CheckBox(context);
        autoExposureCheckBox.setText("Auto exposure");
        autoExposureCheckBox.setTextColor(Color.rgb(243, 245, 247));
        autoExposureCheckBox.setTextSize(12);
        autoExposureCheckBox.setEnabled(false);
        body.addView(autoExposureCheckBox, new LinearLayout.LayoutParams(-1, -2));

        capabilitySummaryView = text("Open the UVC camera to query capabilities.", 11, false);
        capabilitySummaryView.setTextColor(Color.rgb(180, 190, 203));
        body.addView(capabilitySummaryView, new LinearLayout.LayoutParams(-1, -2));

        Button saveLogButton = new Button(context);
        saveLogButton.setAllCaps(false);
        saveLogButton.setText("Save UVC log to Downloads");
        saveLogButton.setTextSize(12);
        saveLogButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                controller.saveDebugLogToDownloads();
            }
        });
        body.addView(saveLogButton, new LinearLayout.LayoutParams(-1, -2));

        // Camera panel children are: title, open/status row, stop button, then status text.
        // Insert here so hardware controls live under the buttons and above the status block.
        int insertIndex = Math.min(3, mainCameraPanel.getChildCount());
        mainCameraPanel.addView(panel, insertIndex, new LinearLayout.LayoutParams(-1, -2));
        wireActions();
        updateValueLabels();
    }

    private LinearLayout findFirstPanelInScrollableControls(View root) {
        ScrollView controlsScroll = findFirstScrollView(root);
        if (controlsScroll == null || controlsScroll.getChildCount() == 0) return null;
        View controlsChild = controlsScroll.getChildAt(0);
        if (!(controlsChild instanceof LinearLayout)) return null;
        LinearLayout controlsColumn = (LinearLayout) controlsChild;
        for (int i = 0; i < controlsColumn.getChildCount(); i++) {
            View child = controlsColumn.getChildAt(i);
            if (child instanceof LinearLayout) return (LinearLayout) child;
        }
        return null;
    }

    private ScrollView findFirstScrollView(View view) {
        if (view instanceof ScrollView) return (ScrollView) view;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            ScrollView found = findFirstScrollView(group.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    private void wireActions() {
        streamModeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (binding || position < 0 || position >= streamModes.size()) return;
                UvcPreviewController.StreamMode selectedMode = streamModes.get(position);
                if (sameMode(selectedMode, activeStreamMode)) return;
                if (isKnownUnsafeLowMode(selectedMode)) {
                    capabilitySummaryView.setText("Rejected " + selectedMode.fullLabel()
                            + " because very small UVC modes have been observed to hang this camera. Choose 320×240 or larger.");
                    restoreActiveStreamSelection();
                    return;
                }
                controller.selectStreamMode(selectedMode);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });

        SeekBar.OnSeekBarChangeListener sliderListener = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser || binding) return;
                // Keep the UI responsive while dragging. The USB control write is sent once
                // on release so preview streaming is not flooded with control transfers.
                updateValueLabels();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                if (!binding) applyCameraControls();
            }
        };
        brightnessSeekBar.setOnSeekBarChangeListener(sliderListener);
        contrastSeekBar.setOnSeekBarChangeListener(sliderListener);
        gainSeekBar.setOnSeekBarChangeListener(sliderListener);
        exposureSeekBar.setOnSeekBarChangeListener(sliderListener);
        autoExposureCheckBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (binding) return;
                updateExposureSliderEnabledState(isChecked);
                applyCameraControls();
            }
        });
    }

    private SeekBar addSlider(LinearLayout parent, String label, TextView valueView) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(text(label, 11, false), new LinearLayout.LayoutParams(0, -2, 1.0f));
        row.addView(valueView, new LinearLayout.LayoutParams(0, -2, 1.0f));
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));

        SeekBar seekBar = new SeekBar(context);
        seekBar.setMax(100);
        seekBar.setProgress(50);
        seekBar.setEnabled(false);
        parent.addView(seekBar, new LinearLayout.LayoutParams(-1, -2));
        return seekBar;
    }

    private void restoreActiveStreamSelection() {
        int activeIndex = indexOfEquivalentMode(activeStreamMode);
        if (activeIndex >= 0) {
            binding = true;
            streamModeSpinner.setSelection(activeIndex, false);
            binding = false;
        }
    }

    private boolean isKnownUnsafeLowMode(UvcPreviewController.StreamMode mode) {
        return mode != null && mode.width * mode.height < MIN_SAFE_STREAM_PIXELS;
    }

    private void updateExposureSliderEnabledState(boolean autoExposureEnabled) {
        if (exposureSeekBar != null) {
            exposureSeekBar.setEnabled(lastCameraOpen && lastExposureSupported && !autoExposureEnabled);
        }
    }

    private void applyCameraControls() {
        updateValueLabels();
        controller.setCameraControls(
                brightnessSeekBar.getProgress(),
                contrastSeekBar.getProgress(),
                gainSeekBar.getProgress(),
                exposureSeekBar.getProgress(),
                autoExposureCheckBox.isChecked());
    }

    private void updateValueLabels() {
        if (brightnessValueView != null) brightnessValueView.setText(brightnessSeekBar.getProgress() + "%");
        if (contrastValueView != null) contrastValueView.setText(contrastSeekBar.getProgress() + "%");
        if (gainValueView != null) gainValueView.setText(gainSeekBar.getProgress() + "%");
        if (exposureValueView != null) exposureValueView.setText(exposureSeekBar.getProgress() + "%");
    }

    private String buildCapabilitySummary(UvcPreviewController.UvcCapabilities capabilities) {
        StringBuilder builder = new StringBuilder();
        builder.append(capabilities.statusText == null ? "UVC status unavailable." : capabilities.statusText).append('\n');
        builder.append("Stream modes: ").append(capabilities.streamModes.size()).append('\n');
        builder.append("Brightness: ").append(capabilities.brightnessSupported ? "available" : "not reported").append('\n');
        builder.append("Contrast: ").append(capabilities.contrastSupported ? "available" : "not reported").append('\n');
        builder.append("Gain: ").append(capabilities.gainSupported ? "available" : "not reported").append('\n');
        builder.append("Exposure: ").append(capabilities.exposureSupported ? capabilities.exposureRangeText : "not reported").append('\n');
        builder.append("Auto exposure: ").append(capabilities.autoExposureSupported ? "available" : "not reported").append('\n');
        if (capabilities.activeStreamMode != null
                && capabilities.activeStreamMode.width * capabilities.activeStreamMode.height > HIGH_STREAM_PIXELS) {
            builder.append("Current stream is high resolution and may lag on USB/phone bandwidth.\n");
        }
        builder.append("Very small modes below 320×240 are listed by some cameras but blocked here because they can hang this device.\n");
        builder.append("Colour/B&W/day-night: not reported as a standard UVC control.");
        return builder.toString();
    }

    private int indexOfEquivalentMode(UvcPreviewController.StreamMode target) {
        for (int i = 0; i < streamModes.size(); i++) {
            if (sameMode(streamModes.get(i), target)) return i;
        }
        return -1;
    }

    private boolean sameModeList(List<UvcPreviewController.StreamMode> first,
                                 List<UvcPreviewController.StreamMode> second) {
        if (first.size() != second.size()) return false;
        for (int i = 0; i < first.size(); i++) {
            if (!sameMode(first.get(i), second.get(i))) return false;
        }
        return true;
    }

    private boolean sameMode(UvcPreviewController.StreamMode a, UvcPreviewController.StreamMode b) {
        return a != null && b != null
                && a.frameFormat == b.frameFormat
                && a.width == b.width
                && a.height == b.height
                && a.fps == b.fps;
    }

    private void updateCollapsedState() {
        if (panel == null) return;
        for (int index = 1; index < panel.getChildCount(); index++) {
            panel.getChildAt(index).setVisibility(collapsed ? View.GONE : View.VISIBLE);
        }
        titleView.setText(collapsed ? "UVC hardware controls  ▼" : "UVC hardware controls  ▲");
    }

    private TextView text(String text, int sizeSp, boolean bold) {
        TextView textView = new TextView(context);
        textView.setText(text);
        textView.setTextSize(sizeSp);
        textView.setTextColor(Color.rgb(243, 245, 247));
        textView.setPadding(0, dp(2), 0, dp(2));
        if (bold) textView.setTypeface(textView.getTypeface(), android.graphics.Typeface.BOLD);
        return textView;
    }

    private TextView rightValue() {
        TextView textView = text("—", 11, false);
        textView.setGravity(Gravity.END);
        return textView;
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
