package com.termux.terminal;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.Inflater;

/**
 * A terminal graphics image received via the kitty graphics protocol
 * `APC G<control data>;<payload> ST` command.
 *
 * The command is received as an `APC` escape sequence, where the control data is a comma separated
 * list of `key=value` pairs and the payload is `base64` encoded image data, or the path to the image
 * file if the transmission medium is not `d` (direct).
 *
 * The image data may also be sent in chunks, where all but the last chunk have the `m` key set to
 * `1`, and the remaining chunks only contain the `m` and optionally `q` keys.
 *
 * - https://sw.kovidgoyal.net/kitty/graphics-protocol/
 *
 * **See Also:**
 * - `TerminalEmulator.doApc()`
 * - `TerminalBitmap`
 */
public class TerminalKittyGraphics {

    public static final String LOG_TAG = "TerminalKittyGraphics";

    /**
     * The character that must be the first character of an `APC` command for it to be a
     * kitty graphics command. It is the `G` in `ESC _ G ... ESC \`.
     */
    public static final char APC_IDENTIFIER = 'G';


    /* The values for the `a` (action) key. */

    /** Transmit image data, without displaying it. */
    public static final char ACTION_TRANSMIT = 't';
    /** Transmit image data and display it at the current cursor position. */
    public static final char ACTION_TRANSMIT_AND_DISPLAY = 'T';
    /** Display a previously transmitted image at the current cursor position. */
    public static final char ACTION_DISPLAY = 'p';
    /** Delete images, as defined by the `d` key. */
    public static final char ACTION_DELETE = 'd';
    /** Query the terminal for the support of a transmission medium or image format. */
    public static final char ACTION_QUERY = 'q';
    /** Transmit the data for a frame of an image animation. */
    public static final char ACTION_FRAME_TRANSMIT = 'f';
    /** Control the animation of an image. */
    public static final char ACTION_ANIMATION_CONTROL = 'a';
    /** Compose the frames of an image animation. */
    public static final char ACTION_FRAME_COMPOSE = 'c';

    public static final char DEFAULT_ACTION = ACTION_TRANSMIT;


    /* The values for the `s` key of the animation control action. */

    /** Stop the animation. */
    public static final int ANIMATION_STOP = 1;
    /** Run the animation, but wait for more frames when the last frame is reached. */
    public static final int ANIMATION_LOADING = 2;
    /** Run the animation, looping back to the first frame when the last frame is reached. */
    public static final int ANIMATION_RUN = 3;


    /* The values for the `f` (format) key. */

    /** 24-bit RGB image data, with the `s` and `v` keys defining the image dimensions. */
    public static final int FORMAT_RGB = 24;
    /** 32-bit RGBA image data, with the `s` and `v` keys defining the image dimensions. */
    public static final int FORMAT_RGBA = 32;
    /** PNG image data, with the image dimensions being read from the image data itself. */
    public static final int FORMAT_PNG = 100;

    public static final int DEFAULT_FORMAT = FORMAT_RGBA;


    /* The values for the `t` (transmission medium) key. */

    /** The image data is transmitted within the escape code itself. */
    public static final char MEDIUM_DIRECT = 'd';
    /** The payload is the path to a regular file that must be read. */
    public static final char MEDIUM_FILE = 'f';
    /** The payload is the path to a temporary file that must be read and then deleted. */
    public static final char MEDIUM_TEMP_FILE = 't';
    /** The payload is the name of a shared memory object. Not supported. */
    public static final char MEDIUM_SHARED_MEMORY = 's';

    public static final char DEFAULT_MEDIUM = MEDIUM_DIRECT;


    /* The values for the `o` (compression) key. */

    /** The image data is zlib compressed, as per RFC 1950. */
    public static final char COMPRESSION_ZLIB = 'z';


    /* The values for the `d` (delete) key. */

    public static final char DELETE_ALL = 'a';
    public static final char DELETE_BY_IMAGE_ID = 'i';
    public static final char DELETE_BY_IMAGE_NUMBER = 'n';
    public static final char DELETE_INTERSECTING_CURSOR = 'c';
    public static final char DELETE_INTERSECTING_CELL = 'p';
    public static final char DELETE_INTERSECTING_CELL_WITH_Z_INDEX = 'q';
    public static final char DELETE_INTERSECTING_COLUMN = 'x';
    public static final char DELETE_INTERSECTING_ROW = 'y';
    public static final char DELETE_WITH_Z_INDEX = 'z';
    /** Delete the animation frames of an image, keeping only its root frame. */
    public static final char DELETE_FRAMES = 'f';
    /** Delete all the images with an id in the range defined by the `x` and `y` keys. */
    public static final char DELETE_BY_IMAGE_ID_RANGE = 'r';

    public static final char DEFAULT_DELETE = DELETE_ALL;


    /* The response codes sent back to the application for a kitty graphics command. */

    public static final String RESPONSE_OK = "OK";
    public static final String RESPONSE_EINVAL = "EINVAL";
    public static final String RESPONSE_ENOENT = "ENOENT";
    public static final String RESPONSE_ENOSPC = "ENOSPC";
    public static final String RESPONSE_EBADF = "EBADF";

    /**
     * The response sent to the application for all image file read failures. The actual reason for
     * the failure must only be logged, since the image data may be sent by a program running on a
     * remote machine that must not be able to learn anything about files it cannot read itself.
     *
     * - https://sw.kovidgoyal.net/kitty/graphics-protocol/#the-transmission-medium
     */
    public static final String RESPONSE_EBADF_MESSAGE = RESPONSE_EBADF + ":Failed to read image file";

    /**
     * The max size of the `base64` encoded image data that can be collected for a single image.
     */
    public static final int MAX_ENCODED_DATA_SIZE = 32 * 1024 * 1024;

    /**
     * The max size of the image data after decompression. Decompression is not done in place and
     * the uncompressed data could be much larger than the compressed data received.
     */
    public static final int MAX_DECODED_DATA_SIZE = 64 * 1024 * 1024;

    /**
     * The string that must be part of the path of a temporary file for it to be deleted after
     * reading it. This is required for security reasons so that a malicious program cannot get
     * arbitrary files deleted.
     *
     * - https://sw.kovidgoyal.net/kitty/graphics-protocol/#the-transmission-medium
     */
    public static final String TEMP_FILE_DELETE_PATH_REQUIREMENT = "tty-graphics-protocol";



    protected final TerminalSessionClient mClient;

    /** The `a` key, the action that this command is performing. */
    private char mAction = DEFAULT_ACTION;

    /** The `i` key, the image id. */
    private int mImageId = 0;
    /**
     * The `I` key, the image number, which is resolved to the id of the newest image transmitted
     * with that number.
     *
     * - https://sw.kovidgoyal.net/kitty/graphics-protocol/#requesting-image-ids-from-the-terminal
     */
    private int mImageNumber = 0;
    /** The `p` key, the placement id. */
    private int mPlacementId = 0;

    /** The `f` key, the format of the image data. */
    private int mFormat = DEFAULT_FORMAT;
    /** The `t` key, the transmission medium of the image data. */
    private char mMedium = DEFAULT_MEDIUM;
    /** The `o` key, the compression of the image data. */
    private char mCompression = 0;

    /** The `s` key, the width in pixels of the image for the `RGB` and `RGBA` formats. */
    private int mSourceWidth = 0;
    /** The `v` key, the height in pixels of the image for the `RGB` and `RGBA` formats. */
    private int mSourceHeight = 0;

    /** The `S` key, the size of the image data to read from an image file. */
    private int mDataSize = 0;
    /** The `O` key, the offset from which to read the image data from an image file. */
    private int mDataOffset = 0;

    /** The `m` key, whether more chunks of image data will follow this command. */
    private boolean mHasMoreChunks = false;

    /** The `q` key, whether the terminal should suppress responses. */
    private int mQuiet = 0;

    /**
     * The `x` key, the meaning of which depends on the action being performed.
     *
     * - For {@link #ACTION_TRANSMIT}, {@link #ACTION_TRANSMIT_AND_DISPLAY} and {@link #ACTION_DISPLAY},
     *   it is the left edge in pixels of the source rectangle of the image to display.
     * - For {@link #ACTION_DELETE}, it is the 1-based column of the cell for the
     *   {@link #DELETE_INTERSECTING_CELL}, {@link #DELETE_INTERSECTING_CELL_WITH_Z_INDEX} and
     *   {@link #DELETE_INTERSECTING_COLUMN} specifiers.
     */
    private int mX = 0;
    /** The `y` key. See {@link #mX} for its meaning, with 1-based row for delete actions. */
    private int mY = 0;
    /** The `w` key, the width in pixels of the source rectangle of the image to display. */
    private int mW = 0;
    /** The `h` key, the height in pixels of the source rectangle of the image to display. */
    private int mH = 0;

    /** The `X` key, the x-offset in pixels within the first cell at which to start displaying the image. */
    private int mCellPixelOffsetX = 0;
    /** The `Y` key, the y-offset in pixels within the first cell at which to start displaying the image. */
    private int mCellPixelOffsetY = 0;

    /** The `c` key, the number of columns to display the image over. */
    private int mColumns = 0;
    /** The `r` key, the number of rows to display the image over. */
    private int mRows = 0;

    /** The `C` key, whether the cursor must not be moved after displaying the image. */
    private boolean mNoCursorMovement = false;

    /** The `z` key, the vertical stacking order of the image, or the gap for animation frames. */
    private int mZIndex = 0;

    /** The `d` key, what to delete for the {@link #ACTION_DELETE} action. */
    private char mDeleteSpecifier = DEFAULT_DELETE;

    /** The `e` key, the action to query the support of, only allowed for the {@link #ACTION_QUERY} action. */
    private char mFeatureQueryAction = 0;

    /**
     * The `N` key, the usage hints from the client. `1` means the image is expected to be used for
     * only a short time and can be evicted before other images when the storage quota is reached.
     */
    private int mUsageHints = 0;

    /**
     * The `key=value` pairs of the command that have a non-default value, excluding the `a`, `i`,
     * `I`, `m`, `q` and `e` keys, which are never included in a feature query response.
     */
    private final LinkedHashMap<Character, String> mSpecifiedKeys = new LinkedHashMap<>();

    /** The `base64` encoded image data collected for the current image, for all chunks received. */
    private final StringBuilder mEncodedData = new StringBuilder(/* Initial capacity. */ 4096);

    /** The decoded image, or `null` if the image data has not been decoded yet or decoding failed. */
    private Bitmap mBitmap;

    /** The error response to send to the application if the command failed, or `null` if it succeeded. */
    private String mError;

    /** Whether the command has been received in full, i.e. all chunks of image data have been received. */
    private boolean mIsReadingChunks = false;



    public TerminalKittyGraphics(@NonNull TerminalSessionClient client) {
        mClient = client;
    }



    public TerminalSessionClient getClient() {
        return mClient;
    }


    public char getAction() {
        return mAction;
    }

    public int getImageId() {
        return mImageId;
    }

    public int getImageNumber() {
        return mImageNumber;
    }

    public int getPlacementId() {
        return mPlacementId;
    }


    public int getFormat() {
        return mFormat;
    }

    public char getMedium() {
        return mMedium;
    }

    public char getCompression() {
        return mCompression;
    }


    public int getSourceWidth() {
        return mSourceWidth;
    }

    public int getSourceHeight() {
        return mSourceHeight;
    }

    public int getDataSize() {
        return mDataSize;
    }

    public int getDataOffset() {
        return mDataOffset;
    }


    public int getQuiet() {
        return mQuiet;
    }

    public boolean shouldSuppressOkResponse() {
        return mQuiet == 1 || mQuiet == 2;
    }

    public boolean shouldSuppressErrorResponse() {
        return mQuiet == 2;
    }


    public int getX() {
        return mX;
    }

    public int getY() {
        return mY;
    }

    public int getW() {
        return mW;
    }

    public int getH() {
        return mH;
    }

    public int getCellPixelOffsetX() {
        return mCellPixelOffsetX;
    }

    public int getCellPixelOffsetY() {
        return mCellPixelOffsetY;
    }


    public int getColumns() {
        return mColumns;
    }

    public int getRows() {
        return mRows;
    }

    public boolean isCursorMovementDisabled() {
        return mNoCursorMovement;
    }

    public int getZIndex() {
        return mZIndex;
    }


    /*
     * The keys below have a different meaning for the animation actions (`a=f`, `a=a` and `a=c`)
     * than for the other actions, so they are handled by dedicated getters.
     */

    /**
     * Get the `c` key for the animation actions, which is the 1-based number of the frame whose
     * image data serves as the base when creating a new frame, or the 1-based number of the frame
     * to compose onto.
     */
    public int getAnimationBaseFrameNumber() {
        return mColumns;
    }

    /**
     * Get the `r` key for the animation actions, which is the 1-based number of the frame that is
     * being edited, or the 1-based number of the frame to compose from.
     */
    public int getAnimationFrameNumber() {
        return mRows;
    }

    /** Get the `s` key for the animation control action, which is one of the `ANIMATION_*` values. */
    public int getAnimationControl() {
        return mSourceWidth;
    }

    /** Get the `v` key for the animation control action, which is the number of loops to play. */
    public int getAnimationLoopCount() {
        return mSourceHeight;
    }

    /** Get the `z` key for the animation actions, which is the gap of a frame in milliseconds, negative for a gapless frame. */
    public int getFrameGap() {
        return mZIndex;
    }

    /** Get the `X` key for the animation frame transmit action, where `1` means the pixels are overwritten instead of blended. */
    public boolean isFrameOverwriteMode() {
        return mCellPixelOffsetX == 1;
    }

    /** Get the `C` key for the animation frame compose action, where `1` means the pixels are overwritten instead of blended. */
    public boolean isComposeOverwriteMode() {
        return mNoCursorMovement;
    }

    /** Get the `Y` key for the animation frame transmit action, which is the 32-bit `RGBA` background color. */
    public int getFrameBackgroundColor() {
        return mCellPixelOffsetY;
    }

    public char getDeleteSpecifier() {
        return mDeleteSpecifier;
    }

    public char getFeatureQueryAction() {
        return mFeatureQueryAction;
    }

    public int getUsageHints() {
        return mUsageHints;
    }

    /** Whether the client has hinted that the image is only used for a short time, i.e. `N=1`. */
    public boolean isTransient() {
        return (mUsageHints & 1) == 1;
    }

    /** The `key=value` pairs of the command that have a non-default value, for feature query responses. */
    public Map<Character, String> getSpecifiedKeys() {
        return mSpecifiedKeys;
    }


    /**
     * Set the image id of the command, which is used to reply with the id that the terminal
     * allocated for an image that was transmitted with an image number instead of an id.
     */
    public void setImageId(int imageId) {
        mImageId = imageId;
    }


    public Bitmap getBitmap() {
        return mBitmap;
    }

    @Nullable
    public String getError() {
        return mError;
    }


    /**
     * Whether the command is still receiving chunks of image data, and its action must only be
     * performed once all chunks have been received.
     */
    public boolean isReadingChunks() {
        return mIsReadingChunks;
    }



    /**
     * Read a command. The command may be a complete command, or a chunk of a command being received
     * in multiple chunks.
     *
     * @param apcCommand The command received between the `APC` and `ST` escape sequences, which
     *                   must start with the {@link #APC_IDENTIFIER} character.
     * @return Returns {@code true} if the command was read successfully, otherwise {@code false}
     * and {@link #getError()} will return the error response to send to the application.
     */
    public boolean readCommand(@NonNull String apcCommand) {
        if (apcCommand.length() < 1 || apcCommand.charAt(0) != APC_IDENTIFIER) {
            return setError(RESPONSE_EINVAL, "The command does not start with the \"" + APC_IDENTIFIER + "\" identifier");
        }

        int payloadStartIndex = apcCommand.indexOf(';');
        String controlData;
        String payload;
        if (payloadStartIndex < 0) {
            controlData = apcCommand.substring(1);
            payload = null;
        } else {
            controlData = apcCommand.substring(1, payloadStartIndex);
            payload = apcCommand.substring(payloadStartIndex + 1);
        }

        if (!mIsReadingChunks) {
            if (!readControlData(controlData)) return false;
        } else {
            if (!readControlDataForChunk(controlData)) return false;
        }

        if (payload != null && payload.length() > 0) {
            if (!readImageData(payload)) return false;
        }

        mIsReadingChunks = mHasMoreChunks;
        return true;
    }

    /**
     * Whether a command is a chunk of a command being received in multiple chunks. Only the `m` and
     * optionally `q` keys may be present in such a command.
     *
     * @param apcCommand The command received between the `APC` and `ST` escape sequences, which
     *                   must start with the {@link #APC_IDENTIFIER} character.
     */
    public static boolean isChunkContinuation(@NonNull String apcCommand) {
        if (apcCommand.length() < 1 || apcCommand.charAt(0) != APC_IDENTIFIER) return false;

        int payloadStartIndex = apcCommand.indexOf(';');
        String controlData = payloadStartIndex < 0 ? apcCommand.substring(1) : apcCommand.substring(1, payloadStartIndex);

        int index = 0;
        while (index < controlData.length()) {
            char key = controlData.charAt(index);
            if (key != 'm' && key != 'q') return false;

            int valueEndIndex = controlData.indexOf(',', index);
            if (valueEndIndex < 0) return true;

            index = valueEndIndex + 1;
        }

        // An empty control data for a command that is not a chunk continuation is invalid, but it
        // must be handled as a new command so that the error is reported to the application.
        return false;
    }



    /**
     * Read the control data of a complete command, which is a comma separated list of `key=value` pairs.
     */
    private boolean readControlData(@NonNull String controlData) {
        int index = 0;
        int length = controlData.length();
        while (index < length) {
            int keyEndIndex = controlData.indexOf('=', index);
            if (keyEndIndex < 0) {
                return setError(RESPONSE_EINVAL, "The value for the key at index " + index + " not found in control data \"" + controlData + "\"");
            }

            if (keyEndIndex != index + 1) {
                return setError(RESPONSE_EINVAL, "The key for the value at index " + keyEndIndex + " is not a single character in control data \"" + controlData + "\"");
            }

            char key = controlData.charAt(index);

            int valueEndIndex = controlData.indexOf(',', keyEndIndex);
            if (valueEndIndex < 0) valueEndIndex = length;

            if (!readControlKeyValue(key, controlData.substring(keyEndIndex + 1, valueEndIndex))) return false;

            index = valueEndIndex + 1;
        }

        return validateControlData();
    }

    /**
     * Read the control data of a chunk following the first chunk of a command. Such a command may
     * only contain the `m` and optionally `q` keys.
     */
    private boolean readControlDataForChunk(@NonNull String controlData) {
        int index = 0;
        int length = controlData.length();
        while (index < length) {
            int keyEndIndex = controlData.indexOf('=', index);
            if (keyEndIndex != index + 1) {
                return setError(RESPONSE_EINVAL, "Invalid chunk control data \"" + controlData + "\"");
            }

            char key = controlData.charAt(index);
            int valueEndIndex = controlData.indexOf(',', keyEndIndex);
            if (valueEndIndex < 0) valueEndIndex = length;

            String value = controlData.substring(keyEndIndex + 1, valueEndIndex);

            if (key == 'm') {
                mHasMoreChunks = parseInt(value, "m", 0) != 0;
            } else if (key == 'q') {
                mQuiet = parseInt(value, "q", 0);
            } else {
                return setError(RESPONSE_EINVAL, "Only the \"m\" and \"q\" keys are allowed for an image data chunk, but found \"" + key + "\"");
            }

            index = valueEndIndex + 1;
        }

        return true;
    }

    private boolean readControlKeyValue(char key, @NonNull String value) {
        switch (key) {
            case 'a':
                mAction = parseAction(value);
                if (mAction == 0) return false;
                break;
            case 'q':
                mQuiet = parseInt(value, "q", 0);
                break;
            case 'f':
                mFormat = parseInt(value, "f", DEFAULT_FORMAT);
                break;
            case 't':
                mMedium = parseChar(value);
                break;
            case 'o':
                mCompression = parseChar(value);
                break;
            case 's':
                mSourceWidth = parseInt(value, "s", 0);
                break;
            case 'v':
                mSourceHeight = parseInt(value, "v", 0);
                break;
            case 'S':
                mDataSize = parseInt(value, "S", 0);
                break;
            case 'O':
                mDataOffset = parseInt(value, "O", 0);
                break;
            case 'i':
                mImageId = parseInt(value, "i", 0);
                break;
            case 'I':
                mImageNumber = parseInt(value, "I", 0);
                break;
            case 'p':
                mPlacementId = parseInt(value, "p", 0);
                break;
            case 'm':
                mHasMoreChunks = parseInt(value, "m", 0) != 0;
                break;
            case 'x':
                mX = parseInt(value, "x", 0);
                break;
            case 'y':
                mY = parseInt(value, "y", 0);
                break;
            case 'w':
                mW = parseInt(value, "w", 0);
                break;
            case 'h':
                mH = parseInt(value, "h", 0);
                break;
            case 'X':
                mCellPixelOffsetX = parseInt(value, "X", 0);
                break;
            case 'Y':
                // The `Y` key is a pixel offset for the display actions, but a 32-bit `RGBA` background
                // color for the animation frame transmit action, which does not fit in a signed int.
                mCellPixelOffsetY = parseColor(value);
                break;
            case 'c':
                mColumns = parseInt(value, "c", 0);
                break;
            case 'r':
                mRows = parseInt(value, "r", 0);
                break;
            case 'C':
                mNoCursorMovement = parseInt(value, "C", 0) != 0;
                break;
            case 'z':
                mZIndex = parseInt(value, "z", 0);
                break;
            case 'd':
                mDeleteSpecifier = parseChar(value);
                break;
            case 'e':
                mFeatureQueryAction = parseChar(value);
                break;
            case 'N':
                mUsageHints = parseInt(value, "N", 0);
                break;
            default:
                // Keys like `U` (unicode placeholders) and `P`, `Q`, `H`, `V` (relative placements)
                // are not supported and are ignored, as is allowed by the protocol.
                break;
        }

        if (mError == null) recordSpecifiedKey(key, value);

        return mError == null;
    }

    /**
     * Record a `key=value` pair of the command so that it can be reported back in a feature query
     * response if the key is supported for the queried action.
     *
     * The `a`, `i`, `I`, `m`, `q` and `e` keys are never included in a feature query response.
     *
     * - https://sw.kovidgoyal.net/kitty/graphics-protocol/#querying-support-for-protocol-features
     */
    private void recordSpecifiedKey(char key, @NonNull String value) {
        switch (key) {
            case 'a':
            case 'i':
            case 'I':
            case 'm':
            case 'q':
            case 'e':
                return;
            default:
                mSpecifiedKeys.put(key, value);
        }
    }

    private boolean validateControlData() {
        // The `i` and `I` keys are mutually exclusive.
        if (mImageId > 0 && mImageNumber > 0) {
            return setError(RESPONSE_EINVAL, "Both the \"i\" and \"I\" keys cannot be specified");
        }

        // The `e` key is only allowed in query actions.
        if (mFeatureQueryAction != 0 && mAction != ACTION_QUERY) {
            return setError(RESPONSE_EINVAL, "The \"e\" key is only allowed for the query action");
        }

        switch (mAction) {
            case ACTION_TRANSMIT:
            case ACTION_TRANSMIT_AND_DISPLAY:
            case ACTION_FRAME_TRANSMIT:
                if (mFormat != FORMAT_RGB && mFormat != FORMAT_RGBA && mFormat != FORMAT_PNG) {
                    return setError(RESPONSE_EINVAL, "Unsupported image format " + mFormat);
                }
                break;
            case ACTION_DISPLAY:
                break;
            case ACTION_DELETE:
                break;
            case ACTION_QUERY:
                break;
            case ACTION_ANIMATION_CONTROL:
                if (mSourceWidth < 0 || mSourceWidth > ANIMATION_RUN) {
                    return setError(RESPONSE_EINVAL, "Unsupported animation control value " + mSourceWidth);
                }
                break;
            case ACTION_FRAME_COMPOSE:
                break;
            default:
                return setError(RESPONSE_EINVAL, "Unsupported action \"" + mAction + "\"");
        }

        return true;
    }


    /**
     * Collect a chunk of `base64` encoded image data, or of the image file path for the
     * {@link #MEDIUM_FILE} and {@link #MEDIUM_TEMP_FILE} transmission mediums.
     */
    private boolean readImageData(@NonNull String payload) {
        if ((long) mEncodedData.length() + payload.length() > MAX_ENCODED_DATA_SIZE) {
            return setError(RESPONSE_ENOSPC, "The image data size is greater than the max size " + MAX_ENCODED_DATA_SIZE);
        }

        try {
            // Appending can cause an increase in capacity and cause an OOM.
            mEncodedData.append(payload);
        } catch (Throwable t) {
            if (t instanceof OutOfMemoryError) System.gc();
            return setError(RESPONSE_ENOSPC, "Collecting image data failed: " + t.getMessage());
        }

        return true;
    }



    /**
     * Decode the image data of the command to a {@link Bitmap}.
     *
     * @return Returns {@code true} if the image was decoded successfully, otherwise {@code false}
     * and {@link #getError()} will return the error response to send to the application.
     */
    public boolean decodeImage() {
        if (mError != null) return false;

        byte[] data = getImageData();
        if (data == null) return false;

        if (mCompression == COMPRESSION_ZLIB) {
            data = inflateImageData(data);
            if (data == null) return false;
        }

        switch (mFormat) {
            case FORMAT_PNG:
                return decodePngImage(data);
            case FORMAT_RGB:
            case FORMAT_RGBA:
                return decodePixelData(data);
            default:
                return setError(RESPONSE_EINVAL, "Unsupported image format " + mFormat);
        }
    }

    /**
     * Get the image data, either by decoding the `base64` encoded payload or by reading the image
     * file defined by the payload.
     */
    @Nullable
    private byte[] getImageData() {
        if (mEncodedData.length() < 1) {
            setError(RESPONSE_EINVAL, "The image data is not set");
            return null;
        }

        switch (mMedium) {
            case MEDIUM_DIRECT:
                return decodeBase64ImageData();
            case MEDIUM_FILE:
            case MEDIUM_TEMP_FILE: {
                String path = decodeBase64ImagePath();
                if (path == null) return null;

                byte[] data = readImageFile(path);
                if (mMedium == MEDIUM_TEMP_FILE) deleteTempImageFile(path);
                return data;
            }
            default:
                setError(RESPONSE_EINVAL, "Unsupported transmission medium \"" + mMedium + "\"");
                return null;
        }
    }

    @Nullable
    private byte[] decodeBase64ImageData() {
        String encodedData = mEncodedData.toString();

        // The length of `base64` encoded data must be a multiple of 4, but the last chunk of a
        // chunked image may be missing the trailing padding.
        int padding = encodedData.length() % 4;
        if (padding > 0) encodedData = encodedData + "====".substring(padding);

        try {
            byte[] data = Base64.decode(encodedData, Base64.DEFAULT);
            if (data == null || data.length < 1) {
                setError(RESPONSE_EINVAL, "The decoded image data is not valid");
                return null;
            }
            return data;
        } catch (Throwable t) {
            if (t instanceof OutOfMemoryError) System.gc();
            setError(RESPONSE_EINVAL, "Decoding the `base64` encoded image data failed: " + t.getMessage());
            return null;
        }
    }

    @Nullable
    private String decodeBase64ImagePath() {
        String encodedPath = mEncodedData.toString();

        int padding = encodedPath.length() % 4;
        if (padding > 0) encodedPath = encodedPath + "====".substring(padding);

        try {
            byte[] data = Base64.decode(encodedPath, Base64.DEFAULT);
            if (data == null || data.length < 1) {
                setError(RESPONSE_EINVAL, "The decoded image file path is not valid");
                return null;
            }
            return new String(data, "UTF-8");
        } catch (Throwable t) {
            if (t instanceof OutOfMemoryError) System.gc();
            setError(RESPONSE_EINVAL, "Decoding the `base64` encoded image file path failed: " + t.getMessage());
            return null;
        }
    }

    /**
     * Read the image data from an image file. Only regular files are allowed to be read and the
     * failures are reported to the application with the same generic error message, so that a
     * program running on a remote machine cannot learn anything about files it cannot read itself.
     */
    @Nullable
    private byte[] readImageFile(@NonNull String path) {
        File file = new File(path);

        // Do not read sensitive files that could be used to block the terminal or leak information.
        if (path.startsWith("/proc/") || path.startsWith("/sys/") || path.startsWith("/dev/")) {
            Logger.logError(mClient, LOG_TAG, "Ignoring image file at \"" + path + "\" since it is in a sensitive directory");
            return setImageFileReadError();
        }

        if (!file.exists() || !file.isFile()) {
            Logger.logError(mClient, LOG_TAG, "Ignoring image file at \"" + path + "\" since it does not exist or is not a regular file");
            return setImageFileReadError();
        }

        long fileLength = file.length();
        int dataSize = mDataSize > 0 ? mDataSize : (int) Math.min(fileLength - mDataOffset, Integer.MAX_VALUE);
        long dataOffset = mDataOffset > 0 ? mDataOffset : 0;

        if (dataSize < 1 || dataOffset + dataSize > fileLength) {
            Logger.logError(mClient, LOG_TAG, "Ignoring image file at \"" + path + "\" since the image data size " +
                dataSize + " and offset " + dataOffset + " are not valid for the file size " + fileLength);
            return setImageFileReadError();
        }

        if (dataSize > MAX_ENCODED_DATA_SIZE) {
            Logger.logError(mClient, LOG_TAG, "Ignoring image file at \"" + path + "\" since the image data size " +
                dataSize + " is greater than the max size " + MAX_ENCODED_DATA_SIZE);
            setError(RESPONSE_ENOSPC, "The image data size is greater than the max size " + MAX_ENCODED_DATA_SIZE);
            return null;
        }

        try (InputStream in = new FileInputStream(file)) {
            long skipped = 0;
            while (skipped < dataOffset) {
                long skip = in.skip(dataOffset - skipped);
                if (skip < 1) break;
                skipped += skip;
            }

            byte[] data = new byte[dataSize];
            int read = 0;
            while (read < dataSize) {
                int count = in.read(data, read, dataSize - read);
                if (count < 1) break;
                read += count;
            }

            if (read < dataSize) {
                Logger.logError(mClient, LOG_TAG, "Failed to read image file at \"" + path + "\" since only " +
                    read + " bytes of " + dataSize + " bytes could be read");
                return setImageFileReadError();
            }

            return data;
        } catch (Throwable t) {
            if (t instanceof OutOfMemoryError) System.gc();
            Logger.logStackTraceWithMessage(mClient, LOG_TAG, "Failed to read image file at \"" + path + "\"", t);
            return setImageFileReadError();
        }
    }

    /**
     * Delete a temporary image file after its data has been read, if it is safe to do so.
     */
    private void deleteTempImageFile(@NonNull String path) {
        if (!path.contains(TEMP_FILE_DELETE_PATH_REQUIREMENT)) {
            Logger.logWarn(mClient, LOG_TAG, "Not deleting temporary image file at \"" + path + "\" since its path " +
                "does not contain the \"" + TEMP_FILE_DELETE_PATH_REQUIREMENT + "\" string");
            return;
        }

        File file = new File(path);
        if (!file.delete()) {
            Logger.logWarn(mClient, LOG_TAG, "Failed to delete temporary image file at \"" + path + "\"");
        }
    }

    @Nullable
    private byte[] inflateImageData(@NonNull byte[] data) {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(data);

            ByteArrayOutputStream output = new ByteArrayOutputStream(Math.max(1024, data.length * 4));
            byte[] buffer = new byte[16384];
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                if (count == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break;
                } else {
                    output.write(buffer, 0, count);
                }

                if (output.size() > MAX_DECODED_DATA_SIZE) {
                    setError(RESPONSE_ENOSPC, "The decompressed image data size is greater than the max size " + MAX_DECODED_DATA_SIZE);
                    return null;
                }
            }

            if (output.size() < 1) {
                setError(RESPONSE_EINVAL, "The decompressed image data is empty");
                return null;
            }

            return output.toByteArray();
        } catch (Throwable t) {
            if (t instanceof OutOfMemoryError) System.gc();
            setError(RESPONSE_EINVAL, "Decompressing the image data failed: " + t.getMessage());
            return null;
        } finally {
            inflater.end();
        }
    }

    private boolean decodePngImage(@NonNull byte[] data) {
        int width;
        int height;

        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, options);
            width = options.outWidth;
            height = options.outHeight;
        } catch (Throwable t) {
            if (t instanceof OutOfMemoryError) System.gc();
            return setError(RESPONSE_EINVAL, "Decoding the image bounds failed: " + t.getMessage());
        }

        if (width < 1 || height < 1) {
            return setError(RESPONSE_EINVAL, "The image dimensions " + width + "x" + height + " are not valid");
        }

        if (!isBitmapSizeWithinLimits(width, height)) return false;

        try {
            Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
            if (bitmap == null) {
                return setError(RESPONSE_EINVAL, "The decoded image bitmap is not set");
            }
            mBitmap = bitmap;
            return true;
        } catch (Throwable t) {
            if (t instanceof OutOfMemoryError) System.gc();
            return setError(RESPONSE_ENOSPC, "Decoding the image failed: " + t.getMessage());
        }
    }

    private boolean decodePixelData(@NonNull byte[] data) {
        if (mSourceWidth < 1 || mSourceHeight < 1) {
            return setError(RESPONSE_EINVAL, "The image dimensions must be defined for the image format " + mFormat);
        }

        if (!isBitmapSizeWithinLimits(mSourceWidth, mSourceHeight)) return false;

        int bytesPerPixel = mFormat == FORMAT_RGBA ? 4 : 3;
        long expectedSize = (long) mSourceWidth * mSourceHeight * bytesPerPixel;
        if (data.length < expectedSize) {
            return setError(RESPONSE_EINVAL, "The image data size " + data.length + " is less than the expected size " + expectedSize);
        }

        try {
            int[] pixels = new int[mSourceWidth * mSourceHeight];
            int index = 0;
            for (int i = 0; i < pixels.length; i++) {
                int r = data[index++] & 0xff;
                int g = data[index++] & 0xff;
                int b = data[index++] & 0xff;
                int a = bytesPerPixel == 4 ? (data[index++] & 0xff) : 0xff;
                pixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
            }

            mBitmap = Bitmap.createBitmap(pixels, mSourceWidth, mSourceHeight, Bitmap.Config.ARGB_8888);
            return true;
        } catch (Throwable t) {
            if (t instanceof OutOfMemoryError) System.gc();
            return setError(RESPONSE_ENOSPC, "Creating the image bitmap failed: " + t.getMessage());
        }
    }

    private boolean isBitmapSizeWithinLimits(int width, int height) {
        long size = (long) width * height * 4;
        if (size > TerminalBitmap.MAX_BITMAP_SIZE) {
            setError(RESPONSE_ENOSPC, "The image size " + size + " for dimensions " +
                width + "x" + height + " is greater than the max bitmap size " + TerminalBitmap.MAX_BITMAP_SIZE);
            return false;
        }
        return true;
    }



    private char parseAction(@NonNull String value) {
        if (value.length() != 1) {
            setError(RESPONSE_EINVAL, "The action value \"" + value + "\" is not a single character");
            return 0;
        }

        char action = value.charAt(0);
        switch (action) {
            case ACTION_TRANSMIT:
            case ACTION_TRANSMIT_AND_DISPLAY:
            case ACTION_DISPLAY:
            case ACTION_DELETE:
            case ACTION_QUERY:
            case ACTION_FRAME_TRANSMIT:
            case ACTION_ANIMATION_CONTROL:
            case ACTION_FRAME_COMPOSE:
                return action;
            default:
                setError(RESPONSE_EINVAL, "The action value \"" + value + "\" is not supported");
                return 0;
        }
    }

    private char parseChar(@NonNull String value) {
        if (value.length() != 1) {
            setError(RESPONSE_EINVAL, "The single character value \"" + value + "\" is not valid");
            return 0;
        }
        return value.charAt(0);
    }

    /** Parse a 32-bit `RGBA` color value, which does not fit in a signed integer. */
    private int parseColor(@NonNull String value) {
        try {
            return (int) Long.parseLong(value);
        } catch (NumberFormatException e) {
            setError(RESPONSE_EINVAL, "The color value \"" + value + "\" is not a valid integer");
            return 0;
        }
    }

    private int parseInt(@NonNull String value, @NonNull String key, int def) {
        if (value.length() < 1) {
            setError(RESPONSE_EINVAL, "The value for the \"" + key + "\" key is not set");
            return def;
        }

        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            // The `i`, `I`, `p` and `z` keys can have values greater than `Integer.MAX_VALUE`.
            long longValue;
            try {
                longValue = Long.parseLong(value);
            } catch (NumberFormatException e2) {
                setError(RESPONSE_EINVAL, "The value \"" + value + "\" for the \"" + key + "\" key is not a valid integer");
                return def;
            }

            if (longValue > Integer.MAX_VALUE) return Integer.MAX_VALUE;
            if (longValue < Integer.MIN_VALUE) return Integer.MIN_VALUE;
            return (int) longValue;
        }
    }



    /**
     * Set the generic error for all image file read failures, so that a program running on a remote
     * machine cannot learn anything about files it cannot read itself.
     */
    @Nullable
    private byte[] setImageFileReadError() {
        setError(RESPONSE_EBADF, "Failed to read image file");
        return null;
    }

    private boolean setError(@NonNull String code, @NonNull String message) {
        mError = code + ":" + message;
        // Do not log the full command if it may contain image data.
        Logger.logError(mClient, LOG_TAG, mError);
        return false;
    }

}
