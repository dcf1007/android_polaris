package com.dcf1007.androidpolaris.ui;

import android.content.Context;
import android.graphics.Color;
import android.text.InputType;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import com.dcf1007.androidpolaris.backend.PolarisBackend;
import com.dcf1007.androidpolaris.backend.VideoAlignmentBackend;

/** Builds the complete native screen UI. It creates views only; MainUiController owns all behavior. */
public final class MainScreenView {
    public final LinearLayout root;
    public final FrameLayout stageFrame, videoLayer, previewMirrorLayer;
    public final TextureView previewTextureView;
    public final ReticleOverlayView reticleOverlayView;

    public final TextView uvcStatusText;
    public final Button refreshUsbButton, streamButton;
    public final Spinner streamTypeSpinner, resolutionSpinner, fpsSpinner;
    public final FineSlider brightnessSlider, contrastSlider, gainSlider, exposureSlider;
    public final CheckBox autoExposureCheckBox;

    public final Spinner videoFitSpinner;
    public final CheckBox mirrorVideoCheckBox, lockVideoScaleCheckBox;
    public final SeekBar videoOffsetXSeekBar, videoOffsetYSeekBar, videoWidthSeekBar, videoHeightSeekBar, videoRotationSeekBar, reticleOpacitySeekBar, videoOpacitySeekBar;
    public final TextView videoOffsetXValue, videoOffsetYValue, videoWidthValue, videoHeightValue, videoRotationValue, reticleOpacityValue, videoOpacityValue;
    public final Button resetAlignmentButton;

    public final EditText dateTimeEditText, latitudeEditText, longitudeEditText, rightAscensionHoursEditText, rightAscensionMinutesEditText, rightAscensionSecondsEditText, offsetDayEditText, pressureEditText, temperatureEditText, elevationEditText;
    public final Button nowButton, useLocationButton, calculateButton;
    public final CheckBox liveTimeCheckBox, lockZeroHourAngleCheckBox;
    public final Spinner offsetMonthSpinner, refractionSpinner;

    public final TextView readoutText, debugLogText;
    public final Button saveLogButton;

    public MainScreenView(Context context) {
        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiStyle.BACKGROUND);
        root.setPadding(dp(context, 12), 0, dp(context, 12), 0);

        TextView title = UiStyle.text(context, "Camera + native reticle overlay aligner", 18, UiStyle.TEXT, true);
        title.setPadding(dp(context, 2), dp(context, 12), dp(context, 2), dp(context, 2));
        root.addView(title, matchWrap());
        TextView hint = UiStyle.text(context, "Connect the USB UVC camera, refresh/query devices if needed, select a stream mode, then start preview. Use alignment controls to match the video to the native reticle.", 13, UiStyle.MUTED, false);
        hint.setPadding(dp(context, 2), 0, dp(context, 2), dp(context, 10));
        root.addView(hint, matchWrap());

        FrameLayout stageWrapper = new FrameLayout(context);
        stageWrapper.setPadding(dp(context, 10), dp(context, 10), dp(context, 10), dp(context, 10));
        stageWrapper.setBackground(UiStyle.roundedBackground(context, UiStyle.STAGE_BACKGROUND, UiStyle.BORDER, 16));
        root.addView(stageWrapper, new LinearLayout.LayoutParams(-1, -2));
        stageFrame = new ReticleAspectStageLayout(context);
        stageFrame.setBackgroundColor(Color.BLACK);
        stageFrame.setClipChildren(true);
        stageFrame.setClipToPadding(true);
        stageWrapper.addView(stageFrame, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        videoLayer = new FrameLayout(context);
        videoLayer.setBackgroundColor(Color.BLACK);
        videoLayer.setClipChildren(true);
        videoLayer.setClipToPadding(true);
        stageFrame.addView(videoLayer, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        previewMirrorLayer = new FrameLayout(context);
        previewMirrorLayer.setBackgroundColor(Color.BLACK);
        previewMirrorLayer.setClipChildren(true);
        previewMirrorLayer.setClipToPadding(true);
        videoLayer.addView(previewMirrorLayer, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        previewTextureView = new TextureView(context);
        previewMirrorLayer.addView(previewTextureView, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        reticleOverlayView = new ReticleOverlayView(context);
        stageFrame.addView(reticleOverlayView, new FrameLayout.LayoutParams(-1, -1));

        ScrollView scrollView = new ScrollView(context);
        root.addView(scrollView, new LinearLayout.LayoutParams(-1, 0, 1.0f));
        LinearLayout panels = new LinearLayout(context);
        panels.setOrientation(LinearLayout.VERTICAL);
        panels.setPadding(0, dp(context, 12), 0, dp(context, 18));
        scrollView.addView(panels, new ScrollView.LayoutParams(-1, -2));

        CollapsiblePanel cameraPanel = addPanel(context, panels, "Camera hardware", 12);
        cameraPanel.body().addView(labelValue(context, "USB OTG UVC camera", "direct libuvc"));
        uvcStatusText = UiStyle.textArea(context, "UVC backend initializing…", false);
        cameraPanel.body().addView(uvcStatusText, bottomMargin(context, 8));
        LinearLayout streamRow = horizontalRow(context);
        refreshUsbButton = UiStyle.button(context, "Refresh USB devices", true, false);
        streamButton = UiStyle.button(context, "Start stream", true, false);
        streamRow.addView(refreshUsbButton, weightedWithMargins(context, 0, 0, 6, 0));
        streamRow.addView(streamButton, weightedWithMargins(context, 6, 0, 0, 0));
        cameraPanel.body().addView(streamRow, matchWrap());
        streamTypeSpinner = addLabeledSpinner(context, cameraPanel.body(), "Stream type");
        resolutionSpinner = addLabeledSpinner(context, cameraPanel.body(), "Resolution");
        fpsSpinner = addLabeledSpinner(context, cameraPanel.body(), "FPS");
        brightnessSlider = addFineSlider(context, cameraPanel.body(), "Brightness");
        contrastSlider = addFineSlider(context, cameraPanel.body(), "Contrast");
        gainSlider = addFineSlider(context, cameraPanel.body(), "Gain");
        exposureSlider = addFineSlider(context, cameraPanel.body(), "Exposure");
        autoExposureCheckBox = checkBox(context, "Auto exposure");
        cameraPanel.body().addView(autoExposureCheckBox, matchWrap());

        CollapsiblePanel alignmentPanel = addPanel(context, panels, "Alignment / position / transparency", 12);
        LinearLayout fitRow = horizontalRow(context);
        LinearLayout fitColumn = verticalColumn(context);
        fitColumn.addView(UiStyle.label(context, "Video fit"));
        videoFitSpinner = spinner(context, VideoAlignmentBackend.FIT_OPTIONS);
        fitColumn.addView(videoFitSpinner, matchWrap());
        fitRow.addView(fitColumn, weighted());
        LinearLayout checkColumn = verticalColumn(context);
        mirrorVideoCheckBox = checkBox(context, "Mirror video");
        lockVideoScaleCheckBox = checkBox(context, "Lock width/height");
        checkColumn.addView(mirrorVideoCheckBox, matchWrap());
        checkColumn.addView(lockVideoScaleCheckBox, matchWrap());
        fitRow.addView(checkColumn, weighted());
        alignmentPanel.body().addView(fitRow, matchWrap());
        videoOffsetXValue = valueText(context, "0%"); videoOffsetXSeekBar = addSeekBar(context, alignmentPanel.body(), "Horizontal position", videoOffsetXValue, 2000, 1000);
        videoOffsetYValue = valueText(context, "0%"); videoOffsetYSeekBar = addSeekBar(context, alignmentPanel.body(), "Vertical position", videoOffsetYValue, 2000, 1000);
        videoWidthValue = valueText(context, "100%"); videoWidthSeekBar = addSeekBar(context, alignmentPanel.body(), "Video width", videoWidthValue, 2800, 800);
        videoHeightValue = valueText(context, "100%"); videoHeightSeekBar = addSeekBar(context, alignmentPanel.body(), "Video height", videoHeightValue, 2800, 800);
        videoRotationValue = valueText(context, "0°"); videoRotationSeekBar = addSeekBar(context, alignmentPanel.body(), "Rotation", videoRotationValue, 3600, 1800);
        resetAlignmentButton = UiStyle.button(context, "Reset alignment", false, true);
        alignmentPanel.body().addView(resetAlignmentButton, matchWrap());
        reticleOpacityValue = valueText(context, "100%"); reticleOpacitySeekBar = addSeekBar(context, alignmentPanel.body(), "Overlay opacity", reticleOpacityValue, 100, 100);
        videoOpacityValue = valueText(context, "100%"); videoOpacitySeekBar = addSeekBar(context, alignmentPanel.body(), "Video opacity", videoOpacityValue, 100, 100);

        CollapsiblePanel polarisPanel = addPanel(context, panels, "Polaris / HA / RA", 12);
        polarisPanel.body().addView(UiStyle.label(context, "Local date/time"));
        LinearLayout timeRow = horizontalRow(context);
        dateTimeEditText = editText(context, "yyyy-MM-dd HH:mm:ss", false);
        nowButton = UiStyle.button(context, "Now", false, false);
        timeRow.addView(dateTimeEditText, weighted());
        timeRow.addView(nowButton, fixedWidth(context, 92));
        polarisPanel.body().addView(timeRow, matchWrap());
        liveTimeCheckBox = checkBox(context, "Live device time");
        polarisPanel.body().addView(liveTimeCheckBox, matchWrap());
        polarisPanel.body().addView(UiStyle.label(context, "Observer site"));
        LinearLayout siteRow = horizontalRow(context);
        latitudeEditText = editText(context, "latitude +N", true);
        longitudeEditText = editText(context, "longitude +E", true);
        siteRow.addView(latitudeEditText, weighted());
        siteRow.addView(longitudeEditText, weighted());
        polarisPanel.body().addView(siteRow, matchWrap());
        useLocationButton = UiStyle.button(context, "Use last known Android location", false, false);
        polarisPanel.body().addView(useLocationButton, matchWrap());
        polarisPanel.body().addView(UiStyle.label(context, "Target right ascension"));
        LinearLayout raRow = horizontalRow(context);
        rightAscensionHoursEditText = editText(context, "hh", true);
        rightAscensionMinutesEditText = editText(context, "mm", true);
        rightAscensionSecondsEditText = editText(context, "ss.s", true);
        raRow.addView(rightAscensionHoursEditText, weighted());
        raRow.addView(rightAscensionMinutesEditText, weighted());
        raRow.addView(rightAscensionSecondsEditText, weighted());
        polarisPanel.body().addView(raRow, matchWrap());
        polarisPanel.body().addView(UiStyle.note(context, "Used for the live RA → HA indicator and date-ring rotation. Polaris physical placement is calculated separately."));
        polarisPanel.body().addView(UiStyle.label(context, "Month-day offset for 0h"));
        LinearLayout offsetRow = horizontalRow(context);
        offsetMonthSpinner = spinner(context, PolarisBackend.MONTH_NAMES);
        offsetDayEditText = editText(context, "day", true);
        offsetRow.addView(offsetMonthSpinner, weighted());
        offsetRow.addView(offsetDayEditText, weighted());
        polarisPanel.body().addView(offsetRow, matchWrap());
        lockZeroHourAngleCheckBox = checkBox(context, "Lock visual reticle to HA 00:00:00 at 31/10");
        polarisPanel.body().addView(lockZeroHourAngleCheckBox, matchWrap());
        polarisPanel.body().addView(UiStyle.label(context, "Atmospheric refraction"));
        refractionSpinner = refractionSpinner(context);
        polarisPanel.body().addView(refractionSpinner, matchWrap());
        LinearLayout atmosphereRow = horizontalRow(context);
        pressureEditText = editText(context, "pressure hPa", true);
        temperatureEditText = editText(context, "temperature °C", true);
        elevationEditText = editText(context, "elevation m", true);
        atmosphereRow.addView(pressureEditText, weighted());
        atmosphereRow.addView(temperatureEditText, weighted());
        atmosphereRow.addView(elevationEditText, weighted());
        polarisPanel.body().addView(atmosphereRow, matchWrap());
        calculateButton = UiStyle.button(context, "Calculate alignment", true, false);
        polarisPanel.body().addView(calculateButton, matchWrap());

        CollapsiblePanel debugPanel = addPanel(context, panels, "Readouts / debug log", 0);
        readoutText = UiStyle.textArea(context, "Readouts will appear here.", true);
        debugPanel.body().addView(readoutText, matchWrap());
        saveLogButton = UiStyle.button(context, "Save UVC log to Downloads", false, false);
        debugPanel.body().addView(saveLogButton, matchWrap());
        debugLogText = UiStyle.textArea(context, "", true);
        debugPanel.body().addView(debugLogText, matchWrap());
        debugPanel.body().addView(UiStyle.note(context, "Native Android build. USB/UVC preview uses direct libuvc; the reticle is native Canvas geometry. No WebView, SVG runtime, or Camera2 preview path is used."));
    }

    public static final class FineSlider {
        public final SeekBar seekBar;
        public final TextView valueText;
        public final Button minusButton;
        public final Button plusButton;
        FineSlider(SeekBar seekBar, TextView valueText, Button minusButton, Button plusButton) { this.seekBar = seekBar; this.valueText = valueText; this.minusButton = minusButton; this.plusButton = plusButton; }
        public int progress() { return seekBar.getProgress(); }
        public void setProgress(int value) { seekBar.setProgress(Math.max(0, Math.min(100, value))); }
        public void setEnabled(boolean enabled) { seekBar.setEnabled(enabled); minusButton.setEnabled(enabled); plusButton.setEnabled(enabled); }
        public void updateLabel() { valueText.setText(seekBar.getProgress() + "%"); }
    }

    private static CollapsiblePanel addPanel(Context context, LinearLayout parent, String title, int bottomMarginDp) { CollapsiblePanel panel = new CollapsiblePanel(context, title); parent.addView(panel.root(), bottomMarginDp > 0 ? bottomMargin(context, bottomMarginDp) : matchWrap()); return panel; }
    private static Spinner addLabeledSpinner(Context context, LinearLayout parent, String label) { parent.addView(UiStyle.label(context, label)); Spinner spinner = new Spinner(context); spinner.setEnabled(false); parent.addView(spinner, matchWrap()); return spinner; }
    private static FineSlider addFineSlider(Context context, LinearLayout parent, String label) { LinearLayout row = horizontalRow(context); row.addView(UiStyle.label(context, label), weighted()); Button minus = UiStyle.button(context, "−", false, false); TextView value = valueText(context, "—"); Button plus = UiStyle.button(context, "+", false, false); row.addView(minus, fixedWidth(context, 44)); row.addView(value, fixedWidth(context, 70)); row.addView(plus, fixedWidth(context, 44)); parent.addView(row, matchWrap()); SeekBar seekBar = new SeekBar(context); seekBar.setMax(100); seekBar.setProgress(50); seekBar.setEnabled(false); parent.addView(seekBar, matchWrap()); return new FineSlider(seekBar, value, minus, plus); }
    private static SeekBar addSeekBar(Context context, LinearLayout parent, String label, TextView value, int max, int progress) { LinearLayout labelRow = horizontalRow(context); labelRow.addView(UiStyle.label(context, label), weighted()); labelRow.addView(value, weighted()); parent.addView(labelRow, matchWrap()); SeekBar seekBar = new SeekBar(context); seekBar.setMax(max); seekBar.setProgress(progress); seekBar.setPadding(0, 0, 0, dp(context, 6)); parent.addView(seekBar, matchWrap()); return seekBar; }
    private static EditText editText(Context context, String hint, boolean numeric) { EditText editText = new EditText(context); editText.setHint(hint); UiStyle.styleEditText(context, editText); if (numeric) editText.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED); return editText; }
    private static CheckBox checkBox(Context context, String text) { CheckBox checkBox = new CheckBox(context); checkBox.setText(text); checkBox.setTextColor(UiStyle.TEXT); checkBox.setTextSize(14); checkBox.setPadding(0, dp(context, 4), 0, dp(context, 4)); return checkBox; }
    private static Spinner spinner(Context context, String[] entries) { Spinner spinner = new Spinner(context); ArrayAdapter<String> adapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, entries); adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); spinner.setAdapter(adapter); spinner.setBackground(UiStyle.roundedBackground(context, UiStyle.PANEL_2, UiStyle.BORDER, 10)); spinner.setPadding(dp(context, 4), 0, dp(context, 4), 0); return spinner; }
    private static Spinner refractionSpinner(Context context) { Spinner spinner = new Spinner(context); ArrayAdapter<PolarisBackend.RefractionMode> adapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, PolarisBackend.RefractionMode.values()); adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); spinner.setAdapter(adapter); spinner.setSelection(0); spinner.setBackground(UiStyle.roundedBackground(context, UiStyle.PANEL_2, UiStyle.BORDER, 10)); spinner.setPadding(dp(context, 4), 0, dp(context, 4), 0); return spinner; }
    private static LinearLayout horizontalRow(Context context) { LinearLayout row = new LinearLayout(context); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(0, dp(context, 4), 0, dp(context, 4)); return row; }
    private static LinearLayout verticalColumn(Context context) { LinearLayout column = new LinearLayout(context); column.setOrientation(LinearLayout.VERTICAL); column.setPadding(dp(context, 2), dp(context, 2), dp(context, 2), dp(context, 2)); return column; }
    private static TextView valueText(Context context, String value) { TextView textView = UiStyle.text(context, value, 13, UiStyle.TEXT, false); textView.setGravity(Gravity.END); return textView; }
    private static View labelValue(Context context, String left, String right) { LinearLayout row = horizontalRow(context); TextView rightText = UiStyle.text(context, right, 13, UiStyle.TEXT, false); rightText.setGravity(Gravity.END); row.addView(UiStyle.label(context, left), weighted()); row.addView(rightText, weighted()); return row; }
    private static LinearLayout.LayoutParams matchWrap() { return new LinearLayout.LayoutParams(-1, -2); }
    private static LinearLayout.LayoutParams bottomMargin(Context context, int bottomDp) { LinearLayout.LayoutParams params = matchWrap(); params.setMargins(0, 0, 0, dp(context, bottomDp)); return params; }
    private static LinearLayout.LayoutParams weighted() { return new LinearLayout.LayoutParams(0, -2, 1.0f); }
    private static LinearLayout.LayoutParams weightedWithMargins(Context context, int l, int t, int r, int b) { LinearLayout.LayoutParams params = weighted(); params.setMargins(dp(context, l), dp(context, t), dp(context, r), dp(context, b)); return params; }
    private static LinearLayout.LayoutParams fixedWidth(Context context, int widthDp) { LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(context, widthDp), -2); params.setMargins(dp(context, 3), dp(context, 3), dp(context, 3), dp(context, 3)); return params; }
    private static int dp(Context context, int value) { return UiStyle.dp(context, value); }

    private static final class ReticleAspectStageLayout extends FrameLayout {
        ReticleAspectStageLayout(Context context) { super(context); }
        @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) { int width = MeasureSpec.getSize(widthMeasureSpec); int height = Math.round((float) (width * PolarisBackend.RETICLE_ASPECT_HEIGHT_OVER_WIDTH)); super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)); }
    }
}
