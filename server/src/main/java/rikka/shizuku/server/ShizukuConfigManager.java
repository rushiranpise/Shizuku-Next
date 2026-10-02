package rikka.shizuku.server;

import static rikka.shizuku.server.ServerConstants.PERMISSION;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.AtomicFile;

import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import kotlin.collections.ArraysKt;
import rikka.hidden.compat.PackageManagerApis;
import rikka.shizuku.server.util.Android17Compat;
import rikka.shizuku.server.util.InstalledPackagesCompat;
import rikka.shizuku.server.util.UsersCompat;
import rikka.shizuku.server.ktx.HandlerKt;

public class ShizukuConfigManager extends ConfigManager {

    private static final Gson GSON_IN = new GsonBuilder()
            .create();
    private static final Gson GSON_OUT = new GsonBuilder()
            .setVersion(ShizukuConfig.LATEST_VERSION)
            .create();

    private static final long WRITE_DELAY = 10 * 1000;

    /**
     * How android.os.UserHandle divides a UID into a user and an app id. Written out because the
     * server runs in a process where the class is not there to ask.
     */
    private static final int PER_USER_RANGE = 100000;

    private static final File FILE = new File("/data/user_de/0/com.android.shell/shizuku.json");
    private static final AtomicFile ATOMIC_FILE = new AtomicFile(FILE);

    public static ShizukuConfig load() {
        FileInputStream stream;
        try {
            stream = ATOMIC_FILE.openRead();
        } catch (FileNotFoundException e) {
            LOGGER.i("no existing config file " + ATOMIC_FILE.getBaseFile() + "; starting empty");
            return new ShizukuConfig();
        }

        ShizukuConfig config = null;
        try {
            config = GSON_IN.fromJson(new InputStreamReader(stream), ShizukuConfig.class);
        } catch (Throwable tr) {
            LOGGER.w(tr, "load config");
        } finally {
            try {
                stream.close();
            } catch (IOException e) {
                LOGGER.w("failed to close: " + e);
            }
        }
        if (config != null) return config;
        return new ShizukuConfig();
    }

    public static void write(ShizukuConfig config) {
        synchronized (ATOMIC_FILE) {
            FileOutputStream stream;
            try {
                stream = ATOMIC_FILE.startWrite();
            } catch (IOException e) {
                LOGGER.w("failed to write state: " + e);
                return;
            }

            try {
                String json = GSON_OUT.toJson(config);
                stream.write(json.getBytes());

                ATOMIC_FILE.finishWrite(stream);
                LOGGER.v("config saved");
            } catch (Throwable tr) {
                LOGGER.w(tr, "can't save %s, restoring backup.", ATOMIC_FILE.getBaseFile());
                ATOMIC_FILE.failWrite(stream);
            }
        }
    }

    private final Runnable mWriteRunner = new Runnable() {

        @Override
        public void run() {
            write(config);
        }
    };

    private final ShizukuConfig config;

    public ShizukuConfigManager() {
        this.config = load();

        boolean changed = false;

        if (config.packages == null) {
            config.packages = new ArrayList<>();
            changed = true;
        }

        // What this device has installed, read once, before a single record is removed: the loop
        // below is the only place that takes grants away, and this is the evidence it needs. A user
        // whose list cannot be read answers with an empty list, the same as a user with nothing
        // installed, so only a non-empty one counts as having read anything.
        Map<Integer, List<PackageInfo>> installed = new LinkedHashMap<>();
        for (int userId : UsersCompat.getUserIdsNoThrow()) {
            installed.put(userId, InstalledPackagesCompat.getInstalledPackagesNoThrow(PackageManager.GET_PERMISSIONS, userId));
        }

        for (ShizukuConfig.PackageEntry entry : new ArrayList<>(config.packages)) {
            if (entry.packages == null) {
                entry.packages = new ArrayList<>();
            }

            Set<String> packages = packagesStillThere(entry.uid, installed);
            if (packages == null) {
                // Nothing could be read for this UID, which is not the same as reading that it is
                // gone: dropping the record here would revoke its apps over a question that was
                // never answered.
                LOGGER.w("cannot tell whether uid %d still has packages; keeping its config", entry.uid);
                continue;
            }

            if (packages.isEmpty()) {
                LOGGER.i("remove config for uid %d since it has gone", entry.uid);
                config.packages.remove(entry);
                changed = true;
                continue;
            }

            boolean packagesChanged = true;

            for (String packageName : entry.packages) {
                if (packages.contains(packageName)) {
                    packagesChanged = false;
                    break;
                }
            }

            final int rawSize = entry.packages.size();
            Set<String> s = new LinkedHashSet<>(entry.packages);
            entry.packages.clear();
            entry.packages.addAll(s);
            final int shrunkSize = entry.packages.size();
            if (shrunkSize < rawSize) {
                LOGGER.w("entry.packages has duplicate! Shrunk. (%d -> %d)", rawSize, shrunkSize);
            }

            if (packagesChanged) {
                LOGGER.i("remove config for uid %d since the packages for it changed", entry.uid);
                config.packages.remove(entry);
                changed = true;
            }
        }

        for (List<PackageInfo> forUser : installed.values()) {
            for (PackageInfo pi : forUser) {
                if (pi == null
                        || pi.applicationInfo == null
                        || pi.requestedPermissions == null
                        || !ArraysKt.contains(pi.requestedPermissions, PERMISSION)) {
                    continue;
                }

                int uid = pi.applicationInfo.uid;
                boolean allowed;
                try {
                    allowed = Android17Compat.checkPermission(PERMISSION, uid) == PackageManager.PERMISSION_GRANTED;
                } catch (Throwable e) {
                    LOGGER.w("checkPermission");
                    continue;
                }

                List<String> packages = new ArrayList<>();
                packages.add(pi.packageName);

                updateLocked(uid, packages, ConfigManager.MASK_PERMISSION, allowed ? ConfigManager.FLAG_ALLOWED : 0);
                changed = true;
            }
        }

        if (changed) {
            scheduleWriteLocked();
        }
    }

    /**
     * The packages a UID still has, or null when nothing could be read about it.
     *
     * The server used to call the compat library's no-throw packages-for-UID here and treat an
     * empty list as an answer, which is how a lookup that never got through managed to remove a
     * record - and the record is the grant. The platform is asked in its throwing spelling
     * instead, so a call that throws is an answer about the reading rather than about the UID, and
     * the installed list of the UID's own user is read alongside it: the two together are what
     * [UidPackages] is handed to decide, and it hands back null when neither of them spoke.
     */
    @Nullable
    private static Set<String> packagesStillThere(int uid, Map<Integer, List<PackageInfo>> installed) {
        String[] lookup = null;
        try {
            lookup = PackageManagerApis.getPackagesForUid(uid);
        } catch (Throwable tr) {
            LOGGER.w(tr, "getPackagesForUid for uid %d", uid);
        }

        List<PackageInfo> forUser = installed.get(uid / PER_USER_RANGE);

        // Null rather than an empty list when the user's list could not be read: an empty
        // enumeration and a user with nothing installed look the same, and the difference is what
        // decides whether a record may be removed.
        List<String> named = null;
        if (forUser != null && !forUser.isEmpty()) {
            named = new ArrayList<>();
            for (PackageInfo pi : forUser) {
                if (pi != null && pi.applicationInfo != null && pi.packageName != null
                        && pi.applicationInfo.uid == uid) {
                    named.add(pi.packageName);
                }
            }
        }

        return UidPackages.of(lookup, named);
    }

    private void scheduleWriteLocked() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (HandlerKt.getWorkerHandler().hasCallbacks(mWriteRunner)) {
                return;
            }
        } else {
            HandlerKt.getWorkerHandler().removeCallbacks(mWriteRunner);
        }
        HandlerKt.getWorkerHandler().postDelayed(mWriteRunner, WRITE_DELAY);
    }

    private ShizukuConfig.PackageEntry findLocked(int uid) {
        for (ShizukuConfig.PackageEntry entry : config.packages) {
            if (uid == entry.uid) {
                return entry;
            }
        }
        return null;
    }

    @Nullable
    public ShizukuConfig.PackageEntry find(int uid) {
        synchronized (this) {
            return findLocked(uid);
        }
    }

    private void updateLocked(int uid, List<String> packages, int mask, int values) {
        ShizukuConfig.PackageEntry entry = findLocked(uid);
        if (entry == null) {
            entry = new ShizukuConfig.PackageEntry(uid, mask & values);
            config.packages.add(entry);
        } else {
            int newValue = (entry.flags & ~mask) | (mask & values);
            if (newValue == entry.flags) {
                return;
            }
            entry.flags = newValue;
        }
        if (packages != null) {
            for (String packageName : packages) {
                if (entry.packages.contains(packageName)) {
                    continue;
                }
                entry.packages.add(packageName);
            }
        }
        scheduleWriteLocked();
    }

    public void update(int uid, List<String> packages, int mask, int values) {
        synchronized (this) {
            updateLocked(uid, packages, mask, values);
        }
    }

    private void removeLocked(int uid) {
        ShizukuConfig.PackageEntry entry = findLocked(uid);
        if (entry == null) {
            return;
        }
        config.packages.remove(entry);
        scheduleWriteLocked();
    }

    public void remove(int uid) {
        synchronized (this) {
            removeLocked(uid);
        }
    }
}
