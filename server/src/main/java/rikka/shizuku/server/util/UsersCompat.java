package rikka.shizuku.server.util;

import android.content.pm.UserInfo;
import android.os.IBinder;
import android.os.ServiceManager;
import android.util.Log;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import rikka.shizuku.server.ServerLog;

/**
 * The users on the device, asked in every shape the platform has answered to.
 *
 * The bundled hidden-api compat library calls {@code IUserManager#getUsers(boolean, boolean,
 * boolean)} on everything from Android 11 up, and compiles its fallback to the one-boolean
 * spelling into the pre-11 branch only. AOSP has declared both shapes - one boolean up to Android
 * 10, three since - so a platform that answers the other one leaves that call throwing
 * {@link NoSuchMethodError}, which {@code getUserIdsNoThrow} catches as {@code Throwable} and
 * answers with {@code {0}}, without a word about it.
 *
 * The device then reads as single-user: every app in a work profile, a Secure Folder or a second
 * account is missing from the lists this server builds, and nothing anywhere says why. That is the
 * failure this class exists to make impossible, or at least visible - it is the same shape of bug
 * as the permission manager's device id, which Android17Compat handles the same way.
 *
 * So the call is made here instead, against the platform's own interface, with every known
 * signature tried in turn. A shape the method does not take is a reason to try the next; a
 * platform that refuses a call it understood is an answer rather than a wrong guess. And when
 * nothing matches, the server says so in the log the manager shows rather than reporting one user
 * and letting the rest be quietly missing.
 */
public final class UsersCompat {

    private static final String TAG = "ShizukuUsersCompat";

    /**
     * What {@code getUsers} has taken: the three-boolean spelling of every recent release, and the
     * one-boolean spelling that Android 10 declared.
     */
    private static final Object[] THREE_BOOLEANS = {true, true, true};
    private static final Object[] ONE_BOOLEAN = {true};

    private static volatile Object sUserManager;
    private static volatile boolean sUserManagerFailed;

    private UsersCompat() {
    }

    private static Object userManager() {
        if (sUserManager == null && !sUserManagerFailed) {
            synchronized (UsersCompat.class) {
                if (sUserManager == null && !sUserManagerFailed) {
                    try {
                        IBinder binder = ServiceManager.getService("user");
                        Class<?> stub = Class.forName("android.os.IUserManager$Stub");
                        sUserManager = stub.getDeclaredMethod("asInterface", IBinder.class)
                                .invoke(null, binder);
                    } catch (Throwable tr) {
                        sUserManagerFailed = true;
                        Log.w(TAG, "no user manager to ask", tr);
                    }
                }
            }
        }
        return sUserManager;
    }

    /**
     * The users on the device, or the primary user alone when nothing can be read.
     *
     * The same contract as the library's {@code getUserIdsNoThrow}, which the callers here were
     * written against: this never throws, and it never returns an empty list, because a caller
     * iterating users would then list nothing at all.
     */
    public static Collection<Integer> getUserIdsNoThrow() {
        Object um = userManager();
        if (um == null) {
            return Collections.singletonList(0);
        }

        for (Method method : overloads(um, "getUsers")) {
            Invocation outcome = invokeFirst(method, um, THREE_BOOLEANS, ONE_BOOLEAN);
            if (!outcome.ran) {
                continue;
            }

            List<?> users = outcome.result instanceof List
                    ? (List<?>) outcome.result
                    : Collections.emptyList();

            Set<Integer> ids = new LinkedHashSet<>();
            for (Object user : users) {
                if (user instanceof UserInfo) {
                    ids.add(((UserInfo) user).id);
                }
            }

            if (!ids.isEmpty()) {
                Log.i(TAG, "users on this device: " + ids);
                return ids;
            }
        }

        ServerLog.mark("could not read the users of this device: no getUsers signature accepted "
                + "the call, so apps belonging to any user other than the primary are not listed");
        return Collections.singletonList(0);
    }

    /** Every overload of [name], whatever its parameters: the shapes are tried, not guessed. */
    private static List<Method> overloads(Object target, String name) {
        List<Method> found = new ArrayList<>();
        for (Method method : target.getClass().getMethods()) {
            if (name.equals(method.getName())) {
                found.add(method);
            }
        }
        return found;
    }

    /**
     * What came of trying a method with a list of argument shapes.
     *
     * [ran] rather than a null result, because a call that ran and answered nothing is an answer -
     * a device with no other users - and not the same thing as a call that was never made.
     */
    private static final class Invocation {
        boolean ran;
        Object result;
    }

    /**
     * Runs the first of [candidates] that the method accepts.
     *
     * IllegalArgumentException is how reflection reports a list that does not fit the method, and
     * that is the only case worth trying another spelling for. InvocationTargetException means the
     * call reached the platform and the platform refused it, which is an answer rather than a
     * wrong guess, so it is not retried as one.
     */
    private static Invocation invokeFirst(Method method, Object target, Object[]... candidates) {
        Invocation outcome = new Invocation();

        for (Object[] args : candidates) {
            if (method.getParameterTypes().length != args.length) {
                continue;
            }

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
