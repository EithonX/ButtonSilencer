package com.buttons.silencer;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.session.MediaSessionManager;
import android.os.Build;
import android.os.FileObserver;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs in a Shizuku UserService process under shell/root identity.
 *
 * <p>The media-key listener consumes headset/media buttons before ordinary media sessions. The
 * optional headset-volume guard observes one explicitly selected evdev input device and restores
 * STREAM_MUSIC after volume presses from that device. It never claims a USB interface and never
 * changes the behavior of the phone's own side buttons.</p>
 */
public final class PrivilegedMediaKeyService extends IPrivilegedBlocker.Stub {
    private static final String LISTENER_CLASS_NAME =
            "android.media.session.MediaSessionManager$OnMediaKeyListener";
    private static final String GETEVENT = "/system/bin/getevent";
    private static final String INPUT_DIR = "/dev/input";
    private static final long[] MEDIA_RECOVERY_DELAYS_MS = {750L, 2_000L, 5_000L};
    private static final long[] VOLUME_RECOVERY_DELAYS_MS = {750L, 2_500L, 8_000L, 30_000L};
    private static final String VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION";
    private static final String EXTRA_VOLUME_STREAM_TYPE =
            "android.media.EXTRA_VOLUME_STREAM_TYPE";
    private static final String EXTRA_VOLUME_STREAM_VALUE =
            "android.media.EXTRA_VOLUME_STREAM_VALUE";
    private static final String RAW_VOLUME_DOWN_PRESS = "0001 0072 00000001";
    private static final String RAW_VOLUME_UP_PRESS = "0001 0073 00000001";

    private final Object lock = new Object();
    private final HandlerThread callbackThread;
    private final Handler callbackHandler;
    private final Runnable mediaRecoveryRunnable;
    private final Runnable volumeRecoveryRunnable;
    private final Runnable volumeRestoreRunnable;
    private final Runnable volumeRestoreFinishRunnable;
    private final AtomicInteger interceptedCount = new AtomicInteger();
    private final AtomicInteger neutralizedVolumeCount = new AtomicInteger();

    private final Context context;
    private Object mediaSessionManager;
    private Object mediaKeyListenerProxy;
    private Method setOnMediaKeyListenerMethod;

    private AudioManager audioManager;
    private Process volumeMonitorProcess;
    private Thread volumeMonitorThread;
    private BroadcastReceiver volumeReceiver;
    private FileObserver inputObserver;
    private int stableMediaVolume = -1;
    private int pendingRestoreVolume = -1;
    private long suppressVolumeUpdatesUntil;
    private boolean volumeRestoreCycleScheduled;
    private int mediaRecoveryAttempt;
    private int volumeRecoveryAttempt;
    private boolean mediaRecoveryScheduled;
    private boolean volumeRecoveryScheduled;

    private volatile boolean enabled;
    private volatile boolean registered;
    private volatile boolean volumeGuardEnabled;
    private volatile boolean diagnosticLoggingEnabled;
    private volatile boolean volumeGuardActive;
    private volatile String selectedVolumeDevice = "";
    private volatile String selectedResolvedPath = "";
    private volatile String lastError = "";
    private volatile String volumeGuardError = "";
    private volatile String volumeGuardWarning = "";
    private volatile String lastEvent = "No privileged media-key event received yet";
    private volatile String lastVolumeEvent = "No selected-headset volume event received yet";

    /** Used by Shizuku versions older than v13. Privileged mode will report a clear error. */
    public PrivilegedMediaKeyService() {
        this(null);
    }

    /** Preferred constructor used by Shizuku v13+. */
    public PrivilegedMediaKeyService(Context context) {
        this.context = context;
        callbackThread = new HandlerThread("ButtonSilencerPrivileged");
        callbackThread.start();
        callbackHandler = new Handler(callbackThread.getLooper());
        mediaRecoveryRunnable = this::recoverMediaListener;
        volumeRecoveryRunnable = this::recoverVolumeGuard;
        volumeRestoreRunnable = () -> restorePendingVolume(false);
        volumeRestoreFinishRunnable = () -> restorePendingVolume(true);
    }

    @Override
    public boolean setEnabled(boolean requestedEnabled) {
        synchronized (lock) {
            if (requestedEnabled) {
                enabled = true;
                if (!registered) {
                    registerListenerLocked();
                }
                if (!registered) {
                    scheduleMediaRecoveryLocked();
                }
            } else {
                enabled = false;
                cancelMediaRecoveryLocked();
                unregisterListenerLocked();
            }
            return enabled && registered;
        }
    }

    @Override
    public boolean isEnabled() {
        return enabled && registered;
    }

    @Override
    public int getStateFlags() {
        int flags = 0;
        if (registered) {
            flags |= PrivilegedState.MEDIA_REGISTERED;
        }
        if (enabled && registered) {
            flags |= PrivilegedState.MEDIA_ENABLED;
        }
        if (volumeGuardEnabled) {
            flags |= PrivilegedState.VOLUME_GUARD_ENABLED;
        }
        if (volumeGuardActive) {
            flags |= PrivilegedState.VOLUME_GUARD_ACTIVE;
        }
        if ((enabled && !lastError.isEmpty())
                || (volumeGuardEnabled && !volumeGuardError.isEmpty())) {
            flags |= PrivilegedState.HAS_ERROR;
        }
        return flags;
    }

    @Override
    public void setDiagnosticLogging(boolean requestedEnabled) {
        diagnosticLoggingEnabled = requestedEnabled;
        if (!requestedEnabled) {
            lastEvent = "Detailed media-event logging is off";
            lastVolumeEvent = "Detailed volume-event logging is off";
        }
    }

    @Override
    public String getStatus() {
        StringBuilder builder = new StringBuilder();
        builder.append("Shizuku media listener: ")
                .append(registered ? (enabled ? "ACTIVE" : "CONNECTED / PASSING")
                        : "NOT REGISTERED")
                .append('\n');
        builder.append("Process UID: ").append(android.os.Process.myUid()).append('\n');
        builder.append("Blocked media events: ").append(interceptedCount.get()).append('\n');
        builder.append(diagnosticLoggingEnabled
                ? lastEvent
                : "Detailed media-event logging is off");
        if (enabled && !lastError.isEmpty()) {
            builder.append("\nMedia-listener error: ").append(lastError);
        }

        builder.append("\n\nHeadset volume guard: ")
                .append(volumeGuardActive ? "ACTIVE"
                        : (volumeGuardEnabled ? "NOT ACTIVE" : "OFF"))
                .append('\n');
        builder.append("Selected device: ")
                .append(selectedVolumeDevice.isEmpty()
                        ? "none"
                        : VolumeInputDeviceParser.displayLabel(selectedVolumeDevice))
                .append('\n');
        if (!selectedResolvedPath.isEmpty()) {
            builder.append("Monitoring: ").append(selectedResolvedPath).append('\n');
        }
        builder.append("Neutralized volume presses: ")
                .append(neutralizedVolumeCount.get()).append('\n');
        builder.append(diagnosticLoggingEnabled
                ? lastVolumeEvent
                : "Detailed volume-event logging is off");
        if (volumeGuardEnabled && !volumeGuardError.isEmpty()) {
            builder.append("\nVolume-guard error: ").append(volumeGuardError);
        }
        if (volumeGuardEnabled && !volumeGuardWarning.isEmpty()) {
            builder.append("\nVolume-guard note: ").append(volumeGuardWarning);
        }
        if (volumeGuardEnabled && !volumeGuardActive) {
            builder.append("\nAuto-recovery: armed (event-driven with bounded retry)");
        }
        return builder.toString();
    }

    @Override
    public String[] listVolumeInputDevices() {
        try {
            List<VolumeInputDeviceParser.Device> devices = scanVolumeDevices();
            List<String> encoded = new ArrayList<>(devices.size());
            for (VolumeInputDeviceParser.Device device : devices) {
                encoded.add(VolumeInputDeviceParser.encode(device));
            }
            volumeGuardError = devices.isEmpty()
                    ? "No input device advertising volume keys was found"
                    : "";
            return encoded.toArray(new String[0]);
        } catch (Exception exception) {
            volumeGuardError = concise(exception);
            return new String[0];
        }
    }

    @Override
    public boolean setHeadsetVolumeGuard(String encodedDevice, boolean requestedEnabled) {
        synchronized (lock) {
            String requestedDevice = encodedDevice == null ? "" : encodedDevice;
            if (requestedEnabled == volumeGuardEnabled
                    && requestedDevice.equals(selectedVolumeDevice)
                    && (!requestedEnabled || volumeGuardActive)) {
                return volumeGuardActive;
            }

            stopVolumeGuardLocked();
            selectedVolumeDevice = requestedDevice;
            volumeGuardEnabled = requestedEnabled;
            volumeRecoveryAttempt = 0;
            if (requestedEnabled) {
                ensureInputObserverLocked();
                startVolumeGuardLocked();
            }
            return volumeGuardActive;
        }
    }

    /** Shizuku reserves this AIDL transaction for stopping a daemon UserService. */
    @Override
    public void destroy() {
        synchronized (lock) {
            enabled = false;
            volumeGuardEnabled = false;
            unregisterListenerLocked();
            stopVolumeGuardLocked();
        }
        callbackThread.quitSafely();
        System.exit(0);
    }

    @SuppressLint({"PrivateApi", "DiscouragedPrivateApi"})
    private void registerListenerLocked() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            lastError = "Privileged media-key listener requires Android 8.0 or newer";
            return;
        }

        try {
            if (context == null) {
                throw new IllegalStateException(
                        "Shizuku v13 or newer is required for the Context constructor"
                );
            }

            Object manager = obtainMediaSessionManager(context);
            Class<?> listenerClass = Class.forName(LISTENER_CLASS_NAME);
            InvocationHandler invocationHandler = this::handleListenerInvocation;
            Object proxy = Proxy.newProxyInstance(
                    PrivilegedMediaKeyService.class.getClassLoader(),
                    new Class<?>[]{listenerClass},
                    invocationHandler
            );

            Method setter = manager.getClass().getDeclaredMethod(
                    "setOnMediaKeyListener",
                    listenerClass,
                    Handler.class
            );
            setter.setAccessible(true);
            setter.invoke(manager, proxy, callbackHandler);

            mediaSessionManager = manager;
            mediaKeyListenerProxy = proxy;
            setOnMediaKeyListenerMethod = setter;
            registered = true;
            lastError = "";
            mediaRecoveryAttempt = 0;
            cancelMediaRecoveryLocked();
        } catch (Exception exception) {
            registered = false;
            mediaSessionManager = null;
            mediaKeyListenerProxy = null;
            setOnMediaKeyListenerMethod = null;
            lastError = concise(unwrap(exception));
        }
    }

    private void startVolumeGuardLocked() {
        volumeGuardActive = false;
        selectedResolvedPath = "";
        volumeGuardError = "";

        if (context == null) {
            volumeGuardError = "Shizuku v13 Context is unavailable";
            scheduleVolumeRecoveryLocked();
            return;
        }

        VolumeInputDeviceParser.Device requested =
                VolumeInputDeviceParser.decode(selectedVolumeDevice);
        if (requested == null) {
            volumeGuardError = "Scan and select the headset input device first";
            return;
        }
        if (requested.likelyInternal) {
            volumeGuardError = "Refusing a device that looks like the phone's own buttons";
            return;
        }

        try {
            VolumeInputDeviceParser.Device resolved = resolveSelectedDevice(requested);
            if (resolved == null) {
                volumeGuardError = "Selected headset input device is not currently connected";
                scheduleVolumeRecoveryLocked();
                return;
            }

            audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (audioManager == null) {
                volumeGuardError = "AudioManager is unavailable in the Shizuku process";
                scheduleVolumeRecoveryLocked();
                return;
            }

            stableMediaVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            registerVolumeReceiverLocked();

            Process process = new ProcessBuilder(GETEVENT, "-q", resolved.path)
                    .redirectErrorStream(true)
                    .start();
            volumeMonitorProcess = process;
            selectedResolvedPath = resolved.path;
            volumeGuardActive = true;
            volumeRecoveryAttempt = 0;
            cancelVolumeRecoveryLocked();

            Thread readerThread = new Thread(
                    () -> readVolumeEvents(process, resolved),
                    "ButtonSilencerGetEvent"
            );
            readerThread.setDaemon(true);
            volumeMonitorThread = readerThread;
            readerThread.start();
        } catch (Exception exception) {
            volumeGuardError = concise(exception);
            clearVolumeMonitorLocked(null);
            if (volumeGuardEnabled) {
                scheduleVolumeRecoveryLocked();
            }
        }
    }

    private VolumeInputDeviceParser.Device resolveSelectedDevice(
            VolumeInputDeviceParser.Device requested
    ) throws Exception {
        List<VolumeInputDeviceParser.Device> devices = scanVolumeDevices();
        for (VolumeInputDeviceParser.Device candidate : devices) {
            if (candidate.path.equals(requested.path) && candidate.name.equals(requested.name)) {
                return candidate;
            }
        }
        for (VolumeInputDeviceParser.Device candidate : devices) {
            if (!candidate.likelyInternal && candidate.name.equals(requested.name)) {
                selectedVolumeDevice = VolumeInputDeviceParser.encode(candidate);
                return candidate;
            }
        }
        return null;
    }

    private List<VolumeInputDeviceParser.Device> scanVolumeDevices() throws Exception {
        Process process = new ProcessBuilder(GETEVENT, "-lp")
                .redirectErrorStream(true)
                .start();
        StringBuilder output = new StringBuilder();
        AtomicReference<IOException> readFailure = new AtomicReference<>();
        Thread outputReader = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8
            ))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            } catch (IOException exception) {
                readFailure.set(exception);
            }
        }, "ButtonSilencerDeviceScan");
        outputReader.setDaemon(true);
        outputReader.start();

        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            outputReader.join(500L);
            throw new IOException("getevent device scan timed out");
        }
        outputReader.join(1_000L);
        if (outputReader.isAlive()) {
            throw new IOException("getevent device scan output did not close");
        }
        IOException readerException = readFailure.get();
        if (readerException != null) {
            throw readerException;
        }
        if (process.exitValue() != 0) {
            throw new IOException("getevent device scan failed with exit " + process.exitValue());
        }
        return VolumeInputDeviceParser.parse(output.toString());
    }

    private void readVolumeEvents(
            Process process,
            VolumeInputDeviceParser.Device selectedDevice
    ) {
        String failure = "Input monitor stopped; waiting for the headset input node";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8
        ))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!volumeGuardEnabled || process != volumeMonitorProcess) {
                    return;
                }
                String event = line.trim();
                if (RAW_VOLUME_UP_PRESS.equals(event)) {
                    handleSelectedHeadsetVolumePress(selectedDevice, true);
                } else if (RAW_VOLUME_DOWN_PRESS.equals(event)) {
                    handleSelectedHeadsetVolumePress(selectedDevice, false);
                }
            }
        } catch (IOException exception) {
            failure = concise(exception);
        }

        synchronized (lock) {
            if (process == volumeMonitorProcess && volumeGuardEnabled) {
                volumeGuardActive = false;
                selectedResolvedPath = "";
                volumeGuardError = failure;
                clearVolumeMonitorLocked(process);
                volumeRecoveryAttempt = 0;
                scheduleVolumeRecoveryLocked();
            }
        }
    }

    private void handleSelectedHeadsetVolumePress(
            VolumeInputDeviceParser.Device device,
            boolean volumeUp
    ) {
        final int targetVolume;
        synchronized (lock) {
            if (!volumeGuardEnabled || !volumeGuardActive || audioManager == null) {
                return;
            }
            int observed = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            long now = SystemClock.uptimeMillis();
            if (now >= suppressVolumeUpdatesUntil && observed != stableMediaVolume) {
                stableMediaVolume = observed;
            }
            targetVolume = stableMediaVolume >= 0 ? stableMediaVolume : observed;
            pendingRestoreVolume = targetVolume;
            suppressVolumeUpdatesUntil = now + 700L;

            // A faulty inline remote can emit a burst of repeated key-down events. Keep at most one
            // small restore cycle in the Handler queue; subsequent events only refresh the target.
            if (!volumeRestoreCycleScheduled) {
                volumeRestoreCycleScheduled = true;
                callbackHandler.postDelayed(volumeRestoreRunnable, 25L);
                callbackHandler.postDelayed(volumeRestoreRunnable, 90L);
                callbackHandler.postDelayed(volumeRestoreFinishRunnable, 220L);
            }
        }

        int count = neutralizedVolumeCount.incrementAndGet();
        if (diagnosticLoggingEnabled) {
            lastVolumeEvent = "NEUTRALIZING " + (volumeUp ? "VOLUME_UP" : "VOLUME_DOWN")
                    + "\n" + device.name + "  count=" + count;
        }
    }

    private void restorePendingVolume(boolean finishCycle) {
        final AudioManager manager;
        final int targetVolume;
        synchronized (lock) {
            if (!volumeGuardEnabled || !volumeGuardActive
                    || audioManager == null || pendingRestoreVolume < 0) {
                if (finishCycle) {
                    volumeRestoreCycleScheduled = false;
                }
                return;
            }
            manager = audioManager;
            targetVolume = pendingRestoreVolume;
        }

        try {
            int current = manager.getStreamVolume(AudioManager.STREAM_MUSIC);
            if (current != targetVolume) {
                manager.setStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        targetVolume,
                        AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE
                );
            }
            synchronized (lock) {
                if (manager == audioManager) {
                    stableMediaVolume = targetVolume;
                }
            }
        } catch (RuntimeException exception) {
            volumeGuardError = concise(exception);
        } finally {
            if (finishCycle) {
                synchronized (lock) {
                    volumeRestoreCycleScheduled = false;
                }
            }
        }
    }

    private void registerVolumeReceiverLocked() {
        if (volumeReceiver != null || context == null) {
            return;
        }

        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context receiverContext, Intent intent) {
                if (!VOLUME_CHANGED_ACTION.equals(intent.getAction())) {
                    return;
                }
                int stream = intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1);
                if (stream != AudioManager.STREAM_MUSIC) {
                    return;
                }
                long now = SystemClock.uptimeMillis();
                synchronized (lock) {
                    if (now < suppressVolumeUpdatesUntil) {
                        return;
                    }
                    int value = intent.getIntExtra(EXTRA_VOLUME_STREAM_VALUE, -1);
                    if (value >= 0) {
                        stableMediaVolume = value;
                    } else if (audioManager != null) {
                        stableMediaVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                    }
                }
            }
        };

        IntentFilter filter = new IntentFilter(VOLUME_CHANGED_ACTION);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter);
            }
            volumeReceiver = receiver;
            volumeGuardWarning = "";
        } catch (RuntimeException exception) {
            // A Shizuku UserService Context is not a normal app Context on every OEM. The guard can
            // still work by snapshotting STREAM_MUSIC directly at the key event; this receiver only
            // improves baseline tracking for legitimate volume changes between headset presses.
            volumeReceiver = null;
            volumeGuardWarning = "Volume-change callback unavailable on this Android build";
        }
    }

    private void ensureInputObserverLocked() {
        if (inputObserver != null) {
            return;
        }
        try {
            inputObserver = new FileObserver(
                    INPUT_DIR,
                    FileObserver.CREATE
                            | FileObserver.DELETE
                            | FileObserver.MOVED_FROM
                            | FileObserver.MOVED_TO
                            | FileObserver.DELETE_SELF
                            | FileObserver.MOVE_SELF
            ) {
                @Override
                public void onEvent(int event, String path) {
                    callbackHandler.post(() -> {
                        synchronized (lock) {
                            if (!volumeGuardEnabled || volumeGuardActive) {
                                return;
                            }
                            // A USB/input-node change is the strongest signal that a reconnect can
                            // succeed. Restart the bounded backoff immediately, without polling.
                            cancelVolumeRecoveryLocked();
                            volumeRecoveryAttempt = 0;
                            scheduleVolumeRecoveryLocked();
                        }
                    });
                }
            };
            inputObserver.startWatching();
        } catch (RuntimeException exception) {
            inputObserver = null;
            volumeGuardWarning = "Input-node observer unavailable: " + concise(exception);
        }
    }

    private void stopInputObserverLocked() {
        FileObserver observer = inputObserver;
        inputObserver = null;
        if (observer != null) {
            observer.stopWatching();
        }
    }

    private void recoverVolumeGuard() {
        synchronized (lock) {
            volumeRecoveryScheduled = false;
            if (!volumeGuardEnabled || volumeGuardActive) {
                return;
            }
            clearVolumeMonitorLocked(null);
            startVolumeGuardLocked();
        }
    }

    private void scheduleVolumeRecoveryLocked() {
        if (!volumeGuardEnabled || volumeGuardActive || volumeRecoveryScheduled) {
            return;
        }
        if (volumeRecoveryAttempt >= VOLUME_RECOVERY_DELAYS_MS.length) {
            // Stay fully idle until /dev/input changes. No permanent timer or wake-up loop.
            return;
        }
        long delay = VOLUME_RECOVERY_DELAYS_MS[volumeRecoveryAttempt++];
        volumeRecoveryScheduled = true;
        callbackHandler.postDelayed(volumeRecoveryRunnable, delay);
    }

    private void cancelVolumeRecoveryLocked() {
        volumeRecoveryScheduled = false;
        callbackHandler.removeCallbacks(volumeRecoveryRunnable);
    }

    private void recoverMediaListener() {
        synchronized (lock) {
            mediaRecoveryScheduled = false;
            if (!enabled || registered) {
                return;
            }
            registerListenerLocked();
            if (!registered) {
                scheduleMediaRecoveryLocked();
            }
        }
    }

    private void scheduleMediaRecoveryLocked() {
        if (!enabled || registered || mediaRecoveryScheduled) {
            return;
        }
        if (mediaRecoveryAttempt >= MEDIA_RECOVERY_DELAYS_MS.length) {
            return;
        }
        long delay = MEDIA_RECOVERY_DELAYS_MS[mediaRecoveryAttempt++];
        mediaRecoveryScheduled = true;
        callbackHandler.postDelayed(mediaRecoveryRunnable, delay);
    }

    private void cancelMediaRecoveryLocked() {
        mediaRecoveryScheduled = false;
        callbackHandler.removeCallbacks(mediaRecoveryRunnable);
    }

    private void clearVolumeMonitorLocked(Process expectedProcess) {
        Process process = volumeMonitorProcess;
        if (expectedProcess != null && process != expectedProcess) {
            return;
        }

        volumeMonitorProcess = null;
        if (process != null && process.isAlive()) {
            process.destroy();
        }

        Thread thread = volumeMonitorThread;
        volumeMonitorThread = null;
        if (thread != null && thread != Thread.currentThread()) {
            thread.interrupt();
        }

        BroadcastReceiver receiver = volumeReceiver;
        volumeReceiver = null;
        if (receiver != null && context != null) {
            try {
                context.unregisterReceiver(receiver);
            } catch (RuntimeException ignored) {
                // Already gone, or this OEM's UserService Context does not support unregistering.
            }
        }

        callbackHandler.removeCallbacks(volumeRestoreRunnable);
        callbackHandler.removeCallbacks(volumeRestoreFinishRunnable);
        volumeRestoreCycleScheduled = false;
        pendingRestoreVolume = -1;
        audioManager = null;
        stableMediaVolume = -1;
        suppressVolumeUpdatesUntil = 0L;
    }

    private void stopVolumeGuardLocked() {
        volumeGuardActive = false;
        selectedResolvedPath = "";
        cancelVolumeRecoveryLocked();
        volumeRecoveryAttempt = 0;
        clearVolumeMonitorLocked(null);
        stopInputObserverLocked();
    }

    @SuppressLint({"PrivateApi", "DiscouragedPrivateApi"})
    private static Object obtainMediaSessionManager(Context context) throws Exception {
        initializeMediaFrameworkIfNeeded();

        Object manager = null;
        try {
            manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE);
        } catch (RuntimeException ignored) {
            // Some OEM UserService Context implementations cannot create framework wrappers.
        }
        if (manager != null) {
            return manager;
        }

        Constructor<MediaSessionManager> constructor =
                MediaSessionManager.class.getDeclaredConstructor(Context.class);
        constructor.setAccessible(true);
        manager = constructor.newInstance(context);
        if (manager == null) {
            throw new IllegalStateException("MediaSessionManager is unavailable");
        }
        return manager;
    }

    @SuppressLint({"PrivateApi", "DiscouragedPrivateApi"})
    private static void initializeMediaFrameworkIfNeeded() throws Exception {
        final Class<?> initializerClass;
        try {
            initializerClass = Class.forName(
                    "android.media.MediaFrameworkPlatformInitializer"
            );
        } catch (ClassNotFoundException notPresentOnOlderAndroid) {
            return;
        }

        Method getter = initializerClass.getDeclaredMethod("getMediaServiceManager");
        getter.setAccessible(true);
        if (getter.invoke(null) != null) {
            return;
        }

        Class<?> managerClass = Class.forName("android.media.MediaServiceManager");
        Constructor<?> managerConstructor = managerClass.getDeclaredConstructor();
        managerConstructor.setAccessible(true);
        Object serviceManager = managerConstructor.newInstance();

        Method setter = initializerClass.getDeclaredMethod(
                "setMediaServiceManager",
                managerClass
        );
        setter.setAccessible(true);
        try {
            setter.invoke(null, serviceManager);
        } catch (InvocationTargetException exception) {
            if (getter.invoke(null) == null) {
                throw exception;
            }
        }
    }

    private Object handleListenerInvocation(Object proxy, Method method, Object[] args) {
        String methodName = method.getName();
        if ("onMediaKey".equals(methodName)) {
            KeyEvent event = args != null && args.length > 0 && args[0] instanceof KeyEvent
                    ? (KeyEvent) args[0]
                    : null;
            return handleMediaKey(event);
        }
        if ("toString".equals(methodName)) {
            return "ButtonSilencerOnMediaKeyListener";
        }
        if ("hashCode".equals(methodName)) {
            return System.identityHashCode(proxy);
        }
        if ("equals".equals(methodName)) {
            return args != null && args.length == 1 && proxy == args[0];
        }
        return defaultValue(method.getReturnType());
    }

    private boolean handleMediaKey(KeyEvent event) {
        if (event == null) {
            return false;
        }

        boolean mediaKey = PrivilegedKeyPolicy.shouldBlock(event.getKeyCode());
        boolean blocked = enabled && mediaKey;
        if (mediaKey) {
            int count = interceptedCount.incrementAndGet();
            if (diagnosticLoggingEnabled) {
                lastEvent = (blocked ? "BLOCKED" : "PASSED")
                        + "  " + actionName(event.getAction())
                        + "\n" + KeyEvent.keyCodeToString(event.getKeyCode())
                        + " (" + event.getKeyCode() + ")"
                        + "  count=" + count;
            }
        }
        return blocked;
    }

    private void unregisterListenerLocked() {
        if (!registered || mediaSessionManager == null || setOnMediaKeyListenerMethod == null) {
            registered = false;
            return;
        }
        try {
            setOnMediaKeyListenerMethod.invoke(mediaSessionManager, null, callbackHandler);
        } catch (Exception exception) {
            lastError = concise(unwrap(exception));
        } finally {
            registered = false;
            mediaSessionManager = null;
            mediaKeyListenerProxy = null;
            setOnMediaKeyListenerMethod = null;
        }
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

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0F;
        }
        if (type == double.class) {
            return 0D;
        }
        return null;
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof InvocationTargetException
                || current instanceof java.lang.reflect.UndeclaredThrowableException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String concise(Throwable throwable) {
        String message = throwable.getMessage();
        return throwable.getClass().getSimpleName()
                + (message == null || message.trim().isEmpty() ? "" : ": " + message);
    }
}
