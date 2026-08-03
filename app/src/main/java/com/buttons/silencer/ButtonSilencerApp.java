package com.buttons.silencer;

import android.app.Application;

public final class ButtonSilencerApp extends Application {
    private ShizukuController shizukuController;

    @Override
    public void onCreate() {
        super.onCreate();
        Preferences.applyVersion3Defaults(this);
        shizukuController = new ShizukuController(this);
    }

    ShizukuController getShizukuController() {
        return shizukuController;
    }
}
