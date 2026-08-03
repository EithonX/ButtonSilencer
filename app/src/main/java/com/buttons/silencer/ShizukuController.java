package com.buttons.silencer;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.IBinder;
import android.os.RemoteException;

import rikka.shizuku.Shizuku;

final class ShizukuController {
    private static final int REQUEST_CODE = 49021;
    private static final int MIN_PRIVILEGED_API = Build.VERSION_CODES.O;

    private final Context context;
    private final Shizuku.UserServiceArgs userServiceArgs;

    private volatile IPrivilegedBlocker remote;
    private volatile boolean binding;
    private volatile String localStatus = "Shizuku binder not connected";

    private final Shizuku.OnBinderReceivedListener binderReceivedListener = this::onBinderReceived;
    private final Shizuku.OnBinderDeadListener binderDeadListener = this::onBinderDead;
    private final Shizuku.OnRequestPermissionResultListener permissionResultListener =
            this::onPermissionResult;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            binding = false;
            remote = IPrivilegedBlocker.Stub.asInterface(service);
            localStatus = "Privileged service connected";
            applyDesiredState();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            binding = false;
            remote = null;
            localStatus = "Privileged service disconnected";
        }
    };

    ShizukuController(Context context) {
        this.context = context.getApplicationContext();
        userServiceArgs = new Shizuku.UserServiceArgs(
                new ComponentName(this.context, PrivilegedMediaKeyService.class)
        )
                .daemon(true)
                .tag("button-silencer-privileged-v3")
                .processNameSuffix("media_key")
                .debuggable(BuildConfig.DEBUG)
                .version(BuildConfig.VERSION_CODE);

        Shizuku.addBinderReceivedListener(binderReceivedListener);
        Shizuku.addBinderDeadListener(binderDeadListener);
        Shizuku.addRequestPermissionResultListener(permissionResultListener);

        try {
            if (Shizuku.pingBinder()) {
                onBinderReceived();
            }
        } catch (RuntimeException e) {
            localStatus = "Shizuku unavailable: " + concise(e);
        }
    }

    void requestPermissionOrConnect() {
        if (Build.VERSION.SDK_INT < MIN_PRIVILEGED_API) {
            localStatus = "Privileged screen-off mode requires Android 8.0 or newer";
            return;
        }

        if (!isBinderAlive()) {
            localStatus = "Start Shizuku, then tap reconnect";
            return;
        }

        try {
            if (Shizuku.isPreV11() || Shizuku.getVersion() < 13) {
                localStatus = "Shizuku v13 or newer is required";
                return;
            }

            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                bindPrivilegedService();
            } else if (Shizuku.shouldShowRequestPermissionRationale()) {
                localStatus = "Shizuku permission was denied; allow it from the Shizuku app";
            } else {
                localStatus = "Requesting Shizuku permission";
                Shizuku.requestPermission(REQUEST_CODE);
            }
        } catch (RuntimeException e) {
            localStatus = "Shizuku request failed: " + concise(e);
        }
    }

    void setPrivilegedEnabled(boolean enabled) {
        Preferences.putBoolean(context, Preferences.KEY_PRIVILEGED_MEDIA, enabled);
        if (enabled) {
            requestPermissionOrConnect();
        } else {
            stopPrivilegedService();
        }
    }


    String[] listVolumeInputDevices() {
        IPrivilegedBlocker current = remote;
        if (current == null) {
            localStatus = "Connect Shizuku before scanning input devices";
            return new String[0];
        }
        try {
            return current.listVolumeInputDevices();
        } catch (RemoteException e) {
            remote = null;
            localStatus = "Input-device scan failed: " + concise(e);
            return new String[0];
        }
    }

    void setHeadsetVolumeGuard(String encodedDevice, boolean enabled) {
        Preferences.putString(context, Preferences.KEY_HEADSET_VOLUME_DEVICE, encodedDevice);
        Preferences.putBoolean(context, Preferences.KEY_HEADSET_VOLUME_GUARD, enabled);

        if (enabled && remote == null) {
            requestPermissionOrConnect();
            return;
        }

        IPrivilegedBlocker current = remote;
        if (current == null) {
            return;
        }
        try {
            boolean active = current.setHeadsetVolumeGuard(encodedDevice, enabled);
            localStatus = active
                    ? "Headset volume guard active"
                    : (enabled ? "Headset volume guard could not start" : "Headset volume guard off");
        } catch (RemoteException e) {
            remote = null;
            localStatus = "Headset volume guard failed: " + concise(e);
        }
    }

    String getStatus() {
        if (Build.VERSION.SDK_INT < MIN_PRIVILEGED_API) {
            return "Unsupported on this Android version";
        }

        IPrivilegedBlocker current = remote;
        if (current != null) {
            try {
                return current.getStatus();
            } catch (RemoteException e) {
                remote = null;
                localStatus = "Privileged service connection lost: " + concise(e);
            }
        }

        return localStatus;
    }

    boolean isRemoteConnected() {
        return remote != null;
    }

    boolean isBinderAlive() {
        try {
            return Shizuku.pingBinder();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    boolean hasPermission() {
        if (!isBinderAlive()) {
            return false;
        }
        try {
            return !Shizuku.isPreV11()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private void onBinderReceived() {
        localStatus = "Shizuku connected";
        if (Preferences.privilegedMediaEnabled(context)) {
            requestPermissionOrConnect();
        }
    }

    private void onBinderDead() {
        binding = false;
        remote = null;
        localStatus = "Shizuku stopped; reopen Shizuku and reconnect";
    }

    private void onPermissionResult(int requestCode, int grantResult) {
        if (requestCode != REQUEST_CODE) {
            return;
        }
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            localStatus = "Shizuku permission granted";
            bindPrivilegedService();
        } else {
            localStatus = "Shizuku permission denied";
        }
    }

    private synchronized void bindPrivilegedService() {
        if (remote != null || binding) {
            applyDesiredState();
            return;
        }

        try {
            binding = true;
            localStatus = "Starting privileged media-key listener";
            Shizuku.bindUserService(userServiceArgs, serviceConnection);
        } catch (RuntimeException e) {
            binding = false;
            localStatus = "Could not start privileged service: " + concise(e);
        }
    }


    private void stopPrivilegedService() {
        IPrivilegedBlocker current = remote;
        if (current != null) {
            try {
                current.setHeadsetVolumeGuard("", false);
                current.setEnabled(false);
            } catch (RemoteException e) {
                localStatus = "Could not disable privileged blocker: " + concise(e);
            }
        }

        remote = null;
        binding = false;
        try {
            if (isBinderAlive() && !Shizuku.isPreV11()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                Shizuku.unbindUserService(userServiceArgs, serviceConnection, true);
            }
            localStatus = "Privileged media-key blocking is off";
        } catch (RuntimeException e) {
            localStatus = "Blocking is off; cleanup failed: " + concise(e);
        }
    }

    private void applyDesiredState() {
        IPrivilegedBlocker current = remote;
        if (current == null) {
            return;
        }

        boolean desired = Preferences.privilegedMediaEnabled(context);
        try {
            boolean active = current.setEnabled(desired);
            boolean volumeActive = current.setHeadsetVolumeGuard(
                    Preferences.headsetVolumeDevice(context),
                    Preferences.headsetVolumeGuardEnabled(context)
            );
            localStatus = active
                    ? "Privileged media-key listener active"
                    : "Privileged listener connected but inactive";
            if (Preferences.headsetVolumeGuardEnabled(context) && !volumeActive) {
                localStatus += "; headset volume guard needs attention";
            }
        } catch (RemoteException e) {
            remote = null;
            localStatus = "Privileged service failed: " + concise(e);
        }
    }

    private static String concise(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty()
                ? throwable.getClass().getSimpleName()
                : message;
    }
}
