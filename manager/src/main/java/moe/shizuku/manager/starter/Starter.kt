package moe.shizuku.manager.starter

import android.util.Log
import androidx.lifecycle.asFlow
import java.io.File
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuApplication
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.start.StartStatusReporter
import moe.shizuku.manager.utils.ShizukuStateMachine

private val app = ShizukuApplication.application

object Starter {

    private val starterFile = File(app.applicationInfo.nativeLibraryDir, "libshizuku.so")

    val userCommand: String = starterFile.absolutePath
    val adbCommand = "adb shell $userCommand"
    val internalCommand = "$userCommand --apk=${app.applicationInfo.sourceDir}"

    val serviceStartedMessage = "Service started, this window will be automatically closed in 3 seconds"

    suspend fun waitForBinder(log: ((String) -> Unit)? = null) {
        try {
            log?.invoke("\nWaiting for service. This may take up to 1 minute...")
            withTimeout(60_000) {
                ShizukuStateMachine.asFlow()
                    .first { it == ShizukuStateMachine.State.RUNNING }
            }
            log?.invoke(serviceStartedMessage)
        } catch (e: TimeoutCancellationException) {
            throw TimeoutException("Failed to receive binder within 1 minute")
        }

        // And then check that it stayed. The payload a device exploit runs stops the app it
        // borrowed once the server has started, so a server that goes down with that app
        // looks exactly like this from here: a binder arrives, the start is reported as
        // successful, and nothing works a moment later. Checked from its own scope because
        // this screen closes itself three seconds after the message above, which is before
        // anyone could have looked, and a check tied to it would be cancelled with it.
        CoroutineScope(Dispatchers.IO).launch {
            delay(10_000)
            ShizukuStateMachine.update()

            when {
                ShizukuStateMachine.isRunning() ->
                    log?.invoke("\nThe service is still running.\n")

                // Stopped on purpose is not a service that went away, and the card already
                // says so: this is the state every deliberate Stop leaves behind.
                ShizukuSettings.getManuallyStopped() ->
                    log?.invoke("\nThe service was stopped again.\n")

                // A background start (boot, the watchdog, an app asking for the binder) has
                // no screen to report on, and the watchdog is what acts on a service that
                // stops by itself: saying it on the home card there would be an alarm about
                // something nobody asked about.
                log == null -> Log.i(
                    AppConstants.TAG,
                    "The service started and then went away again"
                )

                else -> {
                    log.invoke("\nThe service started and then went away again.\n")
                    StartStatusReporter.failed(
                        app.getString(R.string.start_failed_binder_went_away)
                    )
                }
            }
        }
    }

}
