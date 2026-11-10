package com.termux.view;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;

import com.termux.terminal.TerminalBitmap;
import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalRow;
import com.termux.terminal.TextStyle;
import com.termux.terminal.WcWidth;

/**
 * Renderer of a {@link TerminalEmulator} into a {@link Canvas}.
 * <p/>
 * Saves font metrics, so needs to be recreated each time the typeface or font size changes.
 */
public final class TerminalRenderer {

    final int mTextSize;
    final Typeface mTypeface;
    private final Paint mTextPaint = new Paint();

    /** The width of a single mono spaced character obtained by {@link Paint#measureText(String)} on a single 'X'. */
    final float mFontWidth;
    /** The {@link Paint#getFontSpacing()}. See http://www.fampennings.nl/maarten/android/08numgrid/font.png */
    final int mFontLineSpacing;
    /** The {@link Paint#ascent()}. See http://www.fampennings.nl/maarten/android/08numgrid/font.png */
    private final int mFontAscent;
    /** The {@link #mFontLineSpacing} + {@link #mFontAscent}. */
    final int mFontLineSpacingAndAscent;

    private final float[] asciiMeasures = new float[127];

    /**
     * The number of code points whose measured width is cached, see {@link #getMeasuredWidth}.
     * Must be a power of two.
     */
    private static final int MEASURED_WIDTHS_CACHE_SIZE = 2048;
    private static final int MEASURED_WIDTHS_CACHE_MASK = MEASURED_WIDTHS_CACHE_SIZE - 1;
    /** The max number of slots to check for an entry in the {@link #mMeasuredWidthsCacheKeys} table. */
    private static final int MEASURED_WIDTHS_CACHE_MAX_PROBES = 4;

    /**
     * Cache of the widths of the non-ASCII code points as measured by {@link Paint}, since measuring
     * them for each cell on every render pass is very slow and their width cannot change for the
     * lifetime of the renderer. The code point plus one is stored as the key so that `0` denotes an
     * unused slot. The cache is only an optimization, so it never needs to be invalidated and an
     * entry is simply overwritten if no free slot is found within the max number of probes.
     */
    private final int[] mMeasuredWidthsCacheKeys = new int[MEASURED_WIDTHS_CACHE_SIZE];
    private final float[] mMeasuredWidthsCacheValues = new float[MEASURED_WIDTHS_CACHE_SIZE];

    /** Reused rects to prevent allocations when rendering a {@link TerminalBitmap}. */
    private final Rect mBitmapSrcRect = new Rect();
    private final RectF mBitmapDestRect = new RectF();

    /**
     * The text effects and state as last set on {@link #mTextPaint} by {@link #drawTextRun}, so that
     * the native paint is only updated for a run if the state has actually changed. The initial
     * values are the defaults of a new {@link Paint}.
     */
    private boolean mLastPaintBold;
    private boolean mLastPaintUnderline;
    private boolean mLastPaintItalic;
    private boolean mLastPaintStrikeThrough;

    public TerminalRenderer(int textSize, Typeface typeface) {
        mTextSize = textSize;
        mTypeface = typeface;

        mTextPaint.setTypeface(typeface);
        mTextPaint.setAntiAlias(true);
        mTextPaint.setTextSize(textSize);

        mFontLineSpacing = (int) Math.ceil(mTextPaint.getFontSpacing());
        mFontAscent = (int) Math.ceil(mTextPaint.ascent());
        mFontLineSpacingAndAscent = mFontLineSpacing + mFontAscent;
        mFontWidth = mTextPaint.measureText("X");

        StringBuilder sb = new StringBuilder(" ");
        for (int i = 0; i < asciiMeasures.length; i++) {
            sb.setCharAt(0, (char) i);
            asciiMeasures[i] = mTextPaint.measureText(sb, 0, 1);
        }
    }

    /** Render the terminal to a canvas with at a specified row scroll, and an optional rectangular selection. */
    public final void render(TerminalEmulator mEmulator, Canvas canvas, int topRow,
                             int selectionY1, int selectionY2, int selectionX1, int selectionX2) {
        final boolean reverseVideo = mEmulator.isReverseVideo();
        final int endRow = topRow + mEmulator.mRows;
        final int columns = mEmulator.mColumns;
        final int cursorCol = mEmulator.getCursorCol();
        final int cursorRow = mEmulator.getCursorRow();
        final boolean cursorVisible = mEmulator.shouldCursorBeVisible();
        final TerminalBuffer screen = mEmulator.getScreen();
        final int[] palette = mEmulator.mColors.mCurrentColors;
        final int cursorShape = mEmulator.getCursorStyle();

        if (reverseVideo)
            canvas.drawColor(palette[TextStyle.COLOR_INDEX_FOREGROUND], PorterDuff.Mode.SRC);

        float heightOffset = mFontLineSpacingAndAscent;
        for (int row = topRow; row < endRow; row++) {
            heightOffset += mFontLineSpacing;

            final int cursorX = (row == cursorRow && cursorVisible) ? cursorCol : -1;
            int selx1 = -1, selx2 = -1;
            if (row >= selectionY1 && row <= selectionY2) {
                if (row == selectionY1) selx1 = selectionX1;
                selx2 = (row == selectionY2) ? selectionX2 : mEmulator.mColumns;
            }

            TerminalRow lineObject = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(row));
            final char[] line = lineObject.mText;
            final int charsUsedInLine = lineObject.getSpaceUsed();

            long lastRunStyle = 0;
            boolean lastRunInsideCursor = false;
            boolean lastRunInsideSelection = false;
            int lastRunStartColumn = -1;
            int lastRunStartIndex = 0;
            // The width ratio of the last code point added to the current run, see `widthRatio` below.
            float lastRunWidthRatio = 0.f;
            int currentCharIndex = 0;
            float measuredWidthForRun = 0.f;

            for (int column = 0; column < columns; ) {
                final char charAtIndex = line[currentCharIndex];
                final boolean charIsHighsurrogate = Character.isHighSurrogate(charAtIndex);
                final int charsForCodePoint = charIsHighsurrogate ? 2 : 1;
                final int codePoint = charIsHighsurrogate ? Character.toCodePoint(charAtIndex, line[currentCharIndex + 1]) : charAtIndex;
                final long style = lineObject.getStyle(column);
                if (TextStyle.isTerminalBitmap(style)) {
                    // Draw all the following cells of the bitmap row with a single drawBitmap() call
                    // instead of one call for each cell, which can mean thousands of drawing calls
                    // for a large image on each render pass. The cells of a row are always iterated
                    // from the left, so the cell being iterated is the leftmost visible cell of the
                    // remaining part of the row.
                    final int cellsDrawn = renderBitmapRow(mEmulator, canvas, lineObject, style, column,
                        heightOffset - mFontLineSpacing);
                    column += cellsDrawn;
                    currentCharIndex += cellsDrawn;
                    measuredWidthForRun = 0.f;
                    lastRunStyle = 0;
                    lastRunInsideCursor = false;
                    lastRunStartColumn = column;
                    lastRunStartIndex = currentCharIndex;
                    lastRunWidthRatio = 0.f;
                    continue;
                }
                final int codePointWcWidth = WcWidth.width(codePoint);
                final boolean insideCursor = (cursorX == column || (codePointWcWidth == 2 && cursorX == column + 1));
                final boolean insideSelection = column >= selx1 && column <= selx2;

                // Check if the measured text width for this code point is not the same as that expected by wcwidth().
                // This could happen for some fonts which are not truly monospace, or for more exotic characters such as
                // smileys which android font renders as wide.
                // If this is detected, we draw this code point scaled to match what wcwidth() expects.
                final float measuredCodePointWidth = (codePoint < asciiMeasures.length) ? asciiMeasures[codePoint]
                    : getMeasuredWidth(line, currentCharIndex, charsForCodePoint, codePoint);
                // The ratio of the width that the font uses for this code point to the width expected
                // by wcwidth(). If this is not `1`, then the code point is drawn scaled by
                // `drawTextRun()` to match what wcwidth() expects.
                final float widthRatio = (codePointWcWidth <= 0) ? 1.f
                    : measuredCodePointWidth / (mFontWidth * codePointWcWidth);

                if (style != lastRunStyle || insideCursor != lastRunInsideCursor || insideSelection != lastRunInsideSelection
                    || Math.abs(widthRatio - lastRunWidthRatio) > 0.01) {
                    if (column == 0 || column == lastRunStartColumn) {
                        // Skip first column as there is nothing to draw, just record the current style.
                    } else {
                        final int columnWidthSinceLastRun = column - lastRunStartColumn;
                        final int charsSinceLastRun = currentCharIndex - lastRunStartIndex;
                        int cursorColor = lastRunInsideCursor ? mEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] : 0;
                        boolean invertCursorTextColor = false;
                        if (lastRunInsideCursor && cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK) {
                            invertCursorTextColor = true;
                        }
                        drawTextRun(canvas, line, palette, heightOffset, lastRunStartColumn, columnWidthSinceLastRun,
                            lastRunStartIndex, charsSinceLastRun, measuredWidthForRun,
                            cursorColor, cursorShape, lastRunStyle, reverseVideo || invertCursorTextColor || lastRunInsideSelection);
                    }
                    measuredWidthForRun = 0.f;
                    lastRunStyle = style;
                    lastRunInsideCursor = insideCursor;
                    lastRunInsideSelection = insideSelection;
                    lastRunStartColumn = column;
                    lastRunStartIndex = currentCharIndex;
                    lastRunWidthRatio = widthRatio;
                }
                measuredWidthForRun += measuredCodePointWidth;
                column += codePointWcWidth;
                currentCharIndex += charsForCodePoint;
                while (currentCharIndex < charsUsedInLine && WcWidth.width(line, currentCharIndex) <= 0) {
                    // Eat combining chars so that they are treated as part of the last non-combining code point,
                    // instead of e.g. being considered inside the cursor in the next run.
                    currentCharIndex += Character.isHighSurrogate(line[currentCharIndex]) ? 2 : 1;
                }
            }

            final int columnWidthSinceLastRun = columns - lastRunStartColumn;
            final int charsSinceLastRun = currentCharIndex - lastRunStartIndex;
            int cursorColor = lastRunInsideCursor ? mEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] : 0;
            boolean invertCursorTextColor = false;
            if (lastRunInsideCursor && cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK) {
                invertCursorTextColor = true;
            }
            drawTextRun(canvas, line, palette, heightOffset, lastRunStartColumn, columnWidthSinceLastRun, lastRunStartIndex, charsSinceLastRun,
                measuredWidthForRun, cursorColor, cursorShape, lastRunStyle, reverseVideo || invertCursorTextColor || lastRunInsideSelection);
        }
    }

    private void drawTextRun(Canvas canvas, char[] text, int[] palette, float y, int startColumn, int runWidthColumns,
                             int startCharIndex, int runWidthChars, float mes, int cursor, int cursorStyle,
                             long textStyle, boolean reverseVideo) {
        int foreColor = TextStyle.decodeForeColor(textStyle);
        final int effect = TextStyle.decodeEffect(textStyle);
        int backColor = TextStyle.decodeBackColor(textStyle);
        final boolean bold = (effect & (TextStyle.CHARACTER_ATTRIBUTE_BOLD | TextStyle.CHARACTER_ATTRIBUTE_BLINK)) != 0;
        final boolean underline = (effect & TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE) != 0;
        final boolean italic = (effect & TextStyle.CHARACTER_ATTRIBUTE_ITALIC) != 0;
        final boolean strikeThrough = (effect & TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH) != 0;
        final boolean dim = (effect & TextStyle.CHARACTER_ATTRIBUTE_DIM) != 0;

        if ((foreColor & 0xff000000) != 0xff000000) {
            // Let bold have bright colors if applicable (one of the first 8):
            if (bold && foreColor >= 0 && foreColor < 8) foreColor += 8;
            foreColor = palette[foreColor];
        }

        if ((backColor & 0xff000000) != 0xff000000) {
            backColor = palette[backColor];
        }

        // Reverse video here if _one and only one_ of the reverse flags are set:
        final boolean reverseVideoHere = reverseVideo ^ (effect & (TextStyle.CHARACTER_ATTRIBUTE_INVERSE)) != 0;
        if (reverseVideoHere) {
            int tmp = foreColor;
            foreColor = backColor;
            backColor = tmp;
        }

        float left = startColumn * mFontWidth;
        float right = left + runWidthColumns * mFontWidth;

        mes = mes / mFontWidth;
        boolean savedMatrix = false;
        if (Math.abs(mes - runWidthColumns) > 0.01) {
            canvas.save();
            canvas.scale(runWidthColumns / mes, 1.f);
            left *= mes / runWidthColumns;
            right *= mes / runWidthColumns;
            savedMatrix = true;
        }

        if (backColor != palette[TextStyle.COLOR_INDEX_BACKGROUND]) {
            // Only draw non-default background.
            mTextPaint.setColor(backColor);
            canvas.drawRect(left, y - mFontLineSpacingAndAscent + mFontAscent, right, y, mTextPaint);
        }

        if (cursor != 0) {
            mTextPaint.setColor(cursor);
            float cursorHeight = mFontLineSpacingAndAscent - mFontAscent;
            if (cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE) cursorHeight /= 4.f;
            else if (cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR) right -= (((right - left) * 3) / 4.f);
            canvas.drawRect(left, y - cursorHeight, right, y, mTextPaint);
        }

        if ((effect & TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE) == 0) {
            if (dim) {
                int red = (0xFF & (foreColor >> 16));
                int green = (0xFF & (foreColor >> 8));
                int blue = (0xFF & foreColor);
                // Dim color handling used by libvte which in turn took it from xterm
                // (https://bug735245.bugzilla-attachments.gnome.org/attachment.cgi?id=284267):
                red = red * 2 / 3;
                green = green * 2 / 3;
                blue = blue * 2 / 3;
                foreColor = 0xFF000000 + (red << 16) + (green << 8) + blue;
            }

            mTextPaint.setColor(foreColor);

            // Only update the effects that have changed since the last run to prevent redundant
            // calls to the native paint for each run.
            if (bold != mLastPaintBold) {
                mTextPaint.setFakeBoldText(bold);
                mLastPaintBold = bold;
            }
            if (underline != mLastPaintUnderline) {
                mTextPaint.setUnderlineText(underline);
                mLastPaintUnderline = underline;
            }
            if (italic != mLastPaintItalic) {
                mTextPaint.setTextSkewX(italic ? -0.35f : 0.f);
                mLastPaintItalic = italic;
            }
            if (strikeThrough != mLastPaintStrikeThrough) {
                mTextPaint.setStrikeThruText(strikeThrough);
                mLastPaintStrikeThrough = strikeThrough;
            }

            // The text alignment is the default Paint.Align.LEFT.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                canvas.drawTextRun(text, startCharIndex, runWidthChars, startCharIndex, runWidthChars, left, y - mFontLineSpacingAndAscent, false, mTextPaint);
            } else {
                canvas.drawText(text, startCharIndex, runWidthChars, left, y - mFontLineSpacingAndAscent, mTextPaint);
            }
        }

        if (savedMatrix) canvas.restore();
    }

    /**
     * Get the width of a code point as measured by {@link Paint}, caching the value so that
     * {@link Paint#measureText(char[], int, int)} does not have to be called for every cell on every
     * render pass, which is very slow for code points that are not in {@link #asciiMeasures}, like
     * for CJK chars or emoji. The measured width cannot change for the lifetime of a renderer since
     * a new one is created if the typeface or the text size is changed.
     *
     * @param text The {@link TerminalRow#mText} array containing the code point.
     * @param charIndex The index of the code point in {@code text}.
     * @param charsForCodePoint The number of java chars used by the code point, `1` or `2`.
     * @param codePoint The code point to get the width for.
     * @return The width of the code point as measured by {@link Paint}.
     */
    private float getMeasuredWidth(char[] text, int charIndex, int charsForCodePoint, int codePoint) {
        final int cacheKey = codePoint + 1;
        int cacheIndex = (codePoint ^ (codePoint >>> 11)) & MEASURED_WIDTHS_CACHE_MASK;
        for (int i = 0; i < MEASURED_WIDTHS_CACHE_MAX_PROBES; i++, cacheIndex = (cacheIndex + 1) & MEASURED_WIDTHS_CACHE_MASK) {
            final int cachedKey = mMeasuredWidthsCacheKeys[cacheIndex];
            if (cachedKey == cacheKey) return mMeasuredWidthsCacheValues[cacheIndex];
            if (cachedKey == 0) {
                final float width = mTextPaint.measureText(text, charIndex, charsForCodePoint);
                mMeasuredWidthsCacheKeys[cacheIndex] = cacheKey;
                mMeasuredWidthsCacheValues[cacheIndex] = width;
                return width;
            }
        }

        // No free slot was found within the max number of probes, so measure the code point
        // without caching it.
        return mTextPaint.measureText(text, charIndex, charsForCodePoint);
    }

    /**
     * Render the following cells of a {@link TerminalBitmap} row being iterated with a single call to
     * {@link Canvas#drawBitmap(Bitmap, Rect, RectF, Paint)}, instead of drawing each cell of the row
     * separately, which can mean thousands of drawing calls for a large image on each render pass.
     *
     * The cells of the row are drawn exactly as they would be if each of them was drawn separately,
     * since the cell dimensions of the bitmap are used to calculate its source rect. Only the cells
     * that still contain the same bitmap are drawn, so that any cells that were overwritten by text
     * after the bitmap was created are still rendered as text.
     *
     * @param emulator The {@link TerminalEmulator} being rendered, used to get the terminal columns.
     * @param canvas The {@link Canvas} to draw on.
     * @param lineObject The {@link TerminalRow} being rendered.
     * @param style The style of the cell being iterated, which is a {@link TextStyle#isTerminalBitmap}
     *              style.
     * @param column The column of the cell being iterated.
     * @param rowTop The top y coordinate of the row being rendered.
     * @return The number of cells that were rendered and that the caller should skip, at least `1`.
     */
    private int renderBitmapRow(TerminalEmulator emulator, Canvas canvas, TerminalRow lineObject, long style,
                                int column, float rowTop) {
        final TerminalBuffer screen = emulator.getScreen();
        final TerminalBitmap terminalBitmap = screen.getTerminalBitmap(style);
        final Bitmap bitmap = (terminalBitmap == null) ? null : terminalBitmap.getBitmap();
        if (bitmap == null) return 1;

        final int cellWidth = terminalBitmap.getCellWidth();
        final int cellHeight = terminalBitmap.getCellHeight();
        if (cellWidth < 1 || cellHeight < 1) return 1;

        final int bitmapNum = TextStyle.getTerminalBitmapNum(style);
        final int bitmapCellX = TextStyle.getTerminalBitmapX(style);
        final int bitmapCellY = TextStyle.getTerminalBitmapY(style);

        // Clamp to the cells of the bitmap and to the terminal columns, since the bitmap can be wider
        // than the terminal if it was resized to a smaller number of columns after it was created.
        final int bitmapColumns = (bitmap.getWidth() + cellWidth - 1) / cellWidth;
        final int maxCells = Math.min(bitmapColumns - bitmapCellX, emulator.mColumns - column);
        if (maxCells < 1) return 1;

        int cellsWide = 1;
        while (cellsWide < maxCells) {
            final long cellStyle = lineObject.getStyle(column + cellsWide);
            if (!TextStyle.isTerminalBitmap(cellStyle) || TextStyle.getTerminalBitmapNum(cellStyle) != bitmapNum) break;
            cellsWide++;
        }

        final float left = column * mFontWidth + terminalBitmap.getPixelOffsetX();
        final float top = rowTop + terminalBitmap.getPixelOffsetY();
        mBitmapSrcRect.set(bitmapCellX * cellWidth, bitmapCellY * cellHeight,
            (bitmapCellX + cellsWide) * cellWidth, (bitmapCellY + 1) * cellHeight);
        mBitmapDestRect.set(left, top, left + cellsWide * mFontWidth, top + mFontLineSpacing);
        canvas.drawBitmap(bitmap, mBitmapSrcRect, mBitmapDestRect, null);
        return cellsWide;
    }

    public float getFontWidth() {
        return mFontWidth;
    }

    public int getFontLineSpacing() {
        return mFontLineSpacing;
    }
}
