package moe.shizuku.manager.utils

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the Home screen decides to say about Shizuku's permission.
 *
 * The states themselves are Android's: this only maps them. What it has to get right is which of
 * two wrong answers is reported, because they are answered by different things - another package
 * owning the name is uninstalled, a declaration that has not been read back is waited out - and
 * which answers mean there is nothing to say.
 */
class PermissionOwnershipTest {

    private val ours = "moe.shizuku.privileged.api"
    private val copy = "moe.morphe.shizuku.privileged.api"

    @Test
    fun `our own permission with its group registered is nothing to say`() {
        assertEquals(PermissionOwnership.Ours, permissionOwnership(ours, ours, true))
    }

    @Test
    fun `another package owning the name is named`() {
        assertEquals(
            PermissionOwnership.Other(copy),
            permissionOwnership(ours, copy, true)
        )
    }

    @Test
    fun `another package is reported even when the group is missing too`() {
        // The actionable one wins: the copy has to be uninstalled either way.
        assertEquals(
            PermissionOwnership.Other(copy),
            permissionOwnership(ours, copy, false)
        )
    }

    @Test
    fun `a look-up that found no permission at all is not registered`() {
        assertEquals(PermissionOwnership.NotRegistered, permissionOwnership(ours, null, true))
    }

    @Test
    fun `our own permission without its group is not registered yet`() {
        assertEquals(PermissionOwnership.NotRegistered, permissionOwnership(ours, ours, false))
    }
}
