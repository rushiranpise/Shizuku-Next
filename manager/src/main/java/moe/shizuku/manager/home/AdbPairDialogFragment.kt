package moe.shizuku.manager.home

import android.annotation.SuppressLint
import android.app.Application
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.DialogInterface
import android.content.Intent
import android.os.Build.VERSION_CODES
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.*
import moe.shizuku.manager.databinding.AdbPairDialogBinding
import moe.shizuku.manager.utils.SettingsHelper
import moe.shizuku.manager.utils.SettingsPage
import java.net.ConnectException

@RequiresApi(VERSION_CODES.R)
class AdbPairDialogFragment : DialogFragment() {

    private lateinit var binding: AdbPairDialogBinding

    /** Run when the dialog closes, including a successful pairing. */
    var dismissAction: (() -> Unit)? = null

    private val viewModel: ViewModel by activityViewModels()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        binding = AdbPairDialogBinding.inflate(LayoutInflater.from(context))

        val builder = MaterialAlertDialogBuilder(context).apply {
            setTitle(R.string.dialog_adb_pairing_title)
            setView(binding.root)
            setNegativeButton(android.R.string.cancel, null)
            setPositiveButton(android.R.string.ok, null)
        }
        val dialog = builder.create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnShowListener { onDialogShow(dialog) }
        return dialog
    }

    private fun onDialogShow(dialog: AlertDialog) {
        binding.pairingCode.editText!!.doAfterTextChanged {
            binding.pairingCode.error = null
        }

        binding.pairingCode.error = null

        binding.developerOptions.setOnClickListener {
            SettingsHelper.launchOrHighlightWirelessDebugging(it.context)
        }

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val context = it.context
            val port = try {
                binding.port.editText!!.text.toString().toInt()
            } catch (e: Exception) {
                -1
            }
            if (port > 65535 || port < 1) {
                binding.port.error = context.getString(R.string.dialog_adb_invalid_port)
                return@setOnClickListener
            }

            val password = binding.pairingCode.editText!!.text.toString()

            viewModel.run(port, password)
        }

        // Discovery normally fills this in. When it finds nothing, the port shown in the
        // system pairing dialog can be typed instead, so the field stays usable either way.
        viewModel.port.observe(this) { port ->
            val field = binding.port.editText!!
            if (port > 0 && field.text.isNullOrEmpty()) {
                field.setText(port.toString())
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        val context = requireContext()

        viewModel.result.observe(this) {
            if (it == null) {
                dismissAllowingStateLoss()
            } else {
                when (it) {
                    is ConnectException -> {
                        binding.port.error = context.getString(R.string.cannot_connect_port)
                    }
                    is AdbInvalidPairingCodeException -> {
                        binding.pairingCode.error = context.getString(R.string.paring_code_is_wrong)
                    }
                    is AdbKeyException -> {
                        Toast.makeText(context, context.getString(R.string.adb_error_key_store), Toast.LENGTH_LONG)
                            .apply { setGravity(Gravity.CENTER, 0, 0) }.show()
                    }
                }
            }
        }
    }

    fun show(fragmentManager: FragmentManager) {
        if (fragmentManager.isStateSaved) return
        show(fragmentManager, javaClass.simpleName)
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        dismissAction?.invoke()
    }

    override fun getDialog(): AlertDialog? {
        return super.getDialog() as AlertDialog?
    }
}

@SuppressLint("NewApi")
class ViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = getApplication<Application>().applicationContext

    private val _result = MutableLiveData<Throwable?>()
    val result = _result as LiveData<Throwable?>

    private val _port = MutableLiveData<Int>()
    val port = _port as LiveData<Int>

    @Volatile
    private var resolvedHost: String = "127.0.0.1"

    private val adbMdns: AdbMdns = AdbMdns(appContext, AdbMdns.TLS_PAIRING) {
        if (it.second > 0) resolvedHost = it.first
        _port.postValue(it.second)
    }

    init {
        adbMdns.start()
    }

    fun run(port: Int, password: String) {
        GlobalScope.launch(Dispatchers.IO) {
            val host = resolvedHost

            val key = try {
                AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku")
            } catch (e: Throwable) {
                e.printStackTrace()
                _result.postValue(AdbKeyException(e))
                return@launch
            }

            AdbPairingClient(host, port, password, key).runCatching {
                start()
            }.onFailure {
                _result.postValue(it)
                it.printStackTrace()
            }.onSuccess {
                if (it) {
                    _result.postValue(null)
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        adbMdns.stop()
    }
}
