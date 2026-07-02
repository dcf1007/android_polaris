package com.dcf1007.androidpolaris.ui;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.SeekBar;

import com.dcf1007.androidpolaris.backend.CameraHardwareBackend;
import com.dcf1007.androidpolaris.backend.PolarisAlignmentBackend;
import com.dcf1007.androidpolaris.backend.VideoAlignmentBackend;
import com.dcf1007.androidpolaris.model.AlignmentInput;
import com.dcf1007.androidpolaris.model.AlignmentResult;
import com.dcf1007.androidpolaris.model.RefractionMode;
import com.dcf1007.androidpolaris.util.UiFormatting;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Single UI controller for the whole screen.
 *
 * <p>This class is the only layer that mutates view state after construction: text contents, button
 * enabled state, dropdown contents, slider labels and preview geometry. It translates user actions
 * into backend calls and translates backend state snapshots into visible UI state.</p>
 */
public final class MainUiController implements CameraHardwareBackend.Listener {
    public static final int REQUEST_CAMERA_PERMISSION_FOR_UVC = 1001;
    public static final int REQUEST_LOCATION_PERMISSION = 1002;

    private final Activity activity;
    private final MainScreenView view;
    private final CameraHardwareBackend cameraBackend;
    private final VideoAlignmentBackend videoBackend;
    private final PolarisAlignmentBackend polarisBackend;
    private final Handler liveClockHandler = new Handler(Looper.getMainLooper());
    private final StringBuilder appDebugLog = new StringBuilder();

    private final List<CameraHardwareBackend.StreamMode> streamModes = new ArrayList<>();
    private final List<String> typeOptions = new ArrayList<>();
    private final List<String> resolutionOptions = new ArrayList<>();
    private final List<Integer> fpsOptions = new ArrayList<>();
    private CameraHardwareBackend.StreamMode selectedStreamMode;
    private CameraHardwareBackend.StreamMode activeStreamMode;
    private boolean bindingCameraControls;
    private boolean userSelectedStreamMode;
    private boolean applyingOverlayState;
    private VideoAlignmentBackend.State overlayState;

    private final Runnable liveClockRunnable = new Runnable() {
        @Override public void run() {
            if (view.liveTimeCheckBox.isChecked()) {
                view.dateTimeEditText.setText(UiFormatting.formatLocalDateTime(new Date()));
                calculateAndRenderAlignment();
            }
            liveClockHandler.postDelayed(this, 1000L);
        }
    };

    public MainUiController(Activity activity, MainScreenView view, CameraHardwareBackend cameraBackend,
                            VideoAlignmentBackend videoBackend, PolarisAlignmentBackend polarisBackend) {
        this.activity = activity;
        this.view = view;
        this.cameraBackend = cameraBackend;
        this.videoBackend = videoBackend;
        this.polarisBackend = polarisBackend;
    }

    public void initialize() {
        setInitialInputValues();
        loadAndApplyOverlayState();
        wireCameraPanel();
        wireAlignmentPanel();
        wirePolarisPanel();
        wireDebugPanel();
        calculateAndRenderAlignment();
        appendDebugLog("Application launched. UVC controls are ready; refresh or connect a USB camera to query modes.");
    }

    public void onResume() { liveClockHandler.post(liveClockRunnable); }
    public void onPause() { liveClockHandler.removeCallbacks(liveClockRunnable); cameraBackend.unregisterUsbMonitor(); }
    public void onDestroy() { cameraBackend.destroy(); view.reticleOverlayView.destroy(); }

    public void requestCameraPermissionThenOpenUvc() {
        appendDebugLog("UVC query requested.");
        if (activity.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION_FOR_UVC);
            return;
        }
        cameraBackend.requestPermissionAndOpenFirstCamera();
    }

    public void onCameraPermissionGranted() { cameraBackend.requestPermissionAndOpenFirstCamera(); }
    public void onCameraPermissionDenied() { setUvcStatus("Camera permission denied. Android requires CAMERA permission before UVC query."); }
    public void onLocationPermissionGranted() { fillLocationFromLastKnownProvider(); }
    public void onLocationPermissionDenied() { appendDebugLog("Location permission denied. Enter latitude/longitude manually."); }

    @Override public void onCameraStatusChanged(String statusText) { setUvcStatus(statusText); }
    @Override public void onCameraCapabilitiesChanged(CameraHardwareBackend.Capabilities capabilities) { renderCameraCapabilities(capabilities); }

    private void wireCameraPanel() {
        view.refreshUsbButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { requestCameraPermissionThenOpenUvc(); }
        });
        view.streamButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (activeStreamMode != null) cameraBackend.stopStream();
                else cameraBackend.startSelectedStream();
            }
        });
        view.streamTypeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View v, int position, long id) {
                if (bindingCameraControls || position < 0 || position >= typeOptions.size()) return;
                rebuildResolutionSpinner(typeOptions.get(position));
                selectStreamModeFromDropdowns(true);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        view.resolutionSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View v, int position, long id) {
                if (bindingCameraControls || position < 0 || position >= resolutionOptions.size()) return;
                rebuildFpsSpinner(currentTypeLabel(), resolutionOptions.get(position), selectedStreamMode == null ? -1 : selectedStreamMode.fps);
                selectStreamModeFromDropdowns(true);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        view.fpsSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View v, int position, long id) {
                if (!bindingCameraControls) selectStreamModeFromDropdowns(true);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        SeekBar.OnSeekBarChangeListener sliderListener = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { if (fromUser && !bindingCameraControls) updateCameraSliderLabels(); }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { if (!bindingCameraControls) applyCameraControls(); }
        };
        view.brightnessSlider.seekBar.setOnSeekBarChangeListener(sliderListener);
        view.contrastSlider.seekBar.setOnSeekBarChangeListener(sliderListener);
        view.gainSlider.seekBar.setOnSeekBarChangeListener(sliderListener);
        view.exposureSlider.seekBar.setOnSeekBarChangeListener(sliderListener);
        view.brightnessSlider.minusButton.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { stepFineSlider(view.brightnessSlider, -1); } });
        view.brightnessSlider.plusButton.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { stepFineSlider(view.brightnessSlider, 1); } });
        view.contrastSlider.minusButton.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { stepFineSlider(view.contrastSlider, -1); } });
        view.contrastSlider.plusButton.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { stepFineSlider(view.contrastSlider, 1); } });
        view.gainSlider.minusButton.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { stepFineSlider(view.gainSlider, -1); } });
        view.gainSlider.plusButton.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { stepFineSlider(view.gainSlider, 1); } });
        view.exposureSlider.minusButton.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { stepFineSlider(view.exposureSlider, -1); } });
        view.exposureSlider.plusButton.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { stepFineSlider(view.exposureSlider, 1); } });
        view.autoExposureCheckBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (bindingCameraControls) return;
                view.exposureSlider.setEnabled(!isChecked);
                applyCameraControls();
            }
        });
    }

    private void wireAlignmentPanel() {
        view.videoFitSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View v, int position, long id) {
                overlayState.fitMode = videoBackend.sanitizeFitMode(String.valueOf(parent.getItemAtPosition(position)));
                applyOverlayState(true);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        view.mirrorVideoCheckBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() { @Override public void onCheckedChanged(CompoundButton b, boolean c) { overlayState.mirrorVideo = c; applyOverlayState(true); } });
        view.lockVideoScaleCheckBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() { @Override public void onCheckedChanged(CompoundButton b, boolean c) { overlayState.lockScale = c; if (c) syncVideoSizeSliders(view.videoWidthSeekBar); applyOverlayState(true); } });
        view.resetAlignmentButton.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { resetOverlayAlignment(); } });
        SeekBar.OnSeekBarChangeListener overlaySliderListener = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { if (fromUser && !applyingOverlayState) { syncVideoSizeSliders(seekBar); applyOverlayState(true); } }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { applyOverlayState(true); }
        };
        view.videoOffsetXSeekBar.setOnSeekBarChangeListener(overlaySliderListener);
        view.videoOffsetYSeekBar.setOnSeekBarChangeListener(overlaySliderListener);
        view.videoWidthSeekBar.setOnSeekBarChangeListener(overlaySliderListener);
        view.videoHeightSeekBar.setOnSeekBarChangeListener(overlaySliderListener);
        view.videoRotationSeekBar.setOnSeekBarChangeListener(overlaySliderListener);
        view.reticleOpacitySeekBar.setOnSeekBarChangeListener(overlaySliderListener);
        view.videoOpacitySeekBar.setOnSeekBarChangeListener(overlaySliderListener);
    }

    private void wirePolarisPanel() {
        view.nowButton.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { view.liveTimeCheckBox.setChecked(false); view.dateTimeEditText.setText(UiFormatting.formatLocalDateTime(new Date())); calculateAndRenderAlignment(); } });
        view.useLocationButton.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { requestLocationOrFill(); } });
        view.calculateButton.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { calculateAndRenderAlignment(); } });
        AdapterView.OnItemSelectedListener recalculatingSpinner = new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View v, int position, long id) { clampOffsetDayToMonth(); updateAtmosphereFieldState(); calculateAndRenderAlignment(); }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        };
        view.offsetMonthSpinner.setOnItemSelectedListener(recalculatingSpinner);
        view.refractionSpinner.setOnItemSelectedListener(recalculatingSpinner);
        CompoundButton.OnCheckedChangeListener recalculatingCheck = new CompoundButton.OnCheckedChangeListener() { @Override public void onCheckedChanged(CompoundButton b, boolean c) { calculateAndRenderAlignment(); } };
        view.liveTimeCheckBox.setOnCheckedChangeListener(recalculatingCheck);
        view.lockZeroHourAngleCheckBox.setOnCheckedChangeListener(recalculatingCheck);
        TextWatcher recalculatingText = new SimpleTextWatcher() { @Override public void afterTextChanged(Editable s) { calculateAndRenderAlignment(); } };
        view.dateTimeEditText.addTextChangedListener(recalculatingText);
        view.latitudeEditText.addTextChangedListener(recalculatingText);
        view.longitudeEditText.addTextChangedListener(recalculatingText);
        view.rightAscensionHoursEditText.addTextChangedListener(recalculatingText);
        view.rightAscensionMinutesEditText.addTextChangedListener(recalculatingText);
        view.rightAscensionSecondsEditText.addTextChangedListener(recalculatingText);
        view.offsetDayEditText.addTextChangedListener(recalculatingText);
        view.pressureEditText.addTextChangedListener(recalculatingText);
        view.temperatureEditText.addTextChangedListener(recalculatingText);
        view.elevationEditText.addTextChangedListener(recalculatingText);
        updateAtmosphereFieldState();
    }

    private void wireDebugPanel() { view.saveLogButton.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { cameraBackend.saveDebugLogToDownloads(); } }); }

    private void setInitialInputValues() {
        view.dateTimeEditText.setText(UiFormatting.formatLocalDateTime(new Date()));
        view.latitudeEditText.setText("52.520008");
        view.longitudeEditText.setText("13.404954");
        view.rightAscensionHoursEditText.setText("0");
        view.rightAscensionMinutesEditText.setText("0");
        view.rightAscensionSecondsEditText.setText("0.0");
        view.offsetMonthSpinner.setSelection(9);
        view.offsetDayEditText.setText("31");
        view.pressureEditText.setText("1013.25");
        view.temperatureEditText.setText("10.0");
        view.elevationEditText.setText("0");
        view.liveTimeCheckBox.setChecked(true);
        view.lockVideoScaleCheckBox.setChecked(true);
    }

    private void renderCameraCapabilities(CameraHardwareBackend.Capabilities capabilities) {
        bindingCameraControls = true;
        activeStreamMode = capabilities.previewRunning ? capabilities.activeStreamMode : null;
        if (!sameModeList(streamModes, capabilities.streamModes)) {
            streamModes.clear();
            streamModes.addAll(capabilities.streamModes);
            rebuildTypeSpinner();
        }
        CameraHardwareBackend.StreamMode reported = capabilities.selectedStreamMode != null ? capabilities.selectedStreamMode : capabilities.activeStreamMode;
        if (capabilities.previewRunning) selectedStreamMode = capabilities.activeStreamMode;
        else if (!userSelectedStreamMode && !streamModes.isEmpty()) selectedStreamMode = chooseDefaultMode();
        else if (selectedStreamMode == null) selectedStreamMode = reported;
        selectCurrentModeInSpinners();
        if (!userSelectedStreamMode && !capabilities.previewRunning && selectedStreamMode != null && !sameMode(selectedStreamMode, reported)) cameraBackend.selectStreamMode(selectedStreamMode);

        boolean canSelect = capabilities.cameraOpen && !capabilities.previewRunning && !streamModes.isEmpty();
        view.streamTypeSpinner.setEnabled(canSelect);
        view.resolutionSpinner.setEnabled(canSelect && !resolutionOptions.isEmpty());
        view.fpsSpinner.setEnabled(canSelect && !fpsOptions.isEmpty());
        UiStyle.applyPrimaryButtonState(activity, view.refreshUsbButton, !capabilities.previewRunning);
        view.streamButton.setText(capabilities.previewRunning ? "Stop stream" : "Start stream");
        UiStyle.applyPrimaryButtonState(activity, view.streamButton, capabilities.previewRunning || (canSelect && selectedModeFromSpinners() != null));

        view.brightnessSlider.setEnabled(capabilities.cameraOpen && capabilities.brightnessSupported);
        view.contrastSlider.setEnabled(capabilities.cameraOpen && capabilities.contrastSupported);
        view.gainSlider.setEnabled(capabilities.cameraOpen && capabilities.gainSupported);
        view.autoExposureCheckBox.setEnabled(capabilities.cameraOpen && capabilities.autoExposureSupported);
        view.autoExposureCheckBox.setChecked(capabilities.autoExposureEnabled);
        view.exposureSlider.setProgress(capabilities.exposurePercent);
        view.exposureSlider.setEnabled(capabilities.cameraOpen && capabilities.exposureSupported && !capabilities.autoExposureEnabled);
        updateCameraSliderLabels();
        applyPreviewTextureFit();
        bindingCameraControls = false;
    }

    private void rebuildTypeSpinner() {
        typeOptions.clear();
        for (CameraHardwareBackend.StreamMode mode : streamModes) if (!typeOptions.contains(mode.formatLabel())) typeOptions.add(mode.formatLabel());
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_item, typeOptions);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        view.streamTypeSpinner.setAdapter(adapter);
        CameraHardwareBackend.StreamMode mode = selectedStreamMode == null ? chooseDefaultMode() : selectedStreamMode;
        String type = mode == null ? (typeOptions.isEmpty() ? null : typeOptions.get(0)) : mode.formatLabel();
        int index = typeOptions.indexOf(type);
        if (index >= 0) view.streamTypeSpinner.setSelection(index, false);
        rebuildResolutionSpinner(type);
    }

    private void rebuildResolutionSpinner(String type) {
        resolutionOptions.clear();
        for (CameraHardwareBackend.StreamMode mode : streamModes) if (type != null && type.equals(mode.formatLabel()) && !resolutionOptions.contains(mode.resolutionLabel())) resolutionOptions.add(mode.resolutionLabel());
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_item, resolutionOptions);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        view.resolutionSpinner.setAdapter(adapter);
        String resolution = selectedStreamMode == null ? null : selectedStreamMode.resolutionLabel();
        if ((resolution == null || !resolutionOptions.contains(resolution)) && !resolutionOptions.isEmpty()) resolution = resolutionOptions.get(0);
        int index = resolutionOptions.indexOf(resolution);
        if (index >= 0) view.resolutionSpinner.setSelection(index, false);
        rebuildFpsSpinner(type, resolution, selectedStreamMode == null ? -1 : selectedStreamMode.fps);
    }

    private void rebuildFpsSpinner(String type, String resolution, int preferredFps) {
        fpsOptions.clear();
        for (CameraHardwareBackend.StreamMode mode : streamModes) if (type != null && type.equals(mode.formatLabel()) && resolution != null && resolution.equals(mode.resolutionLabel()) && !fpsOptions.contains(mode.fps)) fpsOptions.add(mode.fps);
        ArrayAdapter<Integer> adapter = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_item, fpsOptions);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        view.fpsSpinner.setAdapter(adapter);
        int index = fpsOptions.indexOf(preferredFps);
        if (index < 0) index = highestFpsIndex();
        if (index >= 0) view.fpsSpinner.setSelection(index, false);
    }

    private void selectCurrentModeInSpinners() { if (selectedStreamMode != null) rebuildTypeSpinner(); }
    private String currentTypeLabel() { int i = view.streamTypeSpinner.getSelectedItemPosition(); return i >= 0 && i < typeOptions.size() ? typeOptions.get(i) : null; }

    private void selectStreamModeFromDropdowns(boolean fromUser) {
        CameraHardwareBackend.StreamMode mode = selectedModeFromSpinners();
        if (mode != null && !sameMode(mode, selectedStreamMode)) {
            selectedStreamMode = mode;
            if (fromUser) userSelectedStreamMode = true;
            cameraBackend.selectStreamMode(mode);
        }
    }

    private CameraHardwareBackend.StreamMode selectedModeFromSpinners() {
        String type = currentTypeLabel();
        int resolutionIndex = view.resolutionSpinner.getSelectedItemPosition();
        int fpsIndex = view.fpsSpinner.getSelectedItemPosition();
        if (type == null || resolutionIndex < 0 || resolutionIndex >= resolutionOptions.size() || fpsIndex < 0 || fpsIndex >= fpsOptions.size()) return null;
        String resolution = resolutionOptions.get(resolutionIndex);
        int fps = fpsOptions.get(fpsIndex);
        for (CameraHardwareBackend.StreamMode mode : streamModes) if (type.equals(mode.formatLabel()) && resolution.equals(mode.resolutionLabel()) && mode.fps == fps) return mode;
        return null;
    }

    private CameraHardwareBackend.StreamMode chooseDefaultMode() { CameraHardwareBackend.StreamMode yuyv = bestMode(true); return yuyv != null ? yuyv : bestMode(false); }
    private CameraHardwareBackend.StreamMode bestMode(boolean yuyvOnly) { CameraHardwareBackend.StreamMode best = null; for (CameraHardwareBackend.StreamMode mode : streamModes) { if (yuyvOnly && !mode.formatLabel().contains("YUYV")) continue; if (best == null || rank(mode) > rank(best)) best = mode; } return best; }
    private long rank(CameraHardwareBackend.StreamMode mode) { return (long) mode.width * mode.height * 10000L + mode.fps; }
    private int highestFpsIndex() { int bestIndex = -1, bestFps = -1; for (int i = 0; i < fpsOptions.size(); i++) if (fpsOptions.get(i) > bestFps) { bestFps = fpsOptions.get(i); bestIndex = i; } return bestIndex; }
    private boolean sameModeList(List<CameraHardwareBackend.StreamMode> first, List<CameraHardwareBackend.StreamMode> second) { if (first.size() != second.size()) return false; for (int i = 0; i < first.size(); i++) if (!sameMode(first.get(i), second.get(i))) return false; return true; }
    private boolean sameMode(CameraHardwareBackend.StreamMode first, CameraHardwareBackend.StreamMode second) { return first != null && second != null && first.frameFormat == second.frameFormat && first.width == second.width && first.height == second.height && first.fps == second.fps; }

    private void stepFineSlider(MainScreenView.FineSlider slider, int delta) { if (slider.seekBar.isEnabled()) { slider.setProgress(slider.progress() + delta); updateCameraSliderLabels(); applyCameraControls(); } }
    private void updateCameraSliderLabels() { view.brightnessSlider.updateLabel(); view.contrastSlider.updateLabel(); view.gainSlider.updateLabel(); view.exposureSlider.updateLabel(); }
    private void applyCameraControls() { cameraBackend.setCameraControls(view.brightnessSlider.progress(), view.contrastSlider.progress(), view.gainSlider.progress(), view.exposureSlider.progress(), view.autoExposureCheckBox.isChecked()); }

    private void loadAndApplyOverlayState() {
        overlayState = videoBackend.load(activity);
        applyingOverlayState = true;
        view.mirrorVideoCheckBox.setChecked(overlayState.mirrorVideo);
        view.lockVideoScaleCheckBox.setChecked(overlayState.lockScale);
        selectSpinnerText(view.videoFitSpinner, overlayState.fitMode);
        view.videoOffsetXSeekBar.setProgress(videoBackend.signedPercentToProgress(overlayState.offsetXPercent));
        view.videoOffsetYSeekBar.setProgress(videoBackend.signedPercentToProgress(overlayState.offsetYPercent));
        view.videoWidthSeekBar.setProgress(videoBackend.sizePercentToProgress(overlayState.widthPercent));
        view.videoHeightSeekBar.setProgress(videoBackend.sizePercentToProgress(overlayState.heightPercent));
        view.videoRotationSeekBar.setProgress(videoBackend.rotationDegreesToProgress(overlayState.rotationDegrees));
        view.reticleOpacitySeekBar.setProgress(Math.round(overlayState.reticleOpacityPercent));
        view.videoOpacitySeekBar.setProgress(Math.round(overlayState.videoOpacityPercent));
        applyingOverlayState = false;
        applyOverlayState(false);
    }

    private void applyOverlayState(boolean shouldSave) {
        if (applyingOverlayState) return;
        overlayState.offsetXPercent = videoBackend.signedPercentFromProgress(view.videoOffsetXSeekBar);
        overlayState.offsetYPercent = videoBackend.signedPercentFromProgress(view.videoOffsetYSeekBar);
        overlayState.widthPercent = videoBackend.sizePercentFromProgress(view.videoWidthSeekBar);
        overlayState.heightPercent = videoBackend.sizePercentFromProgress(view.videoHeightSeekBar);
        overlayState.rotationDegrees = videoBackend.rotationDegreesFromProgress(view.videoRotationSeekBar);
        overlayState.reticleOpacityPercent = view.reticleOpacitySeekBar.getProgress();
        overlayState.videoOpacityPercent = view.videoOpacitySeekBar.getProgress();
        overlayState.mirrorVideo = view.mirrorVideoCheckBox.isChecked();
        overlayState.lockScale = view.lockVideoScaleCheckBox.isChecked();
        overlayState.fitMode = videoBackend.sanitizeFitMode(overlayState.fitMode);
        updateOverlayLabels();
        updateVideoLayerTransform();
        applyPreviewTextureFit();
        if (shouldSave) videoBackend.save(activity, overlayState);
    }

    private void updateVideoLayerTransform() {
        if (view.stageFrame.getWidth() <= 0 || view.stageFrame.getHeight() <= 0) { view.stageFrame.post(new Runnable() { @Override public void run() { updateVideoLayerTransform(); } }); return; }
        int width = Math.max(1, Math.round(view.stageFrame.getWidth() * overlayState.widthPercent / 100.0f));
        int height = Math.max(1, Math.round(view.stageFrame.getHeight() * overlayState.heightPercent / 100.0f));
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) view.videoLayer.getLayoutParams();
        if (params.width != width || params.height != height || params.gravity != Gravity.CENTER) { params.width = width; params.height = height; params.gravity = Gravity.CENTER; view.videoLayer.setLayoutParams(params); }
        view.videoLayer.setTranslationX(view.stageFrame.getWidth() * overlayState.offsetXPercent / 100.0f);
        view.videoLayer.setTranslationY(view.stageFrame.getHeight() * overlayState.offsetYPercent / 100.0f);
        view.videoLayer.setRotation(overlayState.rotationDegrees);
        view.videoLayer.setAlpha(overlayState.videoOpacityPercent / 100.0f);
        view.previewMirrorLayer.setScaleX(overlayState.mirrorVideo ? -1.0f : 1.0f);
        view.previewMirrorLayer.setScaleY(1.0f);
        view.reticleOverlayView.setAlpha(overlayState.reticleOpacityPercent / 100.0f);
    }

    private void applyPreviewTextureFit() {
        if (view.previewMirrorLayer.getWidth() <= 0 || view.previewMirrorLayer.getHeight() <= 0) return;
        float sourceAspect = activeStreamMode != null ? activeStreamMode.width / (float) activeStreamMode.height : 4.0f / 3.0f;
        int containerWidth = view.previewMirrorLayer.getWidth();
        int containerHeight = view.previewMirrorLayer.getHeight();
        int textureWidth = containerWidth;
        int textureHeight = containerHeight;
        if (!VideoAlignmentBackend.FIT_STRETCH.equals(overlayState.fitMode)) {
            boolean containerWide = containerWidth / (float) containerHeight > sourceAspect;
            if (VideoAlignmentBackend.FIT_CONTAIN.equals(overlayState.fitMode)) {
                if (containerWide) { textureHeight = containerHeight; textureWidth = Math.round(textureHeight * sourceAspect); }
                else { textureWidth = containerWidth; textureHeight = Math.round(textureWidth / sourceAspect); }
            } else {
                if (containerWide) { textureWidth = containerWidth; textureHeight = Math.round(textureWidth / sourceAspect); }
                else { textureHeight = containerHeight; textureWidth = Math.round(textureHeight * sourceAspect); }
            }
        }
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) view.previewTextureView.getLayoutParams();
        if (params.width != textureWidth || params.height != textureHeight || params.gravity != Gravity.CENTER) { params.width = textureWidth; params.height = textureHeight; params.gravity = Gravity.CENTER; view.previewTextureView.setLayoutParams(params); }
    }

    private void resetOverlayAlignment() {
        overlayState = videoBackend.defaultState();
        applyingOverlayState = true;
        view.videoOffsetXSeekBar.setProgress(videoBackend.signedPercentToProgress(0.0f));
        view.videoOffsetYSeekBar.setProgress(videoBackend.signedPercentToProgress(0.0f));
        view.videoWidthSeekBar.setProgress(videoBackend.sizePercentToProgress(100.0f));
        view.videoHeightSeekBar.setProgress(videoBackend.sizePercentToProgress(100.0f));
        view.videoRotationSeekBar.setProgress(videoBackend.rotationDegreesToProgress(0.0f));
        view.reticleOpacitySeekBar.setProgress(100);
        view.videoOpacitySeekBar.setProgress(100);
        view.mirrorVideoCheckBox.setChecked(false);
        view.lockVideoScaleCheckBox.setChecked(true);
        selectSpinnerText(view.videoFitSpinner, VideoAlignmentBackend.FIT_COVER);
        applyingOverlayState = false;
        applyOverlayState(true);
        appendDebugLog("Video/overlay alignment reset.");
    }

    private void syncVideoSizeSliders(SeekBar changed) { if (view.lockVideoScaleCheckBox.isChecked()) { if (changed == view.videoWidthSeekBar) view.videoHeightSeekBar.setProgress(view.videoWidthSeekBar.getProgress()); else if (changed == view.videoHeightSeekBar) view.videoWidthSeekBar.setProgress(view.videoHeightSeekBar.getProgress()); } }
    private void updateOverlayLabels() { view.videoOffsetXValue.setText(String.format(Locale.US, "%.1f%%", overlayState.offsetXPercent)); view.videoOffsetYValue.setText(String.format(Locale.US, "%.1f%%", overlayState.offsetYPercent)); view.videoWidthValue.setText(String.format(Locale.US, "%.1f%%", overlayState.widthPercent)); view.videoHeightValue.setText(String.format(Locale.US, "%.1f%%", overlayState.heightPercent)); view.videoRotationValue.setText(String.format(Locale.US, "%.1f°", overlayState.rotationDegrees)); view.reticleOpacityValue.setText(String.format(Locale.US, "%.0f%%", overlayState.reticleOpacityPercent)); view.videoOpacityValue.setText(String.format(Locale.US, "%.0f%%", overlayState.videoOpacityPercent)); }

    private void calculateAndRenderAlignment() {
        try {
            clampOffsetDayToMonth();
            PolarisAlignmentBackend.Request request = readPolarisRequest();
            AlignmentInput input = polarisBackend.toInput(request);
            AlignmentResult result = polarisBackend.calculate(request);
            view.reticleOverlayView.setAlignmentResult(result);
            view.readoutText.setText(polarisBackend.formatReadout(result, input));
            if (!result.warningText.isEmpty()) appendDebugLog(result.warningText);
        } catch (Throwable throwable) {
            view.reticleOverlayView.setAlignmentResult(null);
            appendDebugLog("Calculation error: " + throwable.getMessage());
        }
    }

    private PolarisAlignmentBackend.Request readPolarisRequest() {
        PolarisAlignmentBackend.Request request = new PolarisAlignmentBackend.Request();
        request.localDateTimeText = view.dateTimeEditText.getText().toString();
        request.latitudeText = view.latitudeEditText.getText().toString();
        request.longitudeText = view.longitudeEditText.getText().toString();
        request.rightAscensionHoursText = view.rightAscensionHoursEditText.getText().toString();
        request.rightAscensionMinutesText = view.rightAscensionMinutesEditText.getText().toString();
        request.rightAscensionSecondsText = view.rightAscensionSecondsEditText.getText().toString();
        request.offsetMonth = view.offsetMonthSpinner.getSelectedItemPosition() + 1;
        request.offsetDayText = view.offsetDayEditText.getText().toString();
        request.lockZeroHourAngle = view.lockZeroHourAngleCheckBox.isChecked();
        request.refractionMode = (RefractionMode) view.refractionSpinner.getSelectedItem();
        request.pressureText = view.pressureEditText.getText().toString();
        request.temperatureText = view.temperatureEditText.getText().toString();
        request.elevationText = view.elevationEditText.getText().toString();
        return request;
    }

    private void clampOffsetDayToMonth() { int month = view.offsetMonthSpinner.getSelectedItemPosition() + 1; int clamped = polarisBackend.clampOffsetDay(month, view.offsetDayEditText.getText().toString()); if (clamped > 0 && !String.valueOf(clamped).equals(view.offsetDayEditText.getText().toString().trim())) { view.offsetDayEditText.setText(String.valueOf(clamped)); view.offsetDayEditText.setSelection(view.offsetDayEditText.getText().length()); } }
    private void updateAtmosphereFieldState() { RefractionMode mode = (RefractionMode) view.refractionSpinner.getSelectedItem(); view.pressureEditText.setEnabled(mode == RefractionMode.SCALED_PRESSURE_TEMPERATURE); view.temperatureEditText.setEnabled(mode == RefractionMode.SCALED_PRESSURE_TEMPERATURE || mode == RefractionMode.ALTITUDE_PRESSURE_TEMPERATURE); view.elevationEditText.setEnabled(mode == RefractionMode.ALTITUDE_PRESSURE_TEMPERATURE); }

    private void requestLocationOrFill() {
        if (activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQUEST_LOCATION_PERMISSION);
            return;
        }
        fillLocationFromLastKnownProvider();
    }

    private void fillLocationFromLastKnownProvider() {
        LocationManager locationManager = (LocationManager) activity.getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) { appendDebugLog("Location service is unavailable. Enter coordinates manually."); return; }
        if (activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) { appendDebugLog("Location permission is required. Enter coordinates manually or grant permission."); return; }
        Location best = null;
        for (String provider : locationManager.getProviders(true)) { Location candidate = locationManager.getLastKnownLocation(provider); if (candidate != null && (best == null || candidate.getAccuracy() < best.getAccuracy())) best = candidate; }
        if (best == null) { appendDebugLog("No last known location. Enable location or enter coordinates manually."); return; }
        view.latitudeEditText.setText(String.format(Locale.US, "%.6f", best.getLatitude()));
        view.longitudeEditText.setText(String.format(Locale.US, "%.6f", best.getLongitude()));
        if (best.hasAltitude()) view.elevationEditText.setText(String.format(Locale.US, "%.0f", best.getAltitude()));
        appendDebugLog("Filled last known Android location. Verify coordinates before alignment.");
        calculateAndRenderAlignment();
    }

    private void setUvcStatus(String status) { view.uvcStatusText.setText(status == null ? "" : status); appendDebugLog(status == null ? "UVC status cleared." : status); }
    private void appendDebugLog(String message) { if (message == null || message.trim().isEmpty()) return; appDebugLog.append(String.format(Locale.US, "[%tT] %s\n", new Date(), message.trim())); if (appDebugLog.length() > 9000) appDebugLog.delete(0, appDebugLog.length() - 9000); view.debugLogText.setText(appDebugLog.toString().trim()); }
    private void selectSpinnerText(android.widget.Spinner spinner, String wanted) { for (int i = 0; spinner != null && i < spinner.getCount(); i++) if (wanted.equals(String.valueOf(spinner.getItemAtPosition(i)))) { spinner.setSelection(i); return; } if (spinner != null) spinner.setSelection(0); }

    private abstract static class SimpleTextWatcher implements TextWatcher { @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { } @Override public void onTextChanged(CharSequence s, int start, int before, int count) { } }
}
