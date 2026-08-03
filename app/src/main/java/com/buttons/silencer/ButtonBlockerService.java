package com.buttons.silencer;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

public final class ButtonBlockerService extends AccessibilityService {
    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();

        AccessibilityServiceInfo currentInfo = getServiceInfo();
        if (currentInfo != null) {
            currentInfo.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            currentInfo.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
            currentInfo.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
            currentInfo.notificationTimeout = 0L;
            setServiceInfo(currentInfo);
        }
    }

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        if (event == null) {
            return false;
        }

        ButtonPolicy.Category category = ButtonPolicy.categoryFor(event.getKeyCode());
        if (category == ButtonPolicy.Category.OTHER) {
            return false;
        }

        InputDevice device = event.getDevice();
        boolean external = DeviceClassifier.isLikelyExternal(device);

        ButtonPolicy.Config config = new ButtonPolicy.Config(
                Preferences.isMasterEnabled(this),
                Preferences.blockMedia(this),
                Preferences.blockExternalVolume(this),
                Preferences.blockAssistCall(this),
                Preferences.blockAllVolume(this)
        );

        boolean blocked = ButtonPolicy.shouldBlock(category, external, config);
        EventLogStore.record(this, event, device, external, blocked, category);
        return blocked;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Intentionally unused. The service does not inspect UI content.
    }

    @Override
    public void onInterrupt() {
        // No ongoing feedback or work to interrupt.
    }
}
