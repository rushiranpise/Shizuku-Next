package moe.shizuku.manager.ktx

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The manifest strips `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, so registering a dynamic
 * receiver on Android 12 and below must not depend on that permission being granted.
 */
class RegisterNotExportedReceiverTest {

    @Test
    fun `pre-API-33 registration does not require DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`() {
        var registeredReceiver: BroadcastReceiver? = null
        var registeredFilter: IntentFilter? = null
        var usedPermissionOverload = false

        val context = object : ContextWrapper(null) {
            override fun getPackageName(): String = "moe.shizuku.privileged.api"

            override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
                PackageManager.PERMISSION_DENIED

            override fun registerReceiver(
                receiver: BroadcastReceiver?,
                filter: IntentFilter?,
            ): Intent? {
                registeredReceiver = receiver
                registeredFilter = filter
                return null
            }

            override fun registerReceiver(
                receiver: BroadcastReceiver?,
                filter: IntentFilter?,
                broadcastPermission: String?,
                scheduler: android.os.Handler?,
            ): Intent? {
                usedPermissionOverload = true
                return null
            }
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = Unit
        }
        val filter = IntentFilter(Intent.ACTION_SCREEN_ON)

        context.registerNotExportedReceiver(receiver, filter)

        assertSame(receiver, registeredReceiver)
        assertSame(filter, registeredFilter)
        assertEquals(false, usedPermissionOverload)
    }
}
