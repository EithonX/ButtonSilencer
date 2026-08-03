package com.buttons.silencer;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;

import org.junit.Test;

public final class PrivilegedKeyPolicyTest {
    @Test
    public void blocksHeadsetAndMediaKeys() {
        assertTrue(PrivilegedKeyPolicy.shouldBlock(KeyEvent.KEYCODE_HEADSETHOOK));
        assertTrue(PrivilegedKeyPolicy.shouldBlock(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
        assertTrue(PrivilegedKeyPolicy.shouldBlock(KeyEvent.KEYCODE_MEDIA_NEXT));
        assertTrue(PrivilegedKeyPolicy.shouldBlock(KeyEvent.KEYCODE_MEDIA_PREVIOUS));
    }

    @Test
    public void doesNotBlockVolumeOrUnrelatedKeys() {
        assertFalse(PrivilegedKeyPolicy.shouldBlock(KeyEvent.KEYCODE_VOLUME_UP));
        assertFalse(PrivilegedKeyPolicy.shouldBlock(KeyEvent.KEYCODE_VOLUME_DOWN));
        assertFalse(PrivilegedKeyPolicy.shouldBlock(KeyEvent.KEYCODE_POWER));
        assertFalse(PrivilegedKeyPolicy.shouldBlock(KeyEvent.KEYCODE_A));
    }
}
