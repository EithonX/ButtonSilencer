package com.buttons.silencer;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final List<String> volumeDeviceValues = new ArrayList<>();
    private final List<String> volumeDeviceLabels = new ArrayList<>();

    private TextView statusText;
    private TextView shizukuStatusText;
    private TextView eventLogText;
    private Switch masterSwitch;
    private Switch mediaSwitch;
    private Switch externalVolumeSwitch;
    private Switch assistCallSwitch;
    private Switch allVolumeSwitch;
    private Switch privilegedMediaSwitch;
    private Switch headsetVolumeGuardSwitch;
    private Spinner volumeDeviceSpinner;
    private Button scanVolumeDevicesButton;
    private ArrayAdapter<String> volumeDeviceAdapter;
    private ShizukuController shizukuController;
    private boolean updatingVolumeSelection;

    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshStatusAndLog();
            refreshHandler.postDelayed(this, 750L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        shizukuController = ((ButtonSilencerApp) getApplication()).getShizukuController();

        statusText = findViewById(R.id.statusText);
        shizukuStatusText = findViewById(R.id.shizukuStatusText);
        eventLogText = findViewById(R.id.eventLogText);
        masterSwitch = findViewById(R.id.masterSwitch);
        mediaSwitch = findViewById(R.id.mediaSwitch);
        externalVolumeSwitch = findViewById(R.id.externalVolumeSwitch);
        assistCallSwitch = findViewById(R.id.assistCallSwitch);
        allVolumeSwitch = findViewById(R.id.allVolumeSwitch);
        privilegedMediaSwitch = findViewById(R.id.privilegedMediaSwitch);
        headsetVolumeGuardSwitch = findViewById(R.id.headsetVolumeGuardSwitch);
        volumeDeviceSpinner = findViewById(R.id.volumeDeviceSpinner);
        scanVolumeDevicesButton = findViewById(R.id.scanVolumeDevicesButton);

        Button reconnectShizukuButton = findViewById(R.id.reconnectShizukuButton);
        Button openAccessibilityButton = findViewById(R.id.openAccessibilityButton);
        Button openAppInfoButton = findViewById(R.id.openAppInfoButton);
        Button clearEventsButton = findViewById(R.id.clearEventsButton);

        volumeDeviceAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                volumeDeviceLabels
        );
        volumeDeviceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        volumeDeviceSpinner.setAdapter(volumeDeviceAdapter);

        loadSwitchValues();
        loadSavedVolumeDevice();
        attachSwitchListeners();
        attachVolumeDeviceListeners();

        reconnectShizukuButton.setOnClickListener(view ->
                shizukuController.requestPermissionOrConnect());

        openAccessibilityButton.setOnClickListener(view ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        openAppInfoButton.setOnClickListener(view -> startActivity(new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName())
        )));

        clearEventsButton.setOnClickListener(view -> {
            EventLogStore.clear(this);
            refreshStatusAndLog();
        });

        refreshStatusAndLog();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (Preferences.privilegedMediaEnabled(this)
                || Preferences.headsetVolumeGuardEnabled(this)) {
            shizukuController.requestPermissionOrConnect();
        }
        refreshHandler.removeCallbacks(refreshRunnable);
        refreshHandler.post(refreshRunnable);
    }

    @Override
    protected void onStop() {
        refreshHandler.removeCallbacks(refreshRunnable);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        ioExecutor.shutdownNow();
        super.onDestroy();
    }

    private void loadSwitchValues() {
        masterSwitch.setChecked(Preferences.isMasterEnabled(this));
        mediaSwitch.setChecked(Preferences.blockMedia(this));
        externalVolumeSwitch.setChecked(Preferences.blockExternalVolume(this));
        assistCallSwitch.setChecked(Preferences.blockAssistCall(this));
        allVolumeSwitch.setChecked(Preferences.blockAllVolume(this));
        privilegedMediaSwitch.setChecked(Preferences.privilegedMediaEnabled(this));
        headsetVolumeGuardSwitch.setChecked(Preferences.headsetVolumeGuardEnabled(this));
    }

    private void loadSavedVolumeDevice() {
        String saved = Preferences.headsetVolumeDevice(this);
        if (saved.isEmpty()) {
            volumeDeviceSpinner.setEnabled(false);
            return;
        }
        updatingVolumeSelection = true;
        volumeDeviceValues.add(saved);
        volumeDeviceLabels.add(VolumeInputDeviceParser.displayLabel(saved));
        volumeDeviceAdapter.notifyDataSetChanged();
        volumeDeviceSpinner.setSelection(0, false);
        volumeDeviceSpinner.setEnabled(true);
        updatingVolumeSelection = false;
    }

    private void attachSwitchListeners() {
        masterSwitch.setOnCheckedChangeListener((button, checked) -> {
            Preferences.putBoolean(this, Preferences.KEY_MASTER, checked);
            refreshStatusAndLog();
        });

        mediaSwitch.setOnCheckedChangeListener((button, checked) ->
                Preferences.putBoolean(this, Preferences.KEY_MEDIA, checked));

        externalVolumeSwitch.setOnCheckedChangeListener((button, checked) ->
                Preferences.putBoolean(this, Preferences.KEY_EXTERNAL_VOLUME, checked));

        assistCallSwitch.setOnCheckedChangeListener((button, checked) ->
                Preferences.putBoolean(this, Preferences.KEY_ASSIST_CALL, checked));

        allVolumeSwitch.setOnCheckedChangeListener((button, checked) -> {
            Preferences.putBoolean(this, Preferences.KEY_ALL_VOLUME, checked);
            if (checked) {
                Toast.makeText(this, R.string.block_all_volume_warning, Toast.LENGTH_LONG).show();
            }
        });

        privilegedMediaSwitch.setOnCheckedChangeListener((button, checked) -> {
            shizukuController.setPrivilegedEnabled(checked);
            if (checked) {
                Toast.makeText(this, R.string.shizuku_enable_hint, Toast.LENGTH_LONG).show();
            }
            refreshStatusAndLog();
        });

        headsetVolumeGuardSwitch.setOnCheckedChangeListener((button, checked) -> {
            String selected = selectedVolumeDevice();
            if (checked && selected.isEmpty()) {
                button.setChecked(false);
                Toast.makeText(this, R.string.headset_device_prompt, Toast.LENGTH_LONG).show();
                scanVolumeInputDevices();
                return;
            }
            shizukuController.setHeadsetVolumeGuard(selected, checked);
            refreshStatusAndLog();
        });
    }

    private void attachVolumeDeviceListeners() {
        scanVolumeDevicesButton.setOnClickListener(view -> scanVolumeInputDevices());
        volumeDeviceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (updatingVolumeSelection
                        || position < 0
                        || position >= volumeDeviceValues.size()) {
                    return;
                }
                String selected = volumeDeviceValues.get(position);
                Preferences.putString(
                        MainActivity.this,
                        Preferences.KEY_HEADSET_VOLUME_DEVICE,
                        selected
                );
                if (headsetVolumeGuardSwitch.isChecked()) {
                    shizukuController.setHeadsetVolumeGuard(selected, true);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Nothing to persist.
            }
        });
    }

    private void scanVolumeInputDevices() {
        if (!shizukuController.isRemoteConnected()) {
            Toast.makeText(this, R.string.connect_before_scan, Toast.LENGTH_LONG).show();
            shizukuController.requestPermissionOrConnect();
            return;
        }

        scanVolumeDevicesButton.setEnabled(false);
        ioExecutor.execute(() -> {
            String[] devices = shizukuController.listVolumeInputDevices();
            runOnUiThread(() -> {
                scanVolumeDevicesButton.setEnabled(true);
                applyScannedVolumeDevices(devices);
            });
        });
    }

    private void applyScannedVolumeDevices(String[] devices) {
        String saved = Preferences.headsetVolumeDevice(this);
        VolumeInputDeviceParser.Device savedDevice = VolumeInputDeviceParser.decode(saved);
        int selectedIndex = -1;

        updatingVolumeSelection = true;
        volumeDeviceValues.clear();
        volumeDeviceLabels.clear();

        if (devices != null) {
            for (String encoded : devices) {
                if (encoded == null || VolumeInputDeviceParser.decode(encoded) == null) {
                    continue;
                }
                if (selectedIndex < 0 && encoded.equals(saved)) {
                    selectedIndex = volumeDeviceValues.size();
                } else if (selectedIndex < 0 && savedDevice != null) {
                    VolumeInputDeviceParser.Device candidate =
                            VolumeInputDeviceParser.decode(encoded);
                    if (candidate != null
                            && !candidate.likelyInternal
                            && candidate.name.equals(savedDevice.name)) {
                        selectedIndex = volumeDeviceValues.size();
                    }
                }
                volumeDeviceValues.add(encoded);
                volumeDeviceLabels.add(VolumeInputDeviceParser.displayLabel(encoded));
            }
        }

        volumeDeviceAdapter.notifyDataSetChanged();
        boolean hasDevices = !volumeDeviceValues.isEmpty();
        volumeDeviceSpinner.setEnabled(hasDevices);

        if (hasDevices) {
            if (selectedIndex < 0) {
                selectedIndex = firstExternalCandidateIndex();
            }
            volumeDeviceSpinner.setSelection(Math.max(0, selectedIndex), false);
            String selected = volumeDeviceValues.get(Math.max(0, selectedIndex));
            Preferences.putString(this, Preferences.KEY_HEADSET_VOLUME_DEVICE, selected);
            if (headsetVolumeGuardSwitch.isChecked()) {
                shizukuController.setHeadsetVolumeGuard(selected, true);
            }
        } else {
            headsetVolumeGuardSwitch.setChecked(false);
            Toast.makeText(this, R.string.no_volume_devices, Toast.LENGTH_LONG).show();
        }

        updatingVolumeSelection = false;
    }

    private int firstExternalCandidateIndex() {
        for (int i = 0; i < volumeDeviceValues.size(); i++) {
            VolumeInputDeviceParser.Device device =
                    VolumeInputDeviceParser.decode(volumeDeviceValues.get(i));
            if (device != null && !device.likelyInternal) {
                return i;
            }
        }
        return 0;
    }

    private String selectedVolumeDevice() {
        int position = volumeDeviceSpinner.getSelectedItemPosition();
        if (position >= 0 && position < volumeDeviceValues.size()) {
            return volumeDeviceValues.get(position);
        }
        return Preferences.headsetVolumeDevice(this);
    }

    private void refreshStatusAndLog() {
        boolean serviceEnabled = isAccessibilityServiceEnabled();
        boolean blockingEnabled = Preferences.isMasterEnabled(this);

        String serviceState = getString(serviceEnabled
                ? R.string.service_enabled
                : R.string.service_disabled);
        String blockingState = getString(blockingEnabled
                ? R.string.blocking_active
                : R.string.blocking_paused);

        statusText.setText(getString(R.string.status_format, serviceState, blockingState));
        statusText.setAlpha(serviceEnabled ? 1.0f : 0.65f);
        shizukuStatusText.setText(shizukuController.getStatus());
        eventLogText.setText(EventLogStore.formatForDisplay(this));
    }

    private boolean isAccessibilityServiceEnabled() {
        AccessibilityManager manager =
                (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        if (manager == null) {
            return false;
        }

        ComponentName expected = new ComponentName(this, ButtonBlockerService.class);
        List<AccessibilityServiceInfo> enabledServices =
                manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);

        for (AccessibilityServiceInfo info : enabledServices) {
            ResolveInfo resolveInfo = info.getResolveInfo();
            if (resolveInfo == null || resolveInfo.serviceInfo == null) {
                continue;
            }

            ComponentName actual = new ComponentName(
                    resolveInfo.serviceInfo.packageName,
                    resolveInfo.serviceInfo.name
            );
            if (expected.equals(actual)) {
                return true;
            }
        }

        return false;
    }
}
