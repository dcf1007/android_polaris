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
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

final class UvcCameraOptionsPanel {
    private final Context context;
    private final UvcPreviewController controller;
    private final List<UvcPreviewController.StreamMode> streamModes = new ArrayList<>();

    private LinearLayout panel;
    private Spinner streamModeSpinner;
    private Button startStreamButton;
    private SeekBar brightnessSeekBar;
    private SeekBar contrastSeekBar;
    private SeekBar gainSeekBar;
    private SeekBar exposureSeekBar;
    private TextView brightnessValueView;
    private TextView contrastValueView;
    private TextView gainValueView;
    private TextView exposureValueView;
    private CheckBox autoExposureCheckBox;
    private TextView summaryView;
    private boolean binding;
    private boolean collapsed;
    private boolean lastCameraOpen;
    private boolean lastExposureSupported;
    private UvcPreviewController.StreamMode selectedStreamMode;

    UvcCameraOptionsPanel(Context context, UvcPreviewController controller) {
        this.context = context;
        this.controller = controller;
        attach();
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
        selectedStreamMode = capabilities.selectedStreamMode != null ? capabilities.selectedStreamMode : capabilities.activeStreamMode;

        if (!sameModeList(streamModes, capabilities.streamModes)) {
            streamModes.clear();
            streamModes.addAll(capabilities.streamModes);
            ArrayAdapter<UvcPreviewController.StreamMode> adapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, streamModes);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            streamModeSpinner.setAdapter(adapter);
        }
        streamModeSpinner.setEnabled(capabilities.cameraOpen && !streamModes.isEmpty());
        int selectedIndex = indexOfEquivalentMode(selectedStreamMode);
        if (selectedIndex >= 0 && streamModeSpinner.getSelectedItemPosition() != selectedIndex) streamModeSpinner.setSelection(selectedIndex, false);

        startStreamButton.setEnabled(capabilities.cameraOpen && !streamModes.isEmpty());
        startStreamButton.setText(capabilities.previewRunning ? "Apply selected stream by reopening preview" : "Start selected stream");
        brightnessSeekBar.setEnabled(capabilities.cameraOpen && capabilities.brightnessSupported);
        contrastSeekBar.setEnabled(capabilities.cameraOpen && capabilities.contrastSupported);
        gainSeekBar.setEnabled(capabilities.cameraOpen && capabilities.gainSupported);
        autoExposureCheckBox.setEnabled(capabilities.cameraOpen && capabilities.autoExposureSupported);
        autoExposureCheckBox.setChecked(capabilities.autoExposureEnabled);
        exposureSeekBar.setProgress(clamp(capabilities.exposurePercent, 0, 100));
        exposureSeekBar.setEnabled(lastCameraOpen && lastExposureSupported && !capabilities.autoExposureEnabled);
        updateValueLabels();
        summaryView.setText(summary(capabilities));
        binding = false;
    }

    private void attach() {
        if (!(context instanceof Activity)) return;
        FrameLayout root = ((Activity) context).findViewById(android.R.id.content);
        LinearLayout cameraPanel = MainInterfaceOrganizer.findFirstPanelInScrollableControls(root);
        if (cameraPanel == null) return;
        removeTaggedHardwarePanels(cameraPanel);

        panel = new LinearLayout(context);
        panel.setTag(MainInterfaceOrganizer.HARDWARE_CONTROLS_TAG);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(0, dp(8), 0, dp(8));

        final TextView title = text("UVC hardware controls  ▲", 13, true);
        title.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                collapsed = !collapsed;
                for (int i = 1; i < panel.getChildCount(); i++) panel.getChildAt(i).setVisibility(collapsed ? View.GONE : View.VISIBLE);
                title.setText(collapsed ? "UVC hardware controls  ▼" : "UVC hardware controls  ▲");
            }
        });
        panel.addView(title, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        panel.addView(body, new LinearLayout.LayoutParams(-1, -2));

        body.addView(text("Stream mode", 11, false));
        streamModeSpinner = new Spinner(context);
        streamModeSpinner.setEnabled(false);
        body.addView(streamModeSpinner, new LinearLayout.LayoutParams(-1, -2));
        startStreamButton = button("Start selected stream", false, new View.OnClickListener() {
            @Override public void onClick(View view) { controller.startSelectedStream(); }
        });
        body.addView(startStreamButton, new LinearLayout.LayoutParams(-1, -2));

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
        summaryView = text("Open/query the UVC camera to list stream modes and controls.", 11, false);
        summaryView.setTextColor(Color.rgb(180, 190, 203));
        body.addView(summaryView, new LinearLayout.LayoutParams(-1, -2));
        body.addView(button("Save UVC log to Downloads", true, new View.OnClickListener() {
            @Override public void onClick(View view) { controller.saveDebugLogToDownloads(); }
        }), new LinearLayout.LayoutParams(-1, -2));

        cameraPanel.addView(panel, Math.min(3, cameraPanel.getChildCount()), new LinearLayout.LayoutParams(-1, -2));
        wireActions();
        updateValueLabels();
    }

    private void wireActions() {
        streamModeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (binding || position < 0 || position >= streamModes.size()) return;
                UvcPreviewController.StreamMode mode = streamModes.get(position);
                if (!sameMode(mode, selectedStreamMode)) {
                    selectedStreamMode = mode;
                    controller.selectStreamMode(mode);
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        SeekBar.OnSeekBarChangeListener sliderListener = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { if (fromUser && !binding) updateValueLabels(); }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { if (!binding) applyCameraControls(); }
        };
        brightnessSeekBar.setOnSeekBarChangeListener(sliderListener);
        contrastSeekBar.setOnSeekBarChangeListener(sliderListener);
        gainSeekBar.setOnSeekBarChangeListener(sliderListener);
        exposureSeekBar.setOnSeekBarChangeListener(sliderListener);
        autoExposureCheckBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean checked) {
                if (binding) return;
                exposureSeekBar.setEnabled(lastCameraOpen && lastExposureSupported && !checked);
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

    private void applyCameraControls() {
        updateValueLabels();
        controller.setCameraControls(brightnessSeekBar.getProgress(), contrastSeekBar.getProgress(), gainSeekBar.getProgress(), exposureSeekBar.getProgress(), autoExposureCheckBox.isChecked());
    }

    private void updateValueLabels() {
        if (brightnessValueView != null) brightnessValueView.setText(brightnessSeekBar.getProgress() + "%");
        if (contrastValueView != null) contrastValueView.setText(contrastSeekBar.getProgress() + "%");
        if (gainValueView != null) gainValueView.setText(gainSeekBar.getProgress() + "%");
        if (exposureValueView != null) exposureValueView.setText(exposureSeekBar.getProgress() + "%");
    }

    private String summary(UvcPreviewController.UvcCapabilities capabilities) {
        StringBuilder b = new StringBuilder();
        b.append(capabilities.statusText == null ? "UVC status unavailable." : capabilities.statusText).append('\n');
        b.append("Preview: ").append(capabilities.previewRunning ? "running" : "stopped").append('\n');
        b.append("Stream modes: ").append(capabilities.streamModes.size()).append('\n');
        if (capabilities.selectedStreamMode != null) b.append("Selected: ").append(capabilities.selectedStreamMode.fullLabel()).append('\n');
        if (capabilities.activeStreamMode != null) b.append("Active: ").append(capabilities.activeStreamMode.fullLabel()).append('\n');
        b.append("Brightness: ").append(capabilities.brightnessSupported ? "available" : "not reported").append('\n');
        b.append("Contrast: ").append(capabilities.contrastSupported ? "available" : "not reported").append('\n');
        b.append("Gain: ").append(capabilities.gainSupported ? "available" : "not reported").append('\n');
        b.append("Exposure: ").append(capabilities.exposureSupported ? capabilities.exposureRangeText : "not reported").append('\n');
        b.append("Auto exposure: ").append(capabilities.autoExposureSupported ? "available" : "not reported");
        return b.toString();
    }

    private void removeTaggedHardwarePanels(LinearLayout cameraPanel) {
        for (int i = cameraPanel.getChildCount() - 1; i >= 0; i--) {
            if (MainInterfaceOrganizer.HARDWARE_CONTROLS_TAG.equals(cameraPanel.getChildAt(i).getTag())) cameraPanel.removeViewAt(i);
        }
    }

    private int indexOfEquivalentMode(UvcPreviewController.StreamMode target) {
        for (int i = 0; i < streamModes.size(); i++) if (sameMode(streamModes.get(i), target)) return i;
        return -1;
    }

    private boolean sameModeList(List<UvcPreviewController.StreamMode> first, List<UvcPreviewController.StreamMode> second) {
        if (first.size() != second.size()) return false;
        for (int i = 0; i < first.size(); i++) if (!sameMode(first.get(i), second.get(i))) return false;
        return true;
    }

    private boolean sameMode(UvcPreviewController.StreamMode a, UvcPreviewController.StreamMode b) {
        return a != null && b != null && a.frameFormat == b.frameFormat && a.width == b.width && a.height == b.height && a.fps == b.fps;
    }

    private Button button(String text, boolean enabled, View.OnClickListener listener) {
        Button button = new Button(context);
        button.setAllCaps(false);
        button.setText(text);
        button.setTextSize(12);
        button.setEnabled(enabled);
        button.setOnClickListener(listener);
        return button;
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

    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
}
