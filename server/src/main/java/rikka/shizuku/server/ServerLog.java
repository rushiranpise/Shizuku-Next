package rikka.shizuku.server;

import android.os.Process;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Appends this server's own account of starting up to the file the manager reads.
 *
 * The manager cannot see this process. A start whose server never got going and one whose
 * server ran and then failed to hand the binder over are the same 60 second timeout from the
 * manager's side, and the logcat this process writes to needs a reader an app does not have.
 * The starter writes the same file just before it forks this process, so anything written
 * here lands after the line that says the server was started, and the two lines together say
 * which happened.
 *
 * Best effort throughout: this cannot be allowed to affect a start. The server runs as root,
 * the system uid or the adb shell, depending on how it was started, and which of the two
 * directories it may write is not the same for all three.
 */
public class ServerLog {

    private static final String TAG = "ShizukuServerLog";

    /**
     * Both of the manager's external directories, media first: it exists to be reachable from
     * outside the app, so it is the one another process is allowed to write.
     */
    private static final String[] DIRECTORIES = {
            "/storage/emulated/0/Android/media/",
            "/storage/emulated/0/Android/data/",
    };

    private static File file;
    private static boolean resolved;

    public static void mark(String message) {
        try {
            File target = target();
            if (target == null) {
                return;
            }

            try (FileWriter writer = new FileWriter(target, true)) {
                writer.write(new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
                        .format(new Date()) + " server(" + Process.myPid() + "): " + message + "\n");
            }

            Log.i(TAG, message);
        } catch (Throwable tr) {
            Log.w(TAG, "Couldn't append to the manager's log", tr);
        }
    }

    /** The manager's log file, whichever of the two it was the starter managed to write. */
    private static File target() {
        if (resolved) {
            return file;
        }
        resolved = true;

        String pkg = ShizukuService.MANAGER_APPLICATION_ID;
        if (pkg == null || pkg.isEmpty()) {
            return null;
        }

        // Whichever exists is the one the starter wrote, and appending to it keeps one
        // account of the start in one place.
        for (String directory : DIRECTORIES) {
            File existing = new File(directory + pkg, "starter.log");
            if (existing.isFile()) {
                file = existing;
                return file;
            }
        }

        File media = new File(DIRECTORIES[0] + pkg, "starter.log");
        File parent = media.getParentFile();
        if (parent != null) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        file = media;
        return file;
    }
}
