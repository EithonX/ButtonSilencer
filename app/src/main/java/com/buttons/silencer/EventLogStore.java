package com.buttons.silencer;

import android.content.Context;
import android.os.PowerManager;
import android.view.InputDevice;
import android.view.KeyEvent;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;

/** Session-only diagnostics. Nothing is written to storage. */
final class EventLogStore {
    private static final int MAX_EVENTS = 20;
    private static final Deque<String> EVENTS = new ArrayDeque<>();

    private EventLogStore() {
    }

    static synchronized void record(
            Context context,
            KeyEvent event,
            InputDevice device,
            boolean external,
            boolean blocked,
            ButtonPolicy.Category category
    ) {
        if (!Preferences.diagnosticLoggingEnabled(context)) {
            return;
        }

        String time = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
                .format(new Date());
        StringBuilder item = new StringBuilder();
        item.append(time)
                .append("  ")
                .append(blocked ? "BLOCKED" : "PASSED")
                .append("  ")
                .append(actionName(event.getAction()))
                .append('\n')
                .append(KeyEvent.keyCodeToString(event.getKeyCode()))
                .append(" (")
                .append(event.getKeyCode())
                .append(")  scan=")
                .append(event.getScanCode())
                .append("  repeat=")
                .append(event.getRepeatCount())
                .append('\n')
                .append("category=")
                .append(category.name())
                .append("  screen=")
                .append(isInteractive(context) ? "ON" : "OFF")
                .append("  external=")
                .append(external)
                .append('\n')
                .append("device=")
                .append(device == null ? "unknown" : safe(device.getName()))
                .append("  id=")
                .append(event.getDeviceId());

        EVENTS.addFirst(item.toString());
        while (EVENTS.size() > MAX_EVENTS) {
            EVENTS.removeLast();
        }
    }

    static synchronized String formatForDisplay(Context context) {
        if (!Preferences.diagnosticLoggingEnabled(context)) {
            return context.getString(R.string.diagnostics_disabled_message);
        }
        if (EVENTS.isEmpty()) {
            return context.getString(R.string.no_events);
        }
        StringBuilder output = new StringBuilder();
        for (String event : EVENTS) {
            if (output.length() > 0) {
                output.append("\n\n");
            }
            output.append(event);
        }
        return output.toString();
    }

    static synchronized void clear(Context context) {
        EVENTS.clear();
    }

    private static String actionName(int action) {
        if (action == KeyEvent.ACTION_DOWN) {
            return "DOWN";
        }
        if (action == KeyEvent.ACTION_UP) {
            return "UP";
        }
        return Integer.toString(action);
    }

    private static boolean isInteractive(Context context) {
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return powerManager != null && powerManager.isInteractive();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
