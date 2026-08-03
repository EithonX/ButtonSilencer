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
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public final class MainActivity extends Activity {
    private final Handler refreshHandler = new Handler(Looper.getMainLooper());

    private TextView statusText;
    private TextView eventLogText;
    private Switch masterSwitch;
    private Switch mediaSwitch;
    private Switch externalVolumeSwitch;
    private Switch assistCallSwitch;
    private Switch allVolumeSwitch;

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

        statusText = findViewById(R.id.statusText);
        eventLogText = findViewById(R.id.eventLogText);
        masterSwitch = findViewById(R.id.masterSwitch);
        mediaSwitch = findViewById(R.id.mediaSwitch);
        externalVolumeSwitch = findViewById(R.id.externalVolumeSwitch);
        assistCallSwitch = findViewById(R.id.assistCallSwitch);
        allVolumeSwitch = findViewById(R.id.allVolumeSwitch);

        Button openAccessibilityButton = findViewById(R.id.openAccessibilityButton);
        Button openAppInfoButton = findViewById(R.id.openAppInfoButton);
        Button clearEventsButton = findViewById(R.id.clearEventsButton);

        loadSwitchValues();
        attachSwitchListeners();

        openAccessibilityButton.setOnClickListener(view -> {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
        });

        openAppInfoButton.setOnClickListener(view -> {
            Intent intent = new Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())
            );
            startActivity(intent);
        });

        clearEventsButton.setOnClickListener(view -> {
            EventLogStore.clear(this);
            refreshStatusAndLog();
        });

        refreshStatusAndLog();
    }

    @Override
    protected void onStart() {
        super.onStart();
        refreshHandler.removeCallbacks(refreshRunnable);
        refreshHandler.post(refreshRunnable);
    }

    @Override
    protected void onStop() {
        refreshHandler.removeCallbacks(refreshRunnable);
        super.onStop();
    }

    private void loadSwitchValues() {
        masterSwitch.setChecked(Preferences.isMasterEnabled(this));
        mediaSwitch.setChecked(Preferences.blockMedia(this));
        externalVolumeSwitch.setChecked(Preferences.blockExternalVolume(this));
        assistCallSwitch.setChecked(Preferences.blockAssistCall(this));
        allVolumeSwitch.setChecked(Preferences.blockAllVolume(this));
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
