package com.buttons.silencer;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.PowerManager;
import android.view.InputDevice;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class EventLogStore {
    private static final String FILE_NAME = "button_silencer_event_log";
    private static final String KEY_EVENTS = "events_json";
    private static final int MAX_EVENTS = 30;

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
        JSONObject item = new JSONObject();
        try {
            item.put("time", System.currentTimeMillis());
            item.put("action", actionName(event.getAction()));
            item.put("keyCode", event.getKeyCode());
            item.put("keyName", KeyEvent.keyCodeToString(event.getKeyCode()));
            item.put("scanCode", event.getScanCode());
            item.put("repeat", event.getRepeatCount());
            item.put("source", String.format(Locale.ROOT, "0x%08X", event.getSource()));
            item.put("category", category.name());
            item.put("external", external);
            item.put("blocked", blocked);
            item.put("interactive", isInteractive(context));
            item.put("deviceId", event.getDeviceId());

            if (device != null) {
                item.put("deviceName", safe(device.getName()));
                item.put("descriptor", safe(device.getDescriptor()));
                item.put("vendorId", device.getVendorId());
                item.put("productId", device.getProductId());
                item.put("deviceSources", String.format(Locale.ROOT, "0x%08X", device.getSources()));
            }

            SharedPreferences preferences = preferences(context);
            JSONArray oldItems = parseArray(preferences.getString(KEY_EVENTS, "[]"));
            JSONArray newItems = new JSONArray();
            newItems.put(item);

            int copyCount = Math.min(oldItems.length(), MAX_EVENTS - 1);
            for (int i = 0; i < copyCount; i++) {
                newItems.put(oldItems.opt(i));
            }

            preferences.edit().putString(KEY_EVENTS, newItems.toString()).apply();
        } catch (JSONException ignored) {
            // Every value above is a primitive or string, so this should not occur.
        }
    }

    static synchronized String formatForDisplay(Context context) {
        JSONArray items = parseArray(preferences(context).getString(KEY_EVENTS, "[]"));
        if (items.length() == 0) {
            return context.getString(R.string.no_events);
        }

        SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());
        StringBuilder output = new StringBuilder();

        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) {
                continue;
            }

            if (output.length() > 0) {
                output.append("\n\n");
            }

            long time = item.optLong("time", 0L);
            output.append(timeFormat.format(new Date(time)))
                    .append("  ")
                    .append(item.optBoolean("blocked") ? "BLOCKED" : "PASSED")
                    .append("  ")
                    .append(item.optString("action", "?"))
                    .append('\n');

            output.append(item.optString("keyName", "KEYCODE_UNKNOWN"))
                    .append(" (")
                    .append(item.optInt("keyCode", 0))
                    .append(")  scan=")
                    .append(item.optInt("scanCode", 0))
                    .append("  repeat=")
                    .append(item.optInt("repeat", 0))
                    .append('\n');

            output.append("category=")
                    .append(item.optString("category", "OTHER"))
                    .append("  screen=")
                    .append(item.optBoolean("interactive") ? "ON" : "OFF")
                    .append("  external=")
                    .append(item.optBoolean("external"))
                    .append('\n');

            output.append("device=")
                    .append(item.optString("deviceName", "unknown"))
                    .append("  id=")
                    .append(item.optInt("deviceId", -1))
                    .append("  vid:pid=")
                    .append(item.optInt("vendorId", 0))
                    .append(':')
                    .append(item.optInt("productId", 0))
                    .append('\n');

            output.append("source=")
                    .append(item.optString("source", "0x00000000"))
                    .append("  deviceSources=")
                    .append(item.optString("deviceSources", "0x00000000"))
                    .append('\n');

            String descriptor = item.optString("descriptor", "");
            if (!descriptor.isEmpty()) {
                output.append("descriptor=").append(descriptor);
            }
        }

        return output.toString();
    }

    static synchronized void clear(Context context) {
        preferences(context).edit().remove(KEY_EVENTS).apply();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE);
    }

    private static JSONArray parseArray(String value) {
        try {
            return new JSONArray(value == null ? "[]" : value);
        } catch (JSONException ignored) {
            return new JSONArray();
        }
    }

    private static String actionName(int action) {
        switch (action) {
            case KeyEvent.ACTION_DOWN:
                return "DOWN";
            case KeyEvent.ACTION_UP:
                return "UP";
            default:
                return Integer.toString(action);
        }
    }

    private static boolean isInteractive(Context context) {
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return powerManager != null && powerManager.isInteractive();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
