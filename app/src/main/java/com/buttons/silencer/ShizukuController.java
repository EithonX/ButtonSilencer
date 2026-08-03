package com.buttons.silencer;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;

import java.util.concurrent.CopyOnWriteArraySet;

import rikka.shizuku.Shizuku;

final class ShizukuController {
    interface Observer {
        void onControllerStateChanged();
    }

    private static final int REQUEST_CODE = 49021;
    private static final int MIN_PRIVILEGED_API = Build.VERSION_CODES.O;

    private final Context context;
    private final Shizuku.UserServiceArgs userServiceArgs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArraySet<Observer> observers = new CopyOnWriteArraySet<>();

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
            setLocalStatus("Privileged service connected");
            applyDesiredState();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            binding = false;
            remote = null;
            setLocalStatus("Privileged service disconnected");
        }
    };

    ShizukuController(Context context) {
        this.context = context.getApplicationContext();
        userServiceArgs = new Shizuku.UserServiceArgs(
                new ComponentName(this.context, PrivilegedMediaKeyService.class)
        )
                .daemon(true)
                .tag("button-silencer-privileged-v4")
                .processNameSuffix("headset_guard")
                .debuggable(BuildConfig.DEBUG)
                .version(BuildConfig.VERSION_CODE);

        Shizuku.addBinderReceivedListener(binderReceivedListener);
        Shizuku.addBinderDeadListener(binderDeadListener);
        Shizuku.addRequestPermissionResultListener(permissionResultListener);

        try {
            if (Shizuku.pingBinder()) {
                onBinderReceived();
            }
        } catch (RuntimeException exception) {
            setLocalStatus("Shizuku unavailable: " + concise(exception));
        }
    }

    void addObserver(Observer observer) {
        if (observer != null) {
            observers.add(observer);
        }
    }

    void removeObserver(Observer observer) {
        if (observer != null) {
            observers.remove(observer);
        }
    }

    void requestPermissionOrConnect() {
        if (Build.VERSION.SDK_INT < MIN_PRIVILEGED_API) {
            setLocalStatus("Privileged mode requires Android 8.0 or newer");
            return;
        }
        if (!isBinderAlive()) {
            setLocalStatus("Start Shizuku, then reconnect");
            return;
        }

        try {
            if (Shizuku.isPreV11() || Shizuku.getVersion() < 13) {
                setLocalStatus("Shizuku v13 or newer is required");
                return;
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                bindPrivilegedService();
            } else if (Shizuku.shouldShowRequestPermissionRationale()) {
                setLocalStatus("Allow Button Silencer from the Shizuku app");
            } else {
                setLocalStatus("Requesting Shizuku permission");
                Shizuku.requestPermission(REQUEST_CODE);
            }
        } catch (RuntimeException exception) {
            setLocalStatus("Shizuku request failed: " + concise(exception));
        }
    }

    void setProtectionEnabled(boolean enabled) {
        Preferences.putBoolean(context, Preferences.KEY_PRIVILEGED_MEDIA, enabled);
        String device = Preferences.headsetVolumeDevice(context);
        Preferences.putBoolean(
                context,
                Preferences.KEY_HEADSET_VOLUME_GUARD,
                enabled && !device.isEmpty()
        );

        if (enabled) {
            requestPermissionOrConnect();
            IPrivilegedBlocker current = remote;
            if (current != null) {
                applyDesiredState();
            }
        } else {
            stopPrivilegedService();
        }
        notifyObservers();
    }

    void setMediaListenerEnabled(boolean enabled) {
        Preferences.putBoolean(context, Preferences.KEY_PRIVILEGED_MEDIA, enabled);
        IPrivilegedBlocker current = remote;
        if (enabled && current == null) {
            requestPermissionOrConnect();
            return;
        }
        if (current != null) {
            try {
                current.setEnabled(enabled);
                setLocalStatus(enabled
                        ? "Privileged media-key listener enabled"
                        : "Privileged media-key listener disabled");
            } catch (RemoteException exception) {
                remote = null;
                setLocalStatus("Media listener update failed: " + concise(exception));
            }
        }
        stopServiceIfUnused();
        notifyObservers();
    }

    String[] listVolumeInputDevices() {
        IPrivilegedBlocker current = remote;
        if (current == null) {
            setLocalStatus("Connect Shizuku before scanning input devices");
            return new String[0];
        }
        try {
            return current.listVolumeInputDevices();
        } catch (RemoteException exception) {
            remote = null;
            setLocalStatus("Input-device scan failed: " + concise(exception));
            return new String[0];
        }
    }

    void setHeadsetVolumeGuard(String encodedDevice, boolean enabled) {
        String safeDevice = encodedDevice == null ? "" : encodedDevice;
        Preferences.putString(context, Preferences.KEY_HEADSET_VOLUME_DEVICE, safeDevice);
        Preferences.putBoolean(
                context,
                Preferences.KEY_HEADSET_VOLUME_GUARD,
                enabled && !safeDevice.isEmpty()
        );

        IPrivilegedBlocker current = remote;
        if (enabled && current == null) {
            requestPermissionOrConnect();
            return;
        }
        if (current != null) {
            try {
                boolean active = current.setHeadsetVolumeGuard(
                        safeDevice,
                        enabled && !safeDevice.isEmpty()
                );
                setLocalStatus(active
                        ? "Headset volume guard active"
                        : (enabled ? "Headset volume guard needs attention"
                                : "Headset volume guard off"));
            } catch (RemoteException exception) {
                remote = null;
                setLocalStatus("Headset volume guard failed: " + concise(exception));
            }
        }
        stopServiceIfUnused();
        notifyObservers();
    }

    void forgetHeadsetDevice() {
        setHeadsetVolumeGuard("", false);
    }

    void setDiagnosticLogging(boolean enabled) {
        Preferences.putBoolean(context, Preferences.KEY_DIAGNOSTIC_LOGGING, enabled);
        if (!enabled) {
            EventLogStore.clear(context);
        }
        IPrivilegedBlocker current = remote;
        if (current != null) {
            try {
                current.setDiagnosticLogging(enabled);
            } catch (RemoteException exception) {
                remote = null;
                setLocalStatus("Diagnostics update failed: " + concise(exception));
            }
        }
        notifyObservers();
    }

    int getStateFlags() {
        IPrivilegedBlocker current = remote;
        if (current == null) {
            return 0;
        }
        try {
            return current.getStateFlags();
        } catch (RemoteException exception) {
            remote = null;
            setLocalStatus("Privileged state unavailable: " + concise(exception));
            return 0;
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
            } catch (RemoteException exception) {
                remote = null;
                setLocalStatus("Privileged service connection lost: " + concise(exception));
            }
        }
        return localStatus;
    }

    String getLocalStatus() {
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
        setLocalStatus("Shizuku connected");
        if (Preferences.privilegedProtectionRequested(context)) {
            requestPermissionOrConnect();
        }
    }

    private void onBinderDead() {
        binding = false;
        remote = null;
        setLocalStatus("Shizuku stopped; restart it and reconnect");
    }

    private void onPermissionResult(int requestCode, int grantResult) {
        if (requestCode != REQUEST_CODE) {
            return;
        }
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            setLocalStatus("Shizuku permission granted");
            bindPrivilegedService();
        } else {
            setLocalStatus("Shizuku permission denied");
        }
    }

    private synchronized void bindPrivilegedService() {
        if (remote != null) {
            applyDesiredState();
            return;
        }
        if (binding) {
            return;
        }
        try {
            binding = true;
            setLocalStatus("Starting privileged headset guard");
            Shizuku.bindUserService(userServiceArgs, serviceConnection);
        } catch (RuntimeException exception) {
            binding = false;
            setLocalStatus("Could not start privileged service: " + concise(exception));
        }
    }

    private void applyDesiredState() {
        IPrivilegedBlocker current = remote;
        if (current == null) {
            return;
        }
        try {
            current.setDiagnosticLogging(Preferences.diagnosticLoggingEnabled(context));
            boolean mediaActive = current.setEnabled(
                    Preferences.privilegedMediaEnabled(context)
            );
            boolean volumeActive = current.setHeadsetVolumeGuard(
                    Preferences.headsetVolumeDevice(context),
                    Preferences.headsetVolumeGuardEnabled(context)
            );
            if (mediaActive && (volumeActive
                    || !Preferences.headsetVolumeGuardEnabled(context))) {
                setLocalStatus("Headset protection active");
            } else {
                setLocalStatus("Privileged service connected; check configuration");
            }
        } catch (RemoteException exception) {
            remote = null;
            setLocalStatus("Privileged service failed: " + concise(exception));
        }
    }

    private void stopServiceIfUnused() {
        if (!Preferences.privilegedProtectionRequested(context)) {
            stopPrivilegedService();
        }
    }

    private void stopPrivilegedService() {
        IPrivilegedBlocker current = remote;
        if (current != null) {
            try {
                current.setHeadsetVolumeGuard("", false);
                current.setEnabled(false);
            } catch (RemoteException exception) {
                setLocalStatus("Could not disable privileged blocker: " + concise(exception));
            }
        }

        remote = null;
        binding = false;
        try {
            if (isBinderAlive() && !Shizuku.isPreV11()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                Shizuku.unbindUserService(userServiceArgs, serviceConnection, true);
            }
            setLocalStatus("Privileged headset protection is off");
        } catch (RuntimeException exception) {
            setLocalStatus("Protection is off; cleanup failed: " + concise(exception));
        }
    }

    private void setLocalStatus(String status) {
        localStatus = status == null ? "" : status;
        notifyObservers();
    }

    private void notifyObservers() {
        if (observers.isEmpty()) {
            return;
        }
        mainHandler.post(() -> {
            for (Observer observer : observers) {
                observer.onControllerStateChanged();
            }
        });
    }

    private static String concise(Throwable throwable) {
        String message = throwable.getMessage();
        return throwable.getClass().getSimpleName()
                + (message == null || message.trim().isEmpty() ? "" : ": " + message);
    }
}
