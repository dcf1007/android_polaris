package com.dcf1007.androidpolaris.camera;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
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

/** Binds queried UVC capabilities to the visible hardware-controls section. */
final class UvcCameraOptionsPanel {
    private static final int FINE_STEP_PERCENT = 1;
    private static final String STREAM_BUTTON_ROW_TAG = "android-polaris-primary-stream-buttons";
    private static final int COLOR_PRIMARY = Color.rgb(134, 183, 255);
    private static final int COLOR_PRIMARY_TEXT = Color.rgb(6, 16, 31);
    private static final int COLOR_DISABLED_FILL = Color.rgb(58, 63, 73);
    private static final int COLOR_DISABLED_TEXT = Color.rgb(154, 163, 176);

    private final Context context;
    private final UvcPreviewController controller;
    private final List<UvcPreviewController.StreamMode> streamModes = new ArrayList<>();
    private final List<String> streamTypeOptions = new ArrayList<>();
    private final List<String> resolutionOptions = new ArrayList<>();
    private final List<Integer> fpsOptions = new ArrayList<>();

    private LinearLayout panel;
    private Spinner streamTypeSpinner;
    private Spinner resolutionSpinner;
    private Spinner fpsSpinner;
    private Button refreshUsbButton;
    private Button streamActionButton;
    private FineSlider brightnessSlider;
    private FineSlider contrastSlider;
    private FineSlider gainSlider;
    private FineSlider exposureSlider;
    private CheckBox autoExposureCheckBox;
    private TextView summaryView;
    private boolean binding;
    private boolean collapsed;
    private boolean lastCameraOpen;
    private boolean lastExposureSupported;
    private boolean lastPreviewRunning;
    private boolean userSelectedStreamMode;
    private UvcPreviewController.StreamMode selectedStreamMode;

    private static final class FineSlider {
        final SeekBar seekBar;
        final TextView valueView;
        final Button minusButton;
        final Button plusButton;

        FineSlider(SeekBar seekBar, TextView valueView, Button minusButton, Button plusButton) {
            this.seekBar = seekBar;
            this.valueView = valueView;
            this.minusButton = minusButton;
            this.plusButton = plusButton;
        }

        int progress() { return seekBar.getProgress(); }
        void setProgress(int progress) { seekBar.setProgress(clamp(progress, 0, 100)); }
        void setEnabled(boolean enabled) {
            seekBar.setEnabled(enabled);
            minusButton.setEnabled(enabled);
            plusButton.setEnabled(enabled);
        }
        void updateLabel() { valueView.setText(seekBar.getProgress() + "%"); }
    }

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
        lastPreviewRunning = capabilities.previewRunning;

        boolean modesChanged = !sameModeList(streamModes, capabilities.streamModes);
        if (modesChanged) {
            streamModes.clear();
            streamModes.addAll(capabilities.streamModes);
        }

        UvcPreviewController.StreamMode capabilityMode = capabilities.selectedStreamMode != null
                ? capabilities.selectedStreamMode
                : capabilities.activeStreamMode;
        if (capabilities.previewRunning) {
            selectedStreamMode = capabilities.activeStreamMode;
        } else if (!userSelectedStreamMode && !streamModes.isEmpty()) {
            selectedStreamMode = chooseDefaultStreamMode();
        } else if (selectedStreamMode == null) {
            selectedStreamMode = capabilityMode;
        }

        if (modesChanged) rebuildStreamTypeSpinner();
        selectCurrentStreamInDropdowns();
        if (!userSelectedStreamMode && !capabilities.previewRunning && selectedStreamMode != null && !sameMode(selectedStreamMode, capabilityMode)) {
            controller.selectStreamMode(selectedStreamMode);
        }

        boolean streamSelectionEnabled = capabilities.cameraOpen && !capabilities.previewRunning && !streamModes.isEmpty();
        streamTypeSpinner.setEnabled(streamSelectionEnabled);
        resolutionSpinner.setEnabled(streamSelectionEnabled && !resolutionOptions.isEmpty());
        fpsSpinner.setEnabled(streamSelectionEnabled && !fpsOptions.isEmpty());

        if (refreshUsbButton != null) {
            refreshUsbButton.setEnabled(!capabilities.previewRunning);
            stylePrimaryButton(refreshUsbButton);
        }
        if (streamActionButton != null) {
            boolean canStart = streamSelectionEnabled && selectedModeFromDropdowns() != null;
            streamActionButton.setEnabled(capabilities.previewRunning || canStart);
            streamActionButton.setText(capabilities.previewRunning ? "Stop stream" : "Start stream");
            stylePrimaryButton(streamActionButton);
        }

        brightnessSlider.setEnabled(capabilities.cameraOpen && capabilities.brightnessSupported);
        contrastSlider.setEnabled(capabilities.cameraOpen && capabilities.contrastSupported);
        gainSlider.setEnabled(capabilities.cameraOpen && capabilities.gainSupported);
        autoExposureCheckBox.setEnabled(capabilities.cameraOpen && capabilities.autoExposureSupported);
        autoExposureCheckBox.setChecked(capabilities.autoExposureEnabled);
        exposureSlider.setProgress(clamp(capabilities.exposurePercent, 0, 100));
        exposureSlider.setEnabled(lastCameraOpen && lastExposureSupported && !capabilities.autoExposureEnabled);
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
        installPrimaryStreamButtonRow(cameraPanel);

        panel = new LinearLayout(context);
        panel.setTag(MainInterfaceOrganizer.HARDWARE_CONTROLS_TAG);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(0, dp(8), 0, dp(8));

        final TextView title = text("UVC hardware controls  ▲", 13, true);
        title.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                collapsed = !collapsed;
                for (int index = 1; index < panel.getChildCount(); index++) {
                    panel.getChildAt(index).setVisibility(collapsed ? View.GONE : View.VISIBLE);
                }
                title.setText(collapsed ? "UVC hardware controls  ▼" : "UVC hardware controls  ▲");
            }
        });
        panel.addView(title, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        panel.addView(body, new LinearLayout.LayoutParams(-1, -2));

        body.addView(text("Stream type", 11, false));
        streamTypeSpinner = new Spinner(context);
        streamTypeSpinner.setEnabled(false);
        body.addView(streamTypeSpinner, new LinearLayout.LayoutParams(-1, -2));

        body.addView(text("Resolution", 11, false));
        resolutionSpinner = new Spinner(context);
        resolutionSpinner.setEnabled(false);
        body.addView(resolutionSpinner, new LinearLayout.LayoutParams(-1, -2));

        body.addView(text("FPS", 11, false));
        fpsSpinner = new Spinner(context);
        fpsSpinner.setEnabled(false);
        body.addView(fpsSpinner, new LinearLayout.LayoutParams(-1, -2));

        brightnessSlider = addFineSlider(body, "Brightness");
        contrastSlider = addFineSlider(body, "Contrast");
        gainSlider = addFineSlider(body, "Gain");
        exposureSlider = addFineSlider(body, "Exposure");
        autoExposureCheckBox = new CheckBox(context);
        autoExposureCheckBox.setText("Auto exposure");
        autoExposureCheckBox.setTextColor(Color.rgb(243, 245, 247));
        autoExposureCheckBox.setTextSize(12);
        autoExposureCheckBox.setEnabled(false);
        body.addView(autoExposureCheckBox, new LinearLayout.LayoutParams(-1, -2));

        summaryView = text("Automatic USB query lists stream modes and hardware controls after a camera is connected.", 11, false);
        summaryView.setTextColor(Color.rgb(180, 190, 203));
        body.addView(summaryView, new LinearLayout.LayoutParams(-1, -2));
        body.addView(button("Save UVC log to Downloads", true, new View.OnClickListener() {
            @Override public void onClick(View view) { controller.saveDebugLogToDownloads(); }
        }), new LinearLayout.LayoutParams(-1, -2));

        cameraPanel.addView(panel, Math.min(3, cameraPanel.getChildCount()), new LinearLayout.LayoutParams(-1, -2));
        wireActions();
        updateValueLabels();
    }

    private void installPrimaryStreamButtonRow(LinearLayout cameraPanel) {
        removeTaggedRows(cameraPanel, STREAM_BUTTON_ROW_TAG);
        int insertIndex = Math.min(2, cameraPanel.getChildCount());
        View oldStopButton = findDirectChildWithText(cameraPanel, "Stop stream");
        if (oldStopButton == null) oldStopButton = findDirectChildWithText(cameraPanel, "Stop camera");
        if (oldStopButton != null) {
            insertIndex = cameraPanel.indexOfChild(oldStopButton);
            cameraPanel.removeView(oldStopButton);
        }

        LinearLayout row = new LinearLayout(context);
        row.setTag(STREAM_BUTTON_ROW_TAG);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 0, 0, dp(6));

        refreshUsbButton = button("Refresh USB devices", true, new View.OnClickListener() {
            @Override public void onClick(View view) { controller.refreshUsbDevices(); }
        });
        stylePrimaryButton(refreshUsbButton);

        streamActionButton = button("Start stream", false, new View.OnClickListener() {
            @Override public void onClick(View view) {
                if (lastPreviewRunning) controller.stopStream();
                else controller.startSelectedStream();
            }
        });
        stylePrimaryButton(streamActionButton);

        LinearLayout.LayoutParams refreshParams = new LinearLayout.LayoutParams(0, -2, 1.0f);
        refreshParams.setMargins(0, 0, dp(6), 0);
        LinearLayout.LayoutParams streamParams = new LinearLayout.LayoutParams(0, -2, 1.0f);
        streamParams.setMargins(dp(6), 0, 0, 0);
        row.addView(refreshUsbButton, refreshParams);
        row.addView(streamActionButton, streamParams);
        cameraPanel.addView(row, Math.max(0, insertIndex), new LinearLayout.LayoutParams(-1, -2));
    }

    private void wireActions() {
        streamTypeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (binding || position < 0 || position >= streamTypeOptions.size()) return;
                rebuildResolutionSpinnerForType(streamTypeOptions.get(position));
                selectModeFromDropdowns(true);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        resolutionSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (binding || position < 0 || position >= resolutionOptions.size()) return;
                rebuildFpsSpinnerForSelection(currentTypeLabel(), resolutionOptions.get(position), selectedStreamMode == null ? -1 : selectedStreamMode.fps);
                selectModeFromDropdowns(true);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        fpsSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (binding || position < 0 || position >= fpsOptions.size()) return;
                selectModeFromDropdowns(true);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });

        SeekBar.OnSeekBarChangeListener sliderListener = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { if (fromUser && !binding) updateValueLabels(); }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { if (!binding) applyCameraControls(); }
        };
        brightnessSlider.seekBar.setOnSeekBarChangeListener(sliderListener);
        contrastSlider.seekBar.setOnSeekBarChangeListener(sliderListener);
        gainSlider.seekBar.setOnSeekBarChangeListener(sliderListener);
        exposureSlider.seekBar.setOnSeekBarChangeListener(sliderListener);
        autoExposureCheckBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean checked) {
                if (binding) return;
                exposureSlider.setEnabled(lastCameraOpen && lastExposureSupported && !checked);
                applyCameraControls();
            }
        });
    }

    private void rebuildStreamTypeSpinner() {
        streamTypeOptions.clear();
        UvcPreviewController.StreamMode defaultMode = selectedStreamMode == null ? chooseDefaultStreamMode() : selectedStreamMode;
        for (UvcPreviewController.StreamMode mode : streamModes) {
            String label = mode.formatLabel();
            if (!streamTypeOptions.contains(label)) streamTypeOptions.add(label);
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, streamTypeOptions);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        streamTypeSpinner.setAdapter(adapter);
        String selectedType = defaultMode == null ? null : defaultMode.formatLabel();
        if (selectedType == null && !streamTypeOptions.isEmpty()) selectedType = streamTypeOptions.get(0);
        int typeIndex = streamTypeOptions.indexOf(selectedType);
        if (typeIndex >= 0) streamTypeSpinner.setSelection(typeIndex, false);
        rebuildResolutionSpinnerForType(selectedType);
    }

    private void rebuildResolutionSpinnerForType(String typeLabel) {
        resolutionOptions.clear();
        for (UvcPreviewController.StreamMode mode : streamModes) {
            if (typeLabel == null || !typeLabel.equals(mode.formatLabel())) continue;
            String label = mode.resolutionLabel();
            if (!resolutionOptions.contains(label)) resolutionOptions.add(label);
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, resolutionOptions);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        resolutionSpinner.setAdapter(adapter);
        String selectedResolution = selectedStreamMode == null ? null : selectedStreamMode.resolutionLabel();
        if ((selectedResolution == null || !resolutionOptions.contains(selectedResolution)) && !resolutionOptions.isEmpty()) {
            selectedResolution = resolutionOptions.get(0);
        }
        int resolutionIndex = resolutionOptions.indexOf(selectedResolution);
        if (resolutionIndex >= 0) resolutionSpinner.setSelection(resolutionIndex, false);
        rebuildFpsSpinnerForSelection(typeLabel, selectedResolution, selectedStreamMode == null ? -1 : selectedStreamMode.fps);
    }

    private void rebuildFpsSpinnerForSelection(String typeLabel, String resolutionLabel, int preferredFps) {
        fpsOptions.clear();
        for (UvcPreviewController.StreamMode mode : streamModes) {
            if (typeLabel != null && !typeLabel.equals(mode.formatLabel())) continue;
            if (resolutionLabel != null && resolutionLabel.equals(mode.resolutionLabel()) && !fpsOptions.contains(mode.fps)) fpsOptions.add(mode.fps);
        }
        ArrayAdapter<Integer> adapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, fpsOptions);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        fpsSpinner.setAdapter(adapter);
        int fpsIndex = fpsOptions.indexOf(preferredFps);
        if (fpsIndex < 0 && !fpsOptions.isEmpty()) fpsIndex = indexOfHighestFps();
        if (fpsIndex >= 0) fpsSpinner.setSelection(fpsIndex, false);
    }

    private void selectCurrentStreamInDropdowns() {
        if (selectedStreamMode == null) return;
        int typeIndex = streamTypeOptions.indexOf(selectedStreamMode.formatLabel());
        if (typeIndex >= 0 && streamTypeSpinner.getSelectedItemPosition() != typeIndex) streamTypeSpinner.setSelection(typeIndex, false);
        rebuildResolutionSpinnerForType(selectedStreamMode.formatLabel());
        int resolutionIndex = resolutionOptions.indexOf(selectedStreamMode.resolutionLabel());
        if (resolutionIndex >= 0 && resolutionSpinner.getSelectedItemPosition() != resolutionIndex) resolutionSpinner.setSelection(resolutionIndex, false);
        rebuildFpsSpinnerForSelection(selectedStreamMode.formatLabel(), selectedStreamMode.resolutionLabel(), selectedStreamMode.fps);
    }

    private void selectModeFromDropdowns(boolean fromUser) {
        UvcPreviewController.StreamMode selected = selectedModeFromDropdowns();
        if (selected != null && !sameMode(selected, selectedStreamMode)) {
            selectedStreamMode = selected;
            if (fromUser) userSelectedStreamMode = true;
            controller.selectStreamMode(selected);
        }
    }

    private UvcPreviewController.StreamMode selectedModeFromDropdowns() {
        String type = currentTypeLabel();
        int resolutionIndex = resolutionSpinner.getSelectedItemPosition();
        int fpsIndex = fpsSpinner.getSelectedItemPosition();
        if (type == null || resolutionIndex < 0 || resolutionIndex >= resolutionOptions.size() || fpsIndex < 0 || fpsIndex >= fpsOptions.size()) return null;
        String resolution = resolutionOptions.get(resolutionIndex);
        int fps = fpsOptions.get(fpsIndex);
        for (UvcPreviewController.StreamMode mode : streamModes) {
            if (type.equals(mode.formatLabel()) && resolution.equals(mode.resolutionLabel()) && mode.fps == fps) return mode;
        }
        return null;
    }

    private String currentTypeLabel() {
        int typeIndex = streamTypeSpinner.getSelectedItemPosition();
        return typeIndex >= 0 && typeIndex < streamTypeOptions.size() ? streamTypeOptions.get(typeIndex) : null;
    }

    private UvcPreviewController.StreamMode chooseDefaultStreamMode() {
        UvcPreviewController.StreamMode bestUncompressed = bestStreamMode(true);
        return bestUncompressed != null ? bestUncompressed : bestStreamMode(false);
    }

    private UvcPreviewController.StreamMode bestStreamMode(boolean uncompressedOnly) {
        UvcPreviewController.StreamMode best = null;
        for (UvcPreviewController.StreamMode mode : streamModes) {
            boolean uncompressed = mode.formatLabel().contains("YUYV");
            if (uncompressedOnly && !uncompressed) continue;
            if (best == null || streamRank(mode) > streamRank(best)) best = mode;
        }
        return best;
    }

    private long streamRank(UvcPreviewController.StreamMode mode) {
        return (long) mode.width * (long) mode.height * 10000L + mode.fps;
    }

    private int indexOfHighestFps() {
        int bestIndex = -1;
        int bestFps = -1;
        for (int index = 0; index < fpsOptions.size(); index++) {
            if (fpsOptions.get(index) > bestFps) {
                bestFps = fpsOptions.get(index);
                bestIndex = index;
            }
        }
        return bestIndex;
    }

    private FineSlider addFineSlider(LinearLayout parent, String label) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(text(label, 11, false), new LinearLayout.LayoutParams(0, -2, 1.0f));
        Button minus = button("−", false, null);
        TextView value = rightValue();
        Button plus = button("+", false, null);
        row.addView(minus, new LinearLayout.LayoutParams(dp(44), -2));
        row.addView(value, new LinearLayout.LayoutParams(dp(70), -2));
        row.addView(plus, new LinearLayout.LayoutParams(dp(44), -2));
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));

        SeekBar seekBar = new SeekBar(context);
        seekBar.setMax(100);
        seekBar.setProgress(50);
        seekBar.setEnabled(false);
        parent.addView(seekBar, new LinearLayout.LayoutParams(-1, -2));

        final FineSlider fineSlider = new FineSlider(seekBar, value, minus, plus);
        minus.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { stepFineSlider(fineSlider, -FINE_STEP_PERCENT); }
        });
        plus.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { stepFineSlider(fineSlider, FINE_STEP_PERCENT); }
        });
        return fineSlider;
    }

    private void stepFineSlider(FineSlider slider, int delta) {
        if (binding || slider == null || !slider.seekBar.isEnabled()) return;
        slider.setProgress(slider.progress() + delta);
        updateValueLabels();
        applyCameraControls();
    }

    private void applyCameraControls() {
        updateValueLabels();
        controller.setCameraControls(brightnessSlider.progress(), contrastSlider.progress(), gainSlider.progress(), exposureSlider.progress(), autoExposureCheckBox.isChecked());
    }

    private void updateValueLabels() {
        if (brightnessSlider != null) brightnessSlider.updateLabel();
        if (contrastSlider != null) contrastSlider.updateLabel();
        if (gainSlider != null) gainSlider.updateLabel();
        if (exposureSlider != null) exposureSlider.updateLabel();
    }

    private String summary(UvcPreviewController.UvcCapabilities capabilities) {
        StringBuilder builder = new StringBuilder();
        builder.append(capabilities.statusText == null ? "UVC status unavailable." : capabilities.statusText).append('\n');
        builder.append("Preview: ").append(capabilities.previewRunning ? "running; stream controls locked" : "stopped; stream controls selectable").append('\n');
        builder.append("Stream modes: ").append(capabilities.streamModes.size()).append('\n');
        if (capabilities.selectedStreamMode != null) builder.append("Selected: ").append(capabilities.selectedStreamMode.fullLabel()).append('\n');
        if (capabilities.activeStreamMode != null) builder.append("Active: ").append(capabilities.activeStreamMode.fullLabel()).append('\n');
        builder.append("Brightness: ").append(capabilities.brightnessSupported ? "available" : "not reported").append('\n');
        builder.append("Contrast: ").append(capabilities.contrastSupported ? "available" : "not reported").append('\n');
        builder.append("Gain: ").append(capabilities.gainSupported ? "available" : "not reported").append('\n');
        builder.append("Exposure: ").append(capabilities.exposureSupported ? capabilities.exposureRangeText : "not reported").append('\n');
        builder.append("Auto exposure: ").append(capabilities.autoExposureSupported ? "available" : "not reported");
        return builder.toString();
    }

    private void removeTaggedHardwarePanels(LinearLayout cameraPanel) {
        for (int index = cameraPanel.getChildCount() - 1; index >= 0; index--) {
            if (MainInterfaceOrganizer.HARDWARE_CONTROLS_TAG.equals(cameraPanel.getChildAt(index).getTag())) cameraPanel.removeViewAt(index);
        }
    }

    private void removeTaggedRows(LinearLayout panel, String tag) {
        for (int index = panel.getChildCount() - 1; index >= 0; index--) {
            if (tag.equals(panel.getChildAt(index).getTag())) panel.removeViewAt(index);
        }
    }

    private View findDirectChildWithText(LinearLayout panel, String wantedText) {
        for (int index = 0; index < panel.getChildCount(); index++) {
            View child = panel.getChildAt(index);
            if (containsText(child, wantedText)) return child;
        }
        return null;
    }

    private boolean containsText(View view, String wantedText) {
        if (view instanceof TextView && wantedText.contentEquals(((TextView) view).getText())) return true;
        if (!(view instanceof ViewGroup)) return false;
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) if (containsText(group.getChildAt(index), wantedText)) return true;
        return false;
    }

    private boolean sameModeList(List<UvcPreviewController.StreamMode> first, List<UvcPreviewController.StreamMode> second) {
        if (first.size() != second.size()) return false;
        for (int index = 0; index < first.size(); index++) if (!sameMode(first.get(index), second.get(index))) return false;
        return true;
    }

    private boolean sameMode(UvcPreviewController.StreamMode first, UvcPreviewController.StreamMode second) {
        return first != null && second != null
                && first.frameFormat == second.frameFormat
                && first.width == second.width
                && first.height == second.height
                && first.fps == second.fps;
    }

    private Button button(String text, boolean enabled, View.OnClickListener listener) {
        Button button = new Button(context);
        button.setAllCaps(false);
        button.setText(text);
        button.setTextSize(12);
        button.setEnabled(enabled);
        if (listener != null) button.setOnClickListener(listener);
        return button;
    }

    private void stylePrimaryButton(Button button) {
        boolean enabled = button.isEnabled();
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTextColor(enabled ? COLOR_PRIMARY_TEXT : COLOR_DISABLED_TEXT);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(42));
        button.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.RECTANGLE);
        background.setColor(enabled ? COLOR_PRIMARY : COLOR_DISABLED_FILL);
        background.setCornerRadius(dp(10));
        button.setBackground(background);
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
        textView.setGravity(Gravity.CENTER);
        return textView;
    }

    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
}
