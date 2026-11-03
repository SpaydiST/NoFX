package dev.nofx.scan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScanLogicTest {

    @Test
    void emptyResponseIsNotDetected() {
        assertEquals(ScanResult.NOT_DETECTED, ScanLogic.evaluate(keybind("key.freecam.toggle"), ""));
        assertEquals(ScanResult.NOT_DETECTED, ScanLogic.evaluate(translate("msg.freecam.enable", "<NOFX>"), ""));
    }

    @Test
    void keybindRawKeyEchoIsClean() {
        Probe probe = keybind("key.freecam.toggle");
        assertEquals(ScanResult.NOT_DETECTED, ScanLogic.evaluate(probe, "key.freecam.toggle"));
        assertEquals(ScanResult.NOT_DETECTED, ScanLogic.evaluate(probe, "Key.FREECAM.TOGGLE"));
    }

    @Test
    void keybindResolvedDescriptionIsDetected() {
        Probe probe = keybind("key.freecam.toggle");
        assertEquals(ScanResult.DETECTED, ScanLogic.evaluate(probe, "key.w.keyboard"));
        assertEquals(ScanResult.DETECTED, ScanLogic.evaluate(probe, "F4"));
    }

    @Test
    void translateFallbackHitsAreNotDetected() {
        Probe probe = translate("msg.freecam.enable", "<NOFX_ENABLE>");
        assertEquals(ScanResult.NOT_DETECTED, ScanLogic.evaluate(probe, "<NOFX_ENABLE>"));
        assertEquals(ScanResult.NOT_DETECTED, ScanLogic.evaluate(probe, "<nofx_enable> префикс совпал"));
        assertEquals(ScanResult.NOT_DETECTED, ScanLogic.evaluate(probe, "<NOFX_ENABLE> описание клавиши"));
    }

    @Test
    void translateRawKeyMeansModEmptyMeansProtected() {
        Probe probe = translate("text.autoconfig.freecam.option.collision.ignoreAll", "<NOFX>");
        assertEquals(ScanResult.PROTECTED, ScanLogic.evaluate(probe, "text.autoconfig.freecam.option.collision.ignoreAll"));
    }

    @Test
    void translateOtherTextIsDetected() {
        Probe probe = translate("msg.freecam.enable", "<NOFX_ENABLE>");
        assertEquals(ScanResult.DETECTED, ScanLogic.evaluate(probe, "Freecam enabled"));
        assertEquals(ScanResult.DETECTED, ScanLogic.evaluate(probe, "Свободная камера активирована"));
    }

    @Test
    void fallbackWithoutPrefixIsNotTrusted() {
        Probe probe = translate("msg.freecam.enable", "<NOFX_ENABLE>");
        assertEquals(ScanResult.DETECTED, ScanLogic.evaluate(probe, "noise <NOFX_ENABLE> в конце"));
    }

    @Test
    void exploitPreventerDetection() {
        assertTrue(ScanLogic.isExploitPreventer("key.forward", "key.forward"));
        assertTrue(ScanLogic.isExploitPreventer("Key.FORWARD", "key.forward"));
        assertFalse(ScanLogic.isExploitPreventer("key.jump", "key.forward"));
        assertFalse(ScanLogic.isExploitPreventer("", "key.forward"));
    }

    private static Probe keybind(String key) {
        return new Probe(key, Probe.Type.KEYBIND, "");
    }

    private static Probe translate(String key, String fallback) {
        return new Probe(key, Probe.Type.TRANSLATE, fallback);
    }
}