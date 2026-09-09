package com.buttons.silencer;

import android.view.KeyEvent;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ButtonPolicyTest {
    @Test
    public void classifiesCallCapableKeysAsSafetyCritical() {
        assertEquals(
                ButtonPolicy.Category.CALL_SAFETY,
                ButtonPolicy.categoryFor(KeyEvent.KEYCODE_HEADSETHOOK)
        );
        assertEquals(
                ButtonPolicy.Category.CALL_SAFETY,
                ButtonPolicy.categoryFor(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        );
        assertEquals(
                ButtonPolicy.Category.CALL_SAFETY,
                ButtonPolicy.categoryFor(KeyEvent.KEYCODE_CALL)
        );
        assertEquals(
                ButtonPolicy.Category.CALL_SAFETY,
                ButtonPolicy.categoryFor(KeyEvent.KEYCODE_ENDCALL)
        );
        assertEquals(
                ButtonPolicy.Category.VOLUME,
                ButtonPolicy.categoryFor(KeyEvent.KEYCODE_VOLUME_UP)
        );
        assertEquals(
                ButtonPolicy.Category.ASSIST,
                ButtonPolicy.categoryFor(KeyEvent.KEYCODE_VOICE_ASSIST)
        );
    }

    @Test
    public void callSafetyDoesNotDependOnOptionalAdvancedToggles() {
        ButtonPolicy.Config config = new ButtonPolicy.Config(true, false, false, false, false);
        assertTrue(ButtonPolicy.shouldBlock(ButtonPolicy.Category.CALL_SAFETY, true, config));
        assertTrue(ButtonPolicy.shouldBlock(ButtonPolicy.Category.CALL_SAFETY, false, config));
    }

    @Test
    public void masterSwitchAlwaysWins() {
        ButtonPolicy.Config config = new ButtonPolicy.Config(false, true, true, true, true);
        assertFalse(ButtonPolicy.shouldBlock(ButtonPolicy.Category.CALL_SAFETY, true, config));
        assertFalse(ButtonPolicy.shouldBlock(ButtonPolicy.Category.MEDIA, true, config));
        assertFalse(ButtonPolicy.shouldBlock(ButtonPolicy.Category.VOLUME, true, config));
    }

    @Test
    public void externalVolumeDoesNotBlockBuiltInVolume() {
        ButtonPolicy.Config config = new ButtonPolicy.Config(true, true, true, true, false);
        assertTrue(ButtonPolicy.shouldBlock(ButtonPolicy.Category.VOLUME, true, config));
        assertFalse(ButtonPolicy.shouldBlock(ButtonPolicy.Category.VOLUME, false, config));
    }

    @Test
    public void allVolumeModeBlocksBuiltInVolume() {
        ButtonPolicy.Config config = new ButtonPolicy.Config(true, true, false, true, true);
        assertTrue(ButtonPolicy.shouldBlock(ButtonPolicy.Category.VOLUME, false, config));
    }

    @Test
    public void unrelatedKeysAreNeverBlocked() {
        ButtonPolicy.Config config = new ButtonPolicy.Config(true, true, true, true, true);
        assertEquals(ButtonPolicy.Category.OTHER, ButtonPolicy.categoryFor(KeyEvent.KEYCODE_A));
        assertFalse(ButtonPolicy.shouldBlock(ButtonPolicy.Category.OTHER, true, config));
    }
}
