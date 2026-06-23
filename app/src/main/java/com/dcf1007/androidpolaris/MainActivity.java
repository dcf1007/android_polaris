package com.dcf1007.androidpolaris;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import com.dcf1007.androidpolaris.astro.PolarisAlignmentCalculator;
import com.dcf1007.androidpolaris.camera.Camera2PreviewController;
import com.dcf1007.androidpolaris.camera.CameraDeviceInfo;
import com.dcf1007.androidpolaris.camera.UsbUvcDeviceMonitor;
import com.dcf1007.androidpolaris.model.AlignmentInput;
import com.dcf1007.androidpolaris.model.AlignmentResult;
import com.dcf1007.androidpolaris.model.RefractionMode;
import com.dcf1007.androidpolaris.util.UiFormatting;
import com.dcf1007.androidpolaris.view.ReticleOverlayView;

import java.text.ParseException;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Main single-activity Android app.
 *
 * The app is intentionally small and explicit:
 * - Camera2 drives the live preview.
 * - UsbUvcDeviceMonitor detects OTG UVC devices through Android USB Host APIs.
 * - PolarisAlignmentCalculator owns the astronomy.
 * - ReticleOverlayView draws the polar-scope target over the camera feed.
 *
 * Keeping these responsibilities separate makes it easier to replace one layer
 * later, for example adding a native libuvc backend without touching the
 * astronomical calculation code.
 */
public final class MainActivity extends Activity {
    private static final int REQUEST_CAMERA_PERMISSION = 1001;
    private static final int REQUEST_LOCATION_PERMISSION = 1002;

    private final Handler liveClockHandler = new Handler(Looper.getMainLooper());
    private final PolarisAlignmentCalculator alignmentCalculator = new PolarisAlignmentCalculator();

    private TextureView cameraTextureView;
    private ReticleOverlayView reticleOverlayView;
    private Spinner cameraSpinner;
    private Spinner refractionSpinner;
    private CheckBox liveClockCheckBox;
    private EditText dateTimeEditText;
    private EditText latitudeEditText;
    private EditText longitudeEditText;
    private EditText pressureEditText;
    private EditText temperatureEditText;
    private EditText elevationEditText;
    private TextView statusTextView;
    private TextView cameraStatusTextView;
    private TextView usbStatusTextView;
    private TextView readoutTextView;

    private Camera2PreviewController camera2PreviewController;
    private UsbUvcDeviceMonitor usbUvcDeviceMonitor;
    private ArrayAdapter<CameraDeviceInfo> cameraAdapter;

    private final Runnable liveClockRunnable = new Runnable() {
        @Override
        public void run() {
            if (liveClockCheckBox != null && liveClockCheckBox.isChecked()) {
                dateTimeEditText.setText(UiFormatting.formatLocalDateTime(new Date()));
                calculateAndRenderAlignment();
            }
            liveClockHandler.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUserInterface();

        camera2PreviewController = new Camera2PreviewController(this, cameraTextureView, new Camera2PreviewController.StatusListener() {
            @Override
            public void onCameraStatus(String statusText) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        cameraStatusTextView.setText(statusText);
                    }
                });
            }
        });

        usbUvcDeviceMonitor = new UsbUvcDeviceMonitor(this, new UsbUvcDeviceMonitor.Listener() {
            @Override
            public void onUsbStatus(String statusText) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        usbStatusTextView.setText(statusText);
                        refreshCameraList();
                    }
                });
            }
        });

        setInitialValues();
        wireUserInterfaceActions();
        refreshCameraList();
        refreshUsbStatus();
        calculateAndRenderAlignment();
    }

    @Override
    protected void onResume() {
        super.onResume();
        usbUvcDeviceMonitor.register();
        camera2PreviewController.startBackgroundThread();
        liveClockHandler.post(liveClockRunnable);
    }

    @Override
    protected void onPause() {
        liveClockHandler.removeCallbacks(liveClockRunnable);
        camera2PreviewController.closeCamera();
        camera2PreviewController.stopBackgroundThread();
        usbUvcDeviceMonitor.unregister();
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openSelectedCamera();
            } else {
                cameraStatusTextView.setText("Camera permission denied. Camera2 preview cannot start.");
            }
        } else if (requestCode == REQUEST_LOCATION_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                fillLocationFromLastKnownProvider();
            } else {
                statusTextView.setText("Location permission denied. Enter latitude/longitude manually.");
            }
        }
    }

    private void buildUserInterface() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(android.graphics.Color.BLACK);

        FrameLayout previewFrame = new FrameLayout(this);
        previewFrame.setBackgroundColor(android.graphics.Color.BLACK);
        root.addView(previewFrame, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.0f
        ));

        cameraTextureView = new TextureView(this);
        previewFrame.addView(cameraTextureView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        reticleOverlayView = new ReticleOverlayView(this);
        previewFrame.addView(reticleOverlayView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(false);
        root.addView(scrollView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(14), dp(12), dp(14), dp(16));
        controls.setBackgroundColor(android.graphics.Color.rgb(17, 19, 24));
        scrollView.addView(controls);

        TextView title = label("Android Polaris — Camera2 polar alignment", 20, true);
        controls.addView(title);
        controls.addView(note("Camera2 handles built-in cameras and OTG/UVC cameras that Android exposes as external Camera2 devices. Raw UVC devices are detected through USB Host and reported below."));

        cameraSpinner = new Spinner(this);
        controls.addView(fieldLabel("Camera2 device"));
        controls.addView(cameraSpinner);

        LinearLayout cameraButtons = horizontalRow();
        cameraButtons.addView(button("Refresh cameras", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                refreshCameraList();
            }
        }), weightParams());
        cameraButtons.addView(button("Open camera", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openSelectedCamera();
            }
        }), weightParams());
        controls.addView(cameraButtons);

        cameraStatusTextView = note("Camera2 status: not opened.");
        controls.addView(cameraStatusTextView);

        LinearLayout timeRow = horizontalRow();
        dateTimeEditText = editText("yyyy-MM-dd HH:mm:ss");
        timeRow.addView(dateTimeEditText, weightParams());
        Button nowButton = button("Now", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                liveClockCheckBox.setChecked(false);
                dateTimeEditText.setText(UiFormatting.formatLocalDateTime(new Date()));
                calculateAndRenderAlignment();
            }
        });
        timeRow.addView(nowButton, new LinearLayout.LayoutParams(dp(92), LinearLayout.LayoutParams.WRAP_CONTENT));
        controls.addView(fieldLabel("Local date/time"));
        controls.addView(timeRow);

        liveClockCheckBox = new CheckBox(this);
        liveClockCheckBox.setText("Live browser/device time");
        liveClockCheckBox.setTextColor(android.graphics.Color.rgb(232, 232, 232));
        liveClockCheckBox.setChecked(true);
        controls.addView(liveClockCheckBox);

        LinearLayout siteRow = horizontalRow();
        latitudeEditText = editText("latitude +N");
        longitudeEditText = editText("longitude +E");
        siteRow.addView(latitudeEditText, weightParams());
        siteRow.addView(longitudeEditText, weightParams());
        controls.addView(fieldLabel("Observer site"));
        controls.addView(siteRow);
        controls.addView(button("Use last known Android location", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                requestLocationOrFillFromLastKnownProvider();
            }
        }));

        refractionSpinner = new Spinner(this);
        ArrayAdapter<RefractionMode> refractionAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, RefractionMode.values());
        refractionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        refractionSpinner.setAdapter(refractionAdapter);
        refractionSpinner.setSelection(0); // Fixed Bennett remains the default.
        controls.addView(fieldLabel("Atmospheric refraction"));
        controls.addView(refractionSpinner);

        LinearLayout atmosphereRow = horizontalRow();
        pressureEditText = editText("pressure hPa");
        temperatureEditText = editText("temperature °C");
        elevationEditText = editText("elevation m");
        atmosphereRow.addView(pressureEditText, weightParams());
        atmosphereRow.addView(temperatureEditText, weightParams());
        atmosphereRow.addView(elevationEditText, weightParams());
        controls.addView(atmosphereRow);

        LinearLayout actionRow = horizontalRow();
        actionRow.addView(button("Calculate", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                calculateAndRenderAlignment();
            }
        }), weightParams());
        actionRow.addView(button("USB status", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                refreshUsbStatus();
            }
        }), weightParams());
        controls.addView(actionRow);

        controls.addView(button("Request permission for first raw UVC device", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                usbUvcDeviceMonitor.requestPermissionForFirstUvcDevice();
            }
        }));

        statusTextView = note("Alignment status: waiting for input.");
        usbStatusTextView = note("USB status: not scanned.");
        readoutTextView = note("Readouts will appear here.");
        controls.addView(statusTextView);
        controls.addView(usbStatusTextView);
        controls.addView(readoutTextView);

        setContentView(root);
    }

    private void setInitialValues() {
        dateTimeEditText.setText(UiFormatting.formatLocalDateTime(new Date()));
        latitudeEditText.setText("52.520008");
        longitudeEditText.setText("13.404954");
        pressureEditText.setText("1010");
        temperatureEditText.setText("10");
        elevationEditText.setText("0");
    }

    private void wireUserInterfaceActions() {
        AdapterView.OnItemSelectedListener recalculateListener = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateAtmosphereFieldState();
                calculateAndRenderAlignment();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // No-op.
            }
        };
        refractionSpinner.setOnItemSelectedListener(recalculateListener);

        cameraSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                // Selection is applied only when the user taps Open camera; this avoids
                // changing the active camera while scrolling through the list.
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // No-op.
            }
        });

        updateAtmosphereFieldState();
    }

    private void refreshCameraList() {
        List<CameraDeviceInfo> devices = camera2PreviewController.listCameraDevices();
        cameraAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, devices);
        cameraAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        cameraSpinner.setAdapter(cameraAdapter);
        if (devices.isEmpty()) {
            cameraStatusTextView.setText("No Camera2 devices found.");
        } else {
            cameraStatusTextView.setText("Found " + devices.size() + " Camera2 device(s). External devices are listed first when present.");
        }
    }

    private void openSelectedCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
            return;
        }
        CameraDeviceInfo selectedCamera = (CameraDeviceInfo) cameraSpinner.getSelectedItem();
        if (selectedCamera == null) {
            cameraStatusTextView.setText("No Camera2 camera selected.");
            return;
        }
        camera2PreviewController.openCamera(selectedCamera.cameraId);
    }

    private void refreshUsbStatus() {
        usbStatusTextView.setText(usbUvcDeviceMonitor.describeConnectedUvcDevices());
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
            statusTextView.setText("Location service is unavailable. Enter coordinates manually.");
            return;
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            statusTextView.setText("Location permission is required. Enter coordinates manually or grant permission.");
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
            statusTextView.setText("No last known location. Enable location in Android settings or enter coordinates manually.");
            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            return;
        }

        latitudeEditText.setText(String.format(Locale.US, "%.6f", bestLocation.getLatitude()));
        longitudeEditText.setText(String.format(Locale.US, "%.6f", bestLocation.getLongitude()));
        if (bestLocation.hasAltitude()) {
            elevationEditText.setText(String.format(Locale.US, "%.0f", bestLocation.getAltitude()));
        }
        statusTextView.setText("Filled last known Android location. Verify coordinates before alignment.");
        calculateAndRenderAlignment();
    }

    private void calculateAndRenderAlignment() {
        try {
            AlignmentInput input = readAlignmentInputFromUi();
            AlignmentResult result = alignmentCalculator.calculate(input);
            reticleOverlayView.setAlignmentResult(result);
            readoutTextView.setText(formatReadout(result));
            statusTextView.setText(result.warningText.isEmpty()
                    ? "Alignment calculated. Place Polaris on the pink target relative to the NCP/crosshair."
                    : result.warningText);
        } catch (Exception exception) {
            reticleOverlayView.setAlignmentResult(null);
            statusTextView.setText("Calculation error: " + exception.getMessage());
        }
    }

    private AlignmentInput readAlignmentInputFromUi() throws ParseException {
        Date selectedDate = UiFormatting.parseLocalDateTime(dateTimeEditText.getText().toString().trim());
        double latitude = parseDouble(latitudeEditText, "Latitude");
        double longitude = parseDouble(longitudeEditText, "Longitude");
        double pressure = parseOptionalDouble(pressureEditText, 1010.0);
        double temperature = parseOptionalDouble(temperatureEditText, 10.0);
        double elevation = parseOptionalDouble(elevationEditText, 0.0);
        RefractionMode refractionMode = (RefractionMode) refractionSpinner.getSelectedItem();
        if (refractionMode == null) {
            refractionMode = RefractionMode.FIXED_BENNETT;
        }
        return new AlignmentInput(selectedDate, latitude, longitude, refractionMode, pressure, temperature, elevation);
    }

    private String formatReadout(AlignmentResult result) {
        StringBuilder builder = new StringBuilder();
        builder.append("HA: ").append(UiFormatting.formatHours(result.polarisHourAngleHours)).append('\n');
        builder.append("RA/Dec: ").append(UiFormatting.formatRightAscension(result.apparentRightAscensionRadians))
                .append(" / ").append(UiFormatting.formatDeclination(result.apparentDeclinationRadians)).append('\n');
        builder.append("Alt/Az: ").append(UiFormatting.formatDegrees(result.trueAltitudeRadians, 3))
                .append(" / ").append(UiFormatting.formatDegrees(result.trueAzimuthRadians, 3)).append('\n');
        builder.append("LAST: ").append(UiFormatting.formatHours(result.localApparentSiderealTimeDegrees / 15.0)).append('\n');
        builder.append("Refraction: ").append(String.format(Locale.US, "%.3f′ (%s)", result.refractionArcMinutes, result.refractionDescription)).append('\n');
        builder.append("Marker SVG: x=").append(String.format(Locale.US, "%.2f", result.markerSvgX))
                .append(", y=").append(String.format(Locale.US, "%.2f", result.markerSvgY)).append('\n');
        builder.append("Ring scale: ").append(String.format(Locale.US, "%.2f px, %.1f px/tan-rad", result.nominalRingRadiusSvgPixels, result.pixelPerTangentRadian)).append('\n');
        builder.append("ΔT: ").append(String.format(Locale.US, "%.2f s", result.deltaTSeconds));
        return builder.toString();
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

    private TextView label(String text, int sp, boolean bold) {
        TextView textView = new TextView(this);
        textView.setText(text);
        textView.setTextColor(android.graphics.Color.rgb(242, 238, 238));
        textView.setTextSize(sp);
        textView.setGravity(Gravity.START);
        textView.setPadding(0, dp(4), 0, dp(6));
        textView.setTypeface(null, bold ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        return textView;
    }

    private TextView fieldLabel(String text) {
        TextView textView = label(text, 14, true);
        textView.setTextColor(android.graphics.Color.rgb(255, 204, 0));
        return textView;
    }

    private TextView note(String text) {
        TextView textView = label(text, 13, false);
        textView.setTextColor(android.graphics.Color.rgb(170, 175, 186));
        textView.setPadding(0, dp(4), 0, dp(8));
        return textView;
    }

    private EditText editText(String hint) {
        EditText editText = new EditText(this);
        editText.setHint(hint);
        editText.setSingleLine(true);
        editText.setTextColor(android.graphics.Color.rgb(232, 232, 232));
        editText.setHintTextColor(android.graphics.Color.rgb(128, 132, 140));
        editText.setTextSize(14);
        editText.setPadding(dp(10), dp(6), dp(10), dp(6));
        editText.setBackgroundColor(android.graphics.Color.rgb(11, 13, 18));
        return editText;
    }

    private Button button(String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(android.graphics.Color.rgb(232, 232, 232));
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        return button;
    }

    private LinearLayout horizontalRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));
        return row;
    }

    private LinearLayout.LayoutParams weightParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        params.setMargins(dp(2), dp(2), dp(2), dp(2));
        return params;
    }

    private int dp(int value) {
        return (int) Math.round(value * getResources().getDisplayMetrics().density);
    }
}
