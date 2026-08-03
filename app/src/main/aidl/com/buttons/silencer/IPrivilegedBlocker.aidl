package com.buttons.silencer;

interface IPrivilegedBlocker {
    boolean setEnabled(boolean enabled) = 1;
    boolean isEnabled() = 2;
    String getStatus() = 3;
    void destroy() = 16777114; // Destroy transaction reserved by Shizuku UserService.
}
