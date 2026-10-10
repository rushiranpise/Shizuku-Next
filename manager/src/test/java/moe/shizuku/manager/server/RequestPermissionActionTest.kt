package moe.shizuku.manager.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import rikka.shizuku.server.ServerConstants

/**
 * The action the server starts the manager's permission prompt with, and the one a hidden copy's
 * manifest answers.
 *
 * Stealth mode renames the package and rewrites every manifest value that carried it, the
 * activity's action included, so from that point on the copy answers to its own name and to
 * nothing else. An action built from the name the app was built with therefore asks a package that
 * is installed and an action nothing in it answers - a permission request that reaches nobody, and
 * an app left waiting for a prompt that never appears. The server was doing exactly that, and this
 * is the half of the pair a device cannot check without being hidden and then asked for a
 * permission.
 */
class RequestPermissionActionTest {

    @Test
    fun `the action names the package the manager runs as`() {
        assertEquals(
            "moe.morphe.shizuku.privileged.api.intent.action.REQUEST_PERMISSION",
            ServerConstants.requestPermissionAction("moe.morphe.shizuku.privileged.api")
        )
    }

    @Test
    fun `a renamed copy is never asked for at its old name`() {
        assertNotEquals(
            "moe.shizuku.privileged.api.intent.action.REQUEST_PERMISSION",
            ServerConstants.requestPermissionAction("moe.morphe.shizuku.privileged.api")
        )
    }

    @Test
    fun `an install under the original name asks for that name`() {
        assertEquals(
            "moe.shizuku.privileged.api.intent.action.REQUEST_PERMISSION",
            ServerConstants.requestPermissionAction("moe.shizuku.privileged.api")
        )
    }
}
