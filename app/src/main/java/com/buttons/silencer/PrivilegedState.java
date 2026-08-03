package com.buttons.silencer;

final class PrivilegedState {
    static final int MEDIA_REGISTERED = 1;
    static final int MEDIA_ENABLED = 1 << 1;
    static final int VOLUME_GUARD_ENABLED = 1 << 2;
    static final int VOLUME_GUARD_ACTIVE = 1 << 3;
    static final int HAS_ERROR = 1 << 4;

    private PrivilegedState() {
    }
}
