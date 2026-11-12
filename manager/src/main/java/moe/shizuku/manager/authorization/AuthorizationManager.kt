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
import rikka.shizuku.server.ServerConstants
import rikka.parcelablelist.ParcelableListSlice
import rikka.shizuku.Shizuku
import java.util.*

/** One process that attached to the Shizuku service. */
data class ClientProcessInfo(
    val uid: Int,
    val pid: Int,
    val packageName: String,
    val apiVersion: Int,
    val allowed: Boolean
)

object AuthorizationManager {

    private const val FLAG_ALLOWED = 1 shl 1
    private const val FLAG_DENIED = 1 shl 2
    private const val MASK_PERMISSION = FLAG_ALLOWED or FLAG_DENIED

    private fun getApplications(userId: Int): List<PackageInfo> {
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
            @Suppress("UNCHECKED_CAST")
            (ParcelableListSlice.CREATOR.createFromParcel(reply) as ParcelableListSlice<PackageInfo>).list!!
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    fun getPackages(exclude: List<String> = emptyList<String>()): List<PackageInfo> {
        val packages: MutableList<PackageInfo> = ArrayList()
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

                packages.add(pi)
            }
        } else {
            packages.addAll(getApplications(-1))
        }
        return packages
    }

    /**
     * The processes of [uids] that are attached to the service right now.
     *
     * This is the list behind the expandable rows in the Apps screen. It is not a list of
     * every process the app has running, and it does not persist anything.
     */
    fun getClientProcesses(uids: IntArray): List<ClientProcessInfo> {
        if (uids.isEmpty() || Shizuku.getBinder() == null) return emptyList()

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken("moe.shizuku.server.IShizukuService")
            data.writeInt(uids.size)
            data.writeIntArray(uids)
            Shizuku.getBinder()!!.transact(
                ServerConstants.BINDER_TRANSACTION_getClientProcesses, data, reply, 0
            )
            reply.readException()
            @Suppress("UNCHECKED_CAST")
            val slice = ParcelableListSlice.CREATOR.createFromParcel(reply) as ParcelableListSlice<Bundle>
            slice.list.orEmpty().mapNotNull { bundle ->
                val pid = bundle.getInt(ServerConstants.KEY_CLIENT_PROCESS_PID, 0)
                if (pid <= 0) return@mapNotNull null
                ClientProcessInfo(
                    uid = bundle.getInt(ServerConstants.KEY_CLIENT_PROCESS_UID, 0),
                    pid = pid,
                    packageName = bundle.getString(ServerConstants.KEY_CLIENT_PROCESS_PACKAGE) ?: "",
                    apiVersion = bundle.getInt(ServerConstants.KEY_CLIENT_PROCESS_API_VERSION, 0),
                    allowed = bundle.getBoolean(ServerConstants.KEY_CLIENT_PROCESS_ALLOWED, false)
                )
            }
        } catch (e: Throwable) {
            LOGGER.w(e, "getClientProcesses")
            emptyList()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /** Stops one attached client process. The app itself is not force-stopped. */
    fun stopClientProcess(uid: Int, pid: Int): Boolean {
        if (pid <= 0 || Shizuku.getBinder() == null) return false

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken("moe.shizuku.server.IShizukuService")
            data.writeInt(uid)
            data.writeInt(pid)
            Shizuku.getBinder()!!.transact(
                ServerConstants.BINDER_TRANSACTION_stopClientProcess, data, reply, 0
            )
            reply.readException()
            reply.readInt() == 1
        } catch (e: Throwable) {
            LOGGER.w(e, "stopClientProcess")
            false
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

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
}
