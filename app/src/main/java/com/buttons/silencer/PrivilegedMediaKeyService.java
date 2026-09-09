package com.buttons.silencer;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.session.MediaSessionManager;
import android.os.Build;
import android.os.FileObserver;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.File;
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
 * <p>The media-key listener is a broad screen-off fallback for ordinary media routing. For the
 * explicitly selected external headset, the raw-input guard uses Linux EVIOCGRAB on every matching
 * remote-capable evdev node. That exclusive kernel grab keeps media, volume, and call-control
 * button events from reaching Android at all. The phone's own side-button input nodes are never
 * selected or grabbed.</p>
 */
public final class PrivilegedMediaKeyService extends IPrivilegedBlocker.Stub {
    private static final String LISTENER_CLASS_NAME =
            "android.media.session.MediaSessionManager$OnMediaKeyListener";
    private static final String GETEVENT = "/system/bin/getevent";
    private static final String INPUT_DIR = "/dev/input";
    private static final long[] MEDIA_RECOVERY_DELAYS_MS = {750L, 2_000L, 5_000L};
    private static final long[] VOLUME_RECOVERY_DELAYS_MS = {250L, 1_000L, 3_000L, 10_000L, 30_000L};
    private static final long INPUT_TOPOLOGY_RECONCILE_DELAY_MS = 25L;

    private final Object lock = new Object();
    private final HandlerThread callbackThread;
    private final Handler callbackHandler;
    private final Runnable mediaRecoveryRunnable;
    private final Runnable volumeRecoveryRunnable;
    private final Runnable inputTopologyReconcileRunnable;
    private final AtomicInteger interceptedCount = new AtomicInteger();
    private final AtomicInteger suppressedRawReadCount = new AtomicInteger();
    private final List<GrabbedInput> grabbedInputs = new ArrayList<>();

    private final Context context;
    private Object mediaSessionManager;
    private Object mediaKeyListenerProxy;
    private Method setOnMediaKeyListenerMethod;

    private FileObserver inputObserver;
    private int mediaRecoveryAttempt;
    private int volumeRecoveryAttempt;
    private boolean mediaRecoveryScheduled;
    private boolean volumeRecoveryScheduled;
    private boolean inputTopologyReconcileScheduled;

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
    private volatile String lastVolumeEvent = "No selected-headset raw input received yet";

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
        inputTopologyReconcileRunnable = this::reconcileInputTopology;
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
            lastVolumeEvent = "Detailed raw-input logging is off";
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

        builder.append("\n\nSelected-headset raw guard: ")
                .append(volumeGuardActive ? "ACTIVE / EXCLUSIVE"
                        : (volumeGuardEnabled ? "NOT ACTIVE" : "OFF"))
                .append('\n');
        builder.append("Selected device: ")
                .append(selectedVolumeDevice.isEmpty()
                        ? "none"
                        : VolumeInputDeviceParser.displayLabel(selectedVolumeDevice))
                .append('\n');
        if (!selectedResolvedPath.isEmpty()) {
            builder.append("Exclusive input nodes: ").append(selectedResolvedPath).append('\n');
        }
        builder.append("Suppressed raw input reads: ")
                .append(suppressedRawReadCount.get()).append('\n');
        builder.append(diagnosticLoggingEnabled
                ? lastVolumeEvent
                : "Detailed raw-input logging is off");
        if (volumeGuardEnabled && !volumeGuardError.isEmpty()) {
            builder.append("\nRaw-guard error: ").append(volumeGuardError);
        }
        if (volumeGuardEnabled && !volumeGuardWarning.isEmpty()) {
            builder.append("\nRaw-guard note: ").append(volumeGuardWarning);
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
                    ? "No external-control input node was found"
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
        if (!EvdevExclusiveGuard.isAvailable()) {
            volumeGuardError = "Exclusive input guard unavailable: " + EvdevExclusiveGuard.loadError();
            return;
        }

        VolumeInputDeviceParser.Device requested =
                VolumeInputDeviceParser.decode(selectedVolumeDevice);
        if (requested == null) {
            volumeGuardError = "Scan and select the headset input device first";
            return;
        }
        if (requested.likelyInternal
                || VolumeInputDeviceParser.isLikelyInternal(requested.name)) {
            volumeGuardError = "Refusing a device that looks like the phone's own buttons";
            return;
        }

        reconcileVolumeGuardLocked(requested);
    }

    /**
     * Makes the held EVIOCGRAB set match every currently visible remote-capable node for the
     * selected headset name. Existing healthy grabs stay in place while newly appeared composite
     * nodes are acquired, so a USB topology update does not create an avoidable call-safety gap.
     */
    private void reconcileVolumeGuardLocked(VolumeInputDeviceParser.Device requested) {
        if (!volumeGuardEnabled) {
            return;
        }

        try {
            List<VolumeInputDeviceParser.Device> resolved = resolveSelectedDevices(requested);
            if (resolved.isEmpty()) {
                volumeGuardActive = false;
                volumeGuardError = "Selected headset input device is not currently connected";
                clearExclusiveGuardLocked(null);
                scheduleVolumeRecoveryLocked();
                return;
            }

            for (VolumeInputDeviceParser.Device device : resolved) {
                if (!device.exclusiveGrabSafe) {
                    // EVIOCGRAB owns the whole event node. Never take exclusive ownership of a
                    // mixed keyboard or a node that also carries jack/audio-route switch state.
                    // That could disable unrelated input or hide an unplug/routing transition.
                    volumeGuardActive = false;
                    volumeGuardError = "Cannot safely exclusively guard " + device.path + ": "
                            + device.unsafeGrabReason;
                    clearExclusiveGuardLocked(null);
                    cancelVolumeRecoveryLocked();
                    return;
                }
            }

            List<GrabbedInput> stale = new ArrayList<>();
            for (GrabbedInput input : grabbedInputs) {
                if (!containsPath(resolved, input.device.path)) {
                    stale.add(input);
                }
            }
            for (GrabbedInput input : stale) {
                removeGrabbedInputLocked(input);
            }

            String acquisitionError = "";
            for (VolumeInputDeviceParser.Device device : resolved) {
                if (findGrabbedInputByPath(device.path) != null) {
                    continue;
                }
                try {
                    GrabbedInput input = acquireInput(device);
                    grabbedInputs.add(input);
                    startInputReader(input);
                } catch (Exception exception) {
                    if (acquisitionError.isEmpty()) {
                        acquisitionError = concise(exception);
                    }
                }
            }

            selectedResolvedPath = joinPaths(grabbedInputs);
            boolean complete = !grabbedInputs.isEmpty()
                    && grabbedInputs.size() == resolved.size()
                    && allResolvedPathsGrabbed(resolved);
            volumeGuardActive = complete;

            if (complete) {
                volumeGuardError = "";
                volumeRecoveryAttempt = 0;
                cancelVolumeRecoveryLocked();
                return;
            }

            volumeGuardError = acquisitionError.isEmpty()
                    ? "Selected headset has an unguarded control node; retrying"
                    : acquisitionError;
            scheduleVolumeRecoveryLocked();
        } catch (Exception exception) {
            volumeGuardActive = false;
            volumeGuardError = concise(exception);
            if (grabbedInputs.isEmpty()) {
                selectedResolvedPath = "";
            }
            if (volumeGuardEnabled) {
                scheduleVolumeRecoveryLocked();
            }
        }
    }

    private GrabbedInput acquireInput(VolumeInputDeviceParser.Device device) throws Exception {
        ParcelFileDescriptor descriptor = null;
        ParcelFileDescriptor cancelRead = null;
        ParcelFileDescriptor cancelWrite = null;
        try {
            descriptor = ParcelFileDescriptor.open(
                    new File(device.path),
                    ParcelFileDescriptor.MODE_READ_ONLY
            );
            ParcelFileDescriptor[] cancelPipe = ParcelFileDescriptor.createPipe();
            cancelRead = cancelPipe[0];
            cancelWrite = cancelPipe[1];

            GrabbedInput input = new GrabbedInput(device, descriptor, cancelRead, cancelWrite);
            int errno = EvdevExclusiveGuard.setGrab(descriptor.getFd(), true);
            if (errno != 0) {
                throw new IOException(
                        "Could not exclusively guard " + device.path + ": "
                                + EvdevExclusiveGuard.describeError(errno)
                );
            }
            input.grabbed = true;
            return input;
        } catch (Exception exception) {
            closeQuietly(descriptor);
            closeQuietly(cancelRead);
            closeQuietly(cancelWrite);
            throw exception;
        }
    }

    private void startInputReader(GrabbedInput input) {
        Thread reader = new Thread(
                () -> drainGrabbedInput(input),
                "ButtonSilencerEvdev-" + input.device.path.substring(
                        input.device.path.lastIndexOf('/') + 1
                )
        );
        reader.setDaemon(true);
        input.readerThread = reader;
        reader.start();
    }

    private GrabbedInput findGrabbedInputByPath(String path) {
        for (GrabbedInput input : grabbedInputs) {
            if (input.device.path.equals(path)) {
                return input;
            }
        }
        return null;
    }

    private static boolean containsPath(
            List<VolumeInputDeviceParser.Device> devices,
            String path
    ) {
        for (VolumeInputDeviceParser.Device device : devices) {
            if (device.path.equals(path)) {
                return true;
            }
        }
        return false;
    }

    private boolean allResolvedPathsGrabbed(List<VolumeInputDeviceParser.Device> devices) {
        for (VolumeInputDeviceParser.Device device : devices) {
            if (findGrabbedInputByPath(device.path) == null) {
                return false;
            }
        }
        return true;
    }

    /**
     * Re-resolves the selected external device by name and returns every remote-capable event node
     * with that name. Event numbers can change after USB reconnects, and composite USB audio devices
     * can expose call/media and volume controls as separate nodes.
     */
    private List<VolumeInputDeviceParser.Device> resolveSelectedDevices(
            VolumeInputDeviceParser.Device requested
    ) throws Exception {
        List<VolumeInputDeviceParser.Device> devices = scanVolumeDevices();
        List<VolumeInputDeviceParser.Device> matches = new ArrayList<>();

        for (VolumeInputDeviceParser.Device candidate : devices) {
            if (!candidate.likelyInternal && candidate.name.equals(requested.name)) {
                matches.add(candidate);
            }
        }
        return matches;
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

    private void drainGrabbedInput(GrabbedInput input) {
        String failure = "Input node closed; waiting for the headset to reconnect";
        byte[] buffer = new byte[384];
        StructPollfd inputPoll = new StructPollfd();
        inputPoll.fd = input.descriptor.getFileDescriptor();
        inputPoll.events = (short) (OsConstants.POLLIN | OsConstants.POLLERR | OsConstants.POLLHUP);
        StructPollfd cancelPoll = new StructPollfd();
        cancelPoll.fd = input.cancelRead.getFileDescriptor();
        cancelPoll.events = (short) (OsConstants.POLLIN | OsConstants.POLLERR | OsConstants.POLLHUP);
        StructPollfd[] pollSet = {inputPoll, cancelPoll};

        try {
            while (true) {
                // Fully event-driven: the thread sleeps in poll() until the headset produces input,
                // the node disconnects, or shutdown writes to the private cancellation pipe.
                Os.poll(pollSet, -1);

                synchronized (lock) {
                    if (!volumeGuardEnabled || !grabbedInputs.contains(input)) {
                        return;
                    }
                }

                int cancelEvents = cancelPoll.revents & 0xffff;
                if ((cancelEvents & (OsConstants.POLLIN
                        | OsConstants.POLLERR
                        | OsConstants.POLLHUP
                        | OsConstants.POLLNVAL)) != 0) {
                    return;
                }

                int inputEvents = inputPoll.revents & 0xffff;
                if ((inputEvents & OsConstants.POLLIN) != 0) {
                    int read = Os.read(input.descriptor.getFileDescriptor(), buffer, 0, buffer.length);
                    if (read > 0) {
                        int count = suppressedRawReadCount.incrementAndGet();
                        if (diagnosticLoggingEnabled) {
                            lastVolumeEvent = "SUPPRESSED raw headset input\n"
                                    + input.device.name + "  " + input.device.path
                                    + "  read=" + read + "B  count=" + count;
                        }
                    }
                }
                if ((inputEvents & (OsConstants.POLLERR
                        | OsConstants.POLLHUP
                        | OsConstants.POLLNVAL)) != 0) {
                    break;
                }
            }
        } catch (ErrnoException exception) {
            failure = concise(exception);
        }

        synchronized (lock) {
            if (volumeGuardEnabled && grabbedInputs.contains(input)) {
                volumeGuardActive = false;
                volumeGuardError = failure;
                removeGrabbedInputLocked(input);
                volumeRecoveryAttempt = 0;
                scheduleVolumeRecoveryLocked();
            }
        }
    }

    private static String joinPaths(List<GrabbedInput> inputs) {
        StringBuilder builder = new StringBuilder();
        for (GrabbedInput input : inputs) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(input.device.path);
        }
        return builder.toString();
    }

    private void removeGrabbedInputLocked(GrabbedInput input) {
        if (input == null || !grabbedInputs.remove(input)) {
            return;
        }
        List<GrabbedInput> single = new ArrayList<>(1);
        single.add(input);
        releaseInputs(single);
        Thread thread = input.readerThread;
        input.readerThread = null;
        if (thread != null && thread != Thread.currentThread()) {
            thread.interrupt();
        }
        selectedResolvedPath = joinPaths(grabbedInputs);
    }

    private static void releaseInputs(List<GrabbedInput> inputs) {
        byte[] wake = {1};
        for (int index = inputs.size() - 1; index >= 0; index--) {
            GrabbedInput input = inputs.get(index);

            // Wake poll() without periodic timers so teardown never leaves a reader thread parked on
            // a dead/old fd. This is also why the guard can stay battery-idle when no buttons fire.
            try {
                Os.write(input.cancelWrite.getFileDescriptor(), wake, 0, wake.length);
            } catch (ErrnoException ignored) {
                // Closing the pipe below also makes a waiting poll return.
            }

            if (input.grabbed) {
                try {
                    EvdevExclusiveGuard.setGrab(input.descriptor.getFd(), false);
                } catch (RuntimeException | LinkageError ignored) {
                    // Closing the fd below also releases EVIOCGRAB in the kernel.
                }
                input.grabbed = false;
            }
            closeQuietly(input.descriptor);
            closeQuietly(input.cancelRead);
            closeQuietly(input.cancelWrite);
        }
    }

    private static void closeQuietly(ParcelFileDescriptor descriptor) {
        if (descriptor == null) {
            return;
        }
        try {
            descriptor.close();
        } catch (IOException ignored) {
            // Device or cancellation pipe may already be gone.
        }
    }

    private void ensureInputObserverLocked() {
        if (inputObserver != null) {
            return;
        }
        try {
            final int mask = FileObserver.CREATE
                    | FileObserver.DELETE
                    | FileObserver.MOVED_FROM
                    | FileObserver.MOVED_TO
                    | FileObserver.DELETE_SELF
                    | FileObserver.MOVE_SELF;
            inputObserver = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? createModernInputObserver(mask)
                    : createLegacyInputObserver(mask);
            inputObserver.startWatching();
        } catch (RuntimeException exception) {
            inputObserver = null;
            volumeGuardWarning = "Input-node observer unavailable: " + concise(exception);
        }
    }

    @SuppressLint("NewApi")
    private FileObserver createModernInputObserver(int mask) {
        return new FileObserver(new File(INPUT_DIR), mask) {
            @Override
            public void onEvent(int event, String path) {
                onInputDirectoryChanged(path);
            }
        };
    }

    @SuppressWarnings("deprecation")
    private FileObserver createLegacyInputObserver(int mask) {
        return new FileObserver(INPUT_DIR, mask) {
            @Override
            public void onEvent(int event, String path) {
                onInputDirectoryChanged(path);
            }
        };
    }

    private void onInputDirectoryChanged(String path) {
        if (path != null && !path.startsWith("event")) {
            return;
        }
        callbackHandler.post(() -> {
            synchronized (lock) {
                if (!volumeGuardEnabled) {
                    return;
                }

                if (!grabbedInputs.isEmpty()) {
                    // A composite USB remote may expose multiple event nodes. Re-scan after a short
                    // debounce even while protection is active so a newly-created call-control node
                    // is acquired without waiting for a failure on an already-grabbed node.
                    scheduleInputTopologyReconcileLocked();
                    return;
                }

                // No node is held right now. A USB/input-node change is the strongest signal that a
                // reconnect can succeed, so try immediately rather than leaving a deliberate
                // call-safety window. Any race with a half-created node falls back to the normal
                // bounded recovery delays; there is still no permanent polling loop.
                cancelVolumeRecoveryLocked();
                volumeRecoveryAttempt = 0;
                startVolumeGuardLocked();
            }
        });
    }

    private void reconcileInputTopology() {
        synchronized (lock) {
            inputTopologyReconcileScheduled = false;
            if (!volumeGuardEnabled) {
                return;
            }
            VolumeInputDeviceParser.Device requested =
                    VolumeInputDeviceParser.decode(selectedVolumeDevice);
            if (requested == null) {
                return;
            }
            reconcileVolumeGuardLocked(requested);
        }
    }

    private void scheduleInputTopologyReconcileLocked() {
        if (!volumeGuardEnabled || inputTopologyReconcileScheduled) {
            return;
        }
        inputTopologyReconcileScheduled = true;
        callbackHandler.postDelayed(
                inputTopologyReconcileRunnable,
                INPUT_TOPOLOGY_RECONCILE_DELAY_MS
        );
    }

    private void cancelInputTopologyReconcileLocked() {
        inputTopologyReconcileScheduled = false;
        callbackHandler.removeCallbacks(inputTopologyReconcileRunnable);
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
            VolumeInputDeviceParser.Device requested =
                    VolumeInputDeviceParser.decode(selectedVolumeDevice);
            if (requested == null || grabbedInputs.isEmpty()) {
                clearExclusiveGuardLocked(null);
                startVolumeGuardLocked();
            } else {
                reconcileVolumeGuardLocked(requested);
            }
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

    private void clearExclusiveGuardLocked(GrabbedInput expectedInput) {
        if (expectedInput != null && !grabbedInputs.contains(expectedInput)) {
            return;
        }

        List<GrabbedInput> active = new ArrayList<>(grabbedInputs);
        grabbedInputs.clear();
        selectedResolvedPath = "";

        // Drop kernel ownership before closing the descriptors. Closing is itself sufficient to
        // release EVIOCGRAB, but the explicit release makes the normal shutdown path immediate.
        releaseInputs(active);
        for (GrabbedInput input : active) {
            Thread thread = input.readerThread;
            input.readerThread = null;
            if (thread != null && thread != Thread.currentThread()) {
                thread.interrupt();
            }
        }
    }

    private void stopVolumeGuardLocked() {
        volumeGuardActive = false;
        selectedResolvedPath = "";
        volumeGuardWarning = "";
        cancelVolumeRecoveryLocked();
        cancelInputTopologyReconcileLocked();
        volumeRecoveryAttempt = 0;
        clearExclusiveGuardLocked(null);
        stopInputObserverLocked();
    }

    private static final class GrabbedInput {
        final VolumeInputDeviceParser.Device device;
        final ParcelFileDescriptor descriptor;
        final ParcelFileDescriptor cancelRead;
        final ParcelFileDescriptor cancelWrite;
        volatile Thread readerThread;
        boolean grabbed;

        GrabbedInput(
                VolumeInputDeviceParser.Device device,
                ParcelFileDescriptor descriptor,
                ParcelFileDescriptor cancelRead,
                ParcelFileDescriptor cancelWrite
        ) {
            this.device = device;
            this.descriptor = descriptor;
            this.cancelRead = cancelRead;
            this.cancelWrite = cancelWrite;
        }
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
