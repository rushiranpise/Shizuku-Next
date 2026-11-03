package moe.shizuku.manager.starter

import android.util.Log
import moe.shizuku.manager.utils.AppStrings
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

    val serviceStartedMessage: String by lazy {
        AppStrings.get(R.string.start_msg_service_started)
    }

    suspend fun waitForBinder(log: ((String) -> Unit)? = null) {
        try {
            log?.invoke("\n" + AppStrings.get(R.string.start_msg_waiting))
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
                    log?.invoke("\n" + AppStrings.get(R.string.start_msg_still_running) + "\n")

                // A new start has begun since: the service is being replaced, not gone, and
                // saying it went away would blame this start for what the next one is doing.
                ShizukuStateMachine.get() == ShizukuStateMachine.State.STARTING -> Unit

                // Stopped on purpose is not a service that went away, and the card already
                // says so: this is the state every deliberate Stop leaves behind.
                ShizukuSettings.getManuallyStopped() ->
                    log?.invoke("\n" + AppStrings.get(R.string.start_msg_stopped_again) + "\n")

                // A background start (boot, the watchdog, an app asking for the binder) has
                // no screen to report on, and the watchdog is what acts on a service that
                // stops by itself: saying it on the home card there would be an alarm about
                // something nobody asked about.
                log == null -> Log.i(
                    AppConstants.TAG,
                    "The service started and then went away again"
                )

                else -> {
                    log.invoke("\n" + AppStrings.get(R.string.start_msg_went_away) + "\n")
                    StartStatusReporter.failed(
                        app.getString(R.string.start_failed_binder_went_away)
                    )
                }
            }
        }
    }

}
