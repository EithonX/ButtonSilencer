package com.buttons.silencer;

import android.view.KeyEvent;

final class ButtonPolicy {
    enum Category {
        MEDIA,
        VOLUME,
        ASSIST_CALL,
        OTHER
    }

    static final class Config {
        final boolean masterEnabled;
        final boolean blockMedia;
        final boolean blockExternalVolume;
        final boolean blockAssistCall;
        final boolean blockAllVolume;

        Config(
                boolean masterEnabled,
                boolean blockMedia,
                boolean blockExternalVolume,
                boolean blockAssistCall,
                boolean blockAllVolume
        ) {
            this.masterEnabled = masterEnabled;
            this.blockMedia = blockMedia;
            this.blockExternalVolume = blockExternalVolume;
            this.blockAssistCall = blockAssistCall;
            this.blockAllVolume = blockAllVolume;
        }
    }

    private ButtonPolicy() {
    }

    static Category categoryFor(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_HEADSETHOOK:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_STOP:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_CLOSE:
            case KeyEvent.KEYCODE_MEDIA_EJECT:
            case KeyEvent.KEYCODE_MEDIA_RECORD:
            case KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK:
            case KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD:
            case KeyEvent.KEYCODE_MEDIA_STEP_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD:
                return Category.MEDIA;

            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
            case KeyEvent.KEYCODE_MUTE:
                return Category.VOLUME;

            case KeyEvent.KEYCODE_ASSIST:
            case KeyEvent.KEYCODE_VOICE_ASSIST:
            case KeyEvent.KEYCODE_CALL:
            case KeyEvent.KEYCODE_ENDCALL:
                return Category.ASSIST_CALL;

            default:
                return Category.OTHER;
        }
    }

    static boolean shouldBlock(Category category, boolean externalDevice, Config config) {
        if (!config.masterEnabled) {
            return false;
        }

        switch (category) {
            case MEDIA:
                return config.blockMedia;
            case VOLUME:
                return config.blockAllVolume || (externalDevice && config.blockExternalVolume);
            case ASSIST_CALL:
                return config.blockAssistCall;
            case OTHER:
            default:
                return false;
        }
    }
}
