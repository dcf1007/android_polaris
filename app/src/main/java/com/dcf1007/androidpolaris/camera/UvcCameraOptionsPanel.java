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
 * This panel turns that snapshot into enabled/disabled controls and forwards user
 * selections back to the controller. Unsupported controls remain visible but greyed out.</p>
 */
final class UvcCameraOptionsPanel {
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
    private List<UvcPreviewController.StreamMode> streamModes = new ArrayList<>();

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
        streamModes = new ArrayList<>(capabilities.streamModes);
        ArrayAdapter<UvcPreviewController.StreamMode> adapter = new ArrayAdapter<>(
                context, android.R.layout.simple_spinner_item, streamModes);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        streamModeSpinner.setAdapter(adapter);
        streamModeSpinner.setEnabled(capabilities.cameraOpen && !streamModes.isEmpty());
        if (capabilities.activeStreamMode != null) {
            int activeIndex = indexOfEquivalentMode(capabilities.activeStreamMode);
            if (activeIndex >= 0) streamModeSpinner.setSelection(activeIndex, false);
        }

        brightnessSeekBar.setEnabled(capabilities.cameraOpen && capabilities.brightnessSupported);
        contrastSeekBar.setEnabled(capabilities.cameraOpen && capabilities.contrastSupported);
        gainSeekBar.setEnabled(capabilities.cameraOpen && capabilities.gainSupported);
        autoExposureCheckBox.setEnabled(capabilities.cameraOpen && capabilities.autoExposureSupported);
        autoExposureCheckBox.setChecked(capabilities.autoExposureEnabled);
        exposureSeekBar.setEnabled(capabilities.cameraOpen && capabilities.exposureSupported && !capabilities.autoExposureEnabled);
        exposureSeekBar.setProgress(clamp(capabilities.exposurePercent, 0, 100));

        updateValueLabels();
        capabilitySummaryView.setText(buildCapabilitySummary(capabilities));
        binding = false;
    }

    private void attach() {
        if (!(context instanceof Activity)) return;
        FrameLayout root = ((Activity) context).findViewById(android.R.id.content);
        if (root == null) return;

        panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(10), dp(8), dp(10), dp(8));
        panel.setBackgroundColor(Color.argb(238, 16, 18, 24));

        titleView = text("UVC camera options  ▲", 13, true);
        titleView.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                collapsed = !collapsed;
                updateCollapsedState();
            }
        });
        panel.addView(titleView, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scrollView = new ScrollView(context);
        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(body, new ScrollView.LayoutParams(-1, -2));

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

        panel.addView(scrollView, new LinearLayout.LayoutParams(-1, dp(260)));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        params.setMargins(dp(8), dp(8), dp(8), dp(8));
        root.addView(panel, params);
        wireActions();
        updateValueLabels();
    }

    private void wireActions() {
        streamModeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (binding || position < 0 || position >= streamModes.size()) return;
                controller.selectStreamMode(streamModes.get(position));
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });

        SeekBar.OnSeekBarChangeListener sliderListener = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser || binding) return;
                applyCameraControls();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { applyCameraControls(); }
        };
        brightnessSeekBar.setOnSeekBarChangeListener(sliderListener);
        contrastSeekBar.setOnSeekBarChangeListener(sliderListener);
        gainSeekBar.setOnSeekBarChangeListener(sliderListener);
        exposureSeekBar.setOnSeekBarChangeListener(sliderListener);
        autoExposureCheckBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (binding) return;
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
        builder.append("Colour/B&W/day-night: not reported as a standard UVC control.");
        return builder.toString();
    }

    private int indexOfEquivalentMode(UvcPreviewController.StreamMode target) {
        for (int i = 0; i < streamModes.size(); i++) {
            UvcPreviewController.StreamMode mode = streamModes.get(i);
            if (mode.frameFormat == target.frameFormat && mode.width == target.width
                    && mode.height == target.height && mode.fps == target.fps) {
                return i;
            }
        }
        return -1;
    }

    private void updateCollapsedState() {
        if (panel == null) return;
        for (int index = 1; index < panel.getChildCount(); index++) {
            panel.getChildAt(index).setVisibility(collapsed ? View.GONE : View.VISIBLE);
        }
        titleView.setText(collapsed ? "UVC camera options  ▼" : "UVC camera options  ▲");
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
