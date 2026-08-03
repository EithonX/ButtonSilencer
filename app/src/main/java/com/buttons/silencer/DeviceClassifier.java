package com.buttons.silencer;

import android.view.InputDevice;

import java.util.Locale;

final class DeviceClassifier {
    private DeviceClassifier() {
    }

    static boolean isLikelyExternal(InputDevice device) {
        if (device == null) {
            return false;
        }

        if (device.isExternal()) {
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
