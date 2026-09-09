package com.buttons.silencer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class VolumeInputDeviceParser {
    private static final Pattern DEVICE_PATTERN =
            Pattern.compile("^add device \\d+:\\s*(/dev/input/event\\d+)\\s*$");
    private static final Pattern NAME_PATTERN =
            Pattern.compile("^\\s*name:\\s*\"(.*)\"\\s*$");

    private VolumeInputDeviceParser() {
    }

    static List<Device> parse(String output) {
        if (output == null || output.isEmpty()) {
            return Collections.emptyList();
        }

        List<Device> result = new ArrayList<>();
        String currentPath = null;
        String currentName = null;
        boolean hasVolumeUp = false;
        boolean hasVolumeDown = false;

        String[] lines = output.split("\\r?\\n");
        for (String line : lines) {
            Matcher deviceMatcher = DEVICE_PATTERN.matcher(line);
            if (deviceMatcher.matches()) {
                addIfVolumeCapable(result, currentPath, currentName, hasVolumeUp, hasVolumeDown);
                currentPath = deviceMatcher.group(1);
                currentName = null;
                hasVolumeUp = false;
                hasVolumeDown = false;
                continue;
            }

            if (currentPath == null) {
                continue;
            }

            Matcher nameMatcher = NAME_PATTERN.matcher(line);
            if (nameMatcher.matches()) {
                currentName = nameMatcher.group(1).trim();
            }

            String upper = line.toUpperCase(Locale.ROOT);
            if (upper.contains("KEY_VOLUMEUP")) {
                hasVolumeUp = true;
            }
            if (upper.contains("KEY_VOLUMEDOWN")) {
                hasVolumeDown = true;
            }
        }

        addIfVolumeCapable(result, currentPath, currentName, hasVolumeUp, hasVolumeDown);
        result.sort(Comparator
                .comparing((Device device) -> device.likelyInternal)
                .thenComparing(device -> device.name.toLowerCase(Locale.ROOT))
                .thenComparing(device -> device.path));
        return result;
    }

    private static void addIfVolumeCapable(
            List<Device> result,
            String path,
            String name,
            boolean hasVolumeUp,
            boolean hasVolumeDown
    ) {
        if (path == null || (!hasVolumeUp && !hasVolumeDown)) {
            return;
        }
        String safeName = name == null || name.trim().isEmpty() ? "Unnamed input device" : name;
        result.add(new Device(path, safeName, hasVolumeUp, hasVolumeDown, isLikelyInternal(safeName)));
    }

    static boolean isLikelyInternal(String name) {
        String normalized = name.toLowerCase(Locale.ROOT);
        return normalized.contains("gpio")
                || normalized.contains("qpnp")
                || normalized.contains("pmic")
                || normalized.contains("spmi")
                || normalized.contains("keypad")
                || normalized.contains("mtk-kpd")
                || normalized.contains("s2mp")
                || normalized.contains("sec_key")
                || normalized.contains("sec-key")
                || normalized.contains("volume_keys")
                || normalized.contains("volume-keys")
                || normalized.contains("sidekey")
                || normalized.contains("side key")
                || normalized.contains("pwrkey")
                || normalized.contains("powerkey")
                || normalized.contains("power key");
    }

    static String encode(Device device) {
        return sanitize(device.path) + "\t" + sanitize(device.name) + "\t"
                + (device.likelyInternal ? "internal" : "external");
    }

    static Device decode(String encoded) {
        if (encoded == null || encoded.trim().isEmpty()) {
            return null;
        }
        String[] parts = encoded.split("\\t", -1);
        if (parts.length < 2 || !parts[0].startsWith("/dev/input/event")) {
            return null;
        }
        boolean likelyInternal = parts.length >= 3 && "internal".equals(parts[2]);
        return new Device(parts[0], parts[1], true, true, likelyInternal);
    }

    static String displayLabel(String encoded) {
        Device device = decode(encoded);
        if (device == null) {
            return encoded == null ? "" : encoded;
        }
        return device.name + "  (" + device.path + ")"
                + (device.likelyInternal ? "  [likely phone buttons]" : "");
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    static final class Device {
        final String path;
        final String name;
        final boolean hasVolumeUp;
        final boolean hasVolumeDown;
        final boolean likelyInternal;

        Device(
                String path,
                String name,
                boolean hasVolumeUp,
                boolean hasVolumeDown,
                boolean likelyInternal
        ) {
            this.path = path;
            this.name = name;
            this.hasVolumeUp = hasVolumeUp;
            this.hasVolumeDown = hasVolumeDown;
            this.likelyInternal = likelyInternal;
        }
    }
}
