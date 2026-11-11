package rikka.shizuku.server;

public class ServerConstants {

    public static final int MANAGER_APP_NOT_FOUND = 50;

    public static final String PERMISSION = "moe.shizuku.manager.permission.API_V23";

    /**
     * The action the manager's permission prompt answers, as [managerPackage] is installed.
     *
     * This used to be built from the package the manager was *built* with, which is the same
     * string only while the app runs under its original name. Stealth mode renames the package,
     * and the manifest rewrite covers the activity's action along with everything else - so a
     * hidden copy declares its own name + ".intent.action.REQUEST_PERMISSION" while the server
     * went on asking for the original spelling. The intent named a package that is installed and
     * an action nothing there answers, which is a request that reaches nobody: the app asking for
     * permission is left waiting with no prompt to answer it.
     *
     * The server knows the manager's package as it is now - it reads it off the APK it was started
     * from, see [ShizukuService.MANAGER_APPLICATION_ID] - so the request is built from that rather
     * than from moe.shizuku.server.BuildConfig.MANAGER_APPLICATION_ID.
     */
    public static String requestPermissionAction(String managerPackage) {
        return managerPackage + ".intent.action.REQUEST_PERMISSION";
    }

    public static final int BINDER_TRANSACTION_getApplications = 10001;

    /** Manager-only: stop the privileged resources of a temporary grant. */
    public static final int BINDER_TRANSACTION_killTemporary = 10003;

    // Bundle keys for the session/entry bundles inside ManagerAppEntry.
    public static final String KEY_TEMPORARY_SINCE = "since";
    public static final String KEY_TEMPORARY_CMDLINE = "cmdline";
    public static final String KEY_TEMPORARY_PID = "pid";
    public static final String KEY_TEMPORARY_SESSION_PID = "session_pid";
    public static final String KEY_TEMPORARY_KIND = "kind";
    public static final String KEY_TEMPORARY_LABEL = "label";

    public static final int TEMPORARY_KIND_ALL = 0;
    public static final int TEMPORARY_KIND_SHELL = 1;
    public static final int TEMPORARY_KIND_SERVICE = 2;
    public static final int TEMPORARY_KIND_HOST = 3;
}
