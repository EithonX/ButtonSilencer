package com.buttons.silencer;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final ShizukuController.Observer controllerObserver =
            this::onControllerStateChanged;

    private ShizukuController shizukuController;

    private TextView protectionStatusTitle;
    private TextView protectionStatusDetail;
    private TextView statusDot;
    private TextView runtimeStatusText;
    private TextView selectedDeviceName;
    private TextView selectedDevicePath;
    private TextView accessibilityStatus;
    private TextView diagnosticsText;
    private TextView advancedHeader;
    private TextView aboutHeader;
    private View protectionStatusCard;
    private View advancedContainer;
    private View aboutContainer;
    private Switch protectionSwitch;
    private Switch mediaListenerSwitch;
    private Switch headsetVolumeGuardSwitch;
    private Switch diagnosticLoggingSwitch;
    private Switch accessibilityMasterSwitch;
    private Switch accessibilityMediaSwitch;
    private Switch accessibilityExternalVolumeSwitch;
    private Switch accessibilityAssistCallSwitch;
    private Switch accessibilityAllVolumeSwitch;
    private Button scanDevicesButton;
    private Button forgetDeviceButton;
    private Button reconnectShizukuButton;
    private Button openAccessibilityButton;

    private boolean updatingUi;
    private boolean advancedExpanded;
    private boolean aboutExpanded;
    private boolean pendingDeviceScan;
    private boolean pendingAccessibilitySetup;
    private boolean pendingProtectionEnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        applyEdgeToEdgeInsets();

        shizukuController = ((ButtonSilencerApp) getApplication()).getShizukuController();
        bindViews();
        attachListeners();

        TextView versionText = findViewById(R.id.versionText);
        versionText.setText(getString(R.string.version_format, BuildConfig.VERSION_NAME));

        updateExpansionState();
        refreshUi();
    }

    @Override
    protected void onStart() {
        super.onStart();
        shizukuController.addObserver(controllerObserver);
        if (Preferences.privilegedProtectionRequested(this)) {
            shizukuController.requestPermissionOrConnect();
        }
        refreshUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean serviceEnabled = isAccessibilityServiceEnabled();
        if (pendingAccessibilitySetup && serviceEnabled) {
            pendingAccessibilitySetup = false;
            Preferences.putBoolean(this, Preferences.KEY_MASTER, true);
            Toast.makeText(this, R.string.accessibility_ready, Toast.LENGTH_SHORT).show();
        }
        refreshUi();
    }

    @Override
    protected void onStop() {
        shizukuController.removeObserver(controllerObserver);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        ioExecutor.shutdownNow();
        super.onDestroy();
    }


    private void applyEdgeToEdgeInsets() {
        // Android 15+ enforces edge-to-edge for apps targeting modern SDKs. Older Android versions
        // keep their normal decor fitting, so only add explicit system-bar padding where needed.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            return;
        }

        View root = findViewById(R.id.rootScroll);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets systemBars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()
            );
            view.setPadding(
                    systemBars.left,
                    systemBars.top,
                    systemBars.right,
                    systemBars.bottom
            );
            return insets;
        });
        root.requestApplyInsets();
    }

    private void bindViews() {
        protectionStatusTitle = findViewById(R.id.protectionStatusTitle);
        protectionStatusDetail = findViewById(R.id.protectionStatusDetail);
        statusDot = findViewById(R.id.statusDot);
        runtimeStatusText = findViewById(R.id.runtimeStatusText);
        selectedDeviceName = findViewById(R.id.selectedDeviceName);
        selectedDevicePath = findViewById(R.id.selectedDevicePath);
        accessibilityStatus = findViewById(R.id.accessibilityStatus);
        diagnosticsText = findViewById(R.id.diagnosticsText);
        advancedHeader = findViewById(R.id.advancedHeader);
        aboutHeader = findViewById(R.id.aboutHeader);
        protectionStatusCard = findViewById(R.id.protectionStatusCard);
        advancedContainer = findViewById(R.id.advancedContainer);
        aboutContainer = findViewById(R.id.aboutContainer);
        protectionSwitch = findViewById(R.id.protectionSwitch);
        mediaListenerSwitch = findViewById(R.id.mediaListenerSwitch);
        headsetVolumeGuardSwitch = findViewById(R.id.headsetVolumeGuardSwitch);
        diagnosticLoggingSwitch = findViewById(R.id.diagnosticLoggingSwitch);
        accessibilityMasterSwitch = findViewById(R.id.accessibilityMasterSwitch);
        accessibilityMediaSwitch = findViewById(R.id.accessibilityMediaSwitch);
        accessibilityExternalVolumeSwitch =
                findViewById(R.id.accessibilityExternalVolumeSwitch);
        accessibilityAssistCallSwitch = findViewById(R.id.accessibilityAssistCallSwitch);
        accessibilityAllVolumeSwitch = findViewById(R.id.accessibilityAllVolumeSwitch);
        scanDevicesButton = findViewById(R.id.scanDevicesButton);
        forgetDeviceButton = findViewById(R.id.forgetDeviceButton);
        reconnectShizukuButton = findViewById(R.id.reconnectShizukuButton);
        openAccessibilityButton = findViewById(R.id.openAccessibilityButton);
    }

    private void attachListeners() {
        protectionSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (updatingUi) {
                return;
            }
            if (!checked) {
                pendingProtectionEnable = false;
                pendingDeviceScan = false;
                shizukuController.setProtectionEnabled(false);
                refreshUi();
                return;
            }

            // The primary switch represents real, complete protection. Do not leave it visually
            // on while prerequisites are missing; walk the user through them instead.
            if (Preferences.headsetVolumeDevice(this).isEmpty()) {
                pendingProtectionEnable = true;
                updatingUi = true;
                button.setChecked(false);
                updatingUi = false;
                scanVolumeInputDevices();
                return;
            }

            shizukuController.setProtectionEnabled(true);
            refreshUi();
        });

        mediaListenerSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (!updatingUi) {
                shizukuController.setMediaListenerEnabled(checked);
                refreshUi();
            }
        });

        headsetVolumeGuardSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (updatingUi) {
                return;
            }
            String selected = Preferences.headsetVolumeDevice(this);
            if (checked && selected.isEmpty()) {
                updatingUi = true;
                button.setChecked(false);
                updatingUi = false;
                Toast.makeText(this, R.string.select_device_first, Toast.LENGTH_LONG).show();
                scanVolumeInputDevices();
                return;
            }
            shizukuController.setHeadsetVolumeGuard(selected, checked);
            refreshUi();
        });

        diagnosticLoggingSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (!updatingUi) {
                shizukuController.setDiagnosticLogging(checked);
                requestDiagnosticsRefresh();
            }
        });

        accessibilityMasterSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (updatingUi) {
                return;
            }
            if (!isAccessibilityServiceEnabled()) {
                updatingUi = true;
                button.setChecked(false);
                updatingUi = false;
                pendingAccessibilitySetup = true;
                openAccessibilitySettings();
                return;
            }
            Preferences.putBoolean(this, Preferences.KEY_MASTER, checked);
            refreshUi();
        });
        accessibilityMediaSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (!updatingUi) {
                Preferences.putBoolean(this, Preferences.KEY_MEDIA, checked);
            }
        });
        accessibilityExternalVolumeSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (!updatingUi) {
                Preferences.putBoolean(this, Preferences.KEY_EXTERNAL_VOLUME, checked);
            }
        });
        accessibilityAssistCallSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (!updatingUi) {
                Preferences.putBoolean(this, Preferences.KEY_ASSIST_CALL, checked);
            }
        });
        accessibilityAllVolumeSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (!updatingUi) {
                Preferences.putBoolean(this, Preferences.KEY_ALL_VOLUME, checked);
                if (checked) {
                    Toast.makeText(this, R.string.block_all_volume_warning, Toast.LENGTH_LONG)
                            .show();
                }
            }
        });

        scanDevicesButton.setOnClickListener(view -> scanVolumeInputDevices());
        forgetDeviceButton.setOnClickListener(view -> {
            shizukuController.forgetHeadsetDevice();
            Toast.makeText(this, R.string.device_forgotten, Toast.LENGTH_SHORT).show();
            refreshUi();
        });
        reconnectShizukuButton.setOnClickListener(view ->
                shizukuController.requestPermissionOrConnect());

        openAccessibilityButton.setOnClickListener(view -> {
            pendingAccessibilitySetup = !isAccessibilityServiceEnabled();
            openAccessibilitySettings();
        });
        findViewById(R.id.openAppInfoButton).setOnClickListener(view ->
                startActivity(new Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())
                )));
        findViewById(R.id.refreshDiagnosticsButton).setOnClickListener(view ->
                requestDiagnosticsRefresh());
        findViewById(R.id.clearDiagnosticsButton).setOnClickListener(view -> {
            EventLogStore.clear(this);
            requestDiagnosticsRefresh();
        });
        findViewById(R.id.githubButton).setOnClickListener(view ->
                openUrl(getString(R.string.developer_url)));

        advancedHeader.setOnClickListener(view -> {
            advancedExpanded = !advancedExpanded;
            updateExpansionState();
            if (advancedExpanded) {
                requestDiagnosticsRefresh();
            }
        });
        aboutHeader.setOnClickListener(view -> {
            aboutExpanded = !aboutExpanded;
            updateExpansionState();
        });
    }

    private void onControllerStateChanged() {
        refreshUi();
        if (pendingDeviceScan && shizukuController.isRemoteConnected()) {
            pendingDeviceScan = false;
            scanVolumeInputDevices();
        }
        if (pendingProtectionEnable
                && !Preferences.headsetVolumeDevice(this).isEmpty()
                && shizukuController.isRemoteConnected()) {
            pendingProtectionEnable = false;
            shizukuController.setProtectionEnabled(true);
        }
        if (advancedExpanded) {
            requestDiagnosticsRefresh();
        }
    }

    private void refreshUi() {
        if (isDestroyed()) {
            return;
        }

        boolean mediaDesired = Preferences.privilegedMediaEnabled(this);
        boolean volumeDesired = Preferences.headsetVolumeGuardEnabled(this);
        boolean protectionDesired = mediaDesired || volumeDesired;
        String selected = Preferences.headsetVolumeDevice(this);
        VolumeInputDeviceParser.Device selectedDevice = VolumeInputDeviceParser.decode(selected);
        int stateFlags = shizukuController.getStateFlags();

        updatingUi = true;
        protectionSwitch.setChecked(protectionDesired);
        protectionSwitch.setEnabled(!pendingProtectionEnable);
        mediaListenerSwitch.setChecked(mediaDesired);
        headsetVolumeGuardSwitch.setChecked(volumeDesired);
        headsetVolumeGuardSwitch.setEnabled(!selected.isEmpty());
        diagnosticLoggingSwitch.setChecked(Preferences.diagnosticLoggingEnabled(this));
        accessibilityMasterSwitch.setChecked(Preferences.isMasterEnabled(this));
        accessibilityMediaSwitch.setChecked(Preferences.blockMedia(this));
        accessibilityExternalVolumeSwitch.setChecked(Preferences.blockExternalVolume(this));
        accessibilityAssistCallSwitch.setChecked(Preferences.blockAssistCall(this));
        accessibilityAllVolumeSwitch.setChecked(Preferences.blockAllVolume(this));
        updatingUi = false;

        if (selectedDevice == null) {
            selectedDeviceName.setText(R.string.no_headset_selected);
            selectedDevicePath.setText(R.string.scan_device_hint);
            forgetDeviceButton.setEnabled(false);
            forgetDeviceButton.setAlpha(0.45f);
        } else {
            selectedDeviceName.setText(selectedDevice.name);
            selectedDevicePath.setText(selectedDevice.path);
            forgetDeviceButton.setEnabled(true);
            forgetDeviceButton.setAlpha(1.0f);
        }

        updateProtectionStatus(
                protectionDesired,
                mediaDesired,
                volumeDesired,
                stateFlags
        );
        runtimeStatusText.setText(getString(
                R.string.runtime_status_format,
                shizukuController.getLocalStatus()
        ));

        boolean accessibilityEnabled = isAccessibilityServiceEnabled();
        boolean accessibilityFilteringActive = accessibilityEnabled
                && Preferences.isMasterEnabled(this);

        updatingUi = true;
        accessibilityMasterSwitch.setChecked(accessibilityFilteringActive);
        accessibilityMasterSwitch.setEnabled(accessibilityEnabled);
        accessibilityMediaSwitch.setEnabled(accessibilityFilteringActive);
        accessibilityExternalVolumeSwitch.setEnabled(accessibilityFilteringActive);
        accessibilityAssistCallSwitch.setEnabled(accessibilityFilteringActive);
        accessibilityAllVolumeSwitch.setEnabled(accessibilityFilteringActive);
        updatingUi = false;

        accessibilityStatus.setText(accessibilityEnabled
                ? getString(accessibilityFilteringActive
                        ? R.string.accessibility_state_active
                        : R.string.accessibility_state_paused)
                : getString(R.string.accessibility_state_setup_required));
        accessibilityStatus.setAlpha(accessibilityEnabled ? 1.0f : 0.72f);
        openAccessibilityButton.setText(accessibilityEnabled
                ? R.string.manage_accessibility
                : R.string.enable_accessibility_service);
    }

    private void updateProtectionStatus(
            boolean protectionDesired,
            boolean mediaDesired,
            boolean volumeDesired,
            int stateFlags
    ) {
        if (pendingProtectionEnable) {
            if (!shizukuController.isBinderAlive()) {
                applyStatus(
                        R.string.shizuku_not_running,
                        R.string.shizuku_not_running_setup_detail,
                        R.drawable.bg_status_warning,
                        R.color.status_warning
                );
                reconnectShizukuButton.setText(R.string.connect_shizuku);
                reconnectShizukuButton.setVisibility(View.VISIBLE);
            } else if (!shizukuController.hasPermission()) {
                applyStatus(
                        R.string.permission_required,
                        R.string.permission_required_setup_detail,
                        R.drawable.bg_status_warning,
                        R.color.status_warning
                );
                reconnectShizukuButton.setText(R.string.request_shizuku_access);
                reconnectShizukuButton.setVisibility(View.VISIBLE);
            } else {
                applyStatus(
                        R.string.protection_setup,
                        R.string.protection_setup_detail,
                        R.drawable.bg_status_warning,
                        R.color.status_warning
                );
                reconnectShizukuButton.setVisibility(View.GONE);
            }
            return;
        }

        if (!protectionDesired) {
            applyStatus(
                    R.string.protection_off,
                    R.string.protection_off_detail,
                    R.drawable.bg_status_inactive,
                    R.color.status_inactive
            );
            reconnectShizukuButton.setVisibility(View.GONE);
            return;
        }

        if (!shizukuController.isBinderAlive()) {
            applyStatus(
                    R.string.shizuku_not_running,
                    R.string.shizuku_not_running_detail,
                    R.drawable.bg_status_warning,
                    R.color.status_warning
            );
            reconnectShizukuButton.setText(R.string.connect_shizuku);
            reconnectShizukuButton.setVisibility(View.VISIBLE);
            return;
        }

        if (!shizukuController.hasPermission()) {
            applyStatus(
                    R.string.permission_required,
                    R.string.permission_required_detail,
                    R.drawable.bg_status_warning,
                    R.color.status_warning
            );
            reconnectShizukuButton.setText(R.string.request_shizuku_access);
            reconnectShizukuButton.setVisibility(View.VISIBLE);
            return;
        }

        if (!shizukuController.isRemoteConnected()) {
            applyStatus(
                    R.string.connecting,
                    R.string.connecting_detail,
                    R.drawable.bg_status_warning,
                    R.color.status_warning
            );
            reconnectShizukuButton.setText(R.string.connect_shizuku);
            reconnectShizukuButton.setVisibility(View.VISIBLE);
            return;
        }

        boolean mediaReady = !mediaDesired
                || (stateFlags & PrivilegedState.MEDIA_ENABLED) != 0;
        boolean volumeReady = !volumeDesired
                || (stateFlags & PrivilegedState.VOLUME_GUARD_ACTIVE) != 0;
        boolean hasError = (stateFlags & PrivilegedState.HAS_ERROR) != 0;

        if (mediaReady && volumeReady && !hasError) {
            applyStatus(
                    R.string.protection_active,
                    volumeDesired
                            ? R.string.protection_active_full_detail
                            : R.string.protection_active_media_only_detail,
                    R.drawable.bg_status_active,
                    R.color.status_active
            );
            reconnectShizukuButton.setVisibility(View.GONE);
        } else {
            applyStatus(
                    R.string.protection_partial,
                    R.string.protection_partial_detail,
                    R.drawable.bg_status_warning,
                    R.color.status_warning
            );
            reconnectShizukuButton.setText(R.string.retry_protection);
            reconnectShizukuButton.setVisibility(View.VISIBLE);
        }
    }

    private void applyStatus(
            int titleRes,
            int detailRes,
            int backgroundRes,
            int dotColorRes
    ) {
        protectionStatusTitle.setText(titleRes);
        protectionStatusDetail.setText(detailRes);
        protectionStatusCard.setBackgroundResource(backgroundRes);
        statusDot.setTextColor(getColor(dotColorRes));
    }

    private void scanVolumeInputDevices() {
        if (!shizukuController.isRemoteConnected()) {
            pendingDeviceScan = true;
            Toast.makeText(this, R.string.connecting_before_scan, Toast.LENGTH_LONG).show();
            shizukuController.requestPermissionOrConnect();
            return;
        }

        scanDevicesButton.setEnabled(false);
        scanDevicesButton.setAlpha(0.55f);
        scanDevicesButton.setText(R.string.scanning);
        ioExecutor.execute(() -> {
            String[] scanned = shizukuController.listVolumeInputDevices();
            runOnUiThread(() -> {
                if (isDestroyed()) {
                    return;
                }
                scanDevicesButton.setEnabled(true);
                scanDevicesButton.setAlpha(1.0f);
                scanDevicesButton.setText(R.string.scan_devices);
                showDevicePicker(scanned);
            });
        });
    }

    private void showDevicePicker(String[] encodedDevices) {
        List<String> values = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        int ignoredInternalCount = 0;

        if (encodedDevices != null) {
            for (String encoded : encodedDevices) {
                VolumeInputDeviceParser.Device device = VolumeInputDeviceParser.decode(encoded);
                if (device == null) {
                    continue;
                }
                if (device.likelyInternal) {
                    ignoredInternalCount++;
                    continue;
                }
                values.add(encoded);
                labels.add(VolumeInputDeviceParser.displayLabel(encoded));
            }
        }

        if (values.isEmpty()) {
            pendingProtectionEnable = false;
            Toast.makeText(
                    this,
                    ignoredInternalCount > 0
                            ? R.string.only_phone_buttons_found
                            : R.string.no_volume_devices,
                    Toast.LENGTH_LONG
            ).show();
            refreshUi();
            return;
        }

        CharSequence[] items = labels.toArray(new CharSequence[0]);
        new AlertDialog.Builder(this)
                .setTitle(R.string.choose_headset_device)
                .setItems(items, (dialog, index) -> {
                    String selected = values.get(index);
                    boolean completeProtectionSetup = pendingProtectionEnable;
                    pendingProtectionEnable = false;
                    shizukuController.setHeadsetVolumeGuard(
                            selected,
                            Preferences.headsetVolumeGuardEnabled(this)
                    );
                    if (completeProtectionSetup) {
                        shizukuController.setProtectionEnabled(true);
                        Toast.makeText(this, R.string.protection_setup_complete,
                                Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, R.string.device_selected, Toast.LENGTH_SHORT).show();
                    }
                    refreshUi();
                })
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                    pendingProtectionEnable = false;
                    refreshUi();
                })
                .setOnCancelListener(dialog -> {
                    pendingProtectionEnable = false;
                    refreshUi();
                })
                .show();
    }

    private void requestDiagnosticsRefresh() {
        if (!advancedExpanded) {
            return;
        }
        diagnosticsText.setText(R.string.loading_diagnostics);
        ioExecutor.execute(() -> {
            String remoteStatus = shizukuController.getStatus();
            String accessibilityLog = EventLogStore.formatForDisplay(this);
            String combined = remoteStatus
                    + "\n\nAccessibility session events\n"
                    + accessibilityLog;
            runOnUiThread(() -> {
                if (!isDestroyed()) {
                    diagnosticsText.setText(combined);
                }
            });
        });
    }

    private void updateExpansionState() {
        advancedContainer.setVisibility(advancedExpanded ? View.VISIBLE : View.GONE);
        aboutContainer.setVisibility(aboutExpanded ? View.VISIBLE : View.GONE);
        advancedHeader.setText(advancedExpanded
                ? R.string.advanced_hide
                : R.string.advanced_show);
        aboutHeader.setText(aboutExpanded
                ? R.string.about_hide
                : R.string.about_show);
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (ActivityNotFoundException exception) {
            Toast.makeText(this, R.string.accessibility_settings_unavailable,
                    Toast.LENGTH_LONG).show();
        }
    }

    private boolean isAccessibilityServiceEnabled() {
        AccessibilityManager manager =
                (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        if (manager == null) {
            return false;
        }

        ComponentName expected = new ComponentName(this, ButtonBlockerService.class);
        List<AccessibilityServiceInfo> enabledServices =
                manager.getEnabledAccessibilityServiceList(
                        AccessibilityServiceInfo.FEEDBACK_ALL_MASK
                );

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

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException exception) {
            Toast.makeText(this, R.string.no_browser, Toast.LENGTH_LONG).show();
        }
    }
}
