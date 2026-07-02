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
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** UVC controls inserted under the camera-panel status box. No legacy controls are hidden here. */
final class UvcCameraOptionsPanel {
    private static final String ROW_TAG = "android-polaris-stream-row";
    private static final String PANEL_TAG = "android-polaris-uvc-controls";
    private static final int BLUE = Color.rgb(134, 183, 255);
    private static final int BLUE_TEXT = Color.rgb(6, 16, 31);
    private static final int GREY = Color.rgb(58, 63, 73);
    private static final int GREY_TEXT = Color.rgb(154, 163, 176);
    private static final int TEXT = Color.rgb(243, 245, 247);
    private static final int MUTED = Color.rgb(180, 190, 203);

    private final Context context;
    private final UvcPreviewController controller;
    private final List<UvcPreviewController.StreamMode> modes = new ArrayList<>();
    private final List<String> typeOptions = new ArrayList<>();
    private final List<String> resolutionOptions = new ArrayList<>();
    private final List<Integer> fpsOptions = new ArrayList<>();
    private LinearLayout panel;
    private Spinner typeSpinner, resolutionSpinner, fpsSpinner;
    private Button refreshButton, streamButton;
    private FineSlider brightness, contrast, gain, exposure;
    private CheckBox autoExposure;
    private boolean binding, previewRunning, userSelected;
    private UvcPreviewController.StreamMode selectedMode;

    private final class FineSlider {
        final SeekBar bar = new SeekBar(context);
        final TextView value = text("—", 11, false);
        final Button minus = smallButton("−"), plus = smallButton("+");
        int progress() { return bar.getProgress(); }
        void setProgress(int progress) { bar.setProgress(clamp(progress, 0, 100)); }
        void setEnabled(boolean enabled) { bar.setEnabled(enabled); minus.setEnabled(enabled); plus.setEnabled(enabled); }
        void updateLabel() { value.setText(bar.getProgress() + "%"); }
    }

    UvcCameraOptionsPanel(Context context, UvcPreviewController controller) {
        this.context = context;
        this.controller = controller;
        attach();
    }

    void destroy() { removeOwnedViews(); panel = null; }

    void update(UvcPreviewController.UvcCapabilities c) {
        if (panel == null || c == null) return;
        binding = true;
        previewRunning = c.previewRunning;
        if (!sameModeList(modes, c.streamModes)) { modes.clear(); modes.addAll(c.streamModes); rebuildTypeSpinner(); }
        UvcPreviewController.StreamMode reported = c.selectedStreamMode != null ? c.selectedStreamMode : c.activeStreamMode;
        if (c.previewRunning) selectedMode = c.activeStreamMode;
        else if (!userSelected && !modes.isEmpty()) selectedMode = chooseDefaultMode();
        else if (selectedMode == null) selectedMode = reported;
        selectCurrentModeInSpinners();
        if (!userSelected && !c.previewRunning && selectedMode != null && !sameMode(selectedMode, reported)) controller.selectStreamMode(selectedMode);

        boolean canSelect = c.cameraOpen && !c.previewRunning && !modes.isEmpty();
        typeSpinner.setEnabled(canSelect);
        resolutionSpinner.setEnabled(canSelect && !resolutionOptions.isEmpty());
        fpsSpinner.setEnabled(canSelect && !fpsOptions.isEmpty());
        refreshButton.setEnabled(!c.previewRunning);
        stylePrimary(refreshButton);
        streamButton.setText(c.previewRunning ? "Stop stream" : "Start stream");
        streamButton.setEnabled(c.previewRunning || (canSelect && selectedModeFromSpinners() != null));
        stylePrimary(streamButton);

        brightness.setEnabled(c.cameraOpen && c.brightnessSupported);
        contrast.setEnabled(c.cameraOpen && c.contrastSupported);
        gain.setEnabled(c.cameraOpen && c.gainSupported);
        autoExposure.setEnabled(c.cameraOpen && c.autoExposureSupported);
        autoExposure.setChecked(c.autoExposureEnabled);
        exposure.setProgress(c.exposurePercent);
        exposure.setEnabled(c.cameraOpen && c.exposureSupported && !c.autoExposureEnabled);
        updateValueLabels();
        binding = false;
    }

    /** Adds Refresh/Start below the activity's UVC status box, then adds stream/hardware controls. */
    private void attach() {
        LinearLayout cameraPanel = findCameraPanel();
        if (cameraPanel == null) return;
        removeOwnedViews(cameraPanel);
        cameraPanel.addView(buttonRow(), Math.min(2, cameraPanel.getChildCount()), new LinearLayout.LayoutParams(-1, -2));
        panel = hardwarePanel();
        cameraPanel.addView(panel, Math.min(3, cameraPanel.getChildCount()), new LinearLayout.LayoutParams(-1, -2));
        wireActions();
        updateValueLabels();
    }

    private LinearLayout findCameraPanel() {
        if (!(context instanceof Activity)) return null;
        FrameLayout root = ((Activity) context).findViewById(android.R.id.content);
        ScrollView scroll = findScroll(root);
        if (scroll == null || scroll.getChildCount() == 0 || !(scroll.getChildAt(0) instanceof LinearLayout)) return null;
        LinearLayout column = (LinearLayout) scroll.getChildAt(0);
        for (int i = 0; i < column.getChildCount(); i++) if (column.getChildAt(i) instanceof LinearLayout) return (LinearLayout) column.getChildAt(i);
        return null;
    }

    private ScrollView findScroll(View v) {
        if (v instanceof ScrollView) return (ScrollView) v;
        if (!(v instanceof ViewGroup)) return null;
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) { ScrollView s = findScroll(g.getChildAt(i)); if (s != null) return s; }
        return null;
    }

    private void removeOwnedViews() { LinearLayout p = findCameraPanel(); if (p != null) removeOwnedViews(p); }
    private void removeOwnedViews(LinearLayout p) {
        for (int i = p.getChildCount() - 1; i >= 0; i--) {
            Object tag = p.getChildAt(i).getTag();
            if (ROW_TAG.equals(tag) || PANEL_TAG.equals(tag)) p.removeViewAt(i);
        }
    }

    private LinearLayout buttonRow() {
        LinearLayout row = new LinearLayout(context); row.setTag(ROW_TAG); row.setOrientation(LinearLayout.HORIZONTAL); row.setPadding(0, 0, 0, dp(6));
        refreshButton = button("Refresh USB devices", true, new View.OnClickListener() { @Override public void onClick(View v) { controller.refreshUsbDevices(); } });
        streamButton = button("Start stream", false, new View.OnClickListener() { @Override public void onClick(View v) { if (previewRunning) controller.stopStream(); else controller.startSelectedStream(); } });
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, -2, 1); left.setMargins(0, 0, dp(6), 0);
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, -2, 1); right.setMargins(dp(6), 0, 0, 0);
        row.addView(refreshButton, left); row.addView(streamButton, right); stylePrimary(refreshButton); stylePrimary(streamButton); return row;
    }

    private LinearLayout hardwarePanel() {
        LinearLayout outer = new LinearLayout(context); outer.setTag(PANEL_TAG); outer.setOrientation(LinearLayout.VERTICAL); outer.setPadding(0, dp(8), 0, dp(8));
        final TextView title = text("UVC hardware controls  ▲", 13, true); outer.addView(title, new LinearLayout.LayoutParams(-1, -2));
        final LinearLayout body = new LinearLayout(context); body.setOrientation(LinearLayout.VERTICAL); outer.addView(body, new LinearLayout.LayoutParams(-1, -2));
        title.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { boolean hide = body.getVisibility() == View.VISIBLE; body.setVisibility(hide ? View.GONE : View.VISIBLE); title.setText(hide ? "UVC hardware controls  ▼" : "UVC hardware controls  ▲"); } });
        body.addView(text("Stream type", 11, false)); typeSpinner = new Spinner(context); typeSpinner.setEnabled(false); body.addView(typeSpinner, new LinearLayout.LayoutParams(-1, -2));
        body.addView(text("Resolution", 11, false)); resolutionSpinner = new Spinner(context); resolutionSpinner.setEnabled(false); body.addView(resolutionSpinner, new LinearLayout.LayoutParams(-1, -2));
        body.addView(text("FPS", 11, false)); fpsSpinner = new Spinner(context); fpsSpinner.setEnabled(false); body.addView(fpsSpinner, new LinearLayout.LayoutParams(-1, -2));
        brightness = addSlider(body, "Brightness"); contrast = addSlider(body, "Contrast"); gain = addSlider(body, "Gain"); exposure = addSlider(body, "Exposure");
        autoExposure = new CheckBox(context); autoExposure.setText("Auto exposure"); autoExposure.setTextColor(TEXT); autoExposure.setTextSize(12); autoExposure.setEnabled(false); body.addView(autoExposure, new LinearLayout.LayoutParams(-1, -2));
        return outer;
    }

    private void wireActions() {
        typeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() { @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { if (!binding && pos >= 0 && pos < typeOptions.size()) { rebuildResolutionSpinner(typeOptions.get(pos)); selectFromSpinners(true); } } @Override public void onNothingSelected(AdapterView<?> p) { } });
        resolutionSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() { @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { if (!binding && pos >= 0 && pos < resolutionOptions.size()) { rebuildFpsSpinner(currentType(), resolutionOptions.get(pos), selectedMode == null ? -1 : selectedMode.fps); selectFromSpinners(true); } } @Override public void onNothingSelected(AdapterView<?> p) { } });
        fpsSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() { @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { if (!binding) selectFromSpinners(true); } @Override public void onNothingSelected(AdapterView<?> p) { } });
        SeekBar.OnSeekBarChangeListener l = new SeekBar.OnSeekBarChangeListener() { @Override public void onProgressChanged(SeekBar b, int p, boolean user) { if (user && !binding) updateValueLabels(); } @Override public void onStartTrackingTouch(SeekBar b) { } @Override public void onStopTrackingTouch(SeekBar b) { if (!binding) applyCameraControls(); } };
        brightness.bar.setOnSeekBarChangeListener(l); contrast.bar.setOnSeekBarChangeListener(l); gain.bar.setOnSeekBarChangeListener(l); exposure.bar.setOnSeekBarChangeListener(l);
        autoExposure.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() { @Override public void onCheckedChanged(CompoundButton b, boolean checked) { if (!binding) { exposure.setEnabled(!checked); applyCameraControls(); } } });
    }

    private void rebuildTypeSpinner() {
        typeOptions.clear();
        for (UvcPreviewController.StreamMode m : modes) if (!typeOptions.contains(m.formatLabel())) typeOptions.add(m.formatLabel());
        ArrayAdapter<String> a = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, typeOptions); a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); typeSpinner.setAdapter(a);
        UvcPreviewController.StreamMode d = selectedMode == null ? chooseDefaultMode() : selectedMode;
        String type = d == null ? (typeOptions.isEmpty() ? null : typeOptions.get(0)) : d.formatLabel();
        int i = typeOptions.indexOf(type); if (i >= 0) typeSpinner.setSelection(i, false); rebuildResolutionSpinner(type);
    }

    private void rebuildResolutionSpinner(String type) {
        resolutionOptions.clear();
        for (UvcPreviewController.StreamMode m : modes) if (type != null && type.equals(m.formatLabel()) && !resolutionOptions.contains(m.resolutionLabel())) resolutionOptions.add(m.resolutionLabel());
        ArrayAdapter<String> a = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, resolutionOptions); a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); resolutionSpinner.setAdapter(a);
        String r = selectedMode == null ? null : selectedMode.resolutionLabel(); if ((r == null || !resolutionOptions.contains(r)) && !resolutionOptions.isEmpty()) r = resolutionOptions.get(0);
        int i = resolutionOptions.indexOf(r); if (i >= 0) resolutionSpinner.setSelection(i, false); rebuildFpsSpinner(type, r, selectedMode == null ? -1 : selectedMode.fps);
    }

    private void rebuildFpsSpinner(String type, String res, int preferred) {
        fpsOptions.clear();
        for (UvcPreviewController.StreamMode m : modes) if (type != null && type.equals(m.formatLabel()) && res != null && res.equals(m.resolutionLabel()) && !fpsOptions.contains(m.fps)) fpsOptions.add(m.fps);
        ArrayAdapter<Integer> a = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, fpsOptions); a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); fpsSpinner.setAdapter(a);
        int i = fpsOptions.indexOf(preferred); if (i < 0) i = highestFpsIndex(); if (i >= 0) fpsSpinner.setSelection(i, false);
    }

    private void selectCurrentModeInSpinners() { if (selectedMode != null) { rebuildTypeSpinner(); } }
    private void selectFromSpinners(boolean fromUser) { UvcPreviewController.StreamMode m = selectedModeFromSpinners(); if (m != null && !sameMode(m, selectedMode)) { selectedMode = m; if (fromUser) userSelected = true; controller.selectStreamMode(m); } }
    private UvcPreviewController.StreamMode selectedModeFromSpinners() { String t = currentType(); int ri = resolutionSpinner.getSelectedItemPosition(), fi = fpsSpinner.getSelectedItemPosition(); if (t == null || ri < 0 || ri >= resolutionOptions.size() || fi < 0 || fi >= fpsOptions.size()) return null; String r = resolutionOptions.get(ri); int f = fpsOptions.get(fi); for (UvcPreviewController.StreamMode m : modes) if (t.equals(m.formatLabel()) && r.equals(m.resolutionLabel()) && m.fps == f) return m; return null; }
    private String currentType() { int i = typeSpinner.getSelectedItemPosition(); return i >= 0 && i < typeOptions.size() ? typeOptions.get(i) : null; }
    private UvcPreviewController.StreamMode chooseDefaultMode() { UvcPreviewController.StreamMode y = bestMode(true); return y != null ? y : bestMode(false); }
    private UvcPreviewController.StreamMode bestMode(boolean yuyvOnly) { UvcPreviewController.StreamMode best = null; for (UvcPreviewController.StreamMode m : modes) { if (yuyvOnly && !m.formatLabel().contains("YUYV")) continue; if (best == null || rank(m) > rank(best)) best = m; } return best; }
    private long rank(UvcPreviewController.StreamMode m) { return (long) m.width * m.height * 10000L + m.fps; }
    private int highestFpsIndex() { int bi = -1, bv = -1; for (int i = 0; i < fpsOptions.size(); i++) if (fpsOptions.get(i) > bv) { bv = fpsOptions.get(i); bi = i; } return bi; }

    private FineSlider addSlider(LinearLayout parent, String label) { LinearLayout row = new LinearLayout(context); row.setOrientation(LinearLayout.HORIZONTAL); row.addView(text(label, 11, false), new LinearLayout.LayoutParams(0, -2, 1)); FineSlider s = new FineSlider(); s.value.setGravity(Gravity.CENTER); row.addView(s.minus, new LinearLayout.LayoutParams(dp(44), -2)); row.addView(s.value, new LinearLayout.LayoutParams(dp(70), -2)); row.addView(s.plus, new LinearLayout.LayoutParams(dp(44), -2)); parent.addView(row, new LinearLayout.LayoutParams(-1, -2)); s.bar.setMax(100); s.bar.setProgress(50); s.bar.setEnabled(false); parent.addView(s.bar, new LinearLayout.LayoutParams(-1, -2)); s.minus.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { step(s, -1); } }); s.plus.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { step(s, 1); } }); return s; }
    private void step(FineSlider s, int d) { if (!binding && s != null && s.bar.isEnabled()) { s.setProgress(s.progress() + d); updateValueLabels(); applyCameraControls(); } }
    private void applyCameraControls() { updateValueLabels(); controller.setCameraControls(brightness.progress(), contrast.progress(), gain.progress(), exposure.progress(), autoExposure.isChecked()); }
    private void updateValueLabels() { if (brightness != null) brightness.updateLabel(); if (contrast != null) contrast.updateLabel(); if (gain != null) gain.updateLabel(); if (exposure != null) exposure.updateLabel(); }
    private boolean sameModeList(List<UvcPreviewController.StreamMode> a, List<UvcPreviewController.StreamMode> b) { if (a.size() != b.size()) return false; for (int i = 0; i < a.size(); i++) if (!sameMode(a.get(i), b.get(i))) return false; return true; }
    private boolean sameMode(UvcPreviewController.StreamMode a, UvcPreviewController.StreamMode b) { return a != null && b != null && a.frameFormat == b.frameFormat && a.width == b.width && a.height == b.height && a.fps == b.fps; }
    private Button button(String text, boolean enabled, View.OnClickListener l) { Button b = smallButton(text); b.setEnabled(enabled); if (l != null) b.setOnClickListener(l); return b; }
    private Button smallButton(String text) { Button b = new Button(context); b.setAllCaps(false); b.setText(text); b.setTextSize(12); return b; }
    private void stylePrimary(Button b) { boolean e = b.isEnabled(); b.setTextSize(15); b.setTextColor(e ? BLUE_TEXT : GREY_TEXT); b.setGravity(Gravity.CENTER); b.setMinHeight(dp(42)); b.setPadding(dp(10), dp(8), dp(10), dp(8)); GradientDrawable bg = new GradientDrawable(); bg.setShape(GradientDrawable.RECTANGLE); bg.setColor(e ? BLUE : GREY); bg.setCornerRadius(dp(10)); b.setBackground(bg); }
    private TextView text(String s, int sp, boolean bold) { TextView t = new TextView(context); t.setText(s); t.setTextSize(sp); t.setTextColor(bold ? TEXT : MUTED); t.setPadding(0, dp(2), 0, dp(2)); if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD); return t; }
    private int dp(int v) { return Math.round(v * context.getResources().getDisplayMetrics().density); }
    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
}
