package com.termux.terminal;

import org.junit.Test;

/**
 * Tests for the kitty keyboard protocol, both for the commands that the client uses to set the flags
 * and for the encoding of the key events.
 *
 * - https://sw.kovidgoyal.net/kitty/keyboard-protocol/
 */
public class KittyKeyboardTest extends TerminalTestCase {

    private static final int FLAG_DISAMBIGUATE = KeyHandler.KITTY_KEYBOARD_FLAG_DISAMBIGUATE_ESCAPE_CODES;
    private static final int FLAG_EVENT_TYPES = KeyHandler.KITTY_KEYBOARD_FLAG_REPORT_EVENT_TYPES;
    private static final int FLAG_ALL_KEYS = KeyHandler.KITTY_KEYBOARD_FLAG_REPORT_ALL_KEYS_AS_ESCAPE_CODES;
    private static final int FLAG_TEXT = KeyHandler.KITTY_KEYBOARD_FLAG_REPORT_ASSOCIATED_TEXT;

    private static final int KEY_A = android.view.KeyEvent.KEYCODE_A;
    private static final int KEY_ESCAPE = android.view.KeyEvent.KEYCODE_ESCAPE;
    private static final int KEY_ENTER = android.view.KeyEvent.KEYCODE_ENTER;
    private static final int KEY_TAB = android.view.KeyEvent.KEYCODE_TAB;
    private static final int KEY_BACKSPACE = android.view.KeyEvent.KEYCODE_DEL;
    private static final int KEY_UP = android.view.KeyEvent.KEYCODE_DPAD_UP;
    private static final int KEY_F1 = android.view.KeyEvent.KEYCODE_F1;

    private static final int EVENT_PRESS = KeyHandler.KITTY_KEYBOARD_EVENT_TYPE_PRESS;
    private static final int EVENT_REPEAT = KeyHandler.KITTY_KEYBOARD_EVENT_TYPE_REPEAT;
    private static final int EVENT_RELEASE = KeyHandler.KITTY_KEYBOARD_EVENT_TYPE_RELEASE;

    private void enableKittyKeyboard() {
        TerminalEmulator.setKittyKeyboardEnabled(true);
    }

    // The commands that the client uses to set the flags.

    @Test
    public void testFlagsAreNotReportedWhenDisabled() {
        TerminalEmulator.setKittyKeyboardEnabled(false);
        withTerminalSized(20, 5);

        enterString("\033[=1u");
        assertEquals("the flags must be ignored when the protocol is disabled",
            0, mTerminal.getKittyKeyboardFlags());

        enterString("\033[?u");
        assertEquals("a query must not be answered when the protocol is disabled",
            "", mOutput.getOutputAndClear());
    }

    @Test
    public void testSetFlags() {
        enableKittyKeyboard();
        withTerminalSized(20, 5);

        // Mode 1 replaces the flags.
        enterString("\033[=1;1u");
        assertEquals(FLAG_DISAMBIGUATE, mTerminal.getKittyKeyboardFlags());

        enterString("\033[=8;1u");
        assertEquals(FLAG_ALL_KEYS, mTerminal.getKittyKeyboardFlags());

        // Mode 2 is the default and adds the flags.
        enterString("\033[=2u");
        assertEquals(FLAG_ALL_KEYS | FLAG_EVENT_TYPES, mTerminal.getKittyKeyboardFlags());

        // Mode 3 removes the flags.
        enterString("\033[=2;3u");
        assertEquals(FLAG_ALL_KEYS, mTerminal.getKittyKeyboardFlags());

        // Mode 1 with no flags disables all of them again.
        enterString("\033[=0;1u");
        assertEquals("the flags must be cleared by setting them to zero",
            0, mTerminal.getKittyKeyboardFlags());

        TerminalEmulator.setKittyKeyboardEnabled(false);
    }

    @Test
    public void testPushAndPopFlags() {
        enableKittyKeyboard();
        withTerminalSized(20, 5);

        enterString("\033[=1;1u");
        assertEquals(FLAG_DISAMBIGUATE, mTerminal.getKittyKeyboardFlags());

        // Pushing makes the given flags the current ones and keeps the old ones on the stack.
        enterString("\033[>8u");
        assertEquals(FLAG_ALL_KEYS, mTerminal.getKittyKeyboardFlags());

        // Popping without a parameter pops one entry.
        enterString("\033[<u");
        assertEquals(FLAG_DISAMBIGUATE, mTerminal.getKittyKeyboardFlags());

        // Popping past the bottom of the stack keeps the current flags.
        enterString("\033[<10u");
        assertEquals(FLAG_DISAMBIGUATE, mTerminal.getKittyKeyboardFlags());

        enterString("\033[<u");
        assertEquals(FLAG_DISAMBIGUATE, mTerminal.getKittyKeyboardFlags());

        TerminalEmulator.setKittyKeyboardEnabled(false);
    }

    @Test
    public void testQueryReportsFlags() {
        enableKittyKeyboard();
        withTerminalSized(20, 5);

        enterString("\033[=5;1u");
        mOutput.getOutputAndClear();
        enterString("\033[?u");

        assertEquals("\033[?5u", mOutput.getOutputAndClear());

        TerminalEmulator.setKittyKeyboardEnabled(false);
    }

    @Test
    public void testResetDropsFlags() {
        enableKittyKeyboard();
        withTerminalSized(20, 5);

        enterString("\033[=1;1u");
        assertEquals(FLAG_DISAMBIGUATE, mTerminal.getKittyKeyboardFlags());

        mTerminal.reset();
        assertEquals("a reset must drop the flags", 0, mTerminal.getKittyKeyboardFlags());

        TerminalEmulator.setKittyKeyboardEnabled(false);
    }

    // The encoding of the key events.

    /** Without any flag the legacy encoding must be used, so no code is reported. */
    @Test
    public void testNoEncodingWithoutFlags() {
        assertNull(KeyHandler.getKittyKeyboardCode(KEY_A, 0, 0, EVENT_PRESS, 'a'));
        assertNull("the flags for the event types alone must not change the encoding",
            KeyHandler.getKittyKeyboardCode(KEY_A, 0, FLAG_EVENT_TYPES, EVENT_PRESS, 'a'));
    }

    /** The keys that are unambiguous in the legacy encoding must keep it unless all keys are reported. */
    @Test
    public void testOnlyAmbiguousKeysAreReported() {
        assertNull(KeyHandler.getKittyKeyboardCode(KEY_A, 0, FLAG_DISAMBIGUATE, EVENT_PRESS, 'a'));
        assertEquals("\033[97u", KeyHandler.getKittyKeyboardCode(KEY_A, 0, FLAG_ALL_KEYS, EVENT_PRESS, 'a'));
    }

    @Test
    public void testAmbiguousKeys() {
        assertEquals("\033[27u", KeyHandler.getKittyKeyboardCode(KEY_ESCAPE, 0, FLAG_DISAMBIGUATE, EVENT_PRESS, -1));
        assertEquals("\033[13u", KeyHandler.getKittyKeyboardCode(KEY_ENTER, 0, FLAG_DISAMBIGUATE, EVENT_PRESS, -1));
        assertEquals("\033[9u", KeyHandler.getKittyKeyboardCode(KEY_TAB, 0, FLAG_DISAMBIGUATE, EVENT_PRESS, -1));
        assertEquals("\033[127u", KeyHandler.getKittyKeyboardCode(KEY_BACKSPACE, 0, FLAG_DISAMBIGUATE, EVENT_PRESS, -1));
    }

    @Test
    public void testModifiers() {
        // Ctrl is bit 4 of the mask, which is reported as `1 + 4`.
        assertEquals("\033[97;5u", KeyHandler.getKittyKeyboardCode(KEY_A, KeyHandler.KEYMOD_CTRL, FLAG_DISAMBIGUATE, EVENT_PRESS, 'a'));
        assertEquals("\033[97;2u", KeyHandler.getKittyKeyboardCode(KEY_A, KeyHandler.KEYMOD_SHIFT, FLAG_ALL_KEYS, EVENT_PRESS, 'a'));
        assertEquals("\033[9;2u", KeyHandler.getKittyKeyboardCode(KEY_TAB, KeyHandler.KEYMOD_SHIFT, FLAG_DISAMBIGUATE, EVENT_PRESS, -1));
    }

    @Test
    public void testEventTypes() {
        // A press is the default and is omitted.
        assertEquals("\033[27u", KeyHandler.getKittyKeyboardCode(KEY_ESCAPE, 0, FLAG_DISAMBIGUATE | FLAG_EVENT_TYPES, EVENT_PRESS, -1));
        assertEquals("\033[27;1:2u", KeyHandler.getKittyKeyboardCode(KEY_ESCAPE, 0, FLAG_DISAMBIGUATE | FLAG_EVENT_TYPES, EVENT_REPEAT, -1));
        assertEquals("\033[27;1:3u", KeyHandler.getKittyKeyboardCode(KEY_ESCAPE, 0, FLAG_DISAMBIGUATE | FLAG_EVENT_TYPES, EVENT_RELEASE, -1));
    }

    @Test
    public void testAssociatedText() {
        assertEquals("\033[97;1;97u", KeyHandler.getKittyKeyboardCode(KEY_A, 0, FLAG_ALL_KEYS | FLAG_TEXT, EVENT_PRESS, 'a'));
        // Ctrl changes the text, so it is not reported for it.
        assertEquals("\033[97;5u", KeyHandler.getKittyKeyboardCode(KEY_A, KeyHandler.KEYMOD_CTRL, FLAG_ALL_KEYS | FLAG_TEXT, EVENT_PRESS, 'a'));
    }

    @Test
    public void testFunctionalKeys() {
        assertEquals("\033[57352u", KeyHandler.getKittyKeyboardCode(KEY_UP, 0, FLAG_ALL_KEYS, EVENT_PRESS, -1));
        assertEquals("\033[57352;5u", KeyHandler.getKittyKeyboardCode(KEY_UP, KeyHandler.KEYMOD_CTRL, FLAG_ALL_KEYS, EVENT_PRESS, -1));
        assertEquals("\033[57364u", KeyHandler.getKittyKeyboardCode(KEY_F1, 0, FLAG_ALL_KEYS, EVENT_PRESS, -1));
    }
}
