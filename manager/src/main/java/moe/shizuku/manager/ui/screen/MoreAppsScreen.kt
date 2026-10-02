package moe.shizuku.manager.ui.screen

import android.annotation.SuppressLint
import android.webkit.WebChromeClient
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import moe.shizuku.manager.R
import moe.shizuku.manager.utils.MoreApps

/**
 * The community's index of Shizuku modules, for a device that had no browser to open it in.
 *
 * This is the second half of a decision rather than a screen of its own: the Labs tile tries the
 * browser first, and only lands here when there was nothing to try. So it is deliberately thin -
 * a title, a way back and the page itself - because it exists to make sure the list is reachable
 * at all, not to be a browser.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MoreAppsScreen(onBack: () -> Unit) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableIntStateOf(0) }

    // Back walks the page's own history first, because that is what back means inside a page:
    // leaving the screen on the first press would throw away wherever in the list the user had
    // got to, and the list is the only thing here.
    BackHandler {
        val view = webView
        if (view != null && view.canGoBack()) view.goBack() else onBack()
    }

    // A WebView holds a whole browser engine and a view of its own; left to the garbage
    // collector it outlives the screen it belonged to.
    DisposableEffect(Unit) {
        onDispose { webView?.destroy() }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.lab_more_apps)) },
            windowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
            }
        )

        // Only while it is loading: a bar that is always there is one more thing on the screen
        // that says nothing once the page has arrived.
        if (progress in 1..99) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth()
            )
        }

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    // The page keeps the reader's position in local storage, so a scroll is not
                    // lost when a module is opened and the page comes back.
                    settings.domStorageEnabled = true
                    // Without a client, the WebView hands every link out to the browser, which
                    // is the one thing this screen exists to avoid. So this is not decoration:
                    // an empty client is what keeps navigation inside the page.
                    webViewClient = WebViewClient()
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            progress = newProgress
                        }
                    }
                    loadUrl(MoreApps.URL)
                    webView = this
                }
            }
        )
    }
}
