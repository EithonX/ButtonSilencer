package com.buttons.silencer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses getevent capability output for input nodes that can act like a headset remote. */
final class VolumeInputDeviceParser {
    private static final Pattern DEVICE_PATTERN =
            Pattern.compile("^add device \\d+:\\s*(/dev/input/event\\d+)\\s*$");
    private static final Pattern NAME_PATTERN =
            Pattern.compile("^\\s*name:\\s*\"(.*)\"\\s*$");

    private static final String[] REMOTE_KEY_NAMES = {
            "KEY_VOLUMEUP",
            "KEY_VOLUMEDOWN",
            "KEY_MUTE",
            "KEY_MICMUTE",
            "KEY_PLAYPAUSE",
            "KEY_PLAYCD",
            "KEY_PAUSECD",
            "KEY_NEXTSONG",
            "KEY_PREVIOUSSONG",
            "KEY_STOPCD",
            "KEY_CLOSECD",
            "KEY_EJECTCD",
            "KEY_EJECTCLOSECD",
            "KEY_RECORD",
            "KEY_REWIND",
            "KEY_PLAY",
            "KEY_FASTFORWARD",
            "KEY_MEDIA",
            "KEY_PHONE",
            // Android Generic.kl maps these Linux names to CALL and HEADSETHOOK.
            "KEY_SEND",
            "KEY_FORWARDMAIL",
            "KEY_PICKUP_PHONE",
            "KEY_HANGUP_PHONE",
            "KEY_VOICECOMMAND"
    };

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
        boolean hasCallControl = false;
        boolean hasRemoteControl = false;
        boolean hasTypingKeys = false;
        boolean hasRoutingSwitch = false;

        String[] lines = output.split("\\r?\\n");
        for (String line : lines) {
            Matcher deviceMatcher = DEVICE_PATTERN.matcher(line);
            if (deviceMatcher.matches()) {
                addIfRemoteCapable(
                        result,
                        currentPath,
                        currentName,
                        hasVolumeUp,
                        hasVolumeDown,
                        hasCallControl,
                        hasRemoteControl,
                        hasTypingKeys,
                        hasRoutingSwitch
                );
                currentPath = deviceMatcher.group(1);
                currentName = null;
                hasVolumeUp = false;
                hasVolumeDown = false;
                hasCallControl = false;
                hasRemoteControl = false;
                hasTypingKeys = false;
                hasRoutingSwitch = false;
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
            if (upper.contains("KEY_PHONE")
                    || upper.contains("KEY_SEND")
                    || upper.contains("KEY_FORWARDMAIL")
                    || upper.contains("KEY_PICKUP_PHONE")
                    || upper.contains("KEY_HANGUP_PHONE")) {
                hasCallControl = true;
            }
            if (containsRemoteKey(upper)) {
                hasRemoteControl = true;
            }
            if (containsTypingKey(upper)) {
                hasTypingKeys = true;
            }
            if (containsRoutingSwitch(upper)) {
                hasRoutingSwitch = true;
            }
        }

        addIfRemoteCapable(
                result,
                currentPath,
                currentName,
                hasVolumeUp,
                hasVolumeDown,
                hasCallControl,
                hasRemoteControl,
                hasTypingKeys,
                hasRoutingSwitch
        );
        result.sort(Comparator
                .comparing((Device device) -> device.likelyInternal)
                .thenComparing(device -> device.name.toLowerCase(Locale.ROOT))
                .thenComparing(device -> device.path));
        return result;
    }

    private static boolean containsRemoteKey(String upperLine) {
        for (String keyName : REMOTE_KEY_NAMES) {
            if (upperLine.contains(keyName)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsTypingKey(String upperLine) {
        String[] tokens = upperLine.split("[^A-Z0-9_]+");
        for (String token : tokens) {
            if (token.matches("KEY_[A-Z]")
                    || token.matches("KEY_[0-9]")
                    || "KEY_SPACE".equals(token)
                    || "KEY_ENTER".equals(token)
                    || "KEY_BACKSPACE".equals(token)
                    || "KEY_TAB".equals(token)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsRoutingSwitch(String upperLine) {
        return upperLine.contains("SW_HEADPHONE_INSERT")
                || upperLine.contains("SW_MICROPHONE_INSERT")
                || upperLine.contains("SW_LINEOUT_INSERT")
                || upperLine.contains("SW_JACK_PHYSICAL_INSERT")
                || upperLine.contains("SW_LINEIN_INSERT")
                || upperLine.contains("SW_USB_INSERT");
    }

    private static void addIfRemoteCapable(
            List<Device> result,
            String path,
            String name,
            boolean hasVolumeUp,
            boolean hasVolumeDown,
            boolean hasCallControl,
            boolean hasRemoteControl,
            boolean hasTypingKeys,
            boolean hasRoutingSwitch
    ) {
        if (path == null || !hasRemoteControl) {
            return;
        }
        String safeName = name == null || name.trim().isEmpty() ? "Unnamed input device" : name;
        result.add(new Device(
                path,
                safeName,
                hasVolumeUp,
                hasVolumeDown,
                hasCallControl,
                true,
                isLikelyInternal(safeName),
                !hasTypingKeys && !hasRoutingSwitch,
                hasTypingKeys
                        ? "control node also exposes typing keys"
                        : (hasRoutingSwitch
                        ? "control node also carries headset/audio-route switch state"
                        : "")
        ));
    }

    static boolean isLikelyInternal(String name) {
        String normalized = name == null ? "" : name.toLowerCase(Locale.ROOT);
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
        // Keep stored selections compatible with earlier 3.x releases.
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
        // Capabilities are refreshed before any exclusive grab.
        return new Device(
                parts[0], parts[1], true, true, false, true, likelyInternal, true, ""
        );
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
        final boolean hasCallControl;
        final boolean hasRemoteControl;
        final boolean likelyInternal;
        final boolean exclusiveGrabSafe;
        final String unsafeGrabReason;

        Device(
                String path,
                String name,
                boolean hasVolumeUp,
                boolean hasVolumeDown,
                boolean likelyInternal
        ) {
            this(
                    path,
                    name,
                    hasVolumeUp,
                    hasVolumeDown,
                    false,
                    hasVolumeUp || hasVolumeDown,
                    likelyInternal,
                    true,
                    ""
            );
        }

        Device(
                String path,
                String name,
                boolean hasVolumeUp,
                boolean hasVolumeDown,
                boolean hasCallControl,
                boolean hasRemoteControl,
                boolean likelyInternal,
                boolean exclusiveGrabSafe,
                String unsafeGrabReason
        ) {
            this.path = path;
            this.name = name;
            this.hasVolumeUp = hasVolumeUp;
            this.hasVolumeDown = hasVolumeDown;
            this.hasCallControl = hasCallControl;
            this.hasRemoteControl = hasRemoteControl;
            this.likelyInternal = likelyInternal;
            this.exclusiveGrabSafe = exclusiveGrabSafe;
            this.unsafeGrabReason = unsafeGrabReason == null ? "" : unsafeGrabReason;
        }
    }
}
