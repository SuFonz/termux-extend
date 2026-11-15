package com.termux.app;

import android.content.Context;
import android.content.res.AssetManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Installs the shell commands that the app ships in its assets into {@code $PREFIX/bin}.
 *
 * <p>The commands are not part of the bootstrap archive, which is downloaded once and only holds the
 * packages that Termux needs to run, so an asset that is only packaged into the APK is never
 * reachable from the shell. Without this installer a command would have to be copied to
 * {@code $PREFIX/bin} by hand after every build, which is easy to forget and leaves the shell running
 * an old version of the command.</p>
 *
 * <p>The installer runs when the app starts, and only writes a command when the version that it
 * installed before differs from the one in the assets, so a normal start does not touch the file
 * system. A command that the user has modified is never overwritten, since the file that is on disk
 * no longer matches the checksum that was recorded when it was installed.</p>
 */
final class TermuxScriptInstaller {

    private static final String LOG_TAG = "TermuxScriptInstaller";

    /** The commands that the app ships in its assets, in the order they are installed. */
    @NonNull
    private static final String[] ASSET_SCRIPTS = {
        "termux-player",
    };

    /**
     * The directory under {@code $PREFIX} that holds the checksums of the commands that were
     * installed, so that a command is only written again when the one in the assets changed.
     */
    private static final String STATE_DIR_PATH = TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/var/lib/termux-extend";

    private TermuxScriptInstaller() {
    }

    /**
     * Install the commands whose asset changed since they were installed.
     *
     * <p>It is safe to call this on every start, since nothing is written when the assets did not
     * change. The call is cheap even then, since it only reads the checksum files.</p>
     *
     * @param context The context to read the assets with.
     */
    static void installIfNeeded(@NonNull Context context) {
        final File prefixDir = TermuxConstants.TERMUX_PREFIX_DIR;
        if (!prefixDir.exists()) {
            // The bootstrap archive has not been extracted yet, so there is nowhere to install the
            // commands to. They are installed the next time the app starts, once the bootstrap is
            // there.
            Logger.logDebug(LOG_TAG, "Skipping the installation of the shell commands, $PREFIX does not exist yet");
            return;
        }

        final File binDir = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "bin");
        if (!binDir.exists() && !binDir.mkdirs()) {
            Logger.logError(LOG_TAG, "Failed to create the directory \"" + binDir.getAbsolutePath() + "\"");
            return;
        }

        final File stateDir = new File(STATE_DIR_PATH);
        if (!stateDir.exists() && !stateDir.mkdirs()) {
            Logger.logError(LOG_TAG, "Failed to create the directory \"" + stateDir.getAbsolutePath() + "\"");
            return;
        }

        for (String name : ASSET_SCRIPTS) {
            try {
                installScript(context, name, binDir, stateDir);
            } catch (Throwable t) {
                Logger.logError(LOG_TAG, "Failed to install the command \"" + name + "\": " + t.getMessage());
            }
        }
    }

    /** Install a single command if the one in the assets differs from the one that was installed. */
    private static void installScript(@NonNull Context context, @NonNull String name,
                                      @NonNull File binDir, @NonNull File stateDir) throws Throwable {
        final AssetManager assets = context.getAssets();
        final byte[] content;
        try (InputStream inputStream = assets.open(name)) {
            content = normalizeLineEndings(readFully(inputStream));
        }

        final String checksum = sha256(content);

        final File scriptFile = new File(binDir, name);
        final File checksumFile = new File(stateDir, name + ".sha256");

        // The command is not written again when the version that was installed is the one that the
        // assets hold, which is what makes the installer free on a normal start.
        final String installedChecksum = readChecksum(checksumFile);
        if (checksum.equals(installedChecksum) && scriptFile.exists()) return;

        // The command was modified by the user, so it is left alone. The file on disk no longer
        // matches the checksum that was recorded when it was installed, which is how a modified
        // command is told apart from an outdated one.
        if (installedChecksum != null && scriptFile.exists() && !installedChecksum.equals(sha256(scriptFile))) {
            Logger.logWarn(LOG_TAG, "Not installing the command \"" + name
                + "\" since it was modified after it was installed");
            return;
        }

        writeFile(scriptFile, content);
        // The command is executable for the user only, like the commands of the bootstrap archive.
        if (!scriptFile.setExecutable(true, true)) {
            Logger.logWarn(LOG_TAG, "Failed to make the command \"" + name + "\" executable");
        }

        writeFile(checksumFile, checksum.getBytes(StandardCharsets.UTF_8));

        Logger.logInfo(LOG_TAG, "Installed the command \"" + name + "\" to \"" + scriptFile.getAbsolutePath() + "\"");
    }

    /** Read the checksum of the version that was installed, or {@code null} if none was recorded. */
    @Nullable
    private static String readChecksum(@NonNull File checksumFile) {
        if (!checksumFile.exists()) return null;

        try (InputStream inputStream = new java.io.FileInputStream(checksumFile)) {
            return new String(readFully(inputStream), StandardCharsets.UTF_8).trim();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Read the whole content of a stream. */
    @NonNull
    private static byte[] readFully(@NonNull InputStream inputStream) throws Throwable {
        final java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream(
            Math.max(1024, inputStream.available()));
        final byte[] buffer = new byte[8192];
        int count;
        while ((count = inputStream.read(buffer)) > 0) output.write(buffer, 0, count);
        return output.toByteArray();
    }

    /**
     * Replace the `\r\n` line endings of a script with `\n`.
     *
     * <p>The shell cannot run a script whose first line ends with `\r\n`, since the carriage return
     * becomes part of the path of the interpreter, and the script fails with a "bad interpreter"
     * error. The line endings of a file depend on the editor and on the settings of the version
     * control system that it is checked out with, so they are normalized here instead of relying on
     * the file being stored with the right ones.</p>
     */
    @NonNull
    private static byte[] normalizeLineEndings(@NonNull byte[] content) {
        final java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream(content.length);
        for (int i = 0; i < content.length; i++) {
            final byte value = content[i];
            if (value == '\r' && i + 1 < content.length && content[i + 1] == '\n') continue;
            output.write(value);
        }
        return output.toByteArray();
    }

    /** Write a file, replacing the one that is there. */
    private static void writeFile(@NonNull File file, @NonNull byte[] content) throws Throwable {
        try (OutputStream outputStream = new FileOutputStream(file, false)) {
            outputStream.write(content);
            outputStream.flush();
        }
    }

    /** The checksum of a file, which is what tells whether a command was modified after installing. */
    @NonNull
    private static String sha256(@NonNull File file) throws Throwable {
        try (InputStream inputStream = new java.io.FileInputStream(file)) {
            return sha256(readFully(inputStream));
        }
    }

    /** The hex encoded SHA-256 checksum of some bytes. */
    @NonNull
    private static String sha256(@NonNull byte[] content) throws Throwable {
        final MessageDigest digest = MessageDigest.getInstance("SHA-256");
        final byte[] hash = digest.digest(content);

        final StringBuilder builder = new StringBuilder(hash.length * 2);
        for (byte value : hash) builder.append(String.format(Locale.US, "%02x", value));
        return builder.toString();
    }

}
