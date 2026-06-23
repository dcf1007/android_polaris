package com.dcf1007.androidpolaris;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import com.dcf1007.androidpolaris.astro.PolarisAlignmentCalculator;
import com.dcf1007.androidpolaris.camera.UvcPreviewController;
import com.dcf1007.androidpolaris.model.AlignmentInput;
import com.dcf1007.androidpolaris.model.AlignmentResult;
import com.dcf1007.androidpolaris.model.RefractionMode;
import com.dcf1007.androidpolaris.util.UiFormatting;
import com.dcf1007.androidpolaris.view.ReticleOverlayView;

import java.text.ParseException;
import java.util.Date;
import java.util.Locale;

/**
 * Main single-activity Android app.
 *
 * <p>The UI is intentionally built as a native Android equivalent of the original dark browser
 * interface: a square camera stage with the reticle overlay, a small stage badge, rounded control
 * panels, two-column action rows, labelled values, visibility controls, and status/readout blocks.
 * The implementation remains UVC-only and does not use WebView, SVG rendering, or Camera2.</p>
 */
public final class MainActivity extends Activity {
    private static final int REQUEST_CAMERA_PERMISSION_FOR_UVC = 1001;
    private static final int REQUEST_LOCATION_PERMISSION = 1002;
    private static final int USB_VIDEO_CLASS = 14;

    private static final String SETTINGS_NAME = "android_polaris_overlay_settings";
    private static final String[] MONTH_NAMES = {
            "January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December"
    };
    private static final int[] MONTH_DAYS = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};

    // HTML-derived dark theme tokens.
    private static final int COLOR_BACKGROUND = Color.rgb(15, 17, 21);
    private static final int COLOR_STAGE_WRAP = Color.rgb(9, 10, 13);
    private static final int COLOR_PANEL = Color.rgb(24, 27, 34);
    private static final int COLOR_PANEL_2 = Color.rgb(32, 36, 45);
    private static final int COLOR_TEXT = Color.rgb(243, 245, 247);
    private static final int COLOR_MUTED = Color.rgb(174, 182, 194);
    private static final int COLOR_BORDER = Color.rgb(52, 58, 70);
    private static final int COLOR_ACCENT = Color.rgb(134, 183, 255);
    private static final int COLOR_DANGER = Color.rgb(255, 138, 138);
    private static final int COLOR_OK = Color.rgb(158, 240, 179);
    private static final int COLOR_WARN = Color.rgb(255, 212, 121);

    private final Handler liveClockHandler = new Handler(Looper.getMainLooper());
    private final PolarisAlignmentCalculator alignmentCalculator = new PolarisAlignmentCalculator();

    private FrameLayout stageFrame;
    private FrameLayout videoLayer;
    private FrameLayout uvcPreviewContainer;
    private ReticleOverlayView reticleOverlayView;
    private TextView stageBadgeTextView;

    private Spinner refractionSpinner;
    private Spinner offsetMonthSpinner;
    private Spinner videoFitSpinner;
    private CheckBox liveClockCheckBox;
    private CheckBox lockZeroHourAngleCheckBox;
    private CheckBox mirrorVideoCheckBox;
    private CheckBox lockVideoScaleCheckBox;

    private EditText dateTimeEditText;
    private EditText latitudeEditText;
    private EditText longitudeEditText;
    private EditText rightAscensionHoursEditText;
    private EditText rightAscensionMinutesEditText;
    private EditText rightAscensionSecondsEditText;
    private EditText offsetDayEditText;
    private EditText pressureEditText;
    private EditText temperatureEditText;
    private EditText elevationEditText;

    private SeekBar videoOffsetXSeekBar;
    private SeekBar videoOffsetYSeekBar;
    private SeekBar videoWidthSeekBar;
    private SeekBar videoHeightSeekBar;
    private SeekBar videoRotationSeekBar;
    private SeekBar reticleOpacitySeekBar;
    private SeekBar videoOpacitySeekBar;
    private TextView videoOffsetXValueTextView;
    private TextView videoOffsetYValueTextView;
    private TextView videoWidthValueTextView;
    private TextView videoHeightValueTextView;
    private TextView videoRotationValueTextView;
    private TextView reticleOpacityValueTextView;
    private TextView videoOpacityValueTextView;

    private TextView statusTextView;
    private TextView uvcStatusTextView;
    private TextView readoutTextView;
    private TextView debugLogTextView;
    private TextView settingsOutTextView;

    private UvcPreviewController uvcPreviewController;
    private boolean isBuildingOrInitializingUi;
    private boolean isApplyingOverlayControlState;

    private float videoOffsetXPercent;
    private float videoOffsetYPercent;
    private float videoWidthPercent = 100.0f;
    private float videoHeightPercent = 100.0f;
    private float videoRotationDegrees;
    private float reticleOpacityPercent = 100.0f;
    private float videoOpacityPercent = 100.0f;
    private String videoFitMode = "Native UVC aspect";
    private final StringBuilder debugLogBuilder = new StringBuilder();

    private final Runnable liveClockRunnable = new Runnable() {
        @Override public void run() {
            if (liveClockCheckBox != null && liveClockCheckBox.isChecked()) {
                dateTimeEditText.setText(UiFormatting.formatLocalDateTime(new Date()));
                calculateAndRenderAlignment();
            }
            liveClockHandler.postDelayed(this, 1000L);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUserInterface();
        setInitialValues();
        loadOverlayControlSettings();
        wireUserInterfaceActions();
        refreshUvcStatus();
        calculateAndRenderAlignment();
        appendDebugLog("Application launched. UVC backend remains unloaded until Open USB UVC camera is pressed.");
    }

    @Override protected void onResume() {
        super.onResume();
        liveClockHandler.post(liveClockRunnable);
    }

    @Override protected void onPause() {
        liveClockHandler.removeCallbacks(liveClockRunnable);
        if (uvcPreviewController != null) {
            uvcPreviewController.unregister();
        }
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (uvcPreviewController != null) {
            uvcPreviewController.destroy();
            uvcPreviewController = null;
        }
        if (reticleOverlayView != null) {
            reticleOverlayView.destroy();
        }
        super.onDestroy();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION_FOR_UVC) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openUvcCameraAfterRuntimePermission();
            } else {
                setUvcStatus("Camera permission denied. Android requires CAMERA permission before the UVC backend can open preview.");
            }
        } else if (requestCode == REQUEST_LOCATION_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                fillLocationFromLastKnownProvider();
            } else {
                setAlignmentStatus("Location permission denied. Enter latitude/longitude manually.", COLOR_WARN);
            }
        }
    }

    private void buildUserInterface() {
        isBuildingOrInitializingUi = true;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BACKGROUND);
        root.setPadding(dp(12), 0, dp(12), 0);

        // Header mirrors the browser page: compact title plus muted instruction line.
        TextView title = createText("Camera + native reticle overlay aligner", 18, COLOR_TEXT, true);
        title.setPadding(dp(2), dp(12), dp(2), dp(2));
        root.addView(title, matchWrapParams());

        TextView hint = createText(
                "Open the USB OTG UVC camera, then use the alignment controls to match the video to the reticle. The reticle is native Canvas geometry generated from the full drawing.",
                13, COLOR_MUTED, false);
        hint.setPadding(dp(2), 0, dp(2), dp(10));
        root.addView(hint, matchWrapParams());

        // The stage wrapper uses the same rounded black card treatment as the HTML version.
        FrameLayout stageWrapper = new FrameLayout(this);
        stageWrapper.setPadding(dp(10), dp(10), dp(10), dp(10));
        stageWrapper.setBackground(roundedBackground(COLOR_STAGE_WRAP, COLOR_BORDER, 16));
        root.addView(stageWrapper, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        stageFrame = new ReticleAspectStageLayout(this);
        stageFrame.setBackgroundColor(Color.BLACK);
        stageFrame.setClipToOutline(false);
        stageWrapper.addView(stageFrame, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        // Video layer is transformed by the alignment sliders. The UVC preview itself keeps its
        // native negotiated aspect; this layer only positions/scales/rotates the preview relative to
        // the fixed reticle coordinate system.
        videoLayer = new FrameLayout(this);
        videoLayer.setBackgroundColor(Color.BLACK);
        stageFrame.addView(videoLayer, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER));

        uvcPreviewContainer = new FrameLayout(this);
        uvcPreviewContainer.setBackgroundColor(Color.BLACK);
        videoLayer.addView(uvcPreviewContainer, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER));

        reticleOverlayView = new ReticleOverlayView(this);
        stageFrame.addView(reticleOverlayView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        stageBadgeTextView = createText("No camera running", 12, COLOR_MUTED, false);
        stageBadgeTextView.setSingleLine(true);
        stageBadgeTextView.setPadding(dp(8), dp(5), dp(8), dp(5));
        stageBadgeTextView.setBackground(roundedBackground(Color.argb(170, 0, 0, 0), Color.TRANSPARENT, 999));
        FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        badgeParams.setMargins(dp(8), dp(8), dp(8), dp(8));
        stageFrame.addView(stageBadgeTextView, badgeParams);

        ScrollView controlsScrollView = new ScrollView(this);
        controlsScrollView.setFillViewport(false);
        root.addView(controlsScrollView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.0f));

        LinearLayout controlsColumn = new LinearLayout(this);
        controlsColumn.setOrientation(LinearLayout.VERTICAL);
        controlsColumn.setPadding(0, dp(12), 0, dp(18));
        controlsScrollView.addView(controlsColumn, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        LinearLayout cameraPanel = createPanel();
        controlsColumn.addView(cameraPanel, matchWrapParamsWithBottomMargin(12));
        populateCameraPanel(cameraPanel);

        LinearLayout alignmentPanel = createPanel();
        controlsColumn.addView(alignmentPanel, matchWrapParamsWithBottomMargin(12));
        populateAlignmentPanel(alignmentPanel);

        LinearLayout astronomyPanel = createPanel();
        controlsColumn.addView(astronomyPanel, matchWrapParamsWithBottomMargin(12));
        populateAstronomyPanel(astronomyPanel);

        LinearLayout statusPanel = createPanel();
        controlsColumn.addView(statusPanel, matchWrapParams());
        populateStatusPanel(statusPanel);

        setContentView(root);
        isBuildingOrInitializingUi = false;
    }

    private void populateCameraPanel(LinearLayout panel) {
        panel.addView(labelWithRightValue("USB OTG UVC camera", "raw UVC only", null));

        LinearLayout row1 = horizontalRow();
        Button openButton = createButton("Open USB UVC camera", true, false,
                new View.OnClickListener() { @Override public void onClick(View view) { requestCameraPermissionThenOpenUvc(); } });
        Button statusButton = createButton("USB status", false, false,
                new View.OnClickListener() { @Override public void onClick(View view) { refreshUvcStatus(); } });
        row1.addView(openButton, weightParams());
        row1.addView(statusButton, weightParams());
        panel.addView(row1);

        LinearLayout row2 = horizontalRow();
        Button stopButton = createButton("Stop camera", false, true,
                new View.OnClickListener() { @Override public void onClick(View view) { stopUvcCamera(); } });
        Button copyButton = createButton("Copy settings", false, false,
                new View.OnClickListener() { @Override public void onClick(View view) { copyCurrentSettingsToClipboard(); } });
        row2.addView(stopButton, weightParams());
        row2.addView(copyButton, weightParams());
        panel.addView(row2);

        LinearLayout fitRow = horizontalRow();
        LinearLayout fitColumn = verticalColumn();
        fitColumn.addView(smallLabel("Video fit"));
        videoFitSpinner = createSpinner(new String[]{"Native UVC aspect", "Cover / crop", "Contain / no crop", "Fill / stretch"});
        fitColumn.addView(videoFitSpinner, matchWrapParams());
        fitRow.addView(fitColumn, weightParams());

        LinearLayout checkColumn = verticalColumn();
        mirrorVideoCheckBox = createCheckBox("Mirror video");
        lockVideoScaleCheckBox = createCheckBox("Lock width/height");
        lockVideoScaleCheckBox.setChecked(true);
        checkColumn.addView(mirrorVideoCheckBox, matchWrapParams());
        checkColumn.addView(lockVideoScaleCheckBox, matchWrapParams());
        fitRow.addView(checkColumn, weightParams());
        panel.addView(fitRow);

        uvcStatusTextView = createTextArea("UVC status: not scanned.", false);
        panel.addView(uvcStatusTextView, matchWrapParams());
    }

    private void populateAlignmentPanel(LinearLayout panel) {
        panel.addView(sectionTitle("Alignment"));
        videoOffsetXSeekBar = addSlider(panel, "Horizontal position", "0%", videoOffsetXValueTextView = new TextView(this));
        videoOffsetYSeekBar = addSlider(panel, "Vertical position", "0%", videoOffsetYValueTextView = new TextView(this));
        videoWidthSeekBar = addSlider(panel, "Video width", "100%", videoWidthValueTextView = new TextView(this));
        videoHeightSeekBar = addSlider(panel, "Video height", "100%", videoHeightValueTextView = new TextView(this));
        videoRotationSeekBar = addSlider(panel, "Rotation", "0°", videoRotationValueTextView = new TextView(this));

        panel.addView(sectionTitle("Visibility"));
        reticleOpacitySeekBar = addSlider(panel, "Overlay opacity", "100%", reticleOpacityValueTextView = new TextView(this));
        videoOpacitySeekBar = addSlider(panel, "Video opacity", "100%", videoOpacityValueTextView = new TextView(this));

        LinearLayout actionRow = horizontalRow();
        actionRow.addView(createButton("Reset alignment", false, true,
                new View.OnClickListener() { @Override public void onClick(View view) { resetVideoOverlayAlignment(); } }), weightParams());
        actionRow.addView(createButton("Apply", false, false,
                new View.OnClickListener() { @Override public void onClick(View view) { applyVideoOverlayControls(true); } }), weightParams());
        panel.addView(actionRow);

        settingsOutTextView = createTextArea("", true);
        settingsOutTextView.setVisibility(View.GONE);
        panel.addView(settingsOutTextView, matchWrapParams());
    }

    private void populateAstronomyPanel(LinearLayout panel) {
        panel.addView(sectionTitle("Polaris alignment"));

        panel.addView(smallLabel("Local date/time"));
        LinearLayout timeRow = horizontalRow();
        dateTimeEditText = createEditText("yyyy-MM-dd HH:mm:ss", false);
        timeRow.addView(dateTimeEditText, weightParams());
        timeRow.addView(createButton("Now", false, false,
                new View.OnClickListener() { @Override public void onClick(View view) {
                    liveClockCheckBox.setChecked(false);
                    dateTimeEditText.setText(UiFormatting.formatLocalDateTime(new Date()));
                    calculateAndRenderAlignment();
                } }), fixedWidthParams(92));
        panel.addView(timeRow);
        liveClockCheckBox = createCheckBox("Live device time");
        liveClockCheckBox.setChecked(true);
        panel.addView(liveClockCheckBox, matchWrapParams());

        panel.addView(smallLabel("Observer site"));
        LinearLayout siteRow = horizontalRow();
        latitudeEditText = createEditText("latitude +N", true);
        longitudeEditText = createEditText("longitude +E", true);
        siteRow.addView(latitudeEditText, weightParams());
        siteRow.addView(longitudeEditText, weightParams());
        panel.addView(siteRow);
        panel.addView(createButton("Use last known Android location", false, false,
                new View.OnClickListener() { @Override public void onClick(View view) { requestLocationOrFillFromLastKnownProvider(); } }));

        panel.addView(smallLabel("Target right ascension"));
        LinearLayout rightAscensionRow = horizontalRow();
        rightAscensionHoursEditText = createEditText("hh", true);
        rightAscensionMinutesEditText = createEditText("mm", true);
        rightAscensionSecondsEditText = createEditText("ss.s", true);
        rightAscensionRow.addView(rightAscensionHoursEditText, weightParams());
        rightAscensionRow.addView(rightAscensionMinutesEditText, weightParams());
        rightAscensionRow.addView(rightAscensionSecondsEditText, weightParams());
        panel.addView(rightAscensionRow);
        panel.addView(note("Used for the live RA → HA indicator and date-ring rotation. Polaris physical placement is calculated separately."));

        panel.addView(smallLabel("Month-day offset for 0h"));
        LinearLayout offsetRow = horizontalRow();
        offsetMonthSpinner = createSpinner(MONTH_NAMES);
        offsetDayEditText = createEditText("day", true);
        offsetRow.addView(offsetMonthSpinner, weightParams());
        offsetRow.addView(offsetDayEditText, weightParams());
        panel.addView(offsetRow);

        lockZeroHourAngleCheckBox = createCheckBox("Lock visual reticle to HA 00:00:00 at 31/10");
        panel.addView(lockZeroHourAngleCheckBox, matchWrapParams());

        panel.addView(smallLabel("Atmospheric refraction"));
        refractionSpinner = createRefractionSpinner();
        panel.addView(refractionSpinner, matchWrapParams());

        LinearLayout atmosphereRow = horizontalRow();
        pressureEditText = createEditText("pressure hPa", true);
        temperatureEditText = createEditText("temperature °C", true);
        elevationEditText = createEditText("elevation m", true);
        atmosphereRow.addView(pressureEditText, weightParams());
        atmosphereRow.addView(temperatureEditText, weightParams());
        atmosphereRow.addView(elevationEditText, weightParams());
        panel.addView(atmosphereRow);

        panel.addView(createButton("Calculate alignment", true, false,
                new View.OnClickListener() { @Override public void onClick(View view) { calculateAndRenderAlignment(); } }));
    }

    private void populateStatusPanel(LinearLayout panel) {
        panel.addView(sectionTitle("Status"));
        statusTextView = createTextArea("Alignment status: waiting for input.", false);
        panel.addView(statusTextView, matchWrapParams());

        panel.addView(sectionTitle("Readouts"));
        readoutTextView = createTextArea("Readouts will appear here.", true);
        panel.addView(readoutTextView, matchWrapParams());

        panel.addView(sectionTitle("Debug log"));
        debugLogTextView = createTextArea("", true);
        panel.addView(debugLogTextView, matchWrapParams());

        panel.addView(note("Native Android build. USB/UVC preview uses the AUSBC backend; the reticle is native Canvas geometry. No SVG, WebView or Camera2 preview path is used."));
    }

    private void setInitialValues() {
        isBuildingOrInitializingUi = true;
        dateTimeEditText.setText(UiFormatting.formatLocalDateTime(new Date()));
        latitudeEditText.setText("52.520008");
        longitudeEditText.setText("13.404954");
        rightAscensionHoursEditText.setText("0");
        rightAscensionMinutesEditText.setText("0");
        rightAscensionSecondsEditText.setText("0.0");
        offsetMonthSpinner.setSelection(9);
        offsetDayEditText.setText("31");
        pressureEditText.setText("1013.25");
        temperatureEditText.setText("10.0");
        elevationEditText.setText("0");
        isBuildingOrInitializingUi = false;
    }

    private void wireUserInterfaceActions() {
        refractionSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateAtmosphereFieldState();
                calculateAndRenderAlignmentUnlessInitializing();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        offsetMonthSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                clampOffsetDayToSelectedMonth();
                calculateAndRenderAlignmentUnlessInitializing();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        videoFitSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                videoFitMode = String.valueOf(parent.getItemAtPosition(position));
                applyVideoOverlayControls(true);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });

        CompoundButton.OnCheckedChangeListener checkboxListener = new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                calculateAndRenderAlignmentUnlessInitializing();
            }
        };
        liveClockCheckBox.setOnCheckedChangeListener(checkboxListener);
        lockZeroHourAngleCheckBox.setOnCheckedChangeListener(checkboxListener);
        mirrorVideoCheckBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) { applyVideoOverlayControls(true); }
        });
        lockVideoScaleCheckBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    videoHeightSeekBar.setProgress(videoWidthSeekBar.getProgress());
                }
                applyVideoOverlayControls(true);
            }
        });

        TextWatcher recalculatingWatcher = new SimpleTextWatcher() {
            @Override public void afterTextChanged(Editable editable) { calculateAndRenderAlignmentUnlessInitializing(); }
        };
        dateTimeEditText.addTextChangedListener(recalculatingWatcher);
        latitudeEditText.addTextChangedListener(recalculatingWatcher);
        longitudeEditText.addTextChangedListener(recalculatingWatcher);
        rightAscensionHoursEditText.addTextChangedListener(recalculatingWatcher);
        rightAscensionMinutesEditText.addTextChangedListener(recalculatingWatcher);
        rightAscensionSecondsEditText.addTextChangedListener(recalculatingWatcher);
        offsetDayEditText.addTextChangedListener(recalculatingWatcher);
        pressureEditText.addTextChangedListener(recalculatingWatcher);
        temperatureEditText.addTextChangedListener(recalculatingWatcher);
        elevationEditText.addTextChangedListener(recalculatingWatcher);

        SeekBar.OnSeekBarChangeListener sliderListener = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser || isApplyingOverlayControlState) return;
                if (lockVideoScaleCheckBox.isChecked() && seekBar == videoWidthSeekBar) {
                    videoHeightSeekBar.setProgress(videoWidthSeekBar.getProgress());
                } else if (lockVideoScaleCheckBox.isChecked() && seekBar == videoHeightSeekBar) {
                    videoWidthSeekBar.setProgress(videoHeightSeekBar.getProgress());
                }
                applyVideoOverlayControls(true);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { applyVideoOverlayControls(true); }
        };
        videoOffsetXSeekBar.setOnSeekBarChangeListener(sliderListener);
        videoOffsetYSeekBar.setOnSeekBarChangeListener(sliderListener);
        videoWidthSeekBar.setOnSeekBarChangeListener(sliderListener);
        videoHeightSeekBar.setOnSeekBarChangeListener(sliderListener);
        videoRotationSeekBar.setOnSeekBarChangeListener(sliderListener);
        reticleOpacitySeekBar.setOnSeekBarChangeListener(sliderListener);
        videoOpacitySeekBar.setOnSeekBarChangeListener(sliderListener);

        updateAtmosphereFieldState();
        applyVideoOverlayControls(false);
    }

    private void calculateAndRenderAlignmentUnlessInitializing() {
        if (!isBuildingOrInitializingUi) {
            calculateAndRenderAlignment();
        }
    }

    private void requestCameraPermissionThenOpenUvc() {
        appendDebugLog("Open USB UVC camera pressed.");
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION_FOR_UVC);
            return;
        }
        openUvcCameraAfterRuntimePermission();
    }

    private void openUvcCameraAfterRuntimePermission() {
        UvcPreviewController controller = ensureUvcPreviewController();
        if (controller != null) {
            controller.requestPermissionAndOpenFirstCamera();
        }
    }

    private UvcPreviewController ensureUvcPreviewController() {
        if (uvcPreviewController != null) {
            return uvcPreviewController;
        }
        try {
            uvcPreviewController = new UvcPreviewController(this, uvcPreviewContainer, new UvcPreviewController.Listener() {
                @Override public void onUvcStatusChanged(final String statusText) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            setUvcStatus(statusText);
                            stageBadgeTextView.setText(shortenStatusForBadge(statusText));
                            appendDebugLog(statusText);
                        }
                    });
                }
            });
            return uvcPreviewController;
        } catch (Throwable throwable) {
            uvcPreviewController = null;
            setUvcStatus("UVC backend failed to initialize: " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
            appendDebugLog("UVC initialization failure: " + throwable);
            return null;
        }
    }

    private void stopUvcCamera() {
        appendDebugLog("Stop camera pressed.");
        if (uvcPreviewController != null) {
            uvcPreviewController.closeActiveCamera();
        }
        stageBadgeTextView.setText("No camera running");
        setUvcStatus("UVC preview stopped. USB monitor may remain available until the app is paused.");
    }

    private void refreshUvcStatus() {
        if (uvcPreviewController != null) {
            setUvcStatus(uvcPreviewController.describeConnectedUvcDevices());
            return;
        }
        setUvcStatus(describeConnectedUvcDevicesWithAndroidUsbHost());
    }

    private String describeConnectedUvcDevicesWithAndroidUsbHost() {
        UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        if (usbManager == null) {
            return "Android USB service is unavailable.";
        }
        StringBuilder builder = new StringBuilder();
        int uvcCount = 0;
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            if (!isUvcVideoDevice(device)) {
                continue;
            }
            uvcCount++;
            builder.append(String.format(Locale.US,
                    "VID %04x / PID %04x, interfaces=%d, name=%s\n",
                    device.getVendorId(), device.getProductId(),
                    device.getInterfaceCount(), device.getDeviceName()));
        }
        if (uvcCount == 0) {
            return "No raw USB UVC devices detected by Android USB Host.";
        }
        return uvcCount + " raw UVC device(s) detected by Android USB Host:\n"
                + builder.toString().trim()
                + "\nPress Open USB UVC camera to load the UVC backend and request USB permission.";
    }

    private static boolean isUvcVideoDevice(UsbDevice device) {
        if (device == null) {
            return false;
        }
        for (int index = 0; index < device.getInterfaceCount(); index++) {
            UsbInterface usbInterface = device.getInterface(index);
            if (usbInterface != null && usbInterface.getInterfaceClass() == USB_VIDEO_CLASS) {
                return true;
            }
        }
        return false;
    }

    private void requestLocationOrFillFromLastKnownProvider() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQUEST_LOCATION_PERMISSION);
            return;
        }
        fillLocationFromLastKnownProvider();
    }

    private void fillLocationFromLastKnownProvider() {
        LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) {
            setAlignmentStatus("Location service is unavailable. Enter coordinates manually.", COLOR_WARN);
            return;
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            setAlignmentStatus("Location permission is required. Enter coordinates manually or grant permission.", COLOR_WARN);
            return;
        }

        Location bestLocation = null;
        for (String provider : locationManager.getProviders(true)) {
            Location candidate = locationManager.getLastKnownLocation(provider);
            if (candidate != null && (bestLocation == null || candidate.getAccuracy() < bestLocation.getAccuracy())) {
                bestLocation = candidate;
            }
        }
        if (bestLocation == null) {
            setAlignmentStatus("No last known location. Enable location or enter coordinates manually.", COLOR_WARN);
            return;
        }
        latitudeEditText.setText(String.format(Locale.US, "%.6f", bestLocation.getLatitude()));
        longitudeEditText.setText(String.format(Locale.US, "%.6f", bestLocation.getLongitude()));
        if (bestLocation.hasAltitude()) {
            elevationEditText.setText(String.format(Locale.US, "%.0f", bestLocation.getAltitude()));
        }
        setAlignmentStatus("Filled last known Android location. Verify coordinates before alignment.", COLOR_OK);
        calculateAndRenderAlignment();
    }

    private void calculateAndRenderAlignment() {
        try {
            clampOffsetDayToSelectedMonth();
            AlignmentInput input = readAlignmentInputFromUi();
            AlignmentResult result = alignmentCalculator.calculate(input);
            reticleOverlayView.setAlignmentResult(result);
            readoutTextView.setText(formatReadout(result, input));
            if (result.warningText.isEmpty()) {
                setAlignmentStatus("Alignment calculated. Place Polaris on the pink target relative to the NCP/crosshair.", COLOR_OK);
            } else {
                setAlignmentStatus(result.warningText, COLOR_WARN);
            }
        } catch (Exception exception) {
            reticleOverlayView.setAlignmentResult(null);
            setAlignmentStatus("Calculation error: " + exception.getMessage(), COLOR_DANGER);
        }
    }

    private AlignmentInput readAlignmentInputFromUi() throws ParseException {
        Date selectedDate = UiFormatting.parseLocalDateTime(dateTimeEditText.getText().toString().trim());
        double latitude = parseDouble(latitudeEditText, "Latitude");
        double longitude = parseDouble(longitudeEditText, "Longitude");
        double pressure = parseOptionalDouble(pressureEditText, 1013.25);
        double temperature = parseOptionalDouble(temperatureEditText, 10.0);
        double elevation = parseOptionalDouble(elevationEditText, 0.0);
        boolean lockToZeroHa = lockZeroHourAngleCheckBox.isChecked();
        double targetRightAscensionHours = lockToZeroHa ? 0.0 : readTargetRightAscensionHoursFromUi();
        int offsetMonth = offsetMonthSpinner.getSelectedItemPosition() + 1;
        int offsetDay = Math.round((float) parseOptionalDouble(offsetDayEditText, 1.0));
        RefractionMode refractionMode = (RefractionMode) refractionSpinner.getSelectedItem();
        if (refractionMode == null) {
            refractionMode = RefractionMode.FIXED_BENNETT;
        }
        return new AlignmentInput(selectedDate, latitude, longitude, targetRightAscensionHours,
                offsetMonth, offsetDay, lockToZeroHa, refractionMode, pressure, temperature, elevation);
    }

    private double readTargetRightAscensionHoursFromUi() {
        double hours = parseOptionalDouble(rightAscensionHoursEditText, Double.NaN);
        double minutes = parseOptionalDouble(rightAscensionMinutesEditText, Double.NaN);
        double seconds = parseOptionalDouble(rightAscensionSecondsEditText, Double.NaN);
        if (!Double.isFinite(hours) || !Double.isFinite(minutes) || !Double.isFinite(seconds)
                || hours < 0.0 || hours >= 24.0
                || minutes < 0.0 || minutes >= 60.0
                || seconds < 0.0 || seconds >= 60.0) {
            throw new IllegalArgumentException("Target RA must be valid hh/mm/ss.");
        }
        return hours + minutes / 60.0 + seconds / 3600.0;
    }

    private String formatReadout(AlignmentResult result, AlignmentInput input) {
        StringBuilder builder = new StringBuilder();
        builder.append("UTC JD: ").append(String.format(Locale.US, "%.6f", result.julianDateUtc)).append('\n');
        builder.append("LAST: ").append(UiFormatting.formatHours(result.localApparentSiderealTimeDegrees / 15.0))
                .append(" (").append(String.format(Locale.US, "%.5f°", result.localApparentSiderealTimeDegrees)).append(")\n");
        builder.append("LMST: ").append(UiFormatting.formatHours(result.localMeanSiderealTimeDegrees / 15.0))
                .append(" (").append(String.format(Locale.US, "%.5f°", result.localMeanSiderealTimeDegrees)).append(")\n");
        builder.append("Target HA: ").append(UiFormatting.formatHours(result.activeHourAngleHours))
                .append(" (").append(String.format(Locale.US, "%.4f°", result.activeHourAngleDegrees)).append(")");
        if (input.lockReticleToZeroHourAngle) {
            builder.append(" — locked; live calculated HA ")
                    .append(UiFormatting.formatHours(result.calculatedTargetHourAngleHours))
                    .append(" (").append(String.format(Locale.US, "%.4f°", result.calculatedTargetHourAngleDegrees)).append(")");
        }
        builder.append('\n');
        builder.append("0h date label: ").append(formatDayMonth(result.zeroHourDateMonth, result.zeroHourDateDay))
                .append(" (").append(MONTH_NAMES[result.zeroHourDateMonth - 1]).append(")\n");
        builder.append("Date/polar reticle rotation: ").append(String.format(Locale.US, "%.4f°", result.dateAndPolarReticleRotationDegrees)).append('\n');
        builder.append("Polaris RA/Dec: ").append(UiFormatting.formatRightAscension(result.apparentRightAscensionRadians))
                .append(" / ").append(UiFormatting.formatDeclination(result.apparentDeclinationRadians)).append('\n');
        builder.append("Polaris clock: ").append(UiFormatting.formatHours(result.polarisClockAngleDegrees / 15.0))
                .append(" (").append(String.format(Locale.US, "%.3f°", result.polarisClockAngleDegrees)).append(")\n");
        builder.append("Alt/Az: ").append(UiFormatting.formatDegrees(result.trueAltitudeRadians, 3))
                .append(" / ").append(UiFormatting.formatDegrees(result.trueAzimuthRadians, 3)).append('\n');
        builder.append("Refraction: ").append(String.format(Locale.US, "%.3f′ (%s)", result.refractionArcMinutes, result.refractionDescription)).append('\n');
        builder.append("Marker reticle: x=").append(String.format(Locale.US, "%.2f", result.markerReticleX))
                .append(", y=").append(String.format(Locale.US, "%.2f", result.markerReticleY)).append('\n');
        builder.append("Ring scale: ").append(String.format(Locale.US, "%.2f px, %.1f px/tan-rad",
                result.nominalRingRadiusReticlePixels, result.pixelPerTangentRadian)).append('\n');
        builder.append("ΔT: ").append(String.format(Locale.US, "%.2f s", result.deltaTSeconds));
        return builder.toString();
    }

    private void clampOffsetDayToSelectedMonth() {
        if (offsetMonthSpinner == null || offsetDayEditText == null) {
            return;
        }
        int selectedMonth = offsetMonthSpinner.getSelectedItemPosition() + 1;
        if (selectedMonth < 1 || selectedMonth > 12) {
            return;
        }
        int maximumDay = MONTH_DAYS[selectedMonth - 1];
        String rawText = offsetDayEditText.getText().toString().trim();
        if (rawText.isEmpty()) {
            return;
        }
        try {
            int requestedDay = Math.round(Float.parseFloat(rawText));
            int clampedDay = Math.max(1, Math.min(maximumDay, requestedDay));
            String clampedText = String.valueOf(clampedDay);
            if (!clampedText.equals(rawText)) {
                offsetDayEditText.setText(clampedText);
                offsetDayEditText.setSelection(offsetDayEditText.getText().length());
            }
        } catch (NumberFormatException ignored) {
            // Keep partial invalid input while the user is editing.
        }
    }

    private void updateAtmosphereFieldState() {
        RefractionMode mode = (RefractionMode) refractionSpinner.getSelectedItem();
        boolean usesPressure = mode == RefractionMode.SCALED_PRESSURE_TEMPERATURE;
        boolean usesTemperature = mode == RefractionMode.SCALED_PRESSURE_TEMPERATURE
                || mode == RefractionMode.ALTITUDE_PRESSURE_TEMPERATURE;
        boolean usesElevation = mode == RefractionMode.ALTITUDE_PRESSURE_TEMPERATURE;
        pressureEditText.setEnabled(usesPressure);
        temperatureEditText.setEnabled(usesTemperature);
        elevationEditText.setEnabled(usesElevation);
    }

    private void loadOverlayControlSettings() {
        SharedPreferences preferences = getSharedPreferences(SETTINGS_NAME, MODE_PRIVATE);
        videoOffsetXPercent = preferences.getFloat("videoOffsetXPercent", 0.0f);
        videoOffsetYPercent = preferences.getFloat("videoOffsetYPercent", 0.0f);
        videoWidthPercent = preferences.getFloat("videoWidthPercent", 100.0f);
        videoHeightPercent = preferences.getFloat("videoHeightPercent", 100.0f);
        videoRotationDegrees = preferences.getFloat("videoRotationDegrees", 0.0f);
        reticleOpacityPercent = preferences.getFloat("reticleOpacityPercent", 100.0f);
        videoOpacityPercent = preferences.getFloat("videoOpacityPercent", 100.0f);
        boolean mirror = preferences.getBoolean("mirrorVideo", false);
        boolean lockScale = preferences.getBoolean("lockVideoScale", true);
        videoFitMode = preferences.getString("videoFitMode", "Native UVC aspect");

        isApplyingOverlayControlState = true;
        mirrorVideoCheckBox.setChecked(mirror);
        lockVideoScaleCheckBox.setChecked(lockScale);
        selectSpinnerText(videoFitSpinner, videoFitMode);
        videoOffsetXSeekBar.setProgress(percentToSignedSeekProgress(videoOffsetXPercent));
        videoOffsetYSeekBar.setProgress(percentToSignedSeekProgress(videoOffsetYPercent));
        videoWidthSeekBar.setProgress(percentToSizeSeekProgress(videoWidthPercent));
        videoHeightSeekBar.setProgress(percentToSizeSeekProgress(videoHeightPercent));
        videoRotationSeekBar.setProgress(degreesToRotationSeekProgress(videoRotationDegrees));
        reticleOpacitySeekBar.setProgress(Math.round(reticleOpacityPercent));
        videoOpacitySeekBar.setProgress(Math.round(videoOpacityPercent));
        isApplyingOverlayControlState = false;
        applyVideoOverlayControls(false);
    }

    private void saveOverlayControlSettings() {
        getSharedPreferences(SETTINGS_NAME, MODE_PRIVATE).edit()
                .putFloat("videoOffsetXPercent", videoOffsetXPercent)
                .putFloat("videoOffsetYPercent", videoOffsetYPercent)
                .putFloat("videoWidthPercent", videoWidthPercent)
                .putFloat("videoHeightPercent", videoHeightPercent)
                .putFloat("videoRotationDegrees", videoRotationDegrees)
                .putFloat("reticleOpacityPercent", reticleOpacityPercent)
                .putFloat("videoOpacityPercent", videoOpacityPercent)
                .putBoolean("mirrorVideo", mirrorVideoCheckBox.isChecked())
                .putBoolean("lockVideoScale", lockVideoScaleCheckBox.isChecked())
                .putString("videoFitMode", videoFitMode)
                .apply();
    }

    private void applyVideoOverlayControls(boolean shouldSave) {
        if (videoLayer == null || isApplyingOverlayControlState) {
            return;
        }
        videoOffsetXPercent = signedPercentFromSeek(videoOffsetXSeekBar);
        videoOffsetYPercent = signedPercentFromSeek(videoOffsetYSeekBar);
        videoWidthPercent = sizePercentFromSeek(videoWidthSeekBar);
        videoHeightPercent = sizePercentFromSeek(videoHeightSeekBar);
        videoRotationDegrees = rotationDegreesFromSeek(videoRotationSeekBar);
        reticleOpacityPercent = reticleOpacitySeekBar.getProgress();
        videoOpacityPercent = videoOpacitySeekBar.getProgress();

        updateSliderLabels();

        float baseScaleX = videoWidthPercent / 100.0f;
        float baseScaleY = videoHeightPercent / 100.0f;
        if ("Fill / stretch".equals(videoFitMode)) {
            // Intentionally allows non-uniform scaling only when explicitly requested.
            videoLayer.setScaleX((mirrorVideoCheckBox.isChecked() ? -1.0f : 1.0f) * baseScaleX);
            videoLayer.setScaleY(baseScaleY);
        } else {
            // Default/cover/contain modes preserve the camera surface's own aspect ratio.
            float uniformScale = Math.min(baseScaleX, baseScaleY);
            if ("Cover / crop".equals(videoFitMode)) {
                uniformScale = Math.max(baseScaleX, baseScaleY);
            }
            videoLayer.setScaleX((mirrorVideoCheckBox.isChecked() ? -1.0f : 1.0f) * uniformScale);
            videoLayer.setScaleY(uniformScale);
        }
        videoLayer.setRotation(videoRotationDegrees);
        videoLayer.setAlpha(videoOpacityPercent / 100.0f);
        reticleOverlayView.setAlpha(reticleOpacityPercent / 100.0f);
        stageFrame.post(new Runnable() {
            @Override public void run() {
                videoLayer.setTranslationX(stageFrame.getWidth() * videoOffsetXPercent / 100.0f);
                videoLayer.setTranslationY(stageFrame.getHeight() * videoOffsetYPercent / 100.0f);
            }
        });

        if (shouldSave) {
            saveOverlayControlSettings();
        }
    }

    private void resetVideoOverlayAlignment() {
        isApplyingOverlayControlState = true;
        videoOffsetXSeekBar.setProgress(percentToSignedSeekProgress(0.0f));
        videoOffsetYSeekBar.setProgress(percentToSignedSeekProgress(0.0f));
        videoWidthSeekBar.setProgress(percentToSizeSeekProgress(100.0f));
        videoHeightSeekBar.setProgress(percentToSizeSeekProgress(100.0f));
        videoRotationSeekBar.setProgress(degreesToRotationSeekProgress(0.0f));
        reticleOpacitySeekBar.setProgress(100);
        videoOpacitySeekBar.setProgress(100);
        mirrorVideoCheckBox.setChecked(false);
        lockVideoScaleCheckBox.setChecked(true);
        selectSpinnerText(videoFitSpinner, "Native UVC aspect");
        isApplyingOverlayControlState = false;
        applyVideoOverlayControls(true);
        appendDebugLog("Video/overlay alignment reset.");
    }

    private void copyCurrentSettingsToClipboard() {
        String settings = "{\n"
                + "  \"videoOffsetXPercent\": " + formatOneDecimal(videoOffsetXPercent) + ",\n"
                + "  \"videoOffsetYPercent\": " + formatOneDecimal(videoOffsetYPercent) + ",\n"
                + "  \"videoWidthPercent\": " + formatOneDecimal(videoWidthPercent) + ",\n"
                + "  \"videoHeightPercent\": " + formatOneDecimal(videoHeightPercent) + ",\n"
                + "  \"videoRotationDegrees\": " + formatOneDecimal(videoRotationDegrees) + ",\n"
                + "  \"reticleOpacityPercent\": " + formatOneDecimal(reticleOpacityPercent) + ",\n"
                + "  \"videoOpacityPercent\": " + formatOneDecimal(videoOpacityPercent) + ",\n"
                + "  \"mirrorVideo\": " + mirrorVideoCheckBox.isChecked() + ",\n"
                + "  \"lockVideoScale\": " + lockVideoScaleCheckBox.isChecked() + ",\n"
                + "  \"fit\": \"" + videoFitMode + "\"\n"
                + "}";
        settingsOutTextView.setText(settings);
        settingsOutTextView.setVisibility(View.VISIBLE);
        ClipboardManager clipboardManager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboardManager != null) {
            clipboardManager.setPrimaryClip(ClipData.newPlainText("Android Polaris settings", settings));
            setAlignmentStatus("Settings copied to clipboard.", COLOR_OK);
        } else {
            setAlignmentStatus("Settings shown below. Clipboard unavailable.", COLOR_WARN);
        }
    }

    private void updateSliderLabels() {
        videoOffsetXValueTextView.setText(String.format(Locale.US, "%.1f%%", videoOffsetXPercent));
        videoOffsetYValueTextView.setText(String.format(Locale.US, "%.1f%%", videoOffsetYPercent));
        videoWidthValueTextView.setText(String.format(Locale.US, "%.1f%%", videoWidthPercent));
        videoHeightValueTextView.setText(String.format(Locale.US, "%.1f%%", videoHeightPercent));
        videoRotationValueTextView.setText(String.format(Locale.US, "%.1f°", videoRotationDegrees));
        reticleOpacityValueTextView.setText(String.format(Locale.US, "%.0f%%", reticleOpacityPercent));
        videoOpacityValueTextView.setText(String.format(Locale.US, "%.0f%%", videoOpacityPercent));
    }

    private void setUvcStatus(String statusText) {
        uvcStatusTextView.setText(statusText == null ? "" : statusText);
        appendDebugLog(statusText == null ? "UVC status cleared." : statusText);
    }

    private void setAlignmentStatus(String statusText, int color) {
        statusTextView.setText(statusText == null ? "" : statusText);
        statusTextView.setTextColor(color);
    }

    private void appendDebugLog(String message) {
        if (debugLogTextView == null || message == null || message.trim().isEmpty()) {
            return;
        }
        String line = String.format(Locale.US, "[%tT] %s\n", new Date(), message.trim());
        debugLogBuilder.append(line);
        if (debugLogBuilder.length() > 9000) {
            debugLogBuilder.delete(0, debugLogBuilder.length() - 9000);
        }
        debugLogTextView.setText(debugLogBuilder.toString().trim());
    }

    private String shortenStatusForBadge(String statusText) {
        if (statusText == null || statusText.trim().isEmpty()) {
            return "Camera status unavailable";
        }
        String firstLine = statusText.trim().split("\\n", 2)[0];
        return firstLine.length() > 80 ? firstLine.substring(0, 77) + "…" : firstLine;
    }

    private static double parseDouble(EditText editText, String fieldName) {
        String text = editText.getText().toString().trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " is required.");
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(fieldName + " must be a valid number.");
        }
    }

    private static double parseOptionalDouble(EditText editText, double fallback) {
        String text = editText.getText().toString().trim();
        if (text.isEmpty()) {
            return fallback;
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static String formatDayMonth(int month, int day) {
        return String.format(Locale.US, "%02d/%02d", day, month);
    }

    private static String formatOneDecimal(float value) {
        return String.format(Locale.US, "%.1f", value);
    }

    private int percentToSignedSeekProgress(float percent) {
        return Math.max(0, Math.min(2000, Math.round((percent + 100.0f) * 10.0f)));
    }

    private float signedPercentFromSeek(SeekBar seekBar) {
        return seekBar.getProgress() / 10.0f - 100.0f;
    }

    private int percentToSizeSeekProgress(float percent) {
        return Math.max(0, Math.min(2800, Math.round((percent - 20.0f) * 10.0f)));
    }

    private float sizePercentFromSeek(SeekBar seekBar) {
        return 20.0f + seekBar.getProgress() / 10.0f;
    }

    private int degreesToRotationSeekProgress(float degrees) {
        return Math.max(0, Math.min(3600, Math.round((degrees + 180.0f) * 10.0f)));
    }

    private float rotationDegreesFromSeek(SeekBar seekBar) {
        return seekBar.getProgress() / 10.0f - 180.0f;
    }

    private SeekBar addSlider(LinearLayout panel, String label, String initialValue, TextView valueTextView) {
        LinearLayout labelRow = horizontalRow();
        TextView left = smallLabel(label);
        valueTextView.setText(initialValue);
        valueTextView.setTextColor(COLOR_TEXT);
        valueTextView.setTextSize(13);
        valueTextView.setGravity(Gravity.END);
        labelRow.addView(left, weightParams());
        labelRow.addView(valueTextView, weightParams());
        panel.addView(labelRow, matchWrapParams());

        SeekBar seekBar = new SeekBar(this);
        seekBar.setPadding(0, 0, 0, dp(6));
        if (label.equals("Horizontal position") || label.equals("Vertical position")) {
            seekBar.setMax(2000);
            seekBar.setProgress(1000);
        } else if (label.equals("Video width") || label.equals("Video height")) {
            seekBar.setMax(2800);
            seekBar.setProgress(800);
        } else if (label.equals("Rotation")) {
            seekBar.setMax(3600);
            seekBar.setProgress(1800);
        } else {
            seekBar.setMax(100);
            seekBar.setProgress(100);
        }
        panel.addView(seekBar, matchWrapParams());
        return seekBar;
    }

    private LinearLayout createPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(12), dp(12), dp(12));
        panel.setBackground(roundedBackground(COLOR_PANEL, COLOR_BORDER, 16));
        return panel;
    }

    private LinearLayout horizontalRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));
        return row;
    }

    private LinearLayout verticalColumn() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(2), dp(2), dp(2), dp(2));
        return column;
    }

    private TextView createText(String text, int sp, int color, boolean bold) {
        TextView textView = new TextView(this);
        textView.setText(text);
        textView.setTextColor(color);
        textView.setTextSize(sp);
        textView.setLineSpacing(0.0f, 1.08f);
        textView.setGravity(Gravity.START);
        textView.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        return textView;
    }

    private TextView note(String text) {
        TextView textView = createText(text, 12, COLOR_MUTED, false);
        textView.setPadding(0, dp(4), 0, dp(8));
        return textView;
    }

    private TextView smallLabel(String text) {
        TextView textView = createText(text, 13, COLOR_MUTED, false);
        textView.setPadding(dp(2), dp(4), dp(2), dp(2));
        return textView;
    }

    private TextView sectionTitle(String text) {
        TextView textView = createText(text.toUpperCase(Locale.US), 13, COLOR_TEXT, true);
        textView.setLetterSpacing(0.03f);
        textView.setPadding(dp(2), dp(10), dp(2), dp(2));
        return textView;
    }

    private View labelWithRightValue(String leftText, String rightText, TextView valueOut) {
        LinearLayout row = horizontalRow();
        TextView left = smallLabel(leftText);
        TextView right = createText(rightText, 13, COLOR_TEXT, false);
        right.setGravity(Gravity.END);
        row.addView(left, weightParams());
        row.addView(right, weightParams());
        if (valueOut != null) {
            valueOut.setText(rightText);
        }
        return row;
    }

    private EditText createEditText(String hint, boolean numeric) {
        EditText editText = new EditText(this);
        editText.setHint(hint);
        editText.setSingleLine(true);
        editText.setTextColor(COLOR_TEXT);
        editText.setHintTextColor(Color.rgb(128, 132, 140));
        editText.setTextSize(15);
        editText.setPadding(dp(10), dp(8), dp(10), dp(8));
        editText.setBackground(roundedBackground(COLOR_PANEL_2, COLOR_BORDER, 10));
        if (numeric) {
            editText.setInputType(InputType.TYPE_CLASS_NUMBER
                    | InputType.TYPE_NUMBER_FLAG_DECIMAL
                    | InputType.TYPE_NUMBER_FLAG_SIGNED);
        }
        return editText;
    }

    private Button createButton(String text, boolean primary, boolean danger, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(text);
        button.setTextSize(15);
        button.setMinHeight(dp(42));
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(10), dp(8), dp(10), dp(8));
        button.setOnClickListener(listener);
        if (primary) {
            button.setTextColor(Color.rgb(6, 16, 31));
            button.setBackground(roundedBackground(COLOR_ACCENT, Color.TRANSPARENT, 10));
        } else if (danger) {
            button.setTextColor(COLOR_DANGER);
            button.setBackground(roundedBackground(Color.TRANSPARENT, Color.argb(120, 255, 138, 138), 10));
        } else {
            button.setTextColor(COLOR_TEXT);
            button.setBackground(roundedBackground(COLOR_PANEL_2, COLOR_BORDER, 10));
        }
        return button;
    }

    private CheckBox createCheckBox(String text) {
        CheckBox checkBox = new CheckBox(this);
        checkBox.setText(text);
        checkBox.setTextColor(COLOR_TEXT);
        checkBox.setTextSize(14);
        checkBox.setPadding(0, dp(4), 0, dp(4));
        return checkBox;
    }

    private Spinner createSpinner(String[] entries) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, entries);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setBackground(roundedBackground(COLOR_PANEL_2, COLOR_BORDER, 10));
        spinner.setPadding(dp(4), 0, dp(4), 0);
        return spinner;
    }

    private Spinner createRefractionSpinner() {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<RefractionMode> adapter = new ArrayAdapter<RefractionMode>(this, android.R.layout.simple_spinner_item, RefractionMode.values());
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(0);
        spinner.setBackground(roundedBackground(COLOR_PANEL_2, COLOR_BORDER, 10));
        spinner.setPadding(dp(4), 0, dp(4), 0);
        return spinner;
    }

    private TextView createTextArea(String text, boolean monospace) {
        TextView textView = createText(text, 12, COLOR_TEXT, false);
        textView.setTextColor(text == null || text.isEmpty() ? COLOR_MUTED : COLOR_TEXT);
        textView.setPadding(dp(10), dp(9), dp(10), dp(9));
        textView.setBackground(roundedBackground(Color.rgb(17, 20, 26), COLOR_BORDER, 10));
        if (monospace) {
            textView.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
        }
        return textView;
    }

    private GradientDrawable roundedBackground(int fillColor, int strokeColor, int cornerDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(fillColor);
        drawable.setCornerRadius(dp(cornerDp));
        if (strokeColor != Color.TRANSPARENT) {
            drawable.setStroke(dp(1), strokeColor);
        }
        return drawable;
    }

    private LinearLayout.LayoutParams matchWrapParams() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams matchWrapParamsWithBottomMargin(int marginBottomDp) {
        LinearLayout.LayoutParams params = matchWrapParams();
        params.setMargins(0, 0, 0, dp(marginBottomDp));
        return params;
    }

    private LinearLayout.LayoutParams weightParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        params.setMargins(dp(3), dp(3), dp(3), dp(3));
        return params;
    }

    private LinearLayout.LayoutParams fixedWidthParams(int widthDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(widthDp), LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(3), dp(3), dp(3), dp(3));
        return params;
    }

    private void selectSpinnerText(Spinner spinner, String wantedText) {
        if (spinner == null || wantedText == null) {
            return;
        }
        for (int index = 0; index < spinner.getCount(); index++) {
            if (wantedText.equals(String.valueOf(spinner.getItemAtPosition(index)))) {
                spinner.setSelection(index);
                return;
            }
        }
    }

    private int dp(int value) {
        return (int) Math.round(value * getResources().getDisplayMetrics().density);
    }

    private abstract static class SimpleTextWatcher implements TextWatcher {
        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
    }

    /** Stage view with the same almost-square design ratio as the original reticle drawing. */
    private static final class ReticleAspectStageLayout extends FrameLayout {
        private static final double RETICLE_ASPECT_HEIGHT_OVER_WIDTH =
                PolarisAlignmentCalculator.RETICLE_VIEWBOX_HEIGHT / PolarisAlignmentCalculator.RETICLE_VIEWBOX_WIDTH;

        ReticleAspectStageLayout(Context context) {
            super(context);
        }

        @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int availableWidth = MeasureSpec.getSize(widthMeasureSpec);
            int computedHeight = (int) Math.round(availableWidth * RETICLE_ASPECT_HEIGHT_OVER_WIDTH);
            int exactHeightSpec = MeasureSpec.makeMeasureSpec(computedHeight, MeasureSpec.EXACTLY);
            super.onMeasure(widthMeasureSpec, exactHeightSpec);
        }
    }
}
