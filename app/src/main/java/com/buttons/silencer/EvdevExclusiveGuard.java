package com.buttons.silencer;

/**
 * Tiny JNI bridge for Linux EVIOCGRAB.
 *
 * <p>When a selected evdev node is grabbed, the kernel delivers its events only to Button
 * Silencer's file handle until the grab is released or the handle is closed. That is stronger
 * than observing the event after Android has already received it and is the screen-off safety
 * path for faulty headset remotes.</p>
 */
final class EvdevExclusiveGuard {
    private static final String LIBRARY_NAME = "buttonsilencer_evgrab";
    private static final boolean AVAILABLE;
    private static final String LOAD_ERROR;
    private static volatile String invocationError = "";

    static {
        boolean available = false;
        String error = "";
        try {
            System.loadLibrary(LIBRARY_NAME);
            // Exercise symbol resolution immediately. EBADF is the expected response for fd=-1.
            // This turns a missing/renamed JNI symbol into a recoverable "guard unavailable" state
            // instead of crashing the privileged UserService on the first real headset grab.
            int probe = nativeSetGrab(-1, false);
            if (probe == 9) {
                available = true;
            } else {
                error = "native input guard self-test returned " + probe;
            }
        } catch (UnsatisfiedLinkError | SecurityException exception) {
            error = concise(exception);
        }
        AVAILABLE = available;
        LOAD_ERROR = error;
    }

    private EvdevExclusiveGuard() {
    }

    static boolean isAvailable() {
        return AVAILABLE && invocationError.isEmpty();
    }

    static String loadError() {
        if (!invocationError.isEmpty()) {
            return invocationError;
        }
        return LOAD_ERROR;
    }

    static int setGrab(int fileDescriptor, boolean enabled) {
        if (!AVAILABLE) {
            return -1;
        }
        try {
            return nativeSetGrab(fileDescriptor, enabled);
        } catch (UnsatisfiedLinkError | SecurityException exception) {
            invocationError = concise(exception);
            return -1;
        }
    }

    static String describeError(int errno) {
        switch (errno) {
            case 0:
                return "";
            case -1:
                return loadError().isEmpty() ? "native input guard is unavailable" : loadError();
            case 1:
                return "operation not permitted";
            case 2:
                return "input node is no longer present";
            case 9:
                return "input node file descriptor is invalid";
            case 13:
                return "permission denied opening the input node";
            case 16:
                return "input node is already exclusively grabbed by another process";
            case 19:
                return "input node was disconnected";
            case 22:
                return "input node does not support exclusive grabbing";
            case 25:
                return "input node rejected the exclusive-grab ioctl";
            default:
                return "EVIOCGRAB failed with errno " + errno;
        }
    }

    private static native int nativeSetGrab(int fileDescriptor, boolean enabled);

    private static String concise(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return throwable.getClass().getSimpleName();
        }
        return throwable.getClass().getSimpleName() + ": " + message;
    }
}
