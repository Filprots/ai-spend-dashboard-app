package app.aispend.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private const val CONSOLE_URL = "https://platform.claude.com/settings/billing"

/**
 * Lets the user sign in to the Claude Console inside the app and hands back the session cookies
 * plus the WebView's user agent, so later requests look like the same browser.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ConsoleSignInDialog(onSignedIn: (cookie: String, userAgent: String) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("Sign in to Claude Console")
                            Text(
                                "Email sign-in is the most reliable here; Google may refuse in-app sign-in.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Cancel") } },
                )
            },
        ) { padding ->
            AndroidView(
                modifier = Modifier.fillMaxSize().padding(padding),
                factory = { context ->
                    val cookies = CookieManager.getInstance()
                    // Start clean so an expired session is never picked up as a fresh one.
                    cookies.removeAllCookies(null)
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        cookies.setAcceptCookie(true)
                        cookies.setAcceptThirdPartyCookies(this, true)
                        var done = false
                        fun check() {
                            val cookie = cookies.getCookie("https://platform.claude.com").orEmpty()
                            if (!done && cookie.split(";").any { it.trim().startsWith("sessionKey=") }) {
                                done = true
                                onSignedIn(cookie, settings.userAgentString)
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) = check()
                            override fun onPageFinished(view: WebView, url: String?) = check()
                        }
                        loadUrl(CONSOLE_URL)
                    }
                },
                onRelease = { it.destroy() },
            )
        }
    }
}
