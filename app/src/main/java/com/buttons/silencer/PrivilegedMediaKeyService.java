package com.buttons.silencer;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.session.MediaSessionManager;
import android.os.Build;
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
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
    private static final String VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION";
    private static final String EXTRA_VOLUME_STREAM_TYPE =
            "android.media.EXTRA_VOLUME_STREAM_TYPE";
    private static final String EXTRA_VOLUME_STREAM_VALUE =
            "android.media.EXTRA_VOLUME_STREAM_VALUE";

    private final Object lock = new Object();
    private final HandlerThread callbackThread;
    private final Handler callbackHandler;
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
    private int stableMediaVolume = -1;
    private long suppressVolumeUpdatesUntil;

    private volatile boolean enabled;
    private volatile boolean registered;
    private volatile boolean volumeGuardEnabled;
    private volatile boolean diagnosticLoggingEnabled;
    private volatile boolean volumeGuardActive;
    private volatile String selectedVolumeDevice = "";
    private volatile String selectedResolvedPath = "";
    private volatile String lastError = "";
    private volatile String volumeGuardError = "";
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
    }

    @Override
    public boolean setEnabled(boolean requestedEnabled) {
        synchronized (lock) {
            if (requestedEnabled) {
                enabled = true;
                if (!registered) {
                    registerListenerLocked();
                }
            } else {
                enabled = false;
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
            if (requestedEnabled) {
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
                return;
            }

            audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (audioManager == null) {
                volumeGuardError = "AudioManager is unavailable in the Shizuku process";
                return;
            }

            stableMediaVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            registerVolumeReceiverLocked();

            Process process = new ProcessBuilder(GETEVENT, "-lt", resolved.path)
                    .redirectErrorStream(true)
                    .start();
            volumeMonitorProcess = process;
            selectedResolvedPath = resolved.path;
            volumeGuardActive = true;

            Thread readerThread = new Thread(
                    () -> readVolumeEvents(process, resolved),
                    "ButtonSilencerGetEvent"
            );
            readerThread.setDaemon(true);
            volumeMonitorThread = readerThread;
            readerThread.start();
        } catch (Exception exception) {
            volumeGuardError = concise(exception);
            stopVolumeGuardLocked();
            volumeGuardEnabled = true;
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
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8
        ))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("getevent device scan timed out");
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
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8
        ))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!volumeGuardEnabled || process != volumeMonitorProcess) {
                    break;
                }
                String upper = line.toUpperCase(Locale.ROOT);
                boolean volumeKey = upper.contains("KEY_VOLUMEUP")
                        || upper.contains("KEY_VOLUMEDOWN");
                boolean keyDown = upper.contains(" DOWN") || upper.endsWith(" 00000001");
                if (volumeKey && keyDown) {
                    handleSelectedHeadsetVolumePress(selectedDevice, upper);
                }
            }

            synchronized (lock) {
                if (process == volumeMonitorProcess && volumeGuardEnabled) {
                    volumeGuardActive = false;
                    volumeGuardError = "Input monitor stopped; reconnect or rescan the headset";
                }
            }
        } catch (IOException exception) {
            synchronized (lock) {
                if (process == volumeMonitorProcess && volumeGuardEnabled) {
                    volumeGuardActive = false;
                    volumeGuardError = concise(exception);
                }
            }
        }
    }

    private void handleSelectedHeadsetVolumePress(
            VolumeInputDeviceParser.Device device,
            String eventLine
    ) {
        final AudioManager manager;
        final int targetVolume;
        synchronized (lock) {
            if (!volumeGuardEnabled || !volumeGuardActive || audioManager == null) {
                return;
            }
            manager = audioManager;
            int observed = manager.getStreamVolume(AudioManager.STREAM_MUSIC);
            long now = SystemClock.uptimeMillis();
            if (now >= suppressVolumeUpdatesUntil && observed != stableMediaVolume) {
                stableMediaVolume = observed;
            }
            targetVolume = stableMediaVolume >= 0 ? stableMediaVolume : observed;
            suppressVolumeUpdatesUntil = now + 700L;
        }

        int count = neutralizedVolumeCount.incrementAndGet();
        if (diagnosticLoggingEnabled) {
            String direction = eventLine.contains("KEY_VOLUMEUP")
                    ? "VOLUME_UP"
                    : "VOLUME_DOWN";
            lastVolumeEvent = "NEUTRALIZING " + direction
                    + "\n" + device.name + "  count=" + count;
        }

        restoreVolumeLater(manager, targetVolume, 25L);
        restoreVolumeLater(manager, targetVolume, 90L);
        restoreVolumeLater(manager, targetVolume, 220L);
    }

    private void restoreVolumeLater(AudioManager manager, int targetVolume, long delayMillis) {
        callbackHandler.postDelayed(() -> {
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
                    stableMediaVolume = targetVolume;
                }
            } catch (RuntimeException exception) {
                volumeGuardError = concise(exception);
            }
        }, delayMillis);
    }

    private void registerVolumeReceiverLocked() {
        if (volumeReceiver != null || context == null) {
            return;
        }

        volumeReceiver = new BroadcastReceiver() {
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(volumeReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(volumeReceiver, filter);
        }
    }

    private void stopVolumeGuardLocked() {
        volumeGuardActive = false;
        selectedResolvedPath = "";

        Process process = volumeMonitorProcess;
        volumeMonitorProcess = null;
        if (process != null) {
            process.destroy();
        }

        Thread thread = volumeMonitorThread;
        volumeMonitorThread = null;
        if (thread != null) {
            thread.interrupt();
        }

        if (volumeReceiver != null && context != null) {
            try {
                context.unregisterReceiver(volumeReceiver);
            } catch (IllegalArgumentException ignored) {
                // Already unregistered by the framework.
            }
            volumeReceiver = null;
        }

        audioManager = null;
        stableMediaVolume = -1;
        suppressVolumeUpdatesUntil = 0L;
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
