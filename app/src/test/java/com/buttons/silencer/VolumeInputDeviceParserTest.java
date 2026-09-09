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

}
