package moe.shizuku.manager.ui.screen

import android.os.Build
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import moe.shizuku.manager.module.AdbModule
import moe.shizuku.manager.module.AdbModuleManager
import moe.shizuku.manager.module.ModuleCommandRequest
import moe.shizuku.manager.module.ModuleJsBridge
import moe.shizuku.manager.module.ModuleProp
import moe.shizuku.manager.module.ModuleSettings

/**
 * A module's own page, running inside this app.
 *
 * Two things about this WebView are the point of it. It has no internet - a page with a bridge back
 * into Shizuku does not also get a network this app cannot see - and its bridge is the whole of what
 * the page can reach: the information the format says it gets, commands that a person approves
 * unless they have marked that module trusted, and a way to ask for the module settings to be
 * opened - the page a person lands on to be told the access is wrong is the one place a shortcut
 * to the fix belongs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModuleWebUiScreen(
    module: AdbModule,
    active: Boolean = true,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val webRoot = module.webRoot ?: return

    var pendingCommand by remember { mutableStateOf<ModuleCommandRequest?>(null) }
    var approval by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }

    val approve: suspend (ModuleCommandRequest) -> Boolean = { request ->
        if (ModuleSettings.isTrusted(request.module.id)) {
            true
        } else {
            val answer = CompletableDeferred<Boolean>()
            approval = answer
            pendingCommand = request
            answer.await()
        }
    }

    // Android's back, and the page's own history first: a page that has gone somewhere inside
    // itself goes back a step, and only when there is nowhere left in it does back leave for the
    // module list - which the module screen's own back handler does. Switched off with the tab.
    BackHandler(enabled = active && canGoBack) { webView?.goBack() }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(module.name) },
            windowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
            }
        )

        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            factory = { context ->
                WebView(context).apply {
                    webView = this

                    settings.javaScriptEnabled = true
                    settings.blockNetworkLoads = true
                    settings.allowFileAccess = true
                    settings.allowContentAccess = false

                    // A page that ships its own data has to be able to read it: without this a
                    // fetch() of a file sitting next to index.html is refused, and that looks like
                    // the module being broken rather than like a setting of ours.
                    settings.allowFileAccessFromFileURLs = true

                    // Pages that keep a little state expect to find it where they left it.
                    settings.domStorageEnabled = true

                    // The app is dark and a module's page often is not, and the page that cannot
                    // reach its own stylesheet - the internet is off, which is the point - is
                    // exactly the plainest one. Letting the platform darken a light page keeps it
                    // from glaring when everything around it is dark.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        settings.isAlgorithmicDarkeningAllowed = true
                    }

                    // Where the page is in its own history, read as it goes: it is what decides
                    // whether back steps inside the page or leaves it.
                    webViewClient = object : WebViewClient() {
                        override fun doUpdateVisitedHistory(
                            view: WebView,
                            url: String?,
                            isReload: Boolean,
                        ) {
                            canGoBack = view.canGoBack()
                        }
                    }

                    addJavascriptInterface(
                        ModuleJsBridge(module, onOpenSettings) { command, timeoutSeconds ->
                            AdbModuleManager.runWebUiCommand(module, command, approve, timeoutSeconds)
                        },
                        "Shizuku"
                    )

                    loadUrl("file://${File(webRoot, ModuleProp.WEB_UI_ENTRY).absolutePath}")
                }
            }
        )
    }

    pendingCommand?.let { request ->
        CommandReviewDialog(
            request = request,
            onApprove = {
                pendingCommand = null
                approval?.complete(true)
                approval = null
            },
            onReject = {
                pendingCommand = null
                approval?.complete(false)
                approval = null
            }
        )
    }
}
