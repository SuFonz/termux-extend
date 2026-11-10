package com.termux.terminal;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;

/**
 * An image transmitted by the kitty graphics protocol, which may contain multiple frames for an
 * animation.
 *
 * The first frame is the root frame, created from the image data transmitted with the `a=t` or
 * `a=T` actions, and more frames are added with the `a=f` action. Each frame has a gap, which is
 * the number of milliseconds to wait before the next frame is displayed. A negative gap marks a
 * "gapless" frame, which is never displayed, since it is instantly skipped and only serves as the
 * base data for subsequent frames.
 *
 * All frames of an image have the same dimensions, so that a placement of the image can simply be
 * updated with the bitmap of the frame that is currently being displayed.
 *
 * - https://sw.kovidgoyal.net/kitty/graphics-protocol/#animation
 */
public class TerminalKittyImage {

    public static final String LOG_TAG = "TerminalKittyImage";

    /** The default gap in milliseconds between animation frames, see the `z` key. */
    public static final int DEFAULT_FRAME_GAP = 40;

    /** The number of times the animation should loop for an infinite number of loops. */
    public static final int INFINITE_LOOPS = -1;



    protected final TerminalSessionClient mClient;

    /** The width and height in pixels of the image, which is the same for all its frames. */
    protected final int mWidth;
    protected final int mHeight;

    /** The frames of the image, where the first frame is the root frame. */
    private final ArrayList<Bitmap> mFrames = new ArrayList<>();
    /** The gap in milliseconds before the next frame is displayed, for each frame in {@link #mFrames}. */
    private final ArrayList<Integer> mGaps = new ArrayList<>();

    /** The index of the frame currently being displayed. */
    private int mCurrentFrame = 0;

    /** Whether the animation is currently running. */
    private boolean mIsAnimating = false;
    /** Whether the animation is in loading mode, i.e. it waits for more frames at the last frame. */
    private boolean mIsLoading = false;
    /** The number of loops remaining to be played, or {@link #INFINITE_LOOPS}. */
    private int mLoopsRemaining = INFINITE_LOOPS;

    /** The time at which the current frame was displayed, from {@link SystemClock#uptimeMillis()}. */
    private long mFrameDisplayedAt = 0;



    public TerminalKittyImage(@NonNull TerminalSessionClient client, @NonNull Bitmap rootFrame) {
        mClient = client;

        mWidth = rootFrame.getWidth();
        mHeight = rootFrame.getHeight();

        mFrames.add(rootFrame);
        // The root frame has no gap, since the animation only shows the frames after it.
        mGaps.add(0);
    }



    public int getWidth() {
        return mWidth;
    }

    public int getHeight() {
        return mHeight;
    }

    public int getFrameCount() {
        return mFrames.size();
    }

    public int getCurrentFrame() {
        return mCurrentFrame;
    }

    /** Get the {@link Bitmap} of the frame that is currently being displayed. */
    public Bitmap getBitmap() {
        return mFrames.get(mCurrentFrame);
    }

    /** Get the size in bytes of all the frames of the image. */
    public long getByteCount() {
        long byteCount = 0;
        for (Bitmap frame : mFrames) {
            byteCount += frame.getByteCount();
        }
        return byteCount;
    }


    public boolean isAnimating() {
        return mIsAnimating;
    }

    /**
     * Whether the animation is in loading mode, i.e. it waits for more frames at the last frame
     * instead of looping, and resumes when a new frame is added.
     */
    public boolean isLoading() {
        return mIsLoading;
    }


    /**
     * Add a new frame to the image, composed from the frame data transmitted by the client.
     *
     * @param source The decoded frame data, which is drawn onto the new frame.
     * @param baseFrameIndex The 0-based index of the frame whose data is used as the base for the
     *                       new frame, or a negative value to start from an empty canvas.
     * @param x The x position in the image at which to draw the frame data.
     * @param y The y position in the image at which to draw the frame data.
     * @param width The width of the area to draw, or `0` for the remaining width of the image.
     * @param height The height of the area to draw, or `0` for the remaining height of the image.
     * @param backgroundColor The 32-bit `RGBA` background color for an empty canvas.
     * @param overwrite Whether the frame data replaces the pixels instead of being alpha blended.
     * @param gap The gap in milliseconds, where `0` means the {@link #DEFAULT_FRAME_GAP}.
     * @return Returns {@code true} if the frame was added, otherwise {@code false}.
     */
    public boolean addFrame(@NonNull Bitmap source, int baseFrameIndex,
                            int x, int y, int width, int height,
                            int backgroundColor, boolean overwrite, int gap) {
        Bitmap frame = createFrame(source, baseFrameIndex, x, y, width, height, backgroundColor, overwrite);
        if (frame == null) return false;

        mFrames.add(frame);
        mGaps.add(gap == 0 ? DEFAULT_FRAME_GAP : gap);

        return true;
    }

    /**
     * Edit an existing frame by drawing the frame data transmitted by the client onto it.
     *
     * @return Returns {@code true} if the frame was edited, otherwise {@code false}.
     */
    public boolean editFrame(int frameIndex, @NonNull Bitmap source,
                             int x, int y, int width, int height, boolean overwrite) {
        if (frameIndex < 0 || frameIndex >= mFrames.size()) {
            Logger.logError(mClient, LOG_TAG, "The frame index " + frameIndex + " is not valid for the image with " +
                mFrames.size() + " frames");
            return false;
        }

        Bitmap frame = copyBitmap(mFrames.get(frameIndex));
        if (frame == null) return false;

        drawOnto(frame, source, x, y, 0, 0, width, height, overwrite);

        mFrames.set(frameIndex, frame);
        return true;
    }

    /**
     * Compose a rectangle of a frame onto another frame.
     *
     * @return Returns {@code true} if the frames were composed, otherwise {@code false}.
     */
    public boolean composeFrames(int sourceFrameIndex, int destinationFrameIndex,
                                 int destinationX, int destinationY,
                                 int sourceX, int sourceY,
                                 int width, int height, boolean overwrite) {
        if (sourceFrameIndex < 0 || sourceFrameIndex >= mFrames.size() ||
            destinationFrameIndex < 0 || destinationFrameIndex >= mFrames.size()) {
            Logger.logError(mClient, LOG_TAG, "The frame indexes " + sourceFrameIndex + " and " + destinationFrameIndex +
                " are not valid for the image with " + mFrames.size() + " frames");
            return false;
        }

        Bitmap frame = copyBitmap(mFrames.get(destinationFrameIndex));
        if (frame == null) return false;

        drawOnto(frame, mFrames.get(sourceFrameIndex), destinationX, destinationY, sourceX, sourceY,
            width, height, overwrite);

        mFrames.set(destinationFrameIndex, frame);
        return true;
    }

    /**
     * Set the gap of a frame.
     *
     * @param frameIndex The 0-based index of the frame.
     * @param gap The gap in milliseconds, where a negative value creates a gapless frame and `0` is ignored.
     * @return Returns {@code true} if the gap was set, otherwise {@code false}.
     */
    public boolean setGap(int frameIndex, int gap) {
        if (frameIndex < 0 || frameIndex >= mFrames.size() || gap == 0) return false;
        mGaps.set(frameIndex, gap);
        return true;
    }

    /** Delete all the animation frames of the image, keeping only the root frame. */
    public void clearFrames() {
        stopAnimation();

        while (mFrames.size() > 1) {
            mFrames.remove(mFrames.size() - 1);
            mGaps.remove(mGaps.size() - 1);
        }

        mCurrentFrame = 0;
    }



    /**
     * Start the animation of the image.
     *
     * @param loading Whether the animation must wait for new frames when it reaches the last frame
     *                instead of looping.
     * @param loops The number of loops to play, `1` for infinite loop or `0` to keep the current value.
     */
    public void startAnimation(boolean loading, int loops) {
        mIsAnimating = true;
        mIsLoading = loading;
        mFrameDisplayedAt = SystemClock.uptimeMillis();

        if (loops > 0) mLoopsRemaining = loops == 1 ? INFINITE_LOOPS : loops - 1;
    }

    public void stopAnimation() {
        mIsAnimating = false;
        mIsLoading = false;
    }

    /** Set the frame that is currently being displayed, which is clamped to the available frames. */
    public void setCurrentFrame(int frameIndex) {
        if (frameIndex < 0) frameIndex = 0;
        if (frameIndex >= mFrames.size()) frameIndex = mFrames.size() - 1;

        mCurrentFrame = frameIndex;
        mFrameDisplayedAt = SystemClock.uptimeMillis();
    }

    /**
     * Advance the animation to the next frame, skipping gapless frames.
     *
     * @return Returns {@code true} if a new frame should be displayed, otherwise {@code false} if
     * the animation has stopped or is waiting for more frames.
     */
    public boolean advanceFrame() {
        if (!mIsAnimating || mFrames.size() < 2) {
            mIsAnimating = false;
            return false;
        }

        int nextFrame = mCurrentFrame;
        int framesChecked = 0;

        while (true) {
            nextFrame++;

            if (nextFrame >= mFrames.size()) {
                // The animation has reached the last frame.
                if (mIsLoading) {
                    // Wait for the client to send more frames instead of looping. The loading mode
                    // is kept so that the animation resumes when a new frame is added.
                    mIsAnimating = false;
                    return false;
                }

                if (mLoopsRemaining != INFINITE_LOOPS) {
                    mLoopsRemaining--;
                    if (mLoopsRemaining <= 0) {
                        mIsAnimating = false;
                        return false;
                    }
                }

                nextFrame = 0;
            }

            // Gapless frames are never displayed, they only serve as the base data for other frames.
            if (mGaps.get(nextFrame) >= 0) break;

            if (++framesChecked > mFrames.size()) {
                mIsAnimating = false;
                return false;
            }
        }

        mCurrentFrame = nextFrame;
        mFrameDisplayedAt = SystemClock.uptimeMillis();

        return true;
    }

    /**
     * Get the time remaining in milliseconds before the next frame of the animation should be
     * displayed.
     *
     * @return Returns the time remaining, which is negative if the frame is already due, or `-1` if
     * the animation is not running.
     */
    public long getTimeUntilNextFrame() {
        if (!mIsAnimating || mFrames.size() < 2) return -1;

        long gap = Math.max(0, mGaps.get(mCurrentFrame));
        return gap - (SystemClock.uptimeMillis() - mFrameDisplayedAt);
    }



    @Nullable
    private Bitmap createFrame(@NonNull Bitmap source, int baseFrameIndex,
                               int x, int y, int width, int height,
                               int backgroundColor, boolean overwrite) {
        Bitmap frame;

        if (baseFrameIndex >= 0 && baseFrameIndex < mFrames.size()) {
            frame = copyBitmap(mFrames.get(baseFrameIndex));
            if (frame == null) return null;
        } else {
            frame = createEmptyBitmap(backgroundColor);
            if (frame == null) return null;
        }

        drawOnto(frame, source, x, y, 0, 0, width, height, overwrite);

        return frame;
    }

    @Nullable
    private Bitmap createEmptyBitmap(int backgroundColor) {
        try {
            Bitmap bitmap = Bitmap.createBitmap(mWidth, mHeight, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(backgroundColor);
            return bitmap;
        } catch (Throwable t) {
            if (t instanceof OutOfMemoryError) System.gc();
            Logger.logError(mClient, LOG_TAG, "Failed to create the animation frame bitmap: " + t.getMessage());
            return null;
        }
    }

    @Nullable
    private Bitmap copyBitmap(@NonNull Bitmap bitmap) {
        try {
            Bitmap copy = bitmap.copy(Bitmap.Config.ARGB_8888, /* isMutable */ true);
            return copy != null ? copy : bitmap;
        } catch (Throwable t) {
            if (t instanceof OutOfMemoryError) System.gc();
            Logger.logError(mClient, LOG_TAG, "Failed to copy the animation frame bitmap: " + t.getMessage());
            return null;
        }
    }

    /**
     * Draw a rectangle of the source bitmap onto the frame bitmap, either replacing the pixels or
     * alpha blending them.
     *
     * @param width The width of the rectangle to draw, or `0` for the remaining width of the frame.
     * @param height The height of the rectangle to draw, or `0` for the remaining height of the frame.
     */
    private void drawOnto(@NonNull Bitmap frame, @NonNull Bitmap source,
                          int destinationX, int destinationY, int sourceX, int sourceY,
                          int width, int height, boolean overwrite) {
        if (destinationX < 0 || destinationY < 0 || sourceX < 0 || sourceY < 0) return;

        if (width <= 0) width = frame.getWidth() - destinationX;
        if (height <= 0) height = frame.getHeight() - destinationY;

        width = Math.min(width, Math.min(source.getWidth() - sourceX, frame.getWidth() - destinationX));
        height = Math.min(height, Math.min(source.getHeight() - sourceY, frame.getHeight() - destinationY));
        if (width < 1 || height < 1) return;

        Paint paint = new Paint();
        paint.setFilterBitmap(true);
        if (overwrite)
            paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC));

        try {
            Canvas canvas = new Canvas(frame);
            canvas.drawBitmap(source,
                new Rect(sourceX, sourceY, sourceX + width, sourceY + height),
                new Rect(destinationX, destinationY, destinationX + width, destinationY + height),
                paint);
        } catch (Throwable t) {
            if (t instanceof OutOfMemoryError) System.gc();
            Logger.logError(mClient, LOG_TAG, "Failed to draw the animation frame: " + t.getMessage());
        }
    }

}
