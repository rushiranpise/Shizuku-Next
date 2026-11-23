package moe.shizuku.manager.ktx

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.startup.AppInitializer
import androidx.startup.Initializer
import androidx.work.WorkManager
import androidx.work.WorkManagerInitializer
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import moe.shizuku.manager.utils.Diag

/**
 * Makes WorkManager usable in this process, and says whether it is.
 *
 * This app is direct boot aware, so a process can start before the first unlock. In such a
 * process the provider that normally sets WorkManager up was not installed with the others: it is
 * not direct boot aware, and the platform installs it only at the unlock. Asking for WorkManager
 * before then throws. Every start that goes through the queue, and every cancel of one, asks here
 * first.
 *
 * Set up through androidx.startup and not with `WorkManager.initialize`. Setting it up by hand was
 * a crash at boot: a process woken just before the unlock set WorkManager up here, the deferred
 * provider was installed a moment later and set it up a second time, and WorkManager threw
 * "WorkManager is already initialized" on the main thread. [AppInitializer] records what it has
 * set up, and the provider's pass skips a component that is already recorded.
 *
 * False means there is still none. A caller that queues work has to say so and stop; a caller that
 * is only tidying up has nothing to tidy and can carry on.
 */
fun Context.ensureWorkManager(): Boolean {
    if (runCatching { WorkManager.getInstance(this) }.isSuccess) {
        return true
    }

    return runCatching {
        // Only after the unlock: before it, the storage its database lives in cannot be read, so
        // setting it up would fail for a reason the next attempt would not have either.
        check(isUserUnlockedCompat()) { "the user has not unlocked yet" }
        initializeOnce(applicationContext, WorkManagerInitializer::class.java)
        WorkManager.getInstance(this)
        true
    }.getOrElse {
        Diag.warn("WorkManager", "WorkManager is not available in this process", it)
        false
    }
}

/**
 * Runs [initializer] through androidx.startup, on the main thread.
 *
 * The main thread because that is where the provider runs its own pass, and that pass does not
 * take the lock this call does: two threads could otherwise both find the component missing and
 * set it up twice, which is the crash this exists to prevent. A caller already on the main thread
 * runs it in place; any other waits for it, up to [MAIN_THREAD_WAIT_MS].
 */
internal fun <T> initializeOnce(context: Context, initializer: Class<out Initializer<T>>): T {
    val setUp = Callable { AppInitializer.getInstance(context).initializeComponent(initializer) }
    val main = Looper.getMainLooper()
    if (main == null || Looper.myLooper() == main) return setUp.call()

    val task = FutureTask(setUp)
    Handler(main).post(task)
    return task.get(MAIN_THREAD_WAIT_MS, TimeUnit.MILLISECONDS)
}

private const val MAIN_THREAD_WAIT_MS = 5_000L
