package moe.shizuku.manager.authorization

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Parcel
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.Manifest
import moe.shizuku.manager.utils.Logger.LOGGER
import moe.shizuku.manager.utils.ShizukuSystemApis
import rikka.parcelablelist.ParcelableListSlice
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuApiConstants
import rikka.shizuku.server.ManagerAppEntry
import rikka.shizuku.server.ServerConstants
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/** One privileged resource the server created for a temporary grant. */
data class TemporaryEntry(
    val kind: Int,
    val pid: Int,
    val sessionPid: Int,
    val label: String
)

/** One client process holding a temporary grant. */
data class TemporarySession(
    val pid: Int,
    val since: Long,
    /** The holder's command line, e.g. "rish"; empty when it could not be read. */
    val cmdline: String,
    val entries: List<TemporaryEntry>
)

/** An app with a temporary grant and/or privileged resources still running for it. */
data class TemporaryApp(
    val uid: Int,
    val since: Long,
    val shells: Int,
    val services: Int,
    val hosts: Int,
    val sessions: List<TemporarySession>,
    /** Resources whose session is gone, e.g. a daemon user service. */
    val unattributed: List<TemporaryEntry>
) {
    val total: Int get() = shells + services + hosts
}

object AuthorizationManager {

    private const val FLAG_ALLOWED = 1 shl 1
    private const val FLAG_DENIED = 1 shl 2
    private const val MASK_PERMISSION = FLAG_ALLOWED or FLAG_DENIED

    /** uid -> package, for uids that are not in the server's app list (e.g. Termux). */
    private val packageCache = ConcurrentHashMap<Int, PackageInfo>()

    /**
     * Resolve a uid to a package once per manager process. The temporary view asks this per
     * card and on every recomposition, and the answer does not change while the app runs.
     */
    fun packageForUid(context: Context, uid: Int): PackageInfo? {
        packageCache[uid]?.let { return it }
        val pi = runCatching {
            context.packageManager.getPackagesForUid(uid)
                ?.firstOrNull()
                ?.let { context.packageManager.getPackageInfo(it, 0) }
        }.getOrNull() ?: return null
        packageCache[uid] = pi
        return pi
    }

    fun clearPackageCache() {
        packageCache.clear()
    }

    private fun getApplications(userId: Int): List<ManagerAppEntry> {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken("moe.shizuku.server.IShizukuService")
            data.writeInt(userId)
            try {
                Shizuku.getBinder()!!.transact(ServerConstants.BINDER_TRANSACTION_getApplications, data, reply, 0)
            } catch (e: Throwable) {
                throw RuntimeException(e)
            }
            reply.readException()
            if (supportsTemporary()) {
                @Suppress("UNCHECKED_CAST")
                (ParcelableListSlice.CREATOR.createFromParcel(reply) as ParcelableListSlice<ManagerAppEntry>).list!!
            } else {
                // An old running server still answers with the package-only slice. Wrap it so
                // the rest of the manager has one shape to work with; the flags are read the
                // old way, per app, in that case.
                @Suppress("UNCHECKED_CAST")
                val packages = (ParcelableListSlice.CREATOR.createFromParcel(reply) as ParcelableListSlice<PackageInfo>).list!!
                packages.map { pi ->
                    ManagerAppEntry(
                        pi,
                        pi.applicationInfo?.uid ?: -1,
                        0,
                        false,
                        0L,
                        0,
                        0,
                        0,
                        arrayListOf<Bundle>(),
                        arrayListOf<Bundle>()
                    )
                }
            }
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    fun getPackages(exclude: List<String> = emptyList<String>()): List<ManagerAppEntry> {
        val packages: MutableList<ManagerAppEntry> = ArrayList()
        if (Shizuku.isPreV11() || (Shizuku.getVersion() == 11 && Shizuku.getServerPatchVersion() < 3)) {
            val allPackages: MutableList<PackageInfo> = ArrayList()
            for (user in ShizukuSystemApis.getUsers(useCache = false)) {
                try {
                    allPackages.addAll(ShizukuSystemApis.getInstalledPackages((PackageManager.GET_META_DATA or PackageManager.GET_PERMISSIONS).toLong(), user.id))
                } catch (e: Throwable) {
                    LOGGER.w(e, "getInstalledPackages")
                }
            }
            for (pi in allPackages) {
                if (pi.packageName in exclude) continue
                if (pi.applicationInfo?.metaData?.getBoolean("moe.shizuku.client.V3_SUPPORT") != true) continue
                if (pi.requestedPermissions?.contains(Manifest.permission.API_V23) != true) continue

                packages.add(
                    ManagerAppEntry(
                        pi,
                        pi.applicationInfo?.uid ?: -1,
                        0,
                        false,
                        0L,
                        0,
                        0,
                        0,
                        arrayListOf<Bundle>(),
                        arrayListOf<Bundle>()
                    )
                )
            }
        } else {
            packages.addAll(getApplications(-1))
        }
        return packages
    }

    /** The persisted grant bits carried by the app-list entry. */
    fun granted(entry: ManagerAppEntry): Boolean =
        (entry.flags and FLAG_ALLOWED) == FLAG_ALLOWED

    fun granted(packageName: String, uid: Int): Boolean {
        return if (Shizuku.isPreV11()) {
            ShizukuSystemApis.checkPermission(Manifest.permission.API_V23, packageName, uid / 100000) == PackageManager.PERMISSION_GRANTED
        } else {
            (Shizuku.getFlagsForUid(uid, MASK_PERMISSION) and FLAG_ALLOWED) == FLAG_ALLOWED
        }
    }

    fun grant(packageName: String, uid: Int) {
        if (Shizuku.isPreV11()) {
            ShizukuSystemApis.grantRuntimePermission(packageName, Manifest.permission.API_V23, uid / 100000)
        } else {
            Shizuku.updateFlagsForUid(uid, MASK_PERMISSION, FLAG_ALLOWED)
        }
    }

    fun revoke(packageName: String, uid: Int) {
        if (Shizuku.isPreV11()) {
            ShizukuSystemApis.revokeRuntimePermission(packageName, Manifest.permission.API_V23, uid / 100000)
        } else {
            Shizuku.updateFlagsForUid(uid, MASK_PERMISSION, 0)
        }
    }

    /** Whether the running server knows about temporary sessions at all. */
    fun supportsTemporary(): Boolean =
        runCatching { Shizuku.getServerPatchVersion() >= 7 }.getOrDefault(false)

    /**
     * The temporary view of the app list, built from the entries the server already returned.
     *
     * <p>An app is included when it has a live temporary holder or a privileged resource still
     * running for it. The manager's own uid is dropped: it is always allowed, and has no row in
     * the app list anyway.
     */
    fun temporaryApps(entries: List<ManagerAppEntry>): List<TemporaryApp> =
        entries
            .filter { it.temporary || it.shells + it.services + it.hosts > 0 }
            .map { e ->
                val parsed = e.entries.mapNotNull { parseTemporaryEntry(it) }
                val bySession = parsed.groupBy { it.sessionPid }
                val sessions = e.sessions.map { s ->
                    val pid = s.getInt(ServerConstants.KEY_TEMPORARY_PID, 0)
                    TemporarySession(
                        pid = pid,
                        since = s.getLong(ServerConstants.KEY_TEMPORARY_SINCE, 0L),
                        cmdline = s.getString(ServerConstants.KEY_TEMPORARY_CMDLINE) ?: "",
                        entries = bySession[pid].orEmpty()
                    )
                }
                val sessionPids = sessions.map { it.pid }.toSet()
                TemporaryApp(
                    uid = e.uid,
                    since = e.since,
                    shells = e.shells,
                    services = e.services,
                    hosts = e.hosts,
                    sessions = sessions,
                    unattributed = parsed.filter { it.sessionPid !in sessionPids }
                )
            }
            // A shared uid can appear once per package; the temporary state is per uid.
            .distinctBy { it.uid }
            .filter { it.uid != android.os.Process.myUid() }

    private fun parseTemporaryEntry(bundle: Bundle): TemporaryEntry? {
        val kind = bundle.getInt(ServerConstants.KEY_TEMPORARY_KIND, 0)
        if (kind == 0) return null
        return TemporaryEntry(
            kind = kind,
            pid = bundle.getInt(ServerConstants.KEY_TEMPORARY_PID, 0),
            sessionPid = bundle.getInt(ServerConstants.KEY_TEMPORARY_SESSION_PID, 0),
            label = bundle.getString(ServerConstants.KEY_TEMPORARY_LABEL) ?: ""
        )
    }

    /**
     * Stops the privileged resources of a temporary grant. Best-effort: descendants that left
     * the session, or that dropped to another uid, may not be reachable. The client process is
     * never killed and the package is never force-stopped.
     */
    fun killTemporary(uid: Int, sessionPid: Int = 0, kind: Int = 0, pid: Int = 0) {
        if (!supportsTemporary()) return
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ShizukuApiConstants.BINDER_DESCRIPTOR)
            data.writeInt(uid)
            data.writeInt(sessionPid)
            data.writeInt(kind)
            data.writeInt(pid)
            Shizuku.getBinder()!!.transact(
                ServerConstants.BINDER_TRANSACTION_killTemporary, data, reply, 0
            )
            reply.readException()
        } catch (e: Throwable) {
            LOGGER.w(e, "killTemporary")
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}
