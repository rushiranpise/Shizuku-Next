@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package moe.shizuku.manager.ui.screen

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import moe.shizuku.manager.ui.component.PillButton
import moe.shizuku.manager.ui.component.PillButtonQuiet
import moe.shizuku.manager.R
import moe.shizuku.manager.stealth.Action
import moe.shizuku.manager.stealth.ApkType
import moe.shizuku.manager.stealth.StealthTutorialViewModel
import moe.shizuku.manager.stealth.UiState
import moe.shizuku.manager.stealth.MAX_DISPLAY_NAME_LENGTH
import moe.shizuku.manager.stealth.validateDisplayName
import moe.shizuku.manager.stealth.validatePackageName
import moe.shizuku.manager.ui.component.SegmentedColumn
import moe.shizuku.manager.ui.component.SegmentedListItem
import moe.shizuku.manager.utils.ApkUtils.ORIGINAL_PACKAGE_NAME
import moe.shizuku.manager.utils.ApkUtils.buildApkFilename
import moe.shizuku.manager.utils.ApkUtils.installPackage
import moe.shizuku.manager.utils.ApkUtils.uninstallPackage
import moe.shizuku.manager.utils.ApkUtils.wakeStub
import rikka.core.util.ClipboardUtils
import java.io.File

private const val codeSnippet = """
import android.content.Context
import rikka.shizuku.ShizukuProvider

private fun Context.shizukuPermission() =
    runCatching {
        packageManager.getPermissionInfo(ShizukuProvider.PERMISSION, 0)
    }.getOrNull()

fun Context.isShizukuInstalled() =
    shizukuPermission() != null

fun Context.getShizukuPackageName() =
    shizukuPermission()?.packageName
"""

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StealthScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: StealthTutorialViewModel = viewModel()
    val state by vm.uiState.observeAsState(UiState.Idle(Action.HIDE))

    var packageName by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var outDir by remember { mutableStateOf<Uri?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingUninstall by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) {
            outDir = tree
            vm.createApk(ApkType.CLONE)
        }
    }

    LaunchedEffect(state) {
        when (val s = state) {
            is UiState.Pending -> when (s.apkType) {
                ApkType.CLONE -> {
                    val dir = outDir
                    if (dir != null) {
                        runCatching { exportApk(context, dir, s.apk) }
                            .onFailure { error = it.message }
                            .onSuccess { pendingUninstall = true }
                    }
                    vm.refresh()
                }

                ApkType.STUB -> {
                    context.installPackage(s.apk) { ok, msg ->
                        vm.refresh()
                        if (ok) {
                            // Straight after the install, and not on first use: a stub that has
                            // never been started is stopped as far as the platform is concerned,
                            // and stops taking broadcasts - which for an automation app means
                            // silently doing nothing. See wakeStub.
                            wakeStub(context, ORIGINAL_PACKAGE_NAME)
                        } else {
                            error = msg ?: "Install failed"
                        }
                    }
                }
            }

            is UiState.Error -> {
                error = s.error.message
                vm.refresh()
            }

            else -> Unit
        }
    }

    val action = (state as? UiState.Idle)?.action ?: Action.HIDE
    val busy = state is UiState.Loading || state is UiState.Pending
    val packageNameError = packageName.validatePackageName()
    val displayNameError = displayName.validateDisplayName()

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.tools_stealth)) },
            windowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
            }
        )

        if (busy) {
            // The wavy bar is Material 3's expressive indeterminate progress: it reads
            // as "something is running" at a glance without a track to fill.
            LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            // The description lives on the settings row; repeating it here would
            // just push the actual content down.
            // Tapping this card copies a snippet the user can send to a developer
            // whose "is Shizuku installed" check breaks while hidden.
            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (ClipboardUtils.put(context, codeSnippet)) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.toast_copied_to_clipboard),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    shape = MaterialTheme.shapes.large
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.stealth_warning_compatibility),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = stringResource(R.string.stealth_warning_compatibility_2),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = stringResource(R.string.stealth_warning_compatibility_3),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = MaterialTheme.shapes.large
                ) {
                    Text(
                        text = stringResource(R.string.stealth_warning_detection),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            item {
                SegmentedColumn(modifier = Modifier.fillMaxWidth()) {
                    item {
                        SegmentedListItem(
                            headlineContent = { Text(stringResource(R.string.stealth_info_reconfigure)) }
                        )
                    }
                    item {
                        SegmentedListItem(
                            headlineContent = { Text(stringResource(R.string.stealth_info_rish)) }
                        )
                    }
                    item {
                        SegmentedListItem(
                            headlineContent = { Text(stringResource(R.string.stealth_info_intents)) }
                        )
                    }
                    item {
                        SegmentedListItem(
                            headlineContent = { Text(stringResource(R.string.stealth_info_update)) }
                        )
                    }
                }
            }

            if (action == Action.HIDE) {
                item {
                    OutlinedTextField(
                        value = packageName,
                        onValueChange = { packageName = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.stealth_package_name)) },
                        supportingText = {
                            Text(
                                packageNameError?.let { stringResource(it) }
                                    ?: stringResource(R.string.stealth_package_name_helper_text)
                            )
                        },
                        isError = packageNameError != null,
                        singleLine = true
                    )
                }

                // The package name is what other apps address the copy by; this is what a person
                // sees it called. Both are the copy's, so both are asked for before it is built -
                // the APK carries the name, and changing it afterwards means hiding again.
                item {
                    OutlinedTextField(
                        value = displayName,
                        onValueChange = { displayName = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.stealth_display_name)) },
                        supportingText = {
                            Text(
                                displayNameError?.let {
                                    stringResource(it, MAX_DISPLAY_NAME_LENGTH)
                                } ?: stringResource(R.string.stealth_display_name_helper_text)
                            )
                        },
                        isError = displayNameError != null,
                        singleLine = true
                    )
                }
            }

            item {
                Button(
                    onClick = {
                        when (action) {
                            Action.HIDE -> {
                                vm.setPackageName(packageName.ifEmpty { null })
                                vm.setDisplayName(displayName.ifEmpty { null })
                                picker.launch(null)
                            }

                            Action.UNHIDE -> vm.createApk(ApkType.STUB)

                            Action.REHIDE -> context.uninstallPackage(ORIGINAL_PACKAGE_NAME) { _, _ ->
                                vm.refresh()
                            }
                        }
                    },
                    enabled = !busy && (
                        action != Action.HIDE ||
                            (packageNameError == null && displayNameError == null)
                        )
                ) {
                    Text(
                        stringResource(
                            when (action) {
                                Action.HIDE -> R.string.stealth_hide
                                Action.UNHIDE -> R.string.stealth_unhide
                                else -> R.string.stealth_hide
                            }
                        )
                    )
                }
            }
        }
    }

    if (pendingUninstall) {
        AlertDialog(
            onDismissRequest = { pendingUninstall = false },
            title = { Text(stringResource(R.string.stealth_uninstall_required)) },
            text = { Text(stringResource(R.string.stealth_uninstall_message)) },
            confirmButton = {
                PillButton(onClick = {
                    pendingUninstall = false
                    context.uninstallPackage(ORIGINAL_PACKAGE_NAME) { _, _ -> vm.refresh() }
                }) { Text(stringResource(R.string.stealth_uninstall)) }
            },
            dismissButton = {
                PillButtonQuiet(onClick = { pendingUninstall = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    error?.let { message ->
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text(stringResource(R.string.error)) },
            text = { Text(message) },
            confirmButton = {
                PillButton(onClick = { error = null }) { Text(stringResource(android.R.string.ok)) }
            }
        )
    }
}

private fun exportApk(context: Context, outDir: Uri, apk: File) {
    val cr = context.contentResolver
    val docUri = DocumentsContract.buildDocumentUriUsingTree(
        outDir,
        DocumentsContract.getTreeDocumentId(outDir)
    )
    val doc = DocumentsContract.createDocument(
        cr, docUri, "application/vnd.android.package-archive", buildApkFilename()
    ) ?: throw Exception("Could not create file in the selected folder")

    cr.openOutputStream(doc)?.use { output ->
        apk.inputStream().use { input -> input.copyTo(output) }
    }
}
