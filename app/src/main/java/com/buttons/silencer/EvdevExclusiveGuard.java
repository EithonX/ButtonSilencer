package com.buttons.silencer;

/** JNI bridge for Linux EVIOCGRAB on the selected headset input node. */
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
