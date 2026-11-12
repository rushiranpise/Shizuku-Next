package rikka.shizuku.server;

import static android.Manifest.permission.WRITE_SECURE_SETTINGS;
import static rikka.shizuku.ShizukuApiConstants.ATTACH_APPLICATION_API_VERSION;
import static rikka.shizuku.ShizukuApiConstants.ATTACH_APPLICATION_PACKAGE_NAME;
import static rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_PERMISSION_GRANTED;
import static rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_SERVER_PATCH_VERSION;
import static rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_SERVER_SECONTEXT;
import static rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_SERVER_UID;
import static rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_SERVER_VERSION;
import static rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE;
import static rikka.shizuku.ShizukuApiConstants.REQUEST_PERMISSION_REPLY_ALLOWED;
import static rikka.shizuku.ShizukuApiConstants.REQUEST_PERMISSION_REPLY_IS_ONETIME;
import static rikka.shizuku.server.ServerConstants.PERMISSION;

import android.content.Context;
import android.content.IContentProvider;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.ddm.DdmHandleAppName;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import kotlin.collections.ArraysKt;
import moe.shizuku.api.BinderContainer;
import moe.shizuku.common.util.BuildUtils;
import moe.shizuku.common.util.OsUtils;
import moe.shizuku.server.IShizukuApplication;
import rikka.hidden.compat.ActivityManagerApis;
import rikka.hidden.compat.DeviceIdleControllerApis;
import rikka.hidden.compat.PackageManagerApis;
import rikka.shizuku.server.util.Android17Compat;
import rikka.hidden.compat.UserManagerApis;
import rikka.parcelablelist.ParcelableListSlice;
import rikka.rish.RishConfig;
import rikka.rish.RishService;
import rikka.shizuku.ShizukuApiConstants;
import rikka.shizuku.server.api.IContentProviderUtils;
import rikka.shizuku.server.api.RemoteProcessHolder;
import rikka.shizuku.server.util.HandlerUtil;
import rikka.shizuku.server.util.InstalledPackagesCompat;
import rikka.shizuku.server.util.ProcessUtils;
import rikka.shizuku.server.util.UserHandleCompat;
import rikka.shizuku.server.util.UsersCompat;

public class ShizukuService extends Service<ShizukuUserServiceManager, ShizukuClientManager, ShizukuConfigManager> {

    public static final String MANAGER_APPLICATION_ID;

    static {
        String packageName = null;
        try {
            String apk = System.getenv("CLASSPATH");

            int lastSlash = apk.lastIndexOf(File.separatorChar);
            String parentDir = apk.substring(0, lastSlash);

            int secondLastSlash = parentDir.lastIndexOf(File.separatorChar);
            String dirName = parentDir.substring(secondLastSlash + 1);

            int dash = dirName.indexOf('-');
            if (dash > 0) {
                packageName = dirName.substring(0, dash);
            } else {
                packageName = dirName;
            }

            LOGGER.i("Manager package name is " + packageName);
        } catch (Throwable tr) {
            LOGGER.w("Couldn't get manager package name from CLASSPATH", tr);
        }
        MANAGER_APPLICATION_ID = packageName;
    }


    public static void main(String[] args) {
        // First, and before anything can fail: from here on the manager can read what this
        // process did, which it otherwise cannot tell apart from the server never running.
        ServerLog.phase("main entered, uid=" + Process.myUid() + ", sdk=" + Build.VERSION.SDK_INT);

        try {
            DdmHandleAppName.setAppName("shizuku_server", 0);
            RishConfig.setLibraryPath(System.getProperty("shizuku.library.path"));

            Looper.prepareMainLooper();
            new ShizukuService();
            ServerLog.phase("service created, entering the main looper");

            Looper.loop();
        } catch (Throwable tr) {
            // Nothing else can report this one: there is no manager connection to report it
            // over yet, and the logcat is not readable by the app that is waiting.
            ServerLog.mark("startup failed: " + Log.getStackTraceString(tr));
            throw tr;
        }
    }

    private static void waitSystemService(String name) {
        while (ServiceManager.getService(name) == null) {
            try {
                LOGGER.i("service " + name + " is not started, wait 1s.");
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                LOGGER.w(e.getMessage(), e);
            }
        }
    }

    public static ApplicationInfo getManagerApplicationInfo() {
        return Android17Compat.getApplicationInfo(MANAGER_APPLICATION_ID, 0, 0);
    }

    @SuppressWarnings({"FieldCanBeLocal"})
    private final Handler mainHandler = new Handler(Looper.myLooper());
    //private final Context systemContext = HiddenApiBridge.getSystemContext();
    private final ShizukuClientManager clientManager;
    private final ShizukuConfigManager configManager;
    /**
     * The manager's app id, read again whenever the manager may have been reinstalled.
     *
     * It used to be read once at start-up. A manager uninstalled and installed again while the
     * server kept running comes back under a new uid, and every call it made was then refused as
     * not coming from the manager - quietly: granting an app answered as if it had worked and
     * changed nothing, and its shell could not start. See [isManagerAppId].
     */
    private volatile int managerAppId;

    /** When each caller was last looked up because it did not match, for the rate limit. */
    private final Map<Integer, Long> managerRecheckedAt = new ConcurrentHashMap<>();

    /** The listener on the manager's APK, kept so a moved watch leaves no stale listener behind. */
    private final ApkChangedListener managerApkListener = () ->
            mainHandler.postDelayed(this::onManagerApkChanged, MANAGER_APK_SETTLE_MS);

    /** True while a watch that could not be registered is waiting to be retried. */
    private final AtomicBoolean managerApkRetryPending = new AtomicBoolean();

    private static final long MANAGER_RECHECK_INTERVAL_MS = 2000;

    public ShizukuService() {
        super();

        HandlerUtil.setMainHandler(mainHandler);

        LOGGER.i("starting server...");
        ServerLog.phase("starting server");

        waitSystemService("package");
        waitSystemService(Context.ACTIVITY_SERVICE);
        waitSystemService(Context.USER_SERVICE);
        waitSystemService(Context.APP_OPS_SERVICE);
        ServerLog.phase("system services are up");

        ApplicationInfo ai = getManagerApplicationInfo();
        if (ai == null) {
            System.exit(ServerConstants.MANAGER_APP_NOT_FOUND);
        }

        assert ai != null;
        managerAppId = UserHandleCompat.getAppId(ai.uid);

        configManager = getConfigManager();
        clientManager = getClientManager();

        watchManagerApk(ai.sourceDir);

        BinderSender.register(this);

        // Before the hand-over below, not after: from that point on apps are waiting on this
        // process, and a looper that stops answering while they wait is the case the manager's
        // watchdog cannot see. What it needs is a binder death, which is what this produces.
        ServerWatchdog.start(mainHandler);

        // The manager first. It is the one waiting on the start screen, and the clients are only
        // reached after listing every package that asks for the permission and starting the provider
        // of each, which on a phone with many of them is the longer part. Nothing depends on the
        // order: the two are independent hand-overs, and the manager is among the packages the
        // clients' pass lists as well.
        mainHandler.post(() -> {
            ServerLog.phase("handing the binder to the manager");
            sendBinderToManager();
            ServerLog.phase("handed the binder to the manager");
            ServerLog.phase("handing the binder to the clients");
            sendBinderToClient();
            ServerLog.phase("handed the binder to the clients");
            scheduleBinderSweepAfterUnlock();
        });
    }

    /**
     * When the user had not unlocked at start-up, hands the binder to the clients again once they
     * do.
     *
     * Before the unlock the package manager lists only direct-boot aware apps, and the provider of
     * an app that is not direct-boot aware cannot be started for a locked user, so the pass above
     * reaches few of the apps that want the binder. The ones that start after the unlock are caught
     * by [BinderSender] as they come up. An app that was already running before it - a launcher is
     * - is not: the sender remembers having seen its uid, and it will not send again until the uid
     * has gone. This is the pass for those, once, at the unlock.
     *
     * Polled, because a process with no context cannot hear the unlock broadcast, and it is a
     * question of a second or two whether it is found at once or not; nothing is waiting on it.
     */
    private void scheduleBinderSweepAfterUnlock() {
        // Asked of what the listing saw, not of now: the unlock may have come while the passes above
        // were running, with the partial list in them.
        if (!configManager.wasListedWhileLocked()) {
            return;
        }
        ServerLog.mark("user 0 had not unlocked at start-up: the clients get the binder again when it does");
        final long giveUpAt = SystemClock.uptimeMillis() + UNLOCK_SWEEP_GIVE_UP_MS;
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (Boolean.FALSE.equals(UsersCompat.isUserUnlocked(0))) {
                    if (SystemClock.uptimeMillis() < giveUpAt) {
                        mainHandler.postDelayed(this, UNLOCK_SWEEP_INTERVAL_MS);
                    } else {
                        ServerLog.mark("user 0 has not unlocked; no longer waiting to hand out binders");
                    }
                    return;
                }
                ServerLog.phase("user 0 unlocked: handing the binder to the clients again");
                // Not the snapshot: it was taken while locked, which is the reason for this pass.
                for (int userId : UsersCompat.getUserIdsNoThrow()) {
                    sendBinderToClient(ShizukuService.this, userId, null);
                }
                ServerLog.phase("handed the binder to the clients again");
            }
        }, UNLOCK_SWEEP_INTERVAL_MS);
    }

    private static final long UNLOCK_SWEEP_INTERVAL_MS = 1000;

    /** A phone that is not unlocked within this is not going to be, as far as a boot start is concerned. */
    private static final long UNLOCK_SWEEP_GIVE_UP_MS = 30 * 60 * 1000;

    /**
     * Watches the manager's APK, and keeps watching it.
     *
     * The observer fires once and stops, so it is armed again on the APK as it is now: an update
     * moves the APK to a new folder, and an observer left on the old one would see nothing after
     * that - which is how an uninstall that followed an update went unnoticed, and the server kept
     * running for a manager that was no longer there. The look is delayed a little because the
     * old APK goes before the package manager has finished recording the new one.
     */
    private void watchManagerApk(String sourceDir) {
        if (!startManagerApkWatch(sourceDir)) {
            retryManagerApkWatch();
            return;
        }
        mainHandler.postDelayed(() -> {
            ApplicationInfo now = getManagerApplicationInfo();
            if (now == null) {
                exitManagerMissing();
                return;
            }
            updateManagerAppId(now);
            if (!sourceDir.equals(now.sourceDir) || !new File(sourceDir).exists()) {
                watchManagerApk(now.sourceDir);
            }
        }, MANAGER_APK_SETTLE_MS);
    }

    /** Stops the old listener and arms [sourceDir], returning whether the folder was ready. */
    private boolean startManagerApkWatch(String sourceDir) {
        ApkChangedObservers.stop(managerApkListener);
        return ApkChangedObservers.start(sourceDir, managerApkListener);
    }

    /** Retries a watch registration after the APK folder appears, without stacking retries. */
    private void retryManagerApkWatch() {
        if (!managerApkRetryPending.compareAndSet(false, true)) {
            return;
        }
        LOGGER.w("manager APK is not ready to watch, retrying...");
        mainHandler.postDelayed(() -> {
            managerApkRetryPending.set(false);
            ApplicationInfo now = getManagerApplicationInfo();
            if (now == null) {
                exitManagerMissing();
                return;
            }
            updateManagerAppId(now);
            watchManagerApk(now.sourceDir);
        }, MANAGER_APK_SETTLE_MS);
    }

    /** Handles a one-shot APK change: refreshes the id and watches the current APK. */
    private void onManagerApkChanged() {
        ApplicationInfo now = getManagerApplicationInfo();
        if (now == null) {
            exitManagerMissing();
            return;
        }
        updateManagerAppId(now);
        watchManagerApk(now.sourceDir);
    }

    /** Leaves the server running only for an installed manager. */
    private void exitManagerMissing() {
        LOGGER.w("manager app is uninstalled in user 0, exiting...");
        System.exit(ServerConstants.MANAGER_APP_NOT_FOUND);
    }

    private static final long MANAGER_APK_SETTLE_MS = 1500;

    /**
     * Updates the stored manager app id from the package manager.
     *
     * Returns true when the id changed, so the caller can move the APK watch to the new path.
     */
    private boolean updateManagerAppId(ApplicationInfo ai) {
        int appId = UserHandleCompat.getAppId(ai.uid);
        if (appId != managerAppId) {
            LOGGER.i("manager app id changed from %d to %d", managerAppId, appId);
            managerAppId = appId;
            managerRecheckedAt.clear();
            return true;
        }
        return false;
    }

    /**
     * Whether a caller's uid is the manager's.
     *
     * A mismatch is checked against the package manager before it is believed, at most once per
     * caller per [MANAGER_RECHECK_INTERVAL_MS]: the APK watch is what normally notices a reinstall,
     * and this is what still answers correctly when it did not. The rate limit is there because
     * every call from a client app that is not the manager passes through here.
     */
    private boolean isManagerAppId(int callingUid) {
        int appId = UserHandleCompat.getAppId(callingUid);
        if (appId == managerAppId) {
            return true;
        }
        long now = SystemClock.uptimeMillis();
        Long recheckedAt = managerRecheckedAt.get(appId);
        if (recheckedAt != null && now - recheckedAt < MANAGER_RECHECK_INTERVAL_MS) {
            return false;
        }
        managerRecheckedAt.put(appId, now);
        ApplicationInfo ai = getManagerApplicationInfo();
        if (ai == null) {
            return false;
        }
        if (updateManagerAppId(ai)) {
            watchManagerApk(ai.sourceDir);
        }
        return appId == managerAppId;
    }

    @Override
    public ShizukuUserServiceManager onCreateUserServiceManager() {
        return new ShizukuUserServiceManager();
    }

    @Override
    public ShizukuClientManager onCreateClientManager() {
        return new ShizukuClientManager(getConfigManager());
    }

    @Override
    public ShizukuConfigManager onCreateConfigManager() {
        return new ShizukuConfigManager();
    }

    @Override
    public boolean checkCallerManagerPermission(String func, int callingUid, int callingPid) {
        return isManagerAppId(callingUid);
    }

    private int checkCallingPermission() {
        try {
            return ActivityManagerApis.checkPermission(ServerConstants.PERMISSION,
                    Binder.getCallingPid(),
                    Binder.getCallingUid());
        } catch (Throwable tr) {
            LOGGER.w(tr, "checkCallingPermission");
            return PackageManager.PERMISSION_DENIED;
        }
    }

    @Override
    public boolean checkCallerPermission(String func, int callingUid, int callingPid, @Nullable ClientRecord clientRecord) {
        if (isManagerAppId(callingUid)) {
            return true;
        }
        if (clientRecord == null && checkCallingPermission() == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        return false;
    }

    @Override
    public void exit() {
        enforceManagerPermission("exit");
        LOGGER.i("exit");
        // An exit somebody asked for is not a hang, and the monitor would otherwise be free to
        // report one in the moment between this call and the process going away.
        ServerWatchdog.stop();
        System.exit(0);
    }

    @Override
    public void attachUserService(IBinder binder, Bundle options) {
        enforceManagerPermission("func");

        super.attachUserService(binder, options);
    }

    @Override
    public void attachApplication(IShizukuApplication application, Bundle args) {
        if (application == null || args == null) {
            return;
        }

        String requestPackageName = args.getString(ATTACH_APPLICATION_PACKAGE_NAME);
        if (requestPackageName == null) {
            return;
        }

        ServerLog.mark("attachApplication from " + requestPackageName);
        int apiVersion = args.getInt(ATTACH_APPLICATION_API_VERSION, -1);

        int callingPid = Binder.getCallingPid();
        int callingUid = Binder.getCallingUid();
        boolean isManager;
        ClientRecord clientRecord = null;

        List<String> packages = PackageManagerApis.getPackagesForUidNoThrow(callingUid);
        if (!packages.contains(requestPackageName)) {
            LOGGER.w("Request package " + requestPackageName + "does not belong to uid " + callingUid);
            throw new SecurityException("Request package " + requestPackageName + "does not belong to uid " + callingUid);
        }

        isManager = MANAGER_APPLICATION_ID.equals(requestPackageName);

        // The existing client is taken, not just looked for: asking whether one is there and
        // adding one only when there is not left clientRecord null for a client that was already
        // registered, and the reply below dereferences it. A second attach from the same process
        // - a client that reconnected without its pid changing - threw that NPE before the
        // permission answer was written.
        clientRecord = clientManager.findClient(callingUid, callingPid);
        if (clientRecord == null) {
            synchronized (this) {
                clientRecord = clientManager.addClient(callingUid, callingPid, application, requestPackageName, apiVersion);
            }
            if (clientRecord == null) {
                LOGGER.w("Add client failed");
                return;
            }
        }

        LOGGER.d("attachApplication: %s %d %d", requestPackageName, callingUid, callingPid);

        int replyServerVersion = ShizukuApiConstants.SERVER_VERSION;
        if (apiVersion == -1) {
            // ShizukuBinderWrapper has adapted API v13 in dev.rikka.shizuku:api 12.2.0, however
            // attachApplication in 12.2.0 is still old, so that server treat the client as pre 13.
            // This finally cause transactRemote fails.
            // So we can pass 12 here to pretend we are v12 server.
            replyServerVersion = 12;
        }

        Bundle reply = new Bundle();
        reply.putInt(BIND_APPLICATION_SERVER_UID, OsUtils.getUid());
        reply.putInt(BIND_APPLICATION_SERVER_VERSION, replyServerVersion);
        reply.putString(BIND_APPLICATION_SERVER_SECONTEXT, OsUtils.getSELinuxContext());
        reply.putInt(BIND_APPLICATION_SERVER_PATCH_VERSION, ShizukuApiConstants.SERVER_PATCH_VERSION);
        if (!isManager) {
            reply.putBoolean(BIND_APPLICATION_PERMISSION_GRANTED, Objects.requireNonNull(clientRecord).allowed);
            reply.putBoolean(BIND_APPLICATION_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE, false);
        } else {
            // Granted to itself so a fresh install can switch wireless debugging on without
            // anyone at a computer. The compat layer says whether the platform was reached at
            // all: a signature it could not match is not the same thing as a platform that
            // refused, and either way this must not pass in silence.
            if (!Android17Compat.grantRuntimePermission(MANAGER_APPLICATION_ID,
                    WRITE_SECURE_SETTINGS, UserHandleCompat.getUserId(callingUid))) {
                LOGGER.w("could not grant WRITE_SECURE_SETTINGS to the manager");
            }
        }
        try {
            application.bindApplication(reply);
        } catch (Throwable e) {
            LOGGER.w(e, "attachApplication");
        }
    }

    @Override
    public void showPermissionConfirmation(int requestCode, @NonNull ClientRecord clientRecord, int callingUid, int callingPid, int userId) {
        ApplicationInfo ai = Android17Compat.getApplicationInfo(clientRecord.packageName, 0, userId);
        if (ai == null) {
            return;
        }

        PackageInfo pi = Android17Compat.getPackageInfo(MANAGER_APPLICATION_ID, 0, userId);
        UserInfo userInfo = UserManagerApis.getUserInfo(userId);
        boolean isWorkProfileUser = BuildUtils.atLeast30() ?
                "android.os.usertype.profile.MANAGED".equals(userInfo.userType) :
                (userInfo.flags & UserInfo.FLAG_MANAGED_PROFILE) != 0;
        if (pi == null && !isWorkProfileUser) {
            LOGGER.w("Manager not found in non work profile user %d. Revoke permission", userId);
            clientRecord.dispatchRequestPermissionResult(requestCode, false);
            return;
        }

        // Both halves of this have to name the manager the way it is installed now: the package
        // and the action the manifest declares under it. A hidden copy is a rename of both.
        Intent intent = new Intent(ServerConstants.requestPermissionAction(MANAGER_APPLICATION_ID))
                .setPackage(MANAGER_APPLICATION_ID)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
                .putExtra("uid", callingUid)
                .putExtra("pid", callingPid)
                .putExtra("requestCode", requestCode)
                .putExtra("applicationInfo", ai);
        ActivityManagerApis.startActivityNoThrow(intent, null, isWorkProfileUser ? 0 : userId);
    }

    @Override
    public void dispatchPermissionConfirmationResult(int requestUid, int requestPid, int requestCode, Bundle data) throws RemoteException {
        if (!isManagerAppId(Binder.getCallingUid())) {
            LOGGER.w("dispatchPermissionConfirmationResult called not from the manager package");
            return;
        }

        if (data == null) {
            return;
        }

        boolean allowed = data.getBoolean(REQUEST_PERMISSION_REPLY_ALLOWED);
        boolean onetime = data.getBoolean(REQUEST_PERMISSION_REPLY_IS_ONETIME);

        LOGGER.i("dispatchPermissionConfirmationResult: uid=%d, pid=%d, requestCode=%d, allowed=%s, onetime=%s",
                requestUid, requestPid, requestCode, Boolean.toString(allowed), Boolean.toString(onetime));

        List<ClientRecord> records = clientManager.findClients(requestUid);
        List<String> packages = new ArrayList<>();
        long now = System.currentTimeMillis();
        if (records.isEmpty()) {
            LOGGER.w("dispatchPermissionConfirmationResult: no client for uid %d was found", requestUid);
        } else {
            for (ClientRecord record : records) {
                packages.add(record.packageName);
                if (onetime) {
                    // "Once" belongs to the process that asked, not to every process of the
                    // package: a second rish session must get its own prompt.
                    if (record.pid == requestPid) {
                        record.allowed = allowed;
                        record.temporary = allowed;
                        record.since = allowed ? now : 0;
                    }
                } else {
                    record.allowed = allowed;
                    record.temporary = false;
                    record.since = 0;
                }
                if (record.pid == requestPid) {
                    record.dispatchRequestPermissionResult(requestCode, allowed);
                }
            }
        }

        if (!onetime) {
            configManager.update(requestUid, packages, ConfigManager.MASK_PERMISSION, allowed ? ConfigManager.FLAG_ALLOWED : ConfigManager.FLAG_DENIED);
        }

        if (!onetime && allowed) {
            int userId = UserHandleCompat.getUserId(requestUid);

            for (String packageName : PackageManagerApis.getPackagesForUidNoThrow(requestUid)) {
                PackageInfo pi = Android17Compat.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS, userId);
                if (pi == null || pi.requestedPermissions == null || !ArraysKt.contains(pi.requestedPermissions, PERMISSION)) {
                    continue;
                }

                int deviceId = 0;//Context.DEVICE_ID_DEFAULT
                // The answer is reported rather than assumed: a permission that was asked for
                // and never changed has to say so, or the app that asked is told it worked.
                if (allowed) {
                    if (!Android17Compat.grantRuntimePermission(packageName, PERMISSION, userId)) {
                        LOGGER.w("could not grant %s to %s", PERMISSION, packageName);
                    }
                } else {
                    if (!Android17Compat.revokeRuntimePermission(packageName, PERMISSION, userId)) {
                        LOGGER.w("could not revoke %s from %s", PERMISSION, packageName);
                    }
                }
            }
        }
    }

    private int  getFlagsForUidInternal(int uid, int mask, boolean allowRuntimePermission) {
        ShizukuConfig.PackageEntry entry = configManager.find(uid);
        if (entry != null) {
            return entry.flags & mask;
        }

        if (allowRuntimePermission && (mask & ConfigManager.MASK_PERMISSION) != 0) {
            int userId = UserHandleCompat.getUserId(uid);
            for (String packageName : PackageManagerApis.getPackagesForUidNoThrow(uid)) {
                PackageInfo pi = Android17Compat.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS, userId);
                if (pi == null || pi.requestedPermissions == null || !ArraysKt.contains(pi.requestedPermissions, PERMISSION)) {
                    continue;
                }

                try {
                    if (Android17Compat.checkPermission(PERMISSION, uid) == PackageManager.PERMISSION_GRANTED) {
                        return ConfigManager.FLAG_ALLOWED;
                    }
                } catch (Throwable e) {
                    LOGGER.w("getFlagsForUid");
                }
            }
        }
        return 0;
    }

    @Override
    public int getFlagsForUid(int uid, int mask) {
        if (!isManagerAppId(Binder.getCallingUid())) {
            LOGGER.w("updateFlagsForUid is allowed to be called only from the manager");
            return 0;
        }
        return getFlagsForUidInternal(uid, mask, true);
    }

    @Override
    public void updateFlagsForUid(int uid, int mask, int value) throws RemoteException {
        if (!isManagerAppId(Binder.getCallingUid())) {
            LOGGER.w("updateFlagsForUid is allowed to be called only from the manager");
            return;
        }

        int userId = UserHandleCompat.getUserId(uid);

        if ((mask & ConfigManager.MASK_PERMISSION) != 0) {
            boolean allowed = (value & ConfigManager.FLAG_ALLOWED) != 0;
            boolean denied = (value & ConfigManager.FLAG_DENIED) != 0;

            List<ClientRecord> records = clientManager.findClients(uid);
            for (ClientRecord record : records) {
                // A permanent decision replaces any temporary one on this process.
                record.temporary = false;
                record.since = 0;
                if (allowed) {
                    record.allowed = true;
                } else {
                    record.allowed = false;
                    ActivityManagerApis.forceStopPackageNoThrow(record.packageName, UserHandleCompat.getUserId(record.uid));
                    onPermissionRevoked(record.packageName);
                }
            }

            for (String packageName : PackageManagerApis.getPackagesForUidNoThrow(uid)) {
                PackageInfo pi = Android17Compat.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS, userId);
                if (pi == null || pi.requestedPermissions == null || !ArraysKt.contains(pi.requestedPermissions, PERMISSION)) {
                    continue;
                }

                // Answered rather than assumed: a grant this could not make leaves the record
                // saying allowed while the platform never heard about it, which is a permission
                // that is there until the next restart and gone after it, with nothing said
                // either way.
                if (allowed) {
                    if (!Android17Compat.grantRuntimePermission(packageName, PERMISSION, userId)) {
                        LOGGER.w("could not grant %s to %s", PERMISSION, packageName);
                    }
                } else if (!Android17Compat.revokeRuntimePermission(packageName, PERMISSION, userId)) {
                    LOGGER.w("could not revoke %s from %s", PERMISSION, packageName);
                }

                // TODO kill user service using
            }
        }

        configManager.update(uid, null, mask, value);
    }

    private void onPermissionRevoked(String packageName) {
        // TODO add runtime permission listener
        getUserServiceManager().removeUserServicesForPackage(packageName);
    }

    private ParcelableListSlice<ManagerAppEntry> getApplications(int userId) {
        Map<Integer, TemporaryData> temporary = buildTemporaryData();
        List<ManagerAppEntry> list = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        List<Integer> users = new ArrayList<>();
        if (userId == -1) {
            users.addAll(UsersCompat.getUserIdsNoThrow());
        } else {
            users.add(userId);
        }

        for (int user : users) {
            for (PackageInfo pi : InstalledPackagesCompat.getInstalledPackagesNoThrow(PackageManager.GET_META_DATA | PackageManager.GET_PERMISSIONS, user)) {
                if (Objects.equals(MANAGER_APPLICATION_ID, pi.packageName)) continue;
                if (pi.applicationInfo == null) continue;

                int uid = pi.applicationInfo.uid;
                ShizukuConfig.PackageEntry entry = configManager.find(uid);
                if (entry != null && entry.packages != null && !entry.packages.contains(pi.packageName))
                    continue;
                // Report the same effective flags as getFlagsForUidInternal(): a configured
                // entry wins, but a uid with no entry whose permission was granted at the
                // Android runtime level is still reported as allowed.
                int flags = getFlagsForUidInternal(uid, ConfigManager.MASK_PERMISSION, true);

                boolean isClient = pi.applicationInfo.metaData != null
                        && pi.applicationInfo.metaData.getBoolean("moe.shizuku.client.V3_SUPPORT", false)
                        && pi.requestedPermissions != null
                        && ArraysKt.contains(pi.requestedPermissions, PERMISSION);

                if (flags == 0 && !isClient) {
                    continue;
                }

                TemporaryData temp = temporary.get(uid);
                list.add(new ManagerAppEntry(
                        pi, uid, flags,
                        temp != null, temp != null ? temp.since : 0,
                        temp != null ? temp.shells : 0,
                        temp != null ? temp.services : 0,
                        temp != null ? temp.hosts : 0,
                        temp != null ? temp.sessions : new ArrayList<>(),
                        temp != null ? temp.entries : new ArrayList<>()
                ));
                seen.add(uid);
            }
        }

        // A uid whose package is gone but whose privileged resources are still running: keep it
        // visible so the manager can stop them.
        for (Map.Entry<Integer, TemporaryData> e : temporary.entrySet()) {
            int uid = e.getKey();
            if (seen.contains(uid)) continue;
            if (userId != -1 && UserHandleCompat.getUserId(uid) != userId) continue;
            TemporaryData temp = e.getValue();
            list.add(new ManagerAppEntry(
                    null, uid, 0, true, temp.since,
                    temp.shells, temp.services, temp.hosts,
                    temp.sessions, temp.entries
            ));
        }

        return new ParcelableListSlice<>(list);
    }

    /** The temporary state of one uid, folded into its app-list entry. */
    private static final class TemporaryData {
        long since;
        int shells;
        int services;
        int hosts;
        final ArrayList<Bundle> sessions = new ArrayList<>();
        final ArrayList<Bundle> entries = new ArrayList<>();
    }

    /**
     * The live temporary grants and the privileged resources they hold, keyed by uid.
     *
     * <p>An app is included when it has a live temporary holder or when a resource the server
     * created for it is still running, so a daemon that outlived its holder stays visible and
     * killable.
     */
    private Map<Integer, TemporaryData> buildTemporaryData() {
        Map<Integer, TemporaryData> map = new LinkedHashMap<>();

        for (ClientRecord record : clientManager.snapshotClients()) {
            if (!record.temporary || !record.allowed) {
                continue;
            }
            TemporaryData data = dataFor(map, record.uid);
            Bundle session = new Bundle();
            session.putInt(ServerConstants.KEY_TEMPORARY_PID, record.pid);
            session.putLong(ServerConstants.KEY_TEMPORARY_SINCE, record.since);
            session.putString(ServerConstants.KEY_TEMPORARY_CMDLINE, ProcessUtils.cmdline(record.pid));
            data.sessions.add(session);
            if (record.since > 0 && (data.since == 0 || record.since < data.since)) {
                data.since = record.since;
            }
        }

        for (RemoteProcessHolder holder : getRemoteProcesses()) {
            if (!holder.isAliveQuiet() || !holder.isTemporary()) {
                continue;
            }
            TemporaryData data = dataFor(map, holder.getUid());
            data.shells++;
            Bundle entry = new Bundle();
            entry.putInt(ServerConstants.KEY_TEMPORARY_KIND, ServerConstants.TEMPORARY_KIND_SHELL);
            entry.putInt(ServerConstants.KEY_TEMPORARY_PID, holder.getOwnerPid());
            entry.putInt(ServerConstants.KEY_TEMPORARY_SESSION_PID, holder.getOwnerPid());
            entry.putString(ServerConstants.KEY_TEMPORARY_LABEL, "shell");
            data.entries.add(entry);
        }

        for (UserServiceRecord record : getUserServiceManager().snapshotRecords()) {
            if (!record.isAlive()) {
                continue;
            }
            for (UserServiceRecord.Association association : record.snapshotAssociations()) {
                if (!association.temporary) {
                    continue;
                }
                TemporaryData data = dataFor(map, association.uid);
                data.services++;
                Bundle entry = new Bundle();
                entry.putInt(ServerConstants.KEY_TEMPORARY_KIND, ServerConstants.TEMPORARY_KIND_SERVICE);
                entry.putInt(ServerConstants.KEY_TEMPORARY_PID, record.pid);
                entry.putInt(ServerConstants.KEY_TEMPORARY_SESSION_PID, association.pid);
                entry.putString(ServerConstants.KEY_TEMPORARY_LABEL,
                        record.packageName != null ? record.packageName : "service");
                data.entries.add(entry);
            }
        }

        for (RishService.HostEntry entry : RishService.snapshotHosts()) {
            if (!entry.host.isAlive() || !entry.temporary) {
                continue;
            }
            TemporaryData data = dataFor(map, entry.uid);
            data.hosts++;
            Bundle item = new Bundle();
            item.putInt(ServerConstants.KEY_TEMPORARY_KIND, ServerConstants.TEMPORARY_KIND_HOST);
            item.putInt(ServerConstants.KEY_TEMPORARY_PID, entry.host.getPid());
            item.putInt(ServerConstants.KEY_TEMPORARY_SESSION_PID, entry.callingPid);
            item.putString(ServerConstants.KEY_TEMPORARY_LABEL, "rish");
            data.entries.add(item);
            if (entry.since > 0 && (data.since == 0 || entry.since < data.since)) {
                data.since = entry.since;
            }
        }

        return map;
    }

    private static TemporaryData dataFor(Map<Integer, TemporaryData> map, int uid) {
        TemporaryData data = map.get(uid);
        if (data == null) {
            data = new TemporaryData();
            map.put(uid, data);
        }
        return data;
    }

    /**
     * Stops the privileged resources of a temporary grant. Best-effort by design: descendants
     * that left the session, or that dropped to another uid, may not be reachable. The client
     * holder is never killed and the package is never force-stopped.
     */
    private void killTemporaryResources(int uid, int sessionPid, int kind, int pid) {
        if (kind == ServerConstants.TEMPORARY_KIND_ALL || kind == ServerConstants.TEMPORARY_KIND_SHELL) {
            for (RemoteProcessHolder holder : getRemoteProcesses()) {
                if (!holder.isTemporary() || holder.getUid() != uid) continue;
                if (sessionPid != 0 && holder.getOwnerPid() != sessionPid) continue;
                if (pid != 0 && holder.getOwnerPid() != pid) continue;
                holder.destroy();
            }
        }

        if (kind == ServerConstants.TEMPORARY_KIND_ALL || kind == ServerConstants.TEMPORARY_KIND_SERVICE) {
            for (UserServiceRecord record : getUserServiceManager().snapshotRecords()) {
                if (record.removeTemporaryAssociations(uid, sessionPid, pid)) {
                    getUserServiceManager().killUserService(record);
                }
            }
        }

        if (kind == ServerConstants.TEMPORARY_KIND_ALL || kind == ServerConstants.TEMPORARY_KIND_HOST) {
            for (RishService.HostEntry entry : RishService.snapshotHosts()) {
                if (!entry.temporary || entry.uid != uid) continue;
                if (sessionPid != 0 && entry.callingPid != sessionPid) continue;
                if (pid != 0 && entry.host.getPid() != pid) continue;
                ProcessUtils.killSession(entry.host.getPid());
                entry.host.kill();
            }
        }

        for (ClientRecord record : clientManager.findClients(uid)) {
            if (!record.temporary) continue;
            if (sessionPid != 0 && record.pid != sessionPid) continue;
            record.allowed = false;
            record.temporary = false;
            record.since = 0;
        }
    }

    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        //LOGGER.d("transact: code=%d, calling uid=%d", code, Binder.getCallingUid());
        if (code == ServerConstants.BINDER_TRANSACTION_getApplications) {
            data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR);
            int userId = data.readInt();
            ParcelableListSlice<ManagerAppEntry> result = isManagerAppId(Binder.getCallingUid())
                    ? getApplications(userId)
                    : new ParcelableListSlice<>(new ArrayList<>());
            reply.writeNoException();
            result.writeToParcel(reply, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
            return true;
        } else if (code == ServerConstants.BINDER_TRANSACTION_killTemporary) {
            data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR);
            int uid = data.readInt();
            int sessionPid = data.readInt();
            int kind = data.readInt();
            int pid = data.readInt();
            if (isManagerAppId(Binder.getCallingUid())) {
                killTemporaryResources(uid, sessionPid, kind, pid);
            }
            reply.writeNoException();
            return true;
        }
        return super.onTransact(code, data, reply, flags);
    }

    void sendBinderToClient() {
        // The packages the config manager already listed, when it did: listing them is the slow part
        // of this, and it was done a few milliseconds ago for the same flags.
        Map<Integer, List<PackageInfo>> snapshot = configManager.takeInstalledSnapshot();
        Collection<Integer> users = snapshot != null && !snapshot.isEmpty()
                ? snapshot.keySet()
                : UsersCompat.getUserIdsNoThrow();
        for (int userId : users) {
            sendBinderToClient(this, userId, snapshot != null ? snapshot.get(userId) : null);
        }
    }

    /**
     * @param listed the packages of the user, when they are already at hand; null or empty lists
     *     them again (an empty one is as likely an unreadable list as an empty phone).
     */
    private static void sendBinderToClient(Binder binder, int userId, List<PackageInfo> listed) {
        try {
            List<PackageInfo> all = listed != null && !listed.isEmpty()
                    ? listed
                    : InstalledPackagesCompat.getInstalledPackagesNoThrow(
                            PackageManager.GET_PERMISSIONS, userId);
            Stream<PackageInfo> packages = all
                .stream()
                .filter(pi -> pi != null && pi.requestedPermissions != null)
                .filter(pi -> ArraysKt.contains(pi.requestedPermissions, PERMISSION));

            LOGGER.i("sending binders");
            packages
                .parallel()
                .forEach(pi -> {
                    sendBinderToUserApp(binder, pi.packageName, userId);
                });
            LOGGER.i("sent binders");
        } catch (Throwable tr) {
            LOGGER.e("exception when call getInstalledPackages", tr);
        }
    }

    void sendBinderToManager() {
        sendBinderToManager(this);
    }

    private static void sendBinderToManager(Binder binder) {
        for (int userId : UsersCompat.getUserIdsNoThrow()) {
            sendBinderToManager(binder, userId);
        }
    }

    static void sendBinderToManager(Binder binder, int userId) {
        ServerLog.mark("handing the binder to " + MANAGER_APPLICATION_ID + " in user " + userId);
        boolean success = sendBinderToUserApp(binder, MANAGER_APPLICATION_ID, userId);
        ServerLog.mark(success
                ? "the manager took the binder"
                : "the manager did not take the binder: retrying, which force stops it first");
        if (!success) {
            // Nothing to retry in a user the manager is not installed in, and the retry force
            // stops it: on a device with a work profile that is a force stop, on every start,
            // of an app that was never in that user. Only the missing app is skipped, which
            // is exactly the case where the retry could not have worked anyway.
            boolean installed;
            try {
                installed = Android17Compat.getApplicationInfo(MANAGER_APPLICATION_ID, 0, userId) != null;
            } catch (Throwable tr) {
                // Not certain it is absent, so keep the retry rather than skip a user that
                // needs it.
                installed = true;
            }
            if (!installed) {
                ServerLog.mark("not retrying in user " + userId + ": the manager is not installed there");
                return;
            }

            // For unknown reason, sometimes this could happens
            // Kill Shizuku app and try again could work
            try {
                LOGGER.e("kill %s in user %d and try again", MANAGER_APPLICATION_ID, userId);
                ActivityManagerApis.forceStopPackageNoThrow(MANAGER_APPLICATION_ID, userId);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {}
                success = sendBinderToUserApp(binder, MANAGER_APPLICATION_ID, userId);
                if (success) {
                    LOGGER.e("retry succeeded");
                } else {
                    LOGGER.e("retry failed");
                }
            } catch (Throwable tr) {
                LOGGER.e(tr, "retry failed");
            }
        }
    }

    static boolean sendBinderToUserApp(Binder binder, String packageName, int userId) {
        try {
            DeviceIdleControllerApis.addPowerSaveTempWhitelistApp(packageName, 30 * 1000, userId,
                    316/* PowerExemptionManager#REASON_SHELL */, "shell");
        } catch (Throwable tr) {
            LOGGER.e(tr, "Failed to add %d:%s to power save temp whitelist", userId, packageName);
        }

        String name = packageName + ".shizuku";
        IContentProvider provider = null;

        /*
         When we pass IBinder through binder (and really crossed process), the receive side (here is system_server process)
         will always get a new instance of android.os.BinderProxy.

         In the implementation of getContentProviderExternal and removeContentProviderExternal, received
         IBinder is used as the key of a HashMap. But hashCode() is not implemented by BinderProxy, so
         removeContentProviderExternal will never work.

         Luckily, we can pass null. When token is token, count will be used.
         */
        IBinder token = null;

        try {
            provider = ActivityManagerApis.getContentProviderExternal(name, userId, token, name);
            if (provider == null) {
                LOGGER.e("provider is null %s %d", name, userId);
                return false;
            }
            if (!provider.asBinder().pingBinder()) {
                LOGGER.e("provider is dead %s %d", name, userId);
                return false;
            }

            Bundle extra = new Bundle();
            extra.putParcelable("moe.shizuku.privileged.api.intent.extra.BINDER", new BinderContainer(binder));

            Bundle reply = IContentProviderUtils.callCompat(provider, null, name, "sendBinder", null, extra);
            if (reply != null) {
                LOGGER.i("send binder to user app %s in user %d", packageName, userId);
                return true;
            } else {
                LOGGER.w("failed to send binder to user app %s in user %d", packageName, userId);
                return false;
            }
        } catch (Throwable tr) {
            LOGGER.e(tr, "failed to send binder to user app %s in user %d", packageName, userId);
            return false;
        } finally {
            if (provider != null) {
                try {
                    ActivityManagerApis.removeContentProviderExternal(name, token);
                } catch (Throwable tr) {
                    LOGGER.w(tr, "removeContentProviderExternal");
                }
            }
        }
    }

    // ------ Sui only ------

    @Override
    public void dispatchPackageChanged(Intent intent) throws RemoteException {

    }

    @Override
    public boolean isHidden(int uid) throws RemoteException {
        return false;
    }
}
