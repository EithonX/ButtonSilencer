package com.buttons.silencer;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.session.MediaSessionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.KeyEvent;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs in a Shizuku UserService process under shell/root identity. It installs Android's
 * privileged media-key listener and consumes only headset/media key events. It never touches
 * USB, audio routing, input drivers, wake locks, or media playback.
 */
public final class PrivilegedMediaKeyService extends IPrivilegedBlocker.Stub {
    private static final String LISTENER_CLASS_NAME =
            "android.media.session.MediaSessionManager$OnMediaKeyListener";

    private final Object lock = new Object();
    private final HandlerThread callbackThread;
    private final Handler callbackHandler;
    private final AtomicInteger interceptedCount = new AtomicInteger();

    private final Context context;
    private Object mediaSessionManager;
    private Object mediaKeyListenerProxy;
    private Method setOnMediaKeyListenerMethod;

    private volatile boolean enabled;
    private volatile boolean registered;
    private volatile String lastError = "";
    private volatile String lastEvent = "No privileged media-key event received yet";

    /** Used by Shizuku versions older than v13. Screen-off mode will report a clear error. */
    public PrivilegedMediaKeyService() {
        this(null);
    }

    /** Preferred constructor used by Shizuku v13+. */
    public PrivilegedMediaKeyService(Context context) {
        this.context = context;
        callbackThread = new HandlerThread("ButtonSilencerMediaKey");
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
    public String getStatus() {
        StringBuilder builder = new StringBuilder();
        builder.append("Shizuku privileged listener: ")
                .append(registered ? (enabled ? "ACTIVE" : "CONNECTED / PASSING")
                        : "NOT REGISTERED")
                .append('\n');
        builder.append("Process UID: ").append(android.os.Process.myUid()).append('\n');
        builder.append("Intercepted events: ").append(interceptedCount.get()).append('\n');
        builder.append(lastEvent);
        if (!lastError.isEmpty()) {
            builder.append("\nLast error: ").append(lastError);
        }
        return builder.toString();
    }

    /** Shizuku reserves this AIDL transaction for stopping a daemon UserService. */
    @Override
    public void destroy() {
        synchronized (lock) {
            enabled = false;
            unregisterListenerLocked();
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

    @SuppressLint({"PrivateApi", "DiscouragedPrivateApi"})
    private static Object obtainMediaSessionManager(Context context) throws Exception {
        Object manager = null;
        try {
            manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE);
        } catch (RuntimeException ignored) {
            // Shizuku's UserService Context is not a normal app Context on every Android build.
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
            String time = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
                    .format(new Date());
            lastEvent = time
                    + "  " + (blocked ? "BLOCKED" : "PASSED")
                    + "  " + actionName(event.getAction())
                    + "\n" + KeyEvent.keyCodeToString(event.getKeyCode())
                    + " (" + event.getKeyCode() + ")"
                    + "  count=" + count;
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
