package com.buttons.silencer;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public final class VolumeInputDeviceParserTest {
    @Test
    public void parsesAndOrdersExternalBeforePhoneButtons() {
        String sample = "add device 1: /dev/input/event2\n"
                + "  name:     \"gpio-keys\"\n"
                + "  events:\n"
                + "    KEY (0001): KEY_VOLUMEDOWN KEY_VOLUMEUP\n"
                + "add device 2: /dev/input/event7\n"
                + "  name:     \"USB Audio Headset\"\n"
                + "  events:\n"
                + "    KEY (0001): KEY_PLAYPAUSE KEY_VOLUMEUP KEY_VOLUMEDOWN\n";

        List<VolumeInputDeviceParser.Device> devices = VolumeInputDeviceParser.parse(sample);

        assertEquals(2, devices.size());
        assertEquals("USB Audio Headset", devices.get(0).name);
        assertFalse(devices.get(0).likelyInternal);
        assertEquals("gpio-keys", devices.get(1).name);
        assertTrue(devices.get(1).likelyInternal);
    }

    @Test
    public void discoversCompositeCallControlNodeEvenWithoutVolumeKeys() {
        String sample = "add device 1: /dev/input/event11\n"
                + "  name:     \"USB DAC Remote\"\n"
                + "  events:\n"
                + "    KEY (0001): KEY_VOLUMEUP KEY_VOLUMEDOWN\n"
                + "add device 2: /dev/input/event12\n"
                + "  name:     \"USB DAC Remote\"\n"
                + "  events:\n"
                + "    KEY (0001): KEY_PICKUP_PHONE KEY_HANGUP_PHONE KEY_PLAYPAUSE\n";

        List<VolumeInputDeviceParser.Device> devices = VolumeInputDeviceParser.parse(sample);

        assertEquals(2, devices.size());
        assertTrue(devices.get(1).hasCallControl);
        assertTrue(devices.get(1).hasRemoteControl);
        assertFalse(devices.get(1).hasVolumeUp);
        assertFalse(devices.get(1).hasVolumeDown);
    }

    @Test
    public void doesNotOfferOrdinaryKeyboardJustBecauseItHasKeys() {
        String sample = "add device 1: /dev/input/event4\n"
                + "  name:     \"USB Keyboard\"\n"
                + "  events:\n"
                + "    KEY (0001): KEY_A KEY_B KEY_ENTER KEY_SPACE\n";

        assertTrue(VolumeInputDeviceParser.parse(sample).isEmpty());
    }

    @Test
    public void encodeDecodeRoundTrip() {
        VolumeInputDeviceParser.Device original = new VolumeInputDeviceParser.Device(
                "/dev/input/event9",
                "DAC Buttons",
                true,
                true,
                false
        );
        String encoded = VolumeInputDeviceParser.encode(original);
        VolumeInputDeviceParser.Device decoded = VolumeInputDeviceParser.decode(encoded);

        assertNotNull(decoded);
        assertEquals(original.path, decoded.path);
        assertEquals(original.name, decoded.name);
        assertFalse(decoded.likelyInternal);
    }

    @Test
    public void recognizesMoreCommonPhoneKeyDeviceNames() {
        assertTrue(VolumeInputDeviceParser.isLikelyInternal("s2mpu16-keys"));
        assertTrue(VolumeInputDeviceParser.isLikelyInternal("qpnp_pon"));
        assertTrue(VolumeInputDeviceParser.isLikelyInternal("sec_key"));
        assertTrue(VolumeInputDeviceParser.isLikelyInternal("spmi_pmic_arb"));
        assertFalse(VolumeInputDeviceParser.isLikelyInternal("USB Audio Headset"));
    }
    @Test
    public void keyMediaIsRecognizedAsHeadsetRemoteCapability() {
        String sample = "add device 1: /dev/input/event12\n"
                + "  name:     \"USB Headset Remote\"\n"
                + "  events:\n"
                + "    KEY (0001): KEY_MEDIA\n";

        List<VolumeInputDeviceParser.Device> devices = VolumeInputDeviceParser.parse(sample);

        assertEquals(1, devices.size());
        assertTrue(devices.get(0).hasRemoteControl);
        assertTrue(devices.get(0).exclusiveGrabSafe);
    }

    @Test
    public void mixedKeyboardRemoteIsNeverSafeForWholeNodeGrab() {
        String sample = "add device 1: /dev/input/event6\n"
                + "  name:     \"USB Keyboard\"\n"
                + "  events:\n"
                + "    KEY (0001): KEY_A KEY_B KEY_ENTER KEY_PLAYPAUSE\n";

        List<VolumeInputDeviceParser.Device> devices = VolumeInputDeviceParser.parse(sample);

        assertEquals(1, devices.size());
        assertFalse(devices.get(0).exclusiveGrabSafe);
        assertTrue(devices.get(0).unsafeGrabReason.contains("typing"));
    }

    @Test
    public void jackRoutingSwitchMakesWholeNodeGrabUnsafe() {
        String sample = "add device 1: /dev/input/event9\n"
                + "  name:     \"Analog Headset Controls\"\n"
                + "  events:\n"
                + "    KEY (0001): KEY_MEDIA KEY_VOLUMEUP KEY_VOLUMEDOWN\n"
                + "    SW  (0005): SW_HEADPHONE_INSERT SW_MICROPHONE_INSERT\n";

        List<VolumeInputDeviceParser.Device> devices = VolumeInputDeviceParser.parse(sample);

        assertEquals(1, devices.size());
        assertFalse(devices.get(0).exclusiveGrabSafe);
        assertTrue(devices.get(0).unsafeGrabReason.contains("audio-route"));
    }

    @Test
    public void discoversLinuxKeysThatAndroidGenericLayoutMapsToCallControls() {
        String sample = "add device 1: /dev/input/event14\n"
                + "  name:     \"USB Inline Remote\"\n"
                + "  events:\n"
                + "    KEY (0001): KEY_SEND KEY_FORWARDMAIL\n";

        List<VolumeInputDeviceParser.Device> devices = VolumeInputDeviceParser.parse(sample);

        assertEquals(1, devices.size());
        assertTrue(devices.get(0).hasCallControl);
        assertTrue(devices.get(0).hasRemoteControl);
        assertTrue(devices.get(0).exclusiveGrabSafe);
    }

    @Test
    public void discoversTransportOnlyRemoteNodeForCompositeHeadsets() {
        String sample = "add device 1: /dev/input/event15\n"
                + "  name:     \"USB Inline Remote\"\n"
                + "  events:\n"
                + "    KEY (0001): KEY_PLAY KEY_FASTFORWARD KEY_REWIND KEY_RECORD\n";

        List<VolumeInputDeviceParser.Device> devices = VolumeInputDeviceParser.parse(sample);

        assertEquals(1, devices.size());
        assertTrue(devices.get(0).hasRemoteControl);
        assertTrue(devices.get(0).exclusiveGrabSafe);
    }

}
