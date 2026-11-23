package moe.shizuku.manager.ktx

import android.content.Context
import android.content.ContextWrapper
import androidx.startup.AppInitializer
import androidx.startup.Initializer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The boot crash this guards against: WorkManager set up by hand right after the first unlock,
 * then the deferred androidx.startup provider installed and setting it up a second time, which
 * WorkManager answers by throwing on the main thread. Going through [AppInitializer] records the
 * component, and the provider's own pass skips a component that is already recorded.
 */
class WorkManagerInitTest {

    class CountingInitializer : Initializer<Int> {
        override fun create(context: Context): Int = ++created

        override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()

        companion object {
            var created = 0
        }
    }

    // AppInitializer keeps the application context; the stub answers null for it.
    private val context: Context = object : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }

    @Test
    fun `a component set up here is not set up again by the startup pass`() {
        CountingInitializer.created = 0

        initializeOnce(context, CountingInitializer::class.java)
        // What the provider does once it is installed: the same AppInitializer, the same class.
        AppInitializer.getInstance(context).initializeComponent(CountingInitializer::class.java)

        assertEquals(1, CountingInitializer.created)
    }
}
