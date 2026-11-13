package com.termux.terminal;

import org.junit.Test;

/**
 * Tests that an {@link TerminalEmulator#ESC_APC} sequence which is left unterminated does not swallow
 * the output that follows it.
 *
 * <p>An application that is interrupted in the middle of sending an APC command, like when a SIGINT is
 * delivered during a kitty graphics transfer, leaves the sequence without its terminator. Every byte
 * the shell prints afterwards would be collected as payload, which makes the terminal look frozen
 * until the payload reaches its size limit.</p>
 */
public class ApcAbortTest extends TerminalTestCase {

    private static final String UNTERMINATED_APC = "\033_Gm=1;AAAAAAAAAA";

    /** A newline cannot be part of a payload, so it aborts the sequence and is handled normally. */
    @Test
    public void testAbortOnNewline() {
        withTerminalSized(20, 5);

        enterString(UNTERMINATED_APC);
        enterString("\r\n");
        enterString("ok");

        assertEquals("ok", mTerminal.getScreen().getSelectedText(0, 1, 2, 1));
    }

    /** CAN and SUB cancel a sequence, see ECMA-48. */
    @Test
    public void testAbortOnCancel() {
        withTerminalSized(20, 5);

        enterString(UNTERMINATED_APC);
        enterString("\u0018");
        enterString("ok");

        assertEquals("ok", mTerminal.getScreen().getSelectedText(0, 0, 2, 0));
    }

    @Test
    public void testAbortOnSubstitute() {
        withTerminalSized(20, 5);

        enterString(UNTERMINATED_APC);
        enterString("\u001a");
        enterString("ok");

        assertEquals("ok", mTerminal.getScreen().getSelectedText(0, 0, 2, 0));
    }

    /** Text written before an unterminated sequence must stay on the screen. */
    @Test
    public void testTextBeforeTheSequenceIsKept() {
        withTerminalSized(20, 5);

        enterString("abc");
        enterString(UNTERMINATED_APC);
        enterString("\r\n");
        enterString("ok");

        assertEquals("abc", mTerminal.getScreen().getSelectedText(0, 0, 3, 0));
        assertEquals("ok", mTerminal.getScreen().getSelectedText(0, 1, 2, 1));
    }

    /** A properly terminated sequence must still be consumed without printing anything. */
    @Test
    public void testTerminatedSequencePrintsNothing() {
        withTerminalSized(20, 5);

        enterString("\033_Gabc\033\\");
        enterString("ok");

        assertEquals("ok", mTerminal.getScreen().getSelectedText(0, 0, 2, 0));
    }

    /** An ESC that is not followed by a backslash stays part of the payload. */
    @Test
    public void testEscThatIsNotATerminatorStaysInThePayload() {
        withTerminalSized(20, 5);

        // The ESC of the SGR sequence must not terminate the command, only the ESC followed by \.
        enterString("\033_Gabc\033[1m\033\\");
        enterString("ok");

        assertEquals("ok", mTerminal.getScreen().getSelectedText(0, 0, 2, 0));
    }
}
