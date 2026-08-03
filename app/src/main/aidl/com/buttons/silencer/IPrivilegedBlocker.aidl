package com.buttons.silencer;

interface IPrivilegedBlocker {
    boolean setEnabled(boolean enabled) = 1;
    boolean isEnabled() = 2;
    String getStatus() = 3;
    String[] listVolumeInputDevices() = 4;
    boolean setHeadsetVolumeGuard(String encodedDevice, boolean enabled) = 5;
    void destroy() = 16777114; // Destroy transaction reserved by Shizuku UserService.
}
