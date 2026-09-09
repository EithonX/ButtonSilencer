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

/**
 * Owns the app-side Shizuku lifecycle.
 *
 * <p>The privileged UserService itself is a daemon, so it can keep blocking while this normal app
 * process is gone. This controller therefore does not poll. It reacts to Shizuku binder events and
 * to the UserService binder dying, with a small capped rebind backoff only after a real failure.</p>
 */
final class ShizukuController {
    interface Observer {
        void onControllerStateChanged();
    }

    private static final int REQUEST_CODE = 49021;
    private static final int MIN_PRIVILEGED_API = Build.VERSION_CODES.O;
    private static final long[] REBIND_DELAYS_MS = {750L, 2_000L, 5_000L, 15_000L, 30_000L};
    private static final long BIND_TIMEOUT_MS = 8_000L;

    private final Context context;
    private final Shizuku.UserServiceArgs userServiceArgs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArraySet<Observer> observers = new CopyOnWriteArraySet<>();

    private volatile IPrivilegedBlocker remote;
    private volatile IBinder remoteBinder;
    private volatile boolean binding;
    private volatile boolean reconnectScheduled;
    private volatile int reconnectAttempt;
    private volatile String localStatus = "Shizuku binder not connected";

    private final IBinder.DeathRecipient remoteDeathRecipient = this::onRemoteBinderDied;
    private final Shizuku.OnBinderReceivedListener binderReceivedListener = this::onBinderReceived;
    private final Shizuku.OnBinderDeadListener binderDeadListener = this::onBinderDead;
    private final Shizuku.OnRequestPermissionResultListener permissionResultListener =
            this::onPermissionResult;

    private final Runnable bindTimeoutRunnable = () -> {
        synchronized (ShizukuController.this) {
            if (!binding || remote != null) {
                return;
            }
            // Drop a stale app-side binding before trying again. Keep the daemon itself alive;
            // a late callback from an abandoned bind must not wedge future reconnect attempts.
            detachUserServiceConnection();
            binding = false;
            setLocalStatus("Privileged service connection timed out; retrying");
            scheduleReconnect();
        }
    };

    private final Runnable reconnectRunnable;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            binding = false;
            cancelBindTimeout();
            cancelReconnect();
            reconnectAttempt = 0;
            clearRemoteBinder();

            try {
                service.linkToDeath(remoteDeathRecipient, 0);
            } catch (RemoteException exception) {
                setLocalStatus("Privileged service died while connecting");
                scheduleReconnect();
                return;
            }

            remoteBinder = service;
            remote = IPrivilegedBlocker.Stub.asInterface(service);
            setLocalStatus("Privileged service connected");
            applyDesiredState();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            binding = false;
            cancelBindTimeout();
            clearRemoteBinder();
            setLocalStatus("Privileged service disconnected; reconnecting");
            scheduleReconnect();
        }

        @Override
        public void onBindingDied(ComponentName name) {
            binding = false;
            cancelBindTimeout();
            clearRemoteBinder();
            setLocalStatus("Privileged binding died; reconnecting");
            scheduleReconnect();
        }
    };

    ShizukuController(Context context) {
        this.context = context.getApplicationContext();
        reconnectRunnable = () -> {
            reconnectScheduled = false;
            if (!Preferences.privilegedProtectionRequested(this.context)
                    || remote != null || binding) {
                return;
            }
            if (!isBinderAlive()) {
                // Binder-received is the wake-up signal. Do not poll a dead Shizuku service.
                return;
            }
            try {
                if (!Shizuku.isPreV11()
                        && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    bindPrivilegedService();
                }
            } catch (RuntimeException exception) {
                setLocalStatus("Shizuku reconnect failed: " + concise(exception));
                scheduleReconnect();
            }
        };

        userServiceArgs = new Shizuku.UserServiceArgs(
                new ComponentName(this.context, PrivilegedMediaKeyService.class)
        )
                .daemon(true)
                // Keep the tag stable across releases. version() is what tells Shizuku to
                // replace an older daemon with the newly installed service code.
                .tag("button-silencer-privileged-v4")
                .processNameSuffix("headset_guard")
                .debuggable(BuildConfig.DEBUG)
                .version(BuildConfig.VERSION_CODE);

        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener);
        Shizuku.addBinderDeadListener(binderDeadListener);
        Shizuku.addRequestPermissionResultListener(permissionResultListener);
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
        if (reconnectAttempt >= REBIND_DELAYS_MS.length) {
            cancelReconnect();
            reconnectAttempt = 0;
        }
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
            if (remote != null) {
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
                handleRemoteFailure("Media listener update failed", exception);
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
            handleRemoteFailure("Input-device scan failed", exception);
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
                        : (enabled ? "Headset volume guard is recovering"
                                : "Headset volume guard off"));
            } catch (RemoteException exception) {
                handleRemoteFailure("Headset volume guard failed", exception);
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
                handleRemoteFailure("Diagnostics update failed", exception);
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
            handleRemoteFailure("Privileged state unavailable", exception);
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
                handleRemoteFailure("Privileged service connection lost", exception);
            }
        }
        return localStatus;
    }

    String getLocalStatus() {
        return localStatus;
    }

    boolean isRemoteConnected() {
        return remote != null && remoteBinder != null && remoteBinder.isBinderAlive();
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
        cancelReconnect();
        reconnectAttempt = 0;
        setLocalStatus("Shizuku connected");
        if (Preferences.privilegedProtectionRequested(context)) {
            requestPermissionOrConnect();
        }
    }

    private void onBinderDead() {
        binding = false;
        cancelBindTimeout();
        cancelReconnect();
        reconnectAttempt = 0;
        clearRemoteBinder();
        setLocalStatus("Shizuku stopped; protection will reconnect when Shizuku returns");
    }

    private void onRemoteBinderDied() {
        mainHandler.post(() -> {
            binding = false;
            cancelBindTimeout();
            clearRemoteBinder();
            if (!isBinderAlive()) {
                cancelReconnect();
                reconnectAttempt = 0;
                setLocalStatus(
                        "Shizuku stopped; protection will reconnect when Shizuku returns"
                );
                return;
            }
            setLocalStatus("Privileged guard restarted; reconnecting");
            scheduleReconnect();
        });
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
        if (!Preferences.privilegedProtectionRequested(context)) {
            return;
        }
        if (remote != null) {
            applyDesiredState();
            return;
        }
        if (binding) {
            return;
        }
        try {
            binding = true;
            cancelReconnect();
            setLocalStatus(reconnectAttempt == 0
                    ? "Starting privileged headset guard"
                    : "Reconnecting privileged headset guard");
            Shizuku.bindUserService(userServiceArgs, serviceConnection);
            mainHandler.removeCallbacks(bindTimeoutRunnable);
            mainHandler.postDelayed(bindTimeoutRunnable, BIND_TIMEOUT_MS);
        } catch (RuntimeException exception) {
            binding = false;
            cancelBindTimeout();
            setLocalStatus("Could not start privileged service: " + concise(exception));
            scheduleReconnect();
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
            if ((mediaActive || !Preferences.privilegedMediaEnabled(context))
                    && (volumeActive || !Preferences.headsetVolumeGuardEnabled(context))) {
                reconnectAttempt = 0;
                setLocalStatus("Headset protection active");
            } else {
                setLocalStatus("Privileged service connected; recovery is armed");
            }
        } catch (RemoteException exception) {
            handleRemoteFailure("Privileged service failed", exception);
        }
    }

    private void stopServiceIfUnused() {
        if (!Preferences.privilegedProtectionRequested(context)) {
            stopPrivilegedService();
        }
    }

    private void stopPrivilegedService() {
        cancelBindTimeout();
        cancelReconnect();
        reconnectAttempt = 0;
        IPrivilegedBlocker current = remote;
        if (current != null) {
            try {
                current.setHeadsetVolumeGuard("", false);
                current.setEnabled(false);
            } catch (RemoteException exception) {
                setLocalStatus("Could not disable privileged blocker: " + concise(exception));
            }
        }

        clearRemoteBinder();
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

    private void handleRemoteFailure(String prefix, RemoteException exception) {
        binding = false;
        cancelBindTimeout();
        clearRemoteBinder();
        setLocalStatus(prefix + ": " + concise(exception));
        scheduleReconnect();
    }

    private void detachUserServiceConnection() {
        try {
            if (isBinderAlive() && !Shizuku.isPreV11()) {
                Shizuku.unbindUserService(userServiceArgs, serviceConnection, false);
            }
        } catch (RuntimeException ignored) {
            // The bind may not have completed yet; reconnect logic can safely continue.
        }
    }

    private synchronized void scheduleReconnect() {
        if (reconnectScheduled || binding || remote != null
                || !Preferences.privilegedProtectionRequested(context)) {
            return;
        }
        if (!isBinderAlive() || !hasPermission()) {
            return;
        }
        if (reconnectAttempt >= REBIND_DELAYS_MS.length) {
            setLocalStatus("Automatic reconnect paused; tap Reconnect Shizuku");
            return;
        }
        long delay = REBIND_DELAYS_MS[reconnectAttempt++];
        reconnectScheduled = true;
        mainHandler.postDelayed(reconnectRunnable, delay);
    }

    private synchronized void cancelBindTimeout() {
        mainHandler.removeCallbacks(bindTimeoutRunnable);
    }

    private synchronized void cancelReconnect() {
        reconnectScheduled = false;
        mainHandler.removeCallbacks(reconnectRunnable);
    }

    private synchronized void clearRemoteBinder() {
        IBinder binder = remoteBinder;
        remoteBinder = null;
        remote = null;
        if (binder != null) {
            try {
                binder.unlinkToDeath(remoteDeathRecipient, 0);
            } catch (RuntimeException ignored) {
                // Binder was already dead or unlinked.
            }
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
