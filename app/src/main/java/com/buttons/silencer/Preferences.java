package com.buttons.silencer;

import android.content.Context;
import android.content.SharedPreferences;

final class Preferences {
    private static final String FILE_NAME = "button_silencer_preferences";

    static final String KEY_MASTER = "master_enabled";
    static final String KEY_MEDIA = "block_media";
    static final String KEY_EXTERNAL_VOLUME = "block_external_volume";
    static final String KEY_ASSIST_CALL = "block_assist_call";
    static final String KEY_ALL_VOLUME = "block_all_volume";
    static final String KEY_PRIVILEGED_MEDIA = "privileged_media_enabled";

    private Preferences() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE);
    }

    static boolean isMasterEnabled(Context context) {
        return prefs(context).getBoolean(KEY_MASTER, true);
    }

    static boolean blockMedia(Context context) {
        return prefs(context).getBoolean(KEY_MEDIA, true);
    }

    static boolean blockExternalVolume(Context context) {
        return prefs(context).getBoolean(KEY_EXTERNAL_VOLUME, true);
    }

    static boolean blockAssistCall(Context context) {
        return prefs(context).getBoolean(KEY_ASSIST_CALL, true);
    }

    static boolean blockAllVolume(Context context) {
        return prefs(context).getBoolean(KEY_ALL_VOLUME, false);
    }

    static boolean privilegedMediaEnabled(Context context) {
        return prefs(context).getBoolean(KEY_PRIVILEGED_MEDIA, false);
    }

    static void putBoolean(Context context, String key, boolean value) {
        prefs(context).edit().putBoolean(key, value).apply();
    }
}
