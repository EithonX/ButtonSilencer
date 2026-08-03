package com.buttons.silencer;

import android.app.Application;

public final class ButtonSilencerApp extends Application {
    private ShizukuController shizukuController;

    @Override
    public void onCreate() {
        super.onCreate();
        shizukuController = new ShizukuController(this);
    }

    ShizukuController getShizukuController() {
        return shizukuController;
    }
}
