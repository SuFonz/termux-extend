package com.termux.terminal;

import org.junit.Test;

/**
 * Tests for the synchronized output mode (DECSET 2026).
 *
 * <p>While the mode is active the terminal must not notify the client about screen changes, so that a
 * burst of output from the application is presented as a single update instead of one update per
 * write. The text itself is still written to the screen, only the presentation is deferred.</p>
 */
public class SynchronizedOutputTest extends TerminalTestCase {

    @Test
    public void testModeIsSetAndReset() {
        withTerminalSized(10, 5);
        assertFalse("the mode must be off by default", mTerminal.isSynchronizedOutputActive());

        enterString("\033[?2026h");
        assertTrue("DECSET 2026 must enable the mode", mTerminal.isSynchronizedOutputActive());

        enterString("\033[?2026l");
        assertFalse("DECRST 2026 must disable the mode", mTerminal.isSynchronizedOutputActive());
    }

    /** The text is still written while the mode is active, only the updates are held back. */
    @Test
    public void testOutputIsStillWrittenToTheScreen() {
        withTerminalSized(10, 5);

        enterString("\033[?2026h");
        enterString("hi");

        assertEquals("hi", mTerminal.getScreen().getSelectedText(0, 0, 2, 0));
    }

    /** A reset must drop the mode, otherwise updates would stay held back forever. */
    @Test
    public void testResetDropsTheMode() {
        withTerminalSized(10, 5);

        enterString("\033[?2026h");
        assertTrue(mTerminal.isSynchronizedOutputActive());

        mTerminal.reset();
        assertFalse("a reset must drop the synchronized output mode",
            mTerminal.isSynchronizedOutputActive());
    }

    /** Setting the mode again when it is already active must not lose a pending update. */
    @Test
    public void testRepeatedSetKeepsTheMode() {
        withTerminalSized(10, 5);

        enterString("\033[?2026h\033[?2026h");
        assertTrue(mTerminal.isSynchronizedOutputActive());

        enterString("\033[?2026l");
        assertFalse(mTerminal.isSynchronizedOutputActive());
    }
}
