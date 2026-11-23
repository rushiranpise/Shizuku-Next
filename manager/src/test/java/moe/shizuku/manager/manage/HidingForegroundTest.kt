package moe.shizuku.manager.manage

import android.app.usage.UsageEvents
import moe.shizuku.manager.manage.Hiding.TopActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the watch thinks is in front decides whether a target app sees the truth, and the way
 * this goes wrong is quiet: a pause read as the app leaving puts every setting back while the
 * app is still on screen.
 */
class HidingForegroundTest {

    private val resumed = UsageEvents.Event.ACTIVITY_RESUMED
    private val paused = UsageEvents.Event.ACTIVITY_PAUSED

    private fun fold(vararg events: Triple<Int, String, String?>, start: TopActivity? = null) =
        events.fold(start) { top, (type, pkg, cls) ->
            Hiding.foldForegroundEvent(top, type, pkg, cls)
        }

    @Test
    fun `an app that comes to the front is in front`() {
        val top = fold(
            Triple(paused, "launcher", ".Home"),
            Triple(resumed, "target", ".Splash")
        )
        assertEquals("target", top?.packageName)
    }

    @Test
    fun `a splash paused after the main screen resumed does not take the app away`() {
        val top = fold(
            Triple(resumed, "target", ".Splash"),
            Triple(resumed, "target", ".Main"),
            Triple(paused, "target", ".Splash")
        )
        assertEquals(TopActivity("target", ".Main"), top)
    }

    @Test
    fun `the activity on top pausing leaves nothing in front`() {
        val top = fold(
            Triple(resumed, "target", ".Main"),
            Triple(paused, "target", ".Main")
        )
        assertNull(top)
    }

    @Test
    fun `another app pausing does not touch what is in front`() {
        val top = fold(
            Triple(resumed, "target", ".Main"),
            Triple(paused, "manager", ".MainActivity")
        )
        assertEquals("target", top?.packageName)
    }

    @Test
    fun `without class names the package is compared, as before`() {
        val top = fold(
            Triple(resumed, "target", null),
            Triple(paused, "target", ".Main")
        )
        assertNull(top)
    }

    @Test
    fun `events other than resume and pause change nothing`() {
        val start = TopActivity("target", ".Main")
        val top = Hiding.foldForegroundEvent(
            start,
            UsageEvents.Event.ACTIVITY_STOPPED,
            "target",
            ".Splash"
        )
        assertEquals(start, top)
    }
}
