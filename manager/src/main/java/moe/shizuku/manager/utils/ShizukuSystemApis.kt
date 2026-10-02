package moe.shizuku.manager.utils

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.RemoteException
import rikka.hidden.compat.PackageManagerApis
import rikka.hidden.compat.PermissionManagerApis
import rikka.hidden.compat.UserManagerApis
import rikka.hidden.compat.util.SystemServiceBinder
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.server.util.InstalledPackagesCompat

object ShizukuSystemApis {

    init {
        SystemServiceBinder.setOnGetBinderListener {
            return@setOnGetBinderListener ShizukuBinderWrapper(it)
        }
    }

    private val users = arrayListOf<UserInfoCompat>()

    private fun getUsers(): List<UserInfoCompat> {
        return if (!ShizukuStateMachine.isRunning()) {
            arrayListOf(UserInfoCompat(UserHandleCompat.myUserId(), "Owner"))
        } else try {
            val list = UserManagerApis.getUsers(true, true, true)
            val users: MutableList<UserInfoCompat> = ArrayList<UserInfoCompat>()
            for (ui in list) {
                users.add(UserInfoCompat(ui.id, ui.name))
            }
            return users
        } catch (tr: Throwable) {
            arrayListOf(UserInfoCompat(UserHandleCompat.myUserId(), "Owner"))
        }
    }

    fun getUsers(useCache: Boolean = true): List<UserInfoCompat> {
        synchronized(users) {
            if (!useCache || users.isEmpty()) {
                users.clear()
                users.addAll(getUsers())
            }
            return users
        }
    }

    fun getUserInfo(userId: Int): UserInfoCompat {
        return getUsers(useCache = true).firstOrNull { it.id == userId } ?: UserInfoCompat(
            UserHandleCompat.myUserId(),
            "Unknown"
        )
    }

    fun getInstalledPackages(flags: Long, userId: Int): List<PackageInfo> {
        return if (!ShizukuStateMachine.isRunning()) {
            ArrayList()
        } else try {
            InstalledPackagesCompat.getInstalledPackages(flags, userId)
        } catch (tr: RemoteException) {
            throw RuntimeException(tr.message, tr)
        } catch (tr: ReflectiveOperationException) {
            throw RuntimeException(tr.message, tr)
        }
    }

    /**
     * Every installed package, as the shell sees it.
     *
     * Deliberately not [getInstalledPackages] above, which is a different thing wearing a similar
     * name: that one goes through `InstalledPackagesCompat`, whose first act is to try the
     * *caller's* package manager. Inside the server that is the system's, and privileged; inside
     * this app it is the app's own, and filtered - which is how a list of what is installed came
     * back missing 39 user apps while being able to explain none of them.
     *
     * `PackageManagerApis` always goes through the service this object wrapped in `init`, so the
     * answer is the shell's whichever process is asking.
     */
    fun getInstalledPackagesAsShell(flags: Long, userId: Int): List<PackageInfo> {
        if (!ShizukuStateMachine.isRunning()) return emptyList()
        return runCatching {
            PackageManagerApis.getInstalledPackagesNoThrow(flags, userId)
        }.getOrElse { emptyList() }
    }

    /**
     * One package's record, read as the shell.
     *
     * The companion to the list above, and needed for the same reason: a list the shell handed
     * over can name a package this app's own package manager cannot look up, so the detail behind
     * a row has to be read the way the row was.
     */
    fun getPackageInfoAsShell(packageName: String, flags: Long, userId: Int): PackageInfo? {
        if (!ShizukuStateMachine.isRunning()) return null
        return runCatching {
            PackageManagerApis.getPackageInfoNoThrow(packageName, flags, userId)
        }.getOrNull()
    }

    fun checkPermission(permName: String, pkgName: String, userId: Int): Int {
        return if (!ShizukuStateMachine.isRunning()) {
            PackageManager.PERMISSION_DENIED
        } else try {
            PermissionManagerApis.checkPermission(permName, pkgName, userId)
        } catch (tr: RemoteException) {
            throw RuntimeException(tr.message, tr)
        }
    }

    fun grantRuntimePermission(packageName: String, permissionName: String, userId: Int) {
        if (!ShizukuStateMachine.isRunning()) {
            return
        }
        try {
            PermissionManagerApis.grantRuntimePermission(packageName, permissionName, userId)
        } catch (tr: RemoteException) {
            throw RuntimeException(tr.message, tr)
        }
    }

    fun revokeRuntimePermission(packageName: String, permissionName: String, userId: Int) {
        if (!ShizukuStateMachine.isRunning()) {
            return
        }
        try {
            PermissionManagerApis.revokeRuntimePermission(packageName, permissionName, userId)
        } catch (tr: RemoteException) {
            throw RuntimeException(tr.message, tr)
        }
    }
}
