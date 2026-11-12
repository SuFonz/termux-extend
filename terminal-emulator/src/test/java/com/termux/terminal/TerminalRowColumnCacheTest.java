package com.termux.terminal;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Tests that the cached column scan positions of {@link TerminalRow} do not change the result of
 * writing cells, including when the cells are not written from left to right.
 *
 * <p>Rows that contain a wide or a supplementary character cannot use the fast path of
 * {@link TerminalRow#setChar} and scan the row for the index of the column being written. That scan
 * continues from where the previous one ended, which is only correct as long as the text before that
 * position did not change.</p>
 */
public class TerminalRowColumnCacheTest {

    private static final int COLUMNS = 40;
    /** A supplementary plane character, which forces the slow path of setChar. */
    private static final int EMOJI = 0x1F600;
    /** The column holding the emoji, which needs two columns. */
    private static final int EMOJI_COLUMN = 20;

    private static long styleFor(int column) {
        return TextStyle.encode(column % 16, 1, 0);
    }

    private static TerminalRow createRowWrittenInOrder() {
        TerminalRow row = new TerminalRow(COLUMNS, TextStyle.NORMAL);
        row.setChar(EMOJI_COLUMN, EMOJI, styleFor(EMOJI_COLUMN));
        for (int column = 0; column < COLUMNS; column++) {
            if (column == EMOJI_COLUMN || column == EMOJI_COLUMN + 1) continue;
            row.setChar(column, 'a' + (column % 26), styleFor(column));
        }
        return row;
    }

    private static TerminalRow createRowWrittenInReverseOrder() {
        TerminalRow row = new TerminalRow(COLUMNS, TextStyle.NORMAL);
        for (int column = COLUMNS - 1; column >= 0; column--) {
            if (column == EMOJI_COLUMN || column == EMOJI_COLUMN + 1) continue;
            row.setChar(column, 'a' + (column % 26), styleFor(column));
        }
        row.setChar(EMOJI_COLUMN, EMOJI, styleFor(EMOJI_COLUMN));
        return row;
    }

    private static void assertRowsEqual(String message, TerminalRow expected, TerminalRow actual) {
        assertEquals(message + ": space used", expected.getSpaceUsed(), actual.getSpaceUsed());
        for (int i = 0; i < expected.getSpaceUsed(); i++) {
            assertEquals(message + ": text at index " + i, expected.mText[i], actual.mText[i]);
        }
        for (int column = 0; column < COLUMNS; column++) {
            assertEquals(message + ": style at column " + column,
                expected.getStyle(column), actual.getStyle(column));
        }
    }

    /** The result must not depend on the order the cells were written in. */
    @Test
    public void testWriteOrderDoesNotMatter() {
        assertRowsEqual("reverse order", createRowWrittenInOrder(), createRowWrittenInReverseOrder());
    }

    /** A cell that is overwritten repeatedly must always resolve to the same column index. */
    @Test
    public void testRepeatedOverwrite() {
        TerminalRow row = new TerminalRow(COLUMNS, TextStyle.NORMAL);
        row.setChar(EMOJI_COLUMN, EMOJI, styleFor(EMOJI_COLUMN));
        for (int round = 0; round < 5; round++) {
            for (int column = 0; column < COLUMNS; column++) {
                if (column == EMOJI_COLUMN || column == EMOJI_COLUMN + 1) continue;
                row.setChar(column, 'a' + ((column + round) % 26), styleFor(column));
            }
        }

        TerminalRow expected = new TerminalRow(COLUMNS, TextStyle.NORMAL);
        expected.setChar(EMOJI_COLUMN, EMOJI, styleFor(EMOJI_COLUMN));
        for (int column = 0; column < COLUMNS; column++) {
            if (column == EMOJI_COLUMN || column == EMOJI_COLUMN + 1) continue;
            expected.setChar(column, 'a' + ((column + 4) % 26), styleFor(column));
        }

        assertRowsEqual("repeated overwrite", expected, row);
    }

    /** Writing a wide character over a narrow one and back must keep the row consistent. */
    @Test
    public void testWideCharacterReplacedAndRestored() {
        TerminalRow row = new TerminalRow(COLUMNS, TextStyle.NORMAL);
        for (int column = 0; column < COLUMNS; column++) row.setChar(column, 'x', styleFor(column));

        row.setChar(5, EMOJI, styleFor(5));
        row.setChar(5, 'y', styleFor(5));

        // The wide character occupied two columns, so replacing it with a narrow one turns the second
        // column into a space, which is the behavior of the algorithm that was already there.
        assertEquals("the row must keep its column count", COLUMNS, row.getSpaceUsed());
        assertEquals("the written column must hold the new character",
            'y', row.mText[row.findStartOfColumn(5)]);
        for (int column = 0; column < COLUMNS; column++) {
            assertEquals("style at column " + column, styleFor(column), row.getStyle(column));
        }
    }

    /** Combining characters must stay attached to the column they were written to. */
    @Test
    public void testCombiningCharacterAfterOutOfOrderWrite() {
        TerminalRow row = new TerminalRow(COLUMNS, TextStyle.NORMAL);
        row.setChar(EMOJI_COLUMN, EMOJI, styleFor(EMOJI_COLUMN));
        row.setChar(10, 'e', styleFor(10));
        row.setChar(30, 'z', styleFor(30));
        // U+0301 COMBINING ACUTE ACCENT, which has zero width and belongs to column 10.
        row.setChar(10, 0x0301, styleFor(10));

        TerminalRow expected = new TerminalRow(COLUMNS, TextStyle.NORMAL);
        expected.setChar(EMOJI_COLUMN, EMOJI, styleFor(EMOJI_COLUMN));
        expected.setChar(30, 'z', styleFor(30));
        expected.setChar(10, 'e', styleFor(10));
        expected.setChar(10, 0x0301, styleFor(10));

        assertRowsEqual("combining character", expected, row);
    }
}
