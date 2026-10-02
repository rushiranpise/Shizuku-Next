package moe.shizuku.manager.ui.screen

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import moe.shizuku.manager.Helps
import moe.shizuku.manager.R
import moe.shizuku.manager.ui.component.SegmentedColumn
import moe.shizuku.manager.ui.component.SegmentedListItem
import moe.shizuku.manager.utils.CustomTabsHelper
import rikka.compatibility.DeviceCompatibility

private const val SH_NAME = "rish"
private const val DEX_NAME = "rish_shizuku.dex"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) writeRish(context, tree)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.tools_terminal)) },
            windowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
            }
        )

        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            // What rish is lives on the settings row; the screen goes straight to
            // the steps.
            item {
                SegmentedColumn(modifier = Modifier.fillMaxWidth()) {
                    item {
                        SegmentedListItem(
                            headlineContent = { Text(stringResource(R.string.terminal_tutorial_1)) },
                            supportingContent = {
                                Text(
                                    stringResource(
                                        R.string.terminal_tutorial_1_description,
                                        SH_NAME,
                                        DEX_NAME
                                    )
                                )
                            },
                            onClick = { picker.launch(null) }
                        )
                    }
                    item {
                        SegmentedListItem(
                            headlineContent = { Text(stringResource(R.string.terminal_tutorial_2)) },
                            supportingContent = {
                                Column {
                                    Text(
                                        stringResource(
                                            R.string.terminal_tutorial_2_description,
                                            SH_NAME,
                                            SH_NAME,
                                            ".bashrc"
                                        )
                                    )
                                    Text(
                                        text = "cp /sdcard/chosen-folder/* /data/data/terminal.package.name/files",
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        )
                    }
                    item {
                        SegmentedListItem(
                            headlineContent = { Text(stringResource(R.string.terminal_tutorial_3)) },
                            supportingContent = {
                                Text(
                                    text = "sh /path/to/$SH_NAME",
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        )
                    }
                    item {
                        SegmentedListItem(
                            headlineContent = { Text(stringResource(R.string.settings_help)) },
                            onClick = {
                                CustomTabsHelper.launchUrlOrCopy(context, Helps.RISH.get())
                            }
                        )
                    }
                }
            }

            if (runCatching { DeviceCompatibility.isMiui() }.getOrDefault(false)) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(R.string.terminal_tutorial_miui),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(R.string.terminal_tutorial_miui_2),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private fun writeRish(context: Context, tree: Uri) {
    val cr = context.contentResolver
    val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    val child = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    runCatching {
        cr.query(
            child,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            ),
            null, null, null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val name = cursor.getString(1)
                if (name == SH_NAME || name == DEX_NAME) {
                    runCatching {
                        DocumentsContract.deleteDocument(
                            cr,
                            DocumentsContract.buildDocumentUriUsingTree(tree, id)
                        )
                    }
                }
            }
        }
    }

    fun writeToDocument(name: String) {
        runCatching {
            val created = DocumentsContract.createDocument(cr, doc, "application/octet-stream", name) ?: return
            cr.openOutputStream(created)?.use { output ->
                context.assets.open(name).use { input ->
                    if (name == SH_NAME) {
                        input.bufferedReader().use {
                            output.write(it.readText().replace("MANAGER_PKG", context.packageName).toByteArray())
                        }
                    } else {
                        input.copyTo(output)
                    }
                }
            }
        }
    }

    writeToDocument(SH_NAME)
    writeToDocument(DEX_NAME)
}
