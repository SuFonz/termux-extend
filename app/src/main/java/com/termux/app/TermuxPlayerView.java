package com.termux.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A full screen video player that is shown inside the terminal activity instead of in a separate
 * activity.
 *
 * <p>It is added to the container of the terminal view, so switching to the player looks like the
 * terminal switching to a different screen, like the alternate screen buffer of a terminal, instead
 * of like switching to another application. The terminal is still the resumed activity, so the
 * system back gesture returns to the terminal without the player appearing in the recents list.</p>
 *
 * <p>The player is controlled by the `termux-player` command, which writes the stream that it
 * receives on its standard input to a file and then sends a broadcast that starts this player with
 * the path of that file.</p>
 *
 * <p>The player is only added to the activity when the `enable-player` property is set, since it
 * holds a {@link TextureView} that the video decoder draws into, and a user that does not play any
 * videos does not need it.</p>
 */
public final class TermuxPlayerView implements TextureView.SurfaceTextureListener,
    MediaPlayer.OnPreparedListener, MediaPlayer.OnCompletionListener, MediaPlayer.OnErrorListener {

    private static final String LOG_TAG = "TermuxPlayerView";

    /** The action of the broadcast that starts the player. */
    public static final String ACTION_PLAY = "com.termux.app.TermuxPlayerView.PLAY";
    /** The action of the broadcast that closes the player. */
    public static final String ACTION_CLOSE = "com.termux.app.TermuxPlayerView.CLOSE";
    /** The action of the broadcast that adds a file to the playlist of the player. */
    public static final String ACTION_ENQUEUE = "com.termux.app.TermuxPlayerView.ENQUEUE";

    /** The extra that holds the path of the file to play. */
    public static final String EXTRA_PATH = "com.termux.app.TermuxPlayerView.PATH";
    /** The extra that holds the title to show while the stream is being prepared. */
    public static final String EXTRA_TITLE = "com.termux.app.TermuxPlayerView.TITLE";

    /** The number of milliseconds that the playback position moves for a fast forward or rewind. */
    private static final long SEEK_STEP_MS = 10_000;

    /**
     * The number of milliseconds after which a held arrow key starts to seek faster.
     *
     * A key that is held down seeks by {@link #SEEK_STEP_MS} for each repeat at first, and the step
     * grows once the key has been held for this long, so that a long video can be moved through
     * quickly without having to press the key many times.
     */
    private static final long SEEK_ACCELERATION_START_MS = 1_500;

    /** The maximum number of milliseconds that a single step moves when an arrow key is held. */
    private static final long SEEK_STEP_MAX_MS = 120_000;

    /** The time at which the arrow key that is being held was pressed, or `0` when none is held. */
    private long mSeekHoldStartTime;

    /** The interval in milliseconds at which the progress of the playback is updated. */
    private static final long PROGRESS_UPDATE_INTERVAL_MS = 500;

    /** The color of the text and the icons of the player. */
    private static final int COLOR_FOREGROUND = 0xFFFFFFFF;
    /** The color of the secondary text, like the hints at the bottom of the player. */
    private static final int COLOR_FOREGROUND_DIM = 0xB3FFFFFF;
    /** The color of the background of the control bar. */
    private static final int COLOR_BAR_BACKGROUND = 0xCC000000;
    /** The color of the background of a button that is pressed. */
    private static final int COLOR_BUTTON_PRESSED = 0x33FFFFFF;

    @NonNull
    private final TermuxActivity mActivity;

    private final FrameLayout mRoot;
    /**
     * The view that the video is drawn into.
     *
     * <p>A {@link TextureView} is used instead of a {@link SurfaceView} since its content is
     * composited with the rest of the window, so the control bar and the video are always drawn in
     * the order they are added. A {@link SurfaceView} draws into a separate layer that is composited
     * below the window through a hole punched in the window, and the hole and the window contents are
     * updated at different times, which makes the video and the controls overlap for a moment
     * whenever the window is redrawn, like on every tap that shows or hides the controls.</p>
     */
    private final TextureView mTextureView;
    /** The surface that the video is decoded into, which wraps the surface texture of the view. */
    @Nullable
    private Surface mSurface;
    private final TextView mMessageView;
    private final LinearLayout mControls;

    /** The progress bar and the time of the playback, which are created with the control bar. */
    private ProgressBar mProgressBar;
    private TextView mTimeView;
    private TextView mHintView;

    /**
     * The list of the keys that the player handles, which is created the first time it is shown so
     * that it does not cover the video when it is not needed.
     */
    @Nullable
    private TextView mKeyHelpView;

    /** The buttons of the control bar, in the order they are shown. */
    private final List<ControlButton> mButtons = new ArrayList<>();

    /**
     * The button that toggles the playback, which shows a pause icon while the video is playing and
     * a play icon while it is paused or has ended.
     */
    private TextView mPlayPauseButton;

    @Nullable
    private MediaPlayer mMediaPlayer;
    @Nullable
    private String mPath;
    private boolean mSurfaceReady;
    private boolean mPrepared;

    /**
     * Whether the stream has been played to its end.
     *
     * The player stays on the last frame when the stream ends instead of closing, so that it can be
     * played again from the beginning, which is what pressing play does.
     */
    private boolean mEnded;

    /** Whether the controls and the hints are shown, which is toggled by tapping the video. */
    private boolean mControlsVisible = true;

    private boolean mRegistered;

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    /** A button of the control bar, which runs an action when it is clicked. */
    private static final class ControlButton {
        @NonNull
        final TextView mView;
        @NonNull
        final Runnable mAction;

        ControlButton(@NonNull TextView view, @NonNull Runnable action) {
            mView = view;
            mAction = action;
        }
    }

    /**
     * Create a player that adds its views to the container of the terminal view.
     *
     * @param activity The activity that the player belongs to.
     */
    public TermuxPlayerView(@NonNull TermuxActivity activity) {
        mActivity = activity;

        mRoot = new FrameLayout(activity);
        mRoot.setBackgroundColor(0xFF000000);
        mRoot.setVisibility(View.GONE);
        mRoot.setClickable(true);

        mTextureView = new TextureView(activity);
        // The view takes the input focus while the player is shown, so that the input method is not
        // active and the text keys, like the space key, reach the activity instead of being kept by
        // the input method as text.
        mTextureView.setFocusable(true);
        mTextureView.setFocusableInTouchMode(true);
        mTextureView.setSurfaceTextureListener(this);
        mRoot.addView(mTextureView, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mMessageView = new TextView(activity);
        mMessageView.setTextColor(COLOR_FOREGROUND);
        mMessageView.setTextSize(16);
        mMessageView.setGravity(Gravity.CENTER);
        mRoot.addView(mMessageView, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mControls = buildControls();
        final FrameLayout.LayoutParams controlsParams = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        controlsParams.gravity = Gravity.BOTTOM;
        mRoot.addView(mControls, controlsParams);

        // A tap on the video shows or hides the controls. The listener is set on the surface and not
        // on the root of the player, so that a tap that lands on a button of the control bar is
        // dispatched to the button and is not turned into a toggle of the controls instead. The touch
        // is consumed so that it does not reach the terminal view, which would toggle the soft
        // keyboard and resize the activity, which makes the video shrink and grow.
        mTextureView.setClickable(true);
        mTextureView.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                toggleControls();
            }
            return true;
        });

        mContainer = new FrameLayout(activity);
        mContainer.addView(mRoot, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mContainer.setVisibility(View.GONE);
    }

    private final FrameLayout mContainer;

    /** Build the control bar and the hint bar that are shown over the video. */
    @NonNull
    private LinearLayout buildControls() {
        final LinearLayout bar = new LinearLayout(mActivity);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackgroundColor(COLOR_BAR_BACKGROUND);
        bar.setPadding(dp(8), dp(6), dp(8), dp(6));

        final LinearLayout buttons = new LinearLayout(mActivity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER_VERTICAL);

        addButton(buttons, "|◀", "Previous", this::playPrevious);
        addButton(buttons, "◀◀", "Rewind 10 seconds", () -> seekBy(-SEEK_STEP_MS));
        mPlayPauseButton = addButton(buttons, "❚❚", "Play or pause", this::togglePlayPause);
        addButton(buttons, "▶▶", "Forward 10 seconds", () -> seekBy(SEEK_STEP_MS));
        addButton(buttons, "▶|", "Next", this::playNext);
        addButton(buttons, "✕", "Close", this::close);

        bar.addView(buttons, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final LinearLayout progressRow = new LinearLayout(mActivity);
        progressRow.setOrientation(LinearLayout.HORIZONTAL);
        progressRow.setGravity(Gravity.CENTER_VERTICAL);

        mTimeView = new TextView(mActivity);
        mTimeView.setTextColor(COLOR_FOREGROUND_DIM);
        mTimeView.setTextSize(13);
        mTimeView.setText("0:00 / 0:00");
        progressRow.addView(mTimeView, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mProgressBar = new ProgressBar(mActivity, null, android.R.attr.progressBarStyleHorizontal);
        mProgressBar.setMax(1000);
        final LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        progressParams.setMarginStart(dp(8));
        progressParams.setMarginEnd(dp(8));
        progressRow.addView(mProgressBar, progressParams);

        bar.addView(progressRow, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // The hints are a single line, so that the control bar does not cover the video, and the full
        // list of the keys is shown by the button next to the time instead.
        final LinearLayout hintRow = new LinearLayout(mActivity);
        hintRow.setOrientation(LinearLayout.HORIZONTAL);
        hintRow.setGravity(Gravity.CENTER_VERTICAL);

        mHintView = new TextView(mActivity);
        mHintView.setTextColor(COLOR_FOREGROUND_DIM);
        mHintView.setTextSize(14);
        mHintView.setText("Tap the video for the controls  ·  Ctrl+C to stop  ·  "
            + "Tap \"?\" for the keyboard shortcuts");
        hintRow.addView(mHintView, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView helpButton = new TextView(mActivity);
        helpButton.setText("?");
        helpButton.setTextColor(COLOR_FOREGROUND);
        helpButton.setTextSize(16);
        helpButton.setGravity(Gravity.CENTER);
        helpButton.setClickable(true);
        helpButton.setFocusable(false);
        helpButton.setBackground(pressedBackground());
        helpButton.setContentDescription("Show the keyboard shortcuts");
        helpButton.setOnClickListener(view -> toggleKeyHelp());
        hintRow.addView(helpButton, new LinearLayout.LayoutParams(dp(40), dp(32)));

        bar.addView(hintRow, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        return bar;
    }

    /** Show or hide the list of the keys that the player handles. */
    private void toggleKeyHelp() {
        if (mKeyHelpView == null) {
            mKeyHelpView = new TextView(mActivity);
            mKeyHelpView.setTextColor(COLOR_FOREGROUND);
            mKeyHelpView.setTextSize(16);
            mKeyHelpView.setLineSpacing(dp(4), 1f);
            mKeyHelpView.setBackgroundColor(COLOR_BAR_BACKGROUND);
            mKeyHelpView.setPadding(dp(16), dp(14), dp(16), dp(14));
            mKeyHelpView.setText("Left / Right    Rewind or forward 10 seconds\n"
                + "Hold Left / Right    Seek faster the longer it is held\n"
                + "Up / Down    Previous or next stream\n"
                + "Space / Enter    Play or pause\n"
                + "Esc / Back    Close the player\n"
                + "Ctrl+C in the terminal    Stop and return to the shell\n"
                + "Play after the end    Play again from the start");
            mKeyHelpView.setClickable(true);
            mKeyHelpView.setOnClickListener(view -> toggleKeyHelp());

            final FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.gravity = Gravity.CENTER;
            mRoot.addView(mKeyHelpView, params);
        }

        mKeyHelpView.setVisibility(
            mKeyHelpView.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
    }

    /** Add a button to the control bar. */
    @NonNull
    private TextView addButton(@NonNull LinearLayout parent, @NonNull String label,
                               @NonNull String description, @NonNull Runnable action) {
        final TextView button = new TextView(mActivity);
        button.setText(label);
        button.setTextColor(COLOR_FOREGROUND);
        button.setTextSize(18);
        button.setGravity(Gravity.CENTER);
        button.setContentDescription(description);
        button.setClickable(true);
        button.setFocusable(false);
        button.setBackground(pressedBackground());

        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            0, dp(40), 1f);
        params.setMarginEnd(dp(2));
        parent.addView(button, params);

        button.setOnClickListener(view -> action.run());
        mButtons.add(new ControlButton(button, action));
        return button;
    }

    /** The background of a button, which shows that the button is clickable. */
    /**
     * The background of a button, which shows a highlight while the button is pressed so that it is
     * clear that the button can be tapped.
     */
    @NonNull
    private StateListDrawable pressedBackground() {
        final GradientDrawable normal = new GradientDrawable();
        normal.setColor(Color.TRANSPARENT);
        normal.setCornerRadius(dp(4));

        final GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(COLOR_BUTTON_PRESSED);
        pressed.setCornerRadius(dp(4));

        final StateListDrawable drawable = new StateListDrawable();
        drawable.addState(new int[]{android.R.attr.state_pressed}, pressed);
        drawable.addState(new int[]{}, normal);
        return drawable;
    }

    /** Convert a size in dp to pixels. */
    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
            mActivity.getResources().getDisplayMetrics()));
    }

    /** The view that holds the player, which is added to the layout of the activity. */
    @NonNull
    public View getView() {
        return mContainer;
    }

    /** Start listening for the broadcasts that control the player. */
    public void register() {
        if (mRegistered) return;

        final IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_PLAY);
        filter.addAction(ACTION_CLOSE);
        filter.addAction(ACTION_ENQUEUE);
        mActivity.registerReceiver(mReceiver, filter);
        mRegistered = true;
    }

    /** Stop listening for the broadcasts, and close the player. */
    public void unregister() {
        if (mRegistered) {
            try {
                mActivity.unregisterReceiver(mReceiver);
            } catch (Throwable ignored) {
            }
            mRegistered = false;
        }

        close();
    }

    /** Whether the player is currently shown. */
    public boolean isPlaying() {
        return mContainer.getVisibility() == View.VISIBLE;
    }

    /**
     * Whether the back gesture must close the player instead of leaving the activity.
     *
     * <p>The player is shown over the terminal in the same activity, so the back gesture would
     * otherwise leave the activity while the video is still playing.</p>
     */
    public boolean handleBackPressed() {
        if (!isPlaying()) return false;
        close();
        return true;
    }

    /**
     * Handle a key that controls the player.
     *
     * <p>The terminal keeps the input focus while the player is shown, so that its own keys keep
     * working, which means that the keys that the player handles are intercepted before they reach
     * the terminal. Only the keys that have a meaning for a video player are handled here, and every
     * other key, including the text and the control keys like Ctrl+C, is left to the terminal.</p>
     *
     * @param keyCode The code of the key that was pressed.
     * @param event The event of the key.
     * @return Returns {@code true} if the key was handled and must not reach the terminal.
     */
    public boolean handleKeyDown(int keyCode, @NonNull KeyEvent event) {
        if (!isPlaying()) return false;

        // The volume keys adjust the volume of the video, which is what they are pressed for while a
        // video is being watched. The terminal uses them as virtual control and function keys, so
        // they have to be handled here, before the terminal sees them, and handed to the audio
        // manager, which adjusts the volume and shows the volume panel.
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.getRepeatCount() == 0) adjustVolume(keyCode == KeyEvent.KEYCODE_VOLUME_UP);
            return true;
        }

        // A held arrow key seeks repeatedly, and the distance of each step grows the longer the key is
        // held, so that a long video can be moved through quickly. The first press moves by the normal
        // step, and the automatic repeats that follow move by a step that grows with every repeat.
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            final boolean forward = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
            if (event.getRepeatCount() == 0) {
                mSeekHoldStartTime = System.currentTimeMillis();
                seekBy(forward ? SEEK_STEP_MS : -SEEK_STEP_MS);
            } else {
                seekBy(forward ? getHeldSeekStep() : -getHeldSeekStep());
            }
            return true;
        }

        switch (keyCode) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
                // A held media key is a single action, not a repeated one.
                if (event.getRepeatCount() == 0) togglePlayPause();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                if (event.getRepeatCount() == 0) resume();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (event.getRepeatCount() == 0) pause();
                return true;
            case KeyEvent.KEYCODE_MEDIA_STOP:
                if (event.getRepeatCount() == 0) close();
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                seekBy(SEEK_STEP_MS);
                return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                seekBy(-SEEK_STEP_MS);
                return true;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                if (event.getRepeatCount() == 0) playNext();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                if (event.getRepeatCount() == 0) playPrevious();
                return true;

            case KeyEvent.KEYCODE_DPAD_UP:
                if (event.getRepeatCount() == 0) playPrevious();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (event.getRepeatCount() == 0) playNext();
                return true;

            // The space and enter keys toggle the playback, and the escape key closes the player,
            // which is what they do in the player of a desktop video application. A held key must not
            // toggle the playback repeatedly.
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (event.getRepeatCount() == 0) togglePlayPause();
                return true;
            case KeyEvent.KEYCODE_ESCAPE:
                if (event.getRepeatCount() == 0) close();
                return true;

            default:
                return false;
        }
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (ACTION_PLAY.equals(action)) {
                final String path = intent.getStringExtra(EXTRA_PATH);
                if (path == null || path.isEmpty()) {
                    Log.e(LOG_TAG, "The player was started without a path");
                    return;
                }
                play(path, intent.getStringExtra(EXTRA_TITLE));
            } else if (ACTION_ENQUEUE.equals(action)) {
                // A stream that is added while the player is shown is played right away, which is
                // what the `termux-player` command uses to play the next stream of a playlist.
                final String path = intent.getStringExtra(EXTRA_PATH);
                if (path != null && !path.isEmpty()) play(path, intent.getStringExtra(EXTRA_TITLE));
            } else if (ACTION_CLOSE.equals(action)) {
                close();
            }
        }
    };

    /** Show the player and start playing a file. */
    private void play(@NonNull String path, @Nullable String title) {
        // A stream that is already playing is replaced by the new one.
        releasePlayer();

        mPath = path;
        mPrepared = false;

        mMessageView.setText(title != null ? title : "Loading...");
        mMessageView.setVisibility(View.VISIBLE);
        mTimeView.setText("0:00 / 0:00");
        mProgressBar.setProgress(0);

        mContainer.setVisibility(View.VISIBLE);
        mRoot.setVisibility(View.VISIBLE);
        mContainer.bringToFront();

        mControlsVisible = true;
        mControls.setVisibility(View.VISIBLE);

        // The soft keyboard is hidden while the player is shown, since the player is watched and not
        // typed into, and since the keyboard resizes the activity, which makes the video shrink and
        // grow whenever the keyboard is shown or hidden.
        hideSoftKeyboard();

        // The player takes the input focus while it is shown, so that the input method is not active.
        // The keys of the player are intercepted by the activity before the focused view sees them,
        // but the input method keeps the text keys, like the space key, for itself while it is active,
        // so those keys would never reach the activity. The terminal keeps its keyboard state, so the
        // key that stops the playback still reaches the shell, see
        // {@link TermuxActivity#dispatchKeyEvent(KeyEvent)}.
        mTextureView.requestFocus();

        preparePlayerIfReady();
    }

    /** Hide the player and go back to the terminal. */
    public void close() {
        if (mContainer.getVisibility() != View.VISIBLE) return;

        releasePlayer();

        mHandler.removeCallbacks(mProgressUpdater);

        mContainer.setVisibility(View.GONE);
        mRoot.setVisibility(View.GONE);

        // The file of the stream is created by the `termux-player` command for this player, so it is
        // deleted when the player is closed.
        if (mPath != null) {
            try {
                //noinspection ResultOfMethodCallIgnored
                new File(mPath).delete();
            } catch (Throwable ignored) {
            }
            mPath = null;
        }

        // The terminal gets the input focus back, so that the keyboard and the extra keys work.
        if (mActivity.getTerminalView() != null) mActivity.getTerminalView().requestFocus();
    }

    /** Show or hide the controls and the hints, which is what a tap on the video does. */
    private void toggleControls() {
        mControlsVisible = !mControlsVisible;
        mControls.setVisibility(mControlsVisible ? View.VISIBLE : View.GONE);

        // The keyboard is hidden as well, since a tap on the video must not leave the activity
        // resized by a keyboard that was shown by an earlier tap.
        hideSoftKeyboard();
    }

    /**
     * Hide the soft keyboard, which is done when the player is shown and whenever the video is
     * tapped.
     *
     * <p>The keyboard resizes the activity when it is shown or hidden, which makes the video shrink
     * and grow, and the player is watched and not typed into. The terminal keeps the input focus so
     * that the keys of the terminal and Ctrl+C keep working, but the keyboard itself is not needed
     * while a video is playing.</p>
     */
    private void hideSoftKeyboard() {
        final InputMethodManager inputMethodManager =
            (InputMethodManager) mActivity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (inputMethodManager == null) return;

        final View terminalView = mActivity.getTerminalView();
        if (terminalView == null) return;

        inputMethodManager.hideSoftInputFromWindow(terminalView.getWindowToken(), 0);
    }

    @Override
    public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surfaceTexture, int width, int height) {
        mSurface = new Surface(surfaceTexture);
        mSurfaceReady = true;
        preparePlayerIfReady();
    }

    @Override
    public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surfaceTexture, int width, int height) {
    }

    @Override
    public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surfaceTexture) {
        mSurfaceReady = false;

        // The surface is released here and not by the framework, since the video is decoded into it
        // and the player has to be told that the surface is gone before it is released.
        releasePlayer();
        if (mSurface != null) {
            mSurface.release();
            mSurface = null;
        }

        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surfaceTexture) {
    }

    /** Start preparing the media player once both the surface and the file are available. */
    private void preparePlayerIfReady() {
        if (!mSurfaceReady || mMediaPlayer != null || mPath == null) return;

        final File file = new File(mPath);
        if (!file.exists() || !file.isFile() || file.length() < 1) {
            showMessage("The stream is empty or could not be read");
            return;
        }

        try {
            mMediaPlayer = new MediaPlayer();
            mMediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build());
            mMediaPlayer.setOnPreparedListener(this);
            mMediaPlayer.setOnCompletionListener(this);
            mMediaPlayer.setOnErrorListener(this);
            final Surface surface = mSurface;
            if (surface == null) return;

            mMediaPlayer.setSurface(surface);
            mMediaPlayer.setDataSource(mActivity, Uri.fromFile(file));
            mMediaPlayer.prepareAsync();
        } catch (Throwable t) {
            Log.e(LOG_TAG, "Failed to prepare the player: " + t.getMessage());
            showMessage("Failed to play the stream: " + t.getMessage());
            releasePlayer();
        }
    }

    @Override
    public void onPrepared(@NonNull MediaPlayer mediaPlayer) {
        mPrepared = true;
        mMessageView.setVisibility(View.GONE);
        mMediaPlayer.start();
        mEnded = false;
        updatePlayPauseButton();
        mHandler.post(mProgressUpdater);
    }

    @Override
    public void onCompletion(@NonNull MediaPlayer mediaPlayer) {
        // The player stays on the last frame of the stream instead of closing, so that the stream can
        // be played again from the beginning. Pressing play does that, which is why the button is
        // switched back to a play icon here.
        mEnded = true;
        updatePlayPauseButton();
        updateProgress();
    }

    @Override
    public boolean onError(@NonNull MediaPlayer mediaPlayer, int what, int extra) {
        Log.e(LOG_TAG, "The player failed with what=" + what + " extra=" + extra);
        showMessage("This stream could not be played (error " + what + "/" + extra + ")");
        releasePlayer();
        return true;
    }

    /** Show a message over the video, like when the stream cannot be played. */
    private void showMessage(@NonNull String message) {
        mMessageView.setText(message);
        mMessageView.setVisibility(View.VISIBLE);
    }

    /**
     * Change the volume of the media stream by one step.
     *
     * The step is applied to the media stream and not to the ringtone stream, so that the volume of
     * the video is adjusted and the volume panel of the system is shown, which is what pressing a
     * volume key does in any other media application.
     *
     * @param increase Whether the volume must be increased instead of decreased.
     */
    private void adjustVolume(boolean increase) {
        final AudioManager audioManager = (AudioManager) mActivity.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) return;

        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            increase ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI);
    }

    /** Pause the video, which is done when the activity is paused. */
    public void pause() {
        if (mMediaPlayer != null && mPrepared && mMediaPlayer.isPlaying()) {
            mMediaPlayer.pause();
            updatePlayPauseButton();
        }
    }

    /** Resume the video, which is done when the activity is resumed. */
    public void resume() {
        if (mMediaPlayer != null && mPrepared && !mMediaPlayer.isPlaying() && isPlaying()) {
            mMediaPlayer.start();
            updatePlayPauseButton();
        }
    }

    /** Toggle between playing and pausing the video. */
    public void togglePlayPause() {
        if (mMediaPlayer == null || !mPrepared) return;

        if (mMediaPlayer.isPlaying()) {
            mMediaPlayer.pause();
        } else {
            // A stream that has ended is played again from the beginning, which is what a play button
            // does once the video is over.
            if (mEnded) {
                mEnded = false;
                mMediaPlayer.seekTo(0);
            }
            mMediaPlayer.start();
        }

        updatePlayPauseButton();
    }

    /** Show a pause icon while the video is playing, and a play icon while it is not. */
    private void updatePlayPauseButton() {
        if (mPlayPauseButton == null) return;

        final boolean isPlaying = mMediaPlayer != null && mPrepared && mMediaPlayer.isPlaying();
        mPlayPauseButton.setText(isPlaying ? "❚❚" : "▶");
        mPlayPauseButton.setContentDescription(isPlaying ? "Pause" : "Play");
    }

    /**
     * Move the playback position by a number of milliseconds.
     *
     * @param deltaMs The number of milliseconds to move forward, or a negative number to move
     *                backward.
     */
    public void seekBy(long deltaMs) {
        if (mMediaPlayer == null || !mPrepared) return;

        final long duration = mMediaPlayer.getDuration();
        final long target = mMediaPlayer.getCurrentPosition() + deltaMs;
        mMediaPlayer.seekTo((int) Math.max(0, Math.min(target, duration)));
        updateProgress();
    }

    /**
     * The distance that a held arrow key moves for the current repeat.
     *
     * The step doubles every {@link #SEEK_ACCELERATION_START_MS} that the key has been held, up to
     * {@link #SEEK_STEP_MAX_MS}, so that the playback position moves faster the longer the key is
     * held, which is how the seek of a desktop video player behaves.
     */
    private long getHeldSeekStep() {
        if (mSeekHoldStartTime == 0) return SEEK_STEP_MS;

        final long heldMs = System.currentTimeMillis() - mSeekHoldStartTime;
        if (heldMs < SEEK_ACCELERATION_START_MS) return SEEK_STEP_MS;

        final long accelerations = heldMs / SEEK_ACCELERATION_START_MS;
        final long step = SEEK_STEP_MS << Math.min(accelerations - 1, 8);
        return Math.min(step, SEEK_STEP_MAX_MS);
    }

    /** Play the next stream, which the `termux-player` command adds to the playlist. */
    private void playNext() {
        // A stream is played by the command that owns it, so the player asks for the next one by
        // closing itself, which makes the command exit and the next one of the playlist run.
        showMessage("No next stream in the playlist");
        mHandler.postDelayed(() -> {
            if (isPlaying()) mMessageView.setVisibility(View.GONE);
        }, 1500);
    }

    /** Play the previous stream, which the `termux-player` command adds to the playlist. */
    private void playPrevious() {
        showMessage("No previous stream in the playlist");
        mHandler.postDelayed(() -> {
            if (isPlaying()) mMessageView.setVisibility(View.GONE);
        }, 1500);
    }

    /** Update the progress bar and the time of the playback. */
    private void updateProgress() {
        if (mMediaPlayer == null || !mPrepared) return;

        try {
            final int position = mMediaPlayer.getCurrentPosition();
            final int duration = mMediaPlayer.getDuration();
            if (duration > 0) {
                mProgressBar.setProgress((int) (1000L * position / duration));
                mTimeView.setText(formatTime(position) + " / " + formatTime(duration));
            }
        } catch (Throwable ignored) {
        }
    }

    private final Runnable mProgressUpdater = new Runnable() {
        @Override
        public void run() {
            if (!isPlaying()) return;
            updateProgress();
            mHandler.postDelayed(this, PROGRESS_UPDATE_INTERVAL_MS);
        }
    };

    /** Format a number of milliseconds as `m:ss`. */
    @NonNull
    private static String formatTime(int milliseconds) {
        final int totalSeconds = Math.max(0, milliseconds / 1000);
        return String.format(Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60);
    }

    private void releasePlayer() {
        if (mMediaPlayer == null) return;
        try {
            mMediaPlayer.reset();
            mMediaPlayer.release();
        } catch (Throwable ignored) {
        }
        mMediaPlayer = null;
        mPrepared = false;
    }
}
