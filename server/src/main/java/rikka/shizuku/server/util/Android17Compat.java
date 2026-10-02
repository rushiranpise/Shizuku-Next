package rikka.shizuku.server.util;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.IBinder;
import android.os.ServiceManager;
import android.util.Log;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import rikka.hidden.compat.PackageManagerApis;
import rikka.hidden.compat.PermissionManagerApis;
import rikka.shizuku.server.ServerLog;

/**
 * The hidden calls that Android 17 changed, with every signature we know of tried in turn.
 *
 * Android 17 inserted a device id into the permission manager's methods, and the platforms
 * since have not agreed on where: this project passed the permission before the package for
 * `checkPermission` where the fork it was compared against passes the package first, and
 * `revokeRuntimePermission` takes an int device id on one and a string on the other. Guessing
 * one spelling and swallowing the failure is how a permission grant goes missing without a
 * word, which is the worst of the outcomes: the app is told the permission was granted, the
 * platform was never asked, and nothing anywhere says so.
 *
 * So each call is given a list of argument lists, most likely first, and the first that the
 * method accepts is used. A failure that is not a shape mismatch is reported instead of
 * retried, because a platform that refuses a call it understood is answering rather than
 * being asked wrongly. And when nothing matches, the server says so in the log the manager
 * shows, so the grant is not lost silently.
 */
public class Android17Compat {

    private static final String TAG = "ShizukuAndroid17Compat";
    private static final int DEVICE_ID_DEFAULT = 0;

    /** The device id the platform understands as "this device", as a string, and as a name. */
    private static final String DEVICE_ID_DEFAULT_STRING = "default";
    /** What the platform records as the caller of a permission change made from here. */
    private static final String CALLER = "shizuku";

    private static volatile Object sPackageManager;
    private static volatile boolean sPackageManagerFailed;

    private static volatile Object sPermissionManager;
    private static volatile boolean sPermissionManagerFailed;

    private static Object packageManager() {
        if (sPackageManager == null && !sPackageManagerFailed) {
            synchronized (Android17Compat.class) {
                if (sPackageManager == null && !sPackageManagerFailed) {
                    try {
                        IBinder binder = ServiceManager.getService("package");
                        Class<?> stub = Class.forName("android.content.pm.IPackageManager$Stub");
                        sPackageManager = stub.getDeclaredMethod("asInterface", IBinder.class)
                                .invoke(null, binder);
                    } catch (Throwable tr) {
                        sPackageManagerFailed = true;
                        Log.w(TAG, "no package manager to fall back on", tr);
                    }
                }
            }
        }
        return sPackageManager;
    }

    private static Object permissionManager() {
        if (sPermissionManager == null && !sPermissionManagerFailed) {
            synchronized (Android17Compat.class) {
                if (sPermissionManager == null && !sPermissionManagerFailed) {
                    try {
                        IBinder binder = ServiceManager.getService("permissionmgr");
                        Class<?> stub = Class.forName("android.permission.IPermissionManager$Stub");
                        sPermissionManager = stub.getDeclaredMethod("asInterface", IBinder.class)
                                .invoke(null, binder);
                    } catch (Throwable tr) {
                        sPermissionManagerFailed = true;
                        Log.w(TAG, "no permission manager to fall back on", tr);
                    }
                }
            }
        }
        return sPermissionManager;
    }

    public static PackageInfo getPackageInfo(String packageName, long flags, int userId) {
        try {
            return PackageManagerApis.getPackageInfoNoThrow(packageName, flags, userId);
        } catch (NoSuchMethodError e) {
            Object pm = packageManager();
            if (pm != null) {
                for (Method method : overloads(pm, "getPackageInfo")) {
                    Invocation outcome = invokeFirst(method, pm,
                            new Object[]{packageName, flags, userId},
                            new Object[]{packageName, flags, DEVICE_ID_DEFAULT, userId});
                    if (outcome.ran) {
                        return outcome.result instanceof PackageInfo ? (PackageInfo) outcome.result : null;
                    }
                }
                ServerLog.mark("could not read the package info of " + packageName
                        + ": no getPackageInfo signature on this platform accepted it");
            }
            return null;
        }
    }

    public static ApplicationInfo getApplicationInfo(String packageName, long flags, int userId) {
        try {
            return PackageManagerApis.getApplicationInfoNoThrow(packageName, flags, userId);
        } catch (NoSuchMethodError e) {
            Object pm = packageManager();
            if (pm != null) {
                for (Method method : overloads(pm, "getApplicationInfo")) {
                    Invocation outcome = invokeFirst(method, pm,
                            new Object[]{packageName, flags, userId},
                            new Object[]{packageName, flags, DEVICE_ID_DEFAULT, userId});
                    if (outcome.ran) {
                        return outcome.result instanceof ApplicationInfo ? (ApplicationInfo) outcome.result : null;
                    }
                }
                ServerLog.mark("could not read the application info of " + packageName
                        + ": no getApplicationInfo signature on this platform accepted it");
            }
            return null;
        }
    }

    /**
     * Whether [packageName] holds [permissionName].
     *
     * Both orders of the first two arguments are tried, because the two do not read the same
     * and getting it wrong answers "denied" for a permission that is held, which looks like a
     * permission problem rather than a programming one.
     */
    public static int checkPermission(String permissionName, String packageName, int userId) {
        try {
            return PermissionManagerApis.checkPermission(permissionName, packageName, userId);
        } catch (NoSuchMethodError e) {
            Object pm = permissionManager();
            if (pm == null) return android.content.pm.PackageManager.PERMISSION_DENIED;

            for (Method method : overloads(pm, "checkPermission")) {
                Invocation outcome = invokeFirst(method, pm,
                        new Object[]{permissionName, packageName, userId},
                        new Object[]{packageName, permissionName, userId});
                if (outcome.ran && outcome.result instanceof Integer) {
                    return (Integer) outcome.result;
                }
            }
            return android.content.pm.PackageManager.PERMISSION_DENIED;
        } catch (Throwable tr) {
            Log.w(TAG, "checkPermission failed", tr);
            return android.content.pm.PackageManager.PERMISSION_DENIED;
        }
    }

    /** Whether the process with [uid] holds [permissionName]. */
    public static int checkPermission(String permissionName, int uid) {
        try {
            return PermissionManagerApis.checkPermission(permissionName, uid);
        } catch (NoSuchMethodError e) {
            Object pm = permissionManager();
            if (pm == null) return android.content.pm.PackageManager.PERMISSION_DENIED;

            // The permission manager spells this one checkPermission(permission, deviceId, uid)
            // and the package manager spells it checkUidPermission(uid, permission); both are
            // tried because the one that exists is the one that answers.
            for (Method method : overloads(pm, "checkPermission")) {
                Invocation outcome = invokeFirst(method, pm,
                        new Object[]{permissionName, DEVICE_ID_DEFAULT, uid},
                        new Object[]{permissionName, uid});
                if (outcome.ran && outcome.result instanceof Integer) {
                    return (Integer) outcome.result;
                }
            }

            Object npm = packageManager();
            if (npm != null) {
                for (Method method : overloads(npm, "checkUidPermission")) {
                    Invocation outcome = invokeFirst(method, npm,
                            new Object[]{uid, permissionName},
                            new Object[]{permissionName, uid});
                    if (outcome.ran && outcome.result instanceof Integer) {
                        return (Integer) outcome.result;
                    }
                }
            }
            return android.content.pm.PackageManager.PERMISSION_DENIED;
        } catch (Throwable tr) {
            Log.w(TAG, "checkPermission for a uid failed", tr);
            return android.content.pm.PackageManager.PERMISSION_DENIED;
        }
    }

    /** Grants a runtime permission, and says whether the platform was reached. */
    public static boolean grantRuntimePermission(String packageName, String permissionName,
                                                 int userId) {
        try {
            PermissionManagerApis.grantRuntimePermission(packageName, permissionName, userId);
            return true;
        } catch (NoSuchMethodError e) {
            Object pm = permissionManager();
            if (pm == null) return false;

            for (Method method : overloads(pm, "grantRuntimePermission")) {
                if (invokeFirst(method, pm,
                        new Object[]{packageName, permissionName, DEVICE_ID_DEFAULT, userId},
                        new Object[]{packageName, permissionName, userId},
                        new Object[]{packageName, permissionName, DEVICE_ID_DEFAULT, userId, CALLER},
                        new Object[]{packageName, permissionName, DEVICE_ID_DEFAULT_STRING, userId, CALLER}
                ).ran) {
                    return true;
                }
            }

            ServerLog.mark("could not grant " + permissionName + " to " + packageName
                    + ": no grantRuntimePermission signature on this platform accepted it");
            return false;
        } catch (Throwable tr) {
            Log.w(TAG, "grantRuntimePermission failed", tr);
            return false;
        }
    }

    /** Revokes a runtime permission, and says whether the platform was reached. */
    public static boolean revokeRuntimePermission(String packageName, String permissionName,
                                                  int userId) {
        try {
            PermissionManagerApis.revokeRuntimePermission(packageName, permissionName, userId);
            return true;
        } catch (NoSuchMethodError e) {
            Object pm = permissionManager();
            if (pm == null) return false;

            for (Method method : overloads(pm, "revokeRuntimePermission")) {
                if (invokeFirst(method, pm,
                        new Object[]{packageName, permissionName, DEVICE_ID_DEFAULT, userId, CALLER},
                        new Object[]{packageName, permissionName, DEVICE_ID_DEFAULT_STRING, userId, CALLER},
                        new Object[]{packageName, permissionName, DEVICE_ID_DEFAULT, userId},
                        new Object[]{packageName, permissionName, userId}
                ).ran) {
                    return true;
                }
            }

            ServerLog.mark("could not revoke " + permissionName + " from " + packageName
                    + ": no revokeRuntimePermission signature on this platform accepted it");
            return false;
        } catch (Throwable tr) {
            Log.w(TAG, "revokeRuntimePermission failed", tr);
            return false;
        }
    }

    /** Every overload of [name], whatever its parameters: the shapes are tried, not guessed. */
    private static List<Method> overloads(Object target, String name) {
        List<Method> found = new ArrayList<>();
        for (Method method : target.getClass().getMethods()) {
            if (name.equals(method.getName())) found.add(method);
        }
        return found;
    }

    /**
     * What came of trying a method with a list of argument shapes.
     *
     * [ran] rather than a null result, because a call that ran and returned null is an answer
     * (a package that is not installed) and not the same thing as a call that was never made.
     */
    private static final class Invocation {
        boolean ran;
        Object result;
    }

    /**
     * Runs the first of [candidates] that the method accepts.
     *
     * IllegalArgumentException is how reflection reports a list that does not fit the method,
     * and that is the only case worth trying another spelling for.
     * InvocationTargetException means the call reached the platform and the platform refused
     * it, which is an answer rather than a wrong guess, so it is not retried as one.
     */
    private static Invocation invokeFirst(Method method, Object target, Object[]... candidates) {
        Invocation outcome = new Invocation();

        for (Object[] args : candidates) {
            if (method.getParameterTypes().length != args.length) continue;

            try {
                outcome.result = method.invoke(target, args);
                outcome.ran = true;
                return outcome;
            } catch (IllegalArgumentException ex) {
                // A shape this method does not take: the next candidate is for it.
            } catch (InvocationTargetException ex) {
                Log.w(TAG, method.getName() + " was refused by the platform", ex.getCause());
                return outcome;
            } catch (Throwable tr) {
                Log.w(TAG, method.getName() + " could not be invoked", tr);
                return outcome;
            }
        }

        return outcome;
    }
}
