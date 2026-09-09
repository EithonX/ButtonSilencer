# Changelog

## [3.2.0]

### Changed

- Simplified the main screen around protection state, screen-on filtering, screen-off status, and headset selection.
- Moved secondary filtering controls out of the setup path and into Advanced settings.
- Reworked user-facing status text to describe coverage without exposing implementation details.
- Refined project documentation and release notes for the public repository.

### Safety

- Full protection still requires the selected-headset exclusive input guard for screen-off call safety.
- Call, hang-up, and headset-hook keys remain safety-critical and do not depend on optional media filters.

## [3.1.6] - 2026-09-09

### Added

- Screen-on headset filtering through Android Accessibility.
- Screen-off protection through a Shizuku UserService.
- Exclusive selected-headset input guarding for media, volume, and call controls.
- Automatic Shizuku reconnect and input-node reacquisition after device changes.

### Safety

- Phone side-button devices are excluded from headset selection.
- Call-capable headset controls are blocked independently of optional media settings.
- Full protection is reported only when the selected-headset screen-off guard is active.
