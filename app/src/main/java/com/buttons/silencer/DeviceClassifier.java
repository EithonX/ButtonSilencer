package com.buttons.silencer;

import android.os.Build;
import android.view.InputDevice;

import java.util.Locale;

final class DeviceClassifier {
    private DeviceClassifier() {
    }

    static boolean isLikelyExternal(InputDevice device) {
        if (device == null) {
            return false;
        }

        // InputDevice.isExternal() is unavailable on the app's API 26 minimum.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && device.isExternal()) {
            return true;
        }

        String combined = (safe(device.getName()) + " " + safe(device.getDescriptor()))
                .toLowerCase(Locale.ROOT);

        return combined.contains("usb")
                || combined.contains("headset")
                || combined.contains("headphone")
                || combined.contains("earphone")
                || combined.contains("remote")
                || combined.contains("consumer")
                || combined.contains("bluetooth")
                || combined.contains("audio")
                || combined.contains("dac")
                || combined.contains("hid");
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
