package moe.shizuku.manager.authorization

import android.os.Build
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import kotlin.coroutines.resume

/**
 * Confirms a permanent Shizuku grant with the device's own authentication.
 *
 * A grant is the one action here that lets another app borrow the shell, so it should not be
 * possible with only an unlocked phone. The tile gate already asks whether the phone is locked;
 * this asks the user to prove who they are. Android's biometric prompt is used through
 * androidx.biometric so the same path works below Android 10 as well. The setting can turn
 * the check off for people who do not want it.
 */
object GrantAuthenticator {

    suspend fun authenticate(activity: FragmentActivity): Boolean {
        if (!ShizukuSettings.getRequireGrantAuthentication()) return true
        if (activity.isFinishing) return false

        val allowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_WEAK
        }

        if (BiometricManager.from(activity).canAuthenticate(allowed) !=
            BiometricManager.BIOMETRIC_SUCCESS
        ) {
            Toast.makeText(activity, R.string.grant_auth_unavailable, Toast.LENGTH_LONG).show()
            return false
        }

        return suspendCancellableCoroutine { continuation ->
            val prompt = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(
                        result: BiometricPrompt.AuthenticationResult
                    ) {
                        if (continuation.isActive) continuation.resume(true)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (continuation.isActive) continuation.resume(false)
                    }

                    override fun onAuthenticationFailed() {
                        // The prompt stays up and lets the user try again.
                    }
                }
            )

            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(activity.getString(R.string.grant_auth_title))
                .setSubtitle(activity.getString(R.string.grant_auth_subtitle))

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                info.setAllowedAuthenticators(allowed)
            } else {
                info.setAllowedAuthenticators(allowed)
                info.setNegativeButtonText(activity.getString(android.R.string.cancel))
            }

            prompt.authenticate(info.build())
            continuation.invokeOnCancellation { prompt.cancelAuthentication() }
        }
    }
}
