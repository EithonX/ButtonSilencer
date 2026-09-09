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
    private boolean scanInProgress;
    private boolean pendingAccessibilitySetup;

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
        if (pendingAccessibilitySetup) {
            pendingAccessibilitySetup = false;
            if (serviceEnabled) {
                Preferences.putBoolean(this, Preferences.KEY_MASTER, true);
                Toast.makeText(this, R.string.accessibility_ready, Toast.LENGTH_SHORT).show();
            }
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
        if (pendingDeviceScan || scanInProgress) {
            pendingDeviceScan = false;
            scanInProgress = false;
            shizukuController.releaseSetupConnection();
        }
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

            // This switch is intent, while the hero reports actual coverage. Enabling protection
            // must never be blocked by one unavailable engine: Accessibility can protect screen-on
            // independently, and Shizuku can recover the screen-off route asynchronously.
            if (!checked) {
                if (!scanInProgress) {
                    pendingDeviceScan = false;
                }
                Preferences.putBoolean(this, Preferences.KEY_MASTER, false);
                shizukuController.setProtectionEnabled(false);
                refreshUi();
                return;
            }

            if (isAccessibilityServiceEnabled()) {
                Preferences.putBoolean(this, Preferences.KEY_MASTER, true);
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
        boolean privilegedDesired = mediaDesired || volumeDesired;
        boolean accessibilityEnabled = isAccessibilityServiceEnabled();
        boolean accessibilityFilteringActive = accessibilityEnabled
                && Preferences.isMasterEnabled(this);
        boolean protectionDesired = privilegedDesired || accessibilityFilteringActive;
        String selected = Preferences.headsetVolumeDevice(this);
        VolumeInputDeviceParser.Device selectedDevice = VolumeInputDeviceParser.decode(selected);
        int stateFlags = shizukuController.getStateFlags();

        updatingUi = true;
        protectionSwitch.setChecked(protectionDesired);
        protectionSwitch.setEnabled(true);
        mediaListenerSwitch.setChecked(mediaDesired);
        headsetVolumeGuardSwitch.setChecked(volumeDesired);
        headsetVolumeGuardSwitch.setEnabled(!selected.isEmpty());
        diagnosticLoggingSwitch.setChecked(Preferences.diagnosticLoggingEnabled(this));
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
                accessibilityFilteringActive,
                selectedDevice != null,
                stateFlags
        );
        runtimeStatusText.setText(getString(
                R.string.runtime_status_format,
                shizukuController.getLocalStatus()
        ));

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
        updateScanButtonState();
    }

    private void updateProtectionStatus(
            boolean protectionDesired,
            boolean mediaDesired,
            boolean volumeDesired,
            boolean accessibilityActive,
            boolean hasSelectedDevice,
            int stateFlags
    ) {
        boolean remoteConnected = shizukuController.isRemoteConnected();
        boolean mediaReady = remoteConnected
                && mediaDesired
                && (stateFlags & PrivilegedState.MEDIA_ENABLED) != 0;
        boolean volumeReady = remoteConnected
                && volumeDesired
                && (stateFlags & PrivilegedState.VOLUME_GUARD_ACTIVE) != 0;
        boolean hasError = remoteConnected
                && (stateFlags & PrivilegedState.HAS_ERROR) != 0;
        boolean privilegedReady = mediaReady
                && (!volumeDesired || volumeReady)
                && !hasError;

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

        // Accessibility is the preferred screen-on path. A Shizuku outage must never make the
        // whole app look or behave disabled while Accessibility is still actively filtering keys.
        if (accessibilityActive && privilegedReady) {
            applyStatus(
                    R.string.protection_active,
                    hasSelectedDevice && volumeDesired
                            ? R.string.protection_active_full_detail
                            : R.string.protection_active_media_only_detail,
                    R.drawable.bg_status_active,
                    R.color.status_active
            );
            reconnectShizukuButton.setVisibility(View.GONE);
            return;
        }

        if (accessibilityActive) {
            applyStatus(
                    R.string.protection_screen_on_only,
                    mediaDesired || volumeDesired
                            ? R.string.protection_screen_on_only_detail
                            : R.string.protection_screen_on_only_disabled_detail,
                    R.drawable.bg_status_warning,
                    R.color.status_warning
            );
            configureShizukuRecoveryAction(mediaDesired || volumeDesired);
            return;
        }

        if (privilegedReady) {
            applyStatus(
                    R.string.protection_screen_off_only,
                    hasSelectedDevice && volumeDesired
                            ? R.string.protection_screen_off_only_detail
                            : R.string.protection_screen_off_media_only_detail,
                    R.drawable.bg_status_warning,
                    R.color.status_warning
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

        if (!remoteConnected) {
            applyStatus(
                    R.string.connecting,
                    R.string.connecting_detail,
                    R.drawable.bg_status_warning,
                    R.color.status_warning
            );
            reconnectShizukuButton.setText(R.string.retry_protection);
            reconnectShizukuButton.setVisibility(View.VISIBLE);
            return;
        }

        applyStatus(
                R.string.protection_partial,
                R.string.protection_partial_detail,
                R.drawable.bg_status_warning,
                R.color.status_warning
        );
        reconnectShizukuButton.setText(R.string.retry_protection);
        reconnectShizukuButton.setVisibility(View.VISIBLE);
    }

    private void configureShizukuRecoveryAction(boolean privilegedRequested) {
        if (!privilegedRequested) {
            reconnectShizukuButton.setVisibility(View.GONE);
            return;
        }
        if (!shizukuController.isBinderAlive()) {
            reconnectShizukuButton.setText(R.string.connect_shizuku);
            reconnectShizukuButton.setVisibility(View.VISIBLE);
        } else if (!shizukuController.hasPermission()) {
            reconnectShizukuButton.setText(R.string.request_shizuku_access);
            reconnectShizukuButton.setVisibility(View.VISIBLE);
        } else if (!shizukuController.isRemoteConnected()) {
            reconnectShizukuButton.setText(R.string.retry_protection);
            reconnectShizukuButton.setVisibility(View.VISIBLE);
        } else {
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
        if (pendingDeviceScan) {
            return;
        }

        if (!shizukuController.isRemoteConnected()) {
            if (!shizukuController.isBinderAlive()) {
                shizukuController.requestPermissionOrConnect();
                refreshUi();
                return;
            }

            pendingDeviceScan = true;
            updateScanButtonState();
            shizukuController.requestSetupConnection();

            // A setup scan is a one-shot operation, not a mode. Never leave the UI locked forever
            // if permission is denied or an OEM never completes the UserService bind callback.
            scanDevicesButton.postDelayed(() -> {
                if (!pendingDeviceScan || isDestroyed()) {
                    return;
                }
                pendingDeviceScan = false;
                shizukuController.releaseSetupConnection();
                updateScanButtonState();
                refreshUi();
            }, 12_000L);
            return;
        }

        pendingDeviceScan = false;
        scanInProgress = true;
        scanDevicesButton.setEnabled(false);
        scanDevicesButton.setAlpha(0.55f);
        scanDevicesButton.setText(R.string.scanning);
        ioExecutor.execute(() -> {
            String[] scanned = shizukuController.listVolumeInputDevices();
            runOnUiThread(() -> {
                scanInProgress = false;
                shizukuController.releaseSetupConnection();
                if (isDestroyed()) {
                    return;
                }
                updateScanButtonState();
                showDevicePicker(scanned);
            });
        });
    }

    private void updateScanButtonState() {
        if (scanInProgress) {
            scanDevicesButton.setEnabled(false);
            scanDevicesButton.setAlpha(0.55f);
            scanDevicesButton.setText(R.string.scanning);
            return;
        }
        if (pendingDeviceScan) {
            scanDevicesButton.setEnabled(false);
            scanDevicesButton.setAlpha(0.55f);
            scanDevicesButton.setText(R.string.connecting_to_scan);
            return;
        }
        scanDevicesButton.setEnabled(true);
        scanDevicesButton.setAlpha(1.0f);
        scanDevicesButton.setText(shizukuController.isRemoteConnected()
                ? R.string.scan_devices
                : R.string.connect_and_scan);
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
                    // Selecting a headset while global protection is already requested should
                    // immediately add the screen-off volume route. When protection is off we only
                    // remember the device for later.
                    boolean enableVolumeGuard = Preferences.privilegedMediaEnabled(this)
                            || Preferences.headsetVolumeGuardEnabled(this);
                    shizukuController.setHeadsetVolumeGuard(selected, enableVolumeGuard);
                    Toast.makeText(this, R.string.device_selected, Toast.LENGTH_SHORT).show();
                    refreshUi();
                })
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> refreshUi())
                .setOnCancelListener(dialog -> refreshUi())
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
