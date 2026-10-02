package moe.shizuku.manager.settings

import android.app.Dialog
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.R
import moe.shizuku.manager.databinding.BugReportDialogBinding
import moe.shizuku.manager.ktx.asLink
import moe.shizuku.manager.ktx.applyTemplateArgs
import moe.shizuku.manager.ui.screen.readDiagnostics
import moe.shizuku.manager.utils.CustomTabsHelper
import moe.shizuku.manager.worker.AdbStartWorker

class BugReportDialog : DialogFragment() {

    private lateinit var binding: BugReportDialogBinding

    /**
     * The same block the home screen's Copy button produces, read once while the dialog is open
     * and put at the top of both bodies.
     *
     * A report that arrives without it costs three questions before it can be acted on - which
     * Android, which build, is the service even up - and the person filing it is the one least
     * able to answer them, because the app is the only thing that knows. Read here rather than
     * handed in: this dialog is reached from Settings, which keeps none of that state.
     *
     * Empty only in the moment between the dialog appearing and the read finishing, where the
     * bodies fall back to the plain text rather than blocking the buttons on a binder call.
     */
    @Volatile
    private var diagnostics: String = ""

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        binding = BugReportDialogBinding.inflate(layoutInflater)

        // Releases and issues are this fork's; the wiki belongs to the fork this is based on,
        // because it documents the behaviour that came from there.
        val updateLink = getString(R.string.bug_report_dialog_link_update)
            .asLink("https://github.com/rushiranpise/Shizuku-Next/releases/latest")

        val wikiLink = getString(R.string.bug_report_dialog_link_wiki)
            .asLink("https://github.com/thedjchi/Shizuku/wiki#troubleshooting")

        val issuesLink = getString(R.string.bug_report_dialog_link_issues)
            .asLink("https://github.com/rushiranpise/Shizuku-Next/issues")

        binding.apply {
            updateText.applyTemplateArgs(updateLink)
            wikiText.applyTemplateArgs(wikiLink)
            issuesText.applyTemplateArgs(issuesLink)
            methodText.applyTemplateArgs("GitHub")
        }

        lifecycleScope.launch {
            diagnostics = withContext(Dispatchers.IO) { readDiagnostics(context) }
        }

        return MaterialAlertDialogBuilder(context)
            .setTitle(R.string.settings_report_bug)
            .setView(binding.root)
            .setPositiveButton("GitHub") { _, _ ->
                val url = "https://github.com/rushiranpise/Shizuku-Next/issues/new" +
                    "?body=" + Uri.encode(reportBody(context))
                CustomTabsHelper.launchUrlOrCopy(context, url)
            }
            .setNegativeButton(R.string.bug_report_dialog_button_email) { _, _ ->
                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse(
                    "mailto:" + context.getString(R.string.support_email) +
                    "?subject=" + Uri.encode("[ISSUE TITLE]") +
                    "&body=" + Uri.encode(reportBody(context))
                ))
                try {
                    context.startActivity(intent)
                    dismiss()
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(context, context.getString(R.string.toast_no_email_app), Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton(android.R.string.cancel) { dialog, _ ->
                dialog.cancel()
            }
            .create()
    }

    /**
     * What the issue and the email both start with: the diagnostics block, then the prompt to
     * describe the bug. The block goes first because it is the part that is already known, and
     * the part a report without it loses.
     */
    private fun reportBody(context: Context): String = buildString {
        if (diagnostics.isNotEmpty()) {
            appendLine(diagnostics)
            appendLine()
            appendLine("---")
            appendLine()
        }
        append(context.getString(R.string.bug_report_dialog_body_prompt))
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        val nm = requireContext().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(AdbStartWorker.NOTIFICATION_ID)
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (activity is BugReportDialogActivity) activity?.finish()
    }

}
