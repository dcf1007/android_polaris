package com.dcf1007.androidpolaris;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
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
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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
 * <p>The app starts with Android framework classes only, draws the native reticle overlay, and
 * calculates Polaris before the USB camera backend is touched. The UVC backend is created lazily
 * after the user presses Open USB UVC camera.</p>
 */
public final class MainActivity extends Activity {
    private static final int REQUEST_CAMERA_PERMISSION_FOR_UVC = 1001;
    private static final int REQUEST_LOCATION_PERMISSION = 1002;
    private static final int USB_VIDEO_CLASS = 14;
    private static final int PREVIEW_PANEL_MIN_HEIGHT_DP = 260;
    private static final String[] MONTH_NAMES = {"January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"};
    private static final int[] MONTH_DAYS = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};

    private final Handler liveClockHandler = new Handler(Looper.getMainLooper());
    private final PolarisAlignmentCalculator alignmentCalculator = new PolarisAlignmentCalculator();

    private FrameLayout uvcPreviewContainer;
    private ReticleOverlayView reticleOverlayView;
    private Spinner refractionSpinner;
    private Spinner offsetMonthSpinner;
    private CheckBox liveClockCheckBox;
    private CheckBox lockZeroHourAngleCheckBox;
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
    private TextView statusTextView;
    private TextView uvcStatusTextView;
    private TextView readoutTextView;
    private UvcPreviewController uvcPreviewController;
    private boolean isBuildingOrInitializingUi;

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
        wireUserInterfaceActions();
        refreshUvcStatus();
        calculateAndRenderAlignment();
    }

    @Override protected void onResume() { super.onResume(); liveClockHandler.post(liveClockRunnable); }

    @Override protected void onPause() {
        liveClockHandler.removeCallbacks(liveClockRunnable);
        if (uvcPreviewController != null) uvcPreviewController.unregister();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (uvcPreviewController != null) { uvcPreviewController.destroy(); uvcPreviewController = null; }
        if (reticleOverlayView != null) reticleOverlayView.destroy();
        super.onDestroy();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION_FOR_UVC) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) openUvcCameraAfterRuntimePermission();
            else uvcStatusTextView.setText("Camera permission denied. Android requires CAMERA permission before this UVC backend can open preview.");
        } else if (requestCode == REQUEST_LOCATION_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) fillLocationFromLastKnownProvider();
            else statusTextView.setText("Location permission denied. Enter latitude/longitude manually.");
        }
    }

    private void buildUserInterface() {
        isBuildingOrInitializingUi = true;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(android.graphics.Color.BLACK);

        FrameLayout previewFrame = new FrameLayout(this);
        previewFrame.setBackgroundColor(android.graphics.Color.BLACK);
        previewFrame.setMinimumHeight(dp(PREVIEW_PANEL_MIN_HEIGHT_DP));
        root.addView(previewFrame, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));

        uvcPreviewContainer = new FrameLayout(this);
        uvcPreviewContainer.setBackgroundColor(android.graphics.Color.BLACK);
        previewFrame.addView(uvcPreviewContainer, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        reticleOverlayView = new ReticleOverlayView(this);
        previewFrame.addView(reticleOverlayView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        ScrollView scrollView = new ScrollView(this);
        root.addView(scrollView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(14), dp(12), dp(14), dp(16));
        controls.setBackgroundColor(android.graphics.Color.rgb(17, 19, 24));
        scrollView.addView(controls);

        controls.addView(label("Android Polaris — USB UVC polar alignment", 20, true));
        controls.addView(note("UVC-only build. The camera preview keeps the UVC stream aspect; the overlay uses the full native reticle geometry from the sanitized browser drawing."));
        controls.addView(fieldLabel("USB OTG UVC camera"));
        LinearLayout uvcActionRow = horizontalRow();
        uvcActionRow.addView(button("Open USB UVC camera", new View.OnClickListener() { @Override public void onClick(View view) { requestCameraPermissionThenOpenUvc(); } }), weightParams());
        uvcActionRow.addView(button("USB status", new View.OnClickListener() { @Override public void onClick(View view) { refreshUvcStatus(); } }), weightParams());
        controls.addView(uvcActionRow);
        uvcStatusTextView = note("UVC status: not scanned.");
        controls.addView(uvcStatusTextView);

        controls.addView(fieldLabel("Local date/time"));
        LinearLayout timeRow = horizontalRow();
        dateTimeEditText = editText("yyyy-MM-dd HH:mm:ss");
        timeRow.addView(dateTimeEditText, weightParams());
        timeRow.addView(button("Now", new View.OnClickListener() { @Override public void onClick(View view) { liveClockCheckBox.setChecked(false); dateTimeEditText.setText(UiFormatting.formatLocalDateTime(new Date())); calculateAndRenderAlignment(); } }), new LinearLayout.LayoutParams(dp(92), LinearLayout.LayoutParams.WRAP_CONTENT));
        controls.addView(timeRow);
        liveClockCheckBox = new CheckBox(this);
        liveClockCheckBox.setText("Live device time");
        liveClockCheckBox.setTextColor(android.graphics.Color.rgb(232, 232, 232));
        liveClockCheckBox.setChecked(true);
        controls.addView(liveClockCheckBox);

        controls.addView(fieldLabel("Observer site"));
        LinearLayout siteRow = horizontalRow();
        latitudeEditText = numericEditText("latitude +N");
        longitudeEditText = numericEditText("longitude +E");
        siteRow.addView(latitudeEditText, weightParams());
        siteRow.addView(longitudeEditText, weightParams());
        controls.addView(siteRow);
        controls.addView(button("Use last known Android location", new View.OnClickListener() { @Override public void onClick(View view) { requestLocationOrFillFromLastKnownProvider(); } }));

        controls.addView(fieldLabel("Target right ascension"));
        LinearLayout rightAscensionRow = horizontalRow();
        rightAscensionHoursEditText = integerEditText("hh");
        rightAscensionMinutesEditText = integerEditText("mm");
        rightAscensionSecondsEditText = numericEditText("ss.s");
        rightAscensionRow.addView(rightAscensionHoursEditText, weightParams());
        rightAscensionRow.addView(rightAscensionMinutesEditText, weightParams());
        rightAscensionRow.addView(rightAscensionSecondsEditText, weightParams());
        controls.addView(rightAscensionRow);
        controls.addView(note("Used for the live RA → HA indicator and date-ring rotation. Polaris physical placement is calculated separately."));

        controls.addView(fieldLabel("Month-day offset for 0h"));
        LinearLayout offsetRow = horizontalRow();
        offsetMonthSpinner = new Spinner(this);
        ArrayAdapter<String> monthAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, MONTH_NAMES);
        monthAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        offsetMonthSpinner.setAdapter(monthAdapter);
        offsetRow.addView(offsetMonthSpinner, weightParams());
        offsetDayEditText = integerEditText("day");
        offsetRow.addView(offsetDayEditText, weightParams());
        controls.addView(offsetRow);

        lockZeroHourAngleCheckBox = new CheckBox(this);
        lockZeroHourAngleCheckBox.setText("Lock visual reticle to HA 00:00:00 at 31/10");
        lockZeroHourAngleCheckBox.setTextColor(android.graphics.Color.rgb(232, 232, 232));
        controls.addView(lockZeroHourAngleCheckBox);

        controls.addView(fieldLabel("Atmospheric refraction"));
        refractionSpinner = new Spinner(this);
        ArrayAdapter<RefractionMode> refractionAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, RefractionMode.values());
        refractionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        refractionSpinner.setAdapter(refractionAdapter);
        refractionSpinner.setSelection(0);
        controls.addView(refractionSpinner);

        LinearLayout atmosphereRow = horizontalRow();
        pressureEditText = numericEditText("pressure hPa");
        temperatureEditText = numericEditText("temperature °C");
        elevationEditText = numericEditText("elevation m");
        atmosphereRow.addView(pressureEditText, weightParams());
        atmosphereRow.addView(temperatureEditText, weightParams());
        atmosphereRow.addView(elevationEditText, weightParams());
        controls.addView(atmosphereRow);

        controls.addView(button("Calculate alignment", new View.OnClickListener() { @Override public void onClick(View view) { calculateAndRenderAlignment(); } }));
        statusTextView = note("Alignment status: waiting for input.");
        readoutTextView = note("Readouts will appear here.");
        controls.addView(statusTextView);
        controls.addView(readoutTextView);
        setContentView(root);
        isBuildingOrInitializingUi = false;
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
        refractionSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() { @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { updateAtmosphereFieldState(); calculateAndRenderAlignmentUnlessInitializing(); } @Override public void onNothingSelected(AdapterView<?> parent) { } });
        offsetMonthSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() { @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { clampOffsetDayToSelectedMonth(); calculateAndRenderAlignmentUnlessInitializing(); } @Override public void onNothingSelected(AdapterView<?> parent) { } });
        CompoundButton.OnCheckedChangeListener checkboxListener = new CompoundButton.OnCheckedChangeListener() { @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) { calculateAndRenderAlignmentUnlessInitializing(); } };
        liveClockCheckBox.setOnCheckedChangeListener(checkboxListener);
        lockZeroHourAngleCheckBox.setOnCheckedChangeListener(checkboxListener);
        TextWatcher recalculatingWatcher = new SimpleTextWatcher() { @Override public void afterTextChanged(Editable editable) { calculateAndRenderAlignmentUnlessInitializing(); } };
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
        updateAtmosphereFieldState();
    }

    private void calculateAndRenderAlignmentUnlessInitializing() { if (!isBuildingOrInitializingUi) calculateAndRenderAlignment(); }
    private void requestCameraPermissionThenOpenUvc() { if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION_FOR_UVC); return; } openUvcCameraAfterRuntimePermission(); }
    private void openUvcCameraAfterRuntimePermission() { UvcPreviewController controller = ensureUvcPreviewController(); if (controller != null) controller.requestPermissionAndOpenFirstCamera(); }
    private UvcPreviewController ensureUvcPreviewController() { if (uvcPreviewController != null) return uvcPreviewController; try { uvcPreviewController = new UvcPreviewController(this, uvcPreviewContainer, new UvcPreviewController.Listener() { @Override public void onUvcStatusChanged(String statusText) { runOnUiThread(new Runnable() { @Override public void run() { uvcStatusTextView.setText(statusText); } }); } }); return uvcPreviewController; } catch (Throwable throwable) { uvcPreviewController = null; uvcStatusTextView.setText("UVC backend failed to initialize: " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage()); return null; } }
    private void refreshUvcStatus() { if (uvcPreviewController != null) { uvcStatusTextView.setText(uvcPreviewController.describeConnectedUvcDevices()); return; } uvcStatusTextView.setText(describeConnectedUvcDevicesWithAndroidUsbHost()); }

    private String describeConnectedUvcDevicesWithAndroidUsbHost() { UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE); if (usbManager == null) return "Android USB service is unavailable."; StringBuilder builder = new StringBuilder(); int uvcCount = 0; for (UsbDevice device : usbManager.getDeviceList().values()) { if (!isUvcVideoDevice(device)) continue; uvcCount++; builder.append(String.format(Locale.US, "VID %04x / PID %04x, interfaces=%d, name=%s\n", device.getVendorId(), device.getProductId(), device.getInterfaceCount(), device.getDeviceName())); } if (uvcCount == 0) return "No raw USB UVC devices detected by Android USB Host."; return uvcCount + " raw UVC device(s) detected by Android USB Host:\n" + builder.toString().trim() + "\nPress Open USB UVC camera to load the UVC backend and request USB permission."; }
    private static boolean isUvcVideoDevice(UsbDevice device) { if (device == null) return false; for (int index = 0; index < device.getInterfaceCount(); index++) { UsbInterface usbInterface = device.getInterface(index); if (usbInterface != null && usbInterface.getInterfaceClass() == USB_VIDEO_CLASS) return true; } return false; }
    private void requestLocationOrFillFromLastKnownProvider() { if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) { requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQUEST_LOCATION_PERMISSION); return; } fillLocationFromLastKnownProvider(); }

    private void fillLocationFromLastKnownProvider() { LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE); if (locationManager == null) { statusTextView.setText("Location service is unavailable. Enter coordinates manually."); return; } if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) { statusTextView.setText("Location permission is required. Enter coordinates manually or grant permission."); return; } Location bestLocation = null; for (String provider : locationManager.getProviders(true)) { Location candidate = locationManager.getLastKnownLocation(provider); if (candidate != null && (bestLocation == null || candidate.getAccuracy() < bestLocation.getAccuracy())) bestLocation = candidate; } if (bestLocation == null) { statusTextView.setText("No last known location. Enable location or enter coordinates manually."); return; } latitudeEditText.setText(String.format(Locale.US, "%.6f", bestLocation.getLatitude())); longitudeEditText.setText(String.format(Locale.US, "%.6f", bestLocation.getLongitude())); if (bestLocation.hasAltitude()) elevationEditText.setText(String.format(Locale.US, "%.0f", bestLocation.getAltitude())); statusTextView.setText("Filled last known Android location. Verify coordinates before alignment."); calculateAndRenderAlignment(); }

    private void calculateAndRenderAlignment() { try { clampOffsetDayToSelectedMonth(); AlignmentInput input = readAlignmentInputFromUi(); AlignmentResult result = alignmentCalculator.calculate(input); reticleOverlayView.setAlignmentResult(result); readoutTextView.setText(formatReadout(result, input)); statusTextView.setText(result.warningText.isEmpty() ? "Alignment calculated. Place Polaris on the pink target relative to the NCP/crosshair." : result.warningText); } catch (Exception exception) { reticleOverlayView.setAlignmentResult(null); statusTextView.setText("Calculation error: " + exception.getMessage()); } }
    private AlignmentInput readAlignmentInputFromUi() throws ParseException { Date selectedDate = UiFormatting.parseLocalDateTime(dateTimeEditText.getText().toString().trim()); double latitude = parseDouble(latitudeEditText, "Latitude"); double longitude = parseDouble(longitudeEditText, "Longitude"); double pressure = parseOptionalDouble(pressureEditText, 1013.25); double temperature = parseOptionalDouble(temperatureEditText, 10.0); double elevation = parseOptionalDouble(elevationEditText, 0.0); boolean lockToZeroHa = lockZeroHourAngleCheckBox.isChecked(); double targetRightAscensionHours = lockToZeroHa ? 0.0 : readTargetRightAscensionHoursFromUi(); int offsetMonth = offsetMonthSpinner.getSelectedItemPosition() + 1; int offsetDay = Math.round((float) parseOptionalDouble(offsetDayEditText, 1.0)); RefractionMode refractionMode = (RefractionMode) refractionSpinner.getSelectedItem(); if (refractionMode == null) refractionMode = RefractionMode.FIXED_BENNETT; return new AlignmentInput(selectedDate, latitude, longitude, targetRightAscensionHours, offsetMonth, offsetDay, lockToZeroHa, refractionMode, pressure, temperature, elevation); }
    private double readTargetRightAscensionHoursFromUi() { double hours = parseOptionalDouble(rightAscensionHoursEditText, Double.NaN); double minutes = parseOptionalDouble(rightAscensionMinutesEditText, Double.NaN); double seconds = parseOptionalDouble(rightAscensionSecondsEditText, Double.NaN); if (!Double.isFinite(hours) || !Double.isFinite(minutes) || !Double.isFinite(seconds) || hours < 0.0 || hours >= 24.0 || minutes < 0.0 || minutes >= 60.0 || seconds < 0.0 || seconds >= 60.0) throw new IllegalArgumentException("Target RA must be valid hh/mm/ss."); return hours + minutes / 60.0 + seconds / 3600.0; }

    private String formatReadout(AlignmentResult result, AlignmentInput input) { StringBuilder builder = new StringBuilder(); builder.append("UTC JD: ").append(String.format(Locale.US, "%.6f", result.julianDateUtc)).append('\n'); builder.append("LAST: ").append(UiFormatting.formatHours(result.localApparentSiderealTimeDegrees / 15.0)).append(" (").append(String.format(Locale.US, "%.5f°", result.localApparentSiderealTimeDegrees)).append(")\n"); builder.append("LMST: ").append(UiFormatting.formatHours(result.localMeanSiderealTimeDegrees / 15.0)).append(" (").append(String.format(Locale.US, "%.5f°", result.localMeanSiderealTimeDegrees)).append(")\n"); builder.append("Target HA: ").append(UiFormatting.formatHours(result.activeHourAngleHours)).append(" (").append(String.format(Locale.US, "%.4f°", result.activeHourAngleDegrees)).append(")"); if (input.lockReticleToZeroHourAngle) builder.append(" — locked; live calculated HA ").append(UiFormatting.formatHours(result.calculatedTargetHourAngleHours)).append(" (").append(String.format(Locale.US, "%.4f°", result.calculatedTargetHourAngleDegrees)).append(")"); builder.append('\n'); builder.append("0h date label: ").append(formatDayMonth(result.zeroHourDateMonth, result.zeroHourDateDay)).append(" (").append(MONTH_NAMES[result.zeroHourDateMonth - 1]).append(")\n"); builder.append("Date/polar reticle rotation: ").append(String.format(Locale.US, "%.4f°", result.dateAndPolarReticleRotationDegrees)).append('\n'); builder.append("Polaris RA/Dec: ").append(UiFormatting.formatRightAscension(result.apparentRightAscensionRadians)).append(" / ").append(UiFormatting.formatDeclination(result.apparentDeclinationRadians)).append('\n'); builder.append("Polaris clock: ").append(UiFormatting.formatHours(result.polarisClockAngleDegrees / 15.0)).append(" (").append(String.format(Locale.US, "%.3f°", result.polarisClockAngleDegrees)).append(")\n"); builder.append("Alt/Az: ").append(UiFormatting.formatDegrees(result.trueAltitudeRadians, 3)).append(" / ").append(UiFormatting.formatDegrees(result.trueAzimuthRadians, 3)).append('\n'); builder.append("Refraction: ").append(String.format(Locale.US, "%.3f′ (%s)", result.refractionArcMinutes, result.refractionDescription)).append('\n'); builder.append("Marker reticle: x=").append(String.format(Locale.US, "%.2f", result.markerReticleX)).append(", y=").append(String.format(Locale.US, "%.2f", result.markerReticleY)).append('\n'); builder.append("Ring scale: ").append(String.format(Locale.US, "%.2f px, %.1f px/tan-rad", result.nominalRingRadiusReticlePixels, result.pixelPerTangentRadian)).append('\n'); builder.append("ΔT: ").append(String.format(Locale.US, "%.2f s", result.deltaTSeconds)); return builder.toString(); }

    private void clampOffsetDayToSelectedMonth() { if (offsetMonthSpinner == null || offsetDayEditText == null) return; int selectedMonth = offsetMonthSpinner.getSelectedItemPosition() + 1; if (selectedMonth < 1 || selectedMonth > 12) return; int maximumDay = MONTH_DAYS[selectedMonth - 1]; String rawText = offsetDayEditText.getText().toString().trim(); if (rawText.isEmpty()) return; try { int requestedDay = Math.round(Float.parseFloat(rawText)); int clampedDay = Math.max(1, Math.min(maximumDay, requestedDay)); String clampedText = String.valueOf(clampedDay); if (!clampedText.equals(rawText)) { offsetDayEditText.setText(clampedText); offsetDayEditText.setSelection(offsetDayEditText.getText().length()); } } catch (NumberFormatException ignored) { } }
    private void updateAtmosphereFieldState() { RefractionMode mode = (RefractionMode) refractionSpinner.getSelectedItem(); boolean usesPressure = mode == RefractionMode.SCALED_PRESSURE_TEMPERATURE; boolean usesTemperature = mode == RefractionMode.SCALED_PRESSURE_TEMPERATURE || mode == RefractionMode.ALTITUDE_PRESSURE_TEMPERATURE; boolean usesElevation = mode == RefractionMode.ALTITUDE_PRESSURE_TEMPERATURE; pressureEditText.setEnabled(usesPressure); temperatureEditText.setEnabled(usesTemperature); elevationEditText.setEnabled(usesElevation); }
    private static double parseDouble(EditText editText, String fieldName) { String text = editText.getText().toString().trim(); if (text.isEmpty()) throw new IllegalArgumentException(fieldName + " is required."); try { return Double.parseDouble(text); } catch (NumberFormatException exception) { throw new IllegalArgumentException(fieldName + " must be a valid number."); } }
    private static double parseOptionalDouble(EditText editText, double fallback) { String text = editText.getText().toString().trim(); if (text.isEmpty()) return fallback; try { return Double.parseDouble(text); } catch (NumberFormatException exception) { return fallback; } }
    private static String formatDayMonth(int month, int day) { return String.format(Locale.US, "%02d/%02d", day, month); }
    private TextView label(String text, int sp, boolean bold) { TextView textView = new TextView(this); textView.setText(text); textView.setTextColor(android.graphics.Color.rgb(242, 238, 238)); textView.setTextSize(sp); textView.setGravity(Gravity.START); textView.setPadding(0, dp(4), 0, dp(6)); textView.setTypeface(null, bold ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL); return textView; }
    private TextView fieldLabel(String text) { TextView textView = label(text, 14, true); textView.setTextColor(android.graphics.Color.rgb(255, 204, 0)); return textView; }
    private TextView note(String text) { TextView textView = label(text, 13, false); textView.setTextColor(android.graphics.Color.rgb(170, 175, 186)); textView.setPadding(0, dp(4), 0, dp(8)); return textView; }
    private EditText editText(String hint) { EditText editText = new EditText(this); editText.setHint(hint); editText.setSingleLine(true); editText.setTextColor(android.graphics.Color.rgb(232, 232, 232)); editText.setHintTextColor(android.graphics.Color.rgb(128, 132, 140)); editText.setTextSize(14); editText.setPadding(dp(10), dp(6), dp(10), dp(6)); editText.setBackgroundColor(android.graphics.Color.rgb(11, 13, 18)); return editText; }
    private EditText numericEditText(String hint) { EditText editText = editText(hint); editText.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED); return editText; }
    private EditText integerEditText(String hint) { EditText editText = editText(hint); editText.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED); return editText; }
    private Button button(String text, View.OnClickListener listener) { Button button = new Button(this); button.setText(text); button.setTextColor(android.graphics.Color.rgb(232, 232, 232)); button.setAllCaps(false); button.setOnClickListener(listener); return button; }
    private LinearLayout horizontalRow() { LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(0, dp(4), 0, dp(4)); return row; }
    private LinearLayout.LayoutParams weightParams() { LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f); params.setMargins(dp(2), dp(2), dp(2), dp(2)); return params; }
    private int dp(int value) { return (int) Math.round(value * getResources().getDisplayMetrics().density); }
    private abstract static class SimpleTextWatcher implements TextWatcher { @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { } @Override public void onTextChanged(CharSequence s, int start, int before, int count) { } }
}
