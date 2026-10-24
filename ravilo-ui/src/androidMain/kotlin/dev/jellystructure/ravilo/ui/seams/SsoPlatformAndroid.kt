package dev.jellystructure.ravilo.ui.seams

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.jellystructure.ravilo.ui.isTvPlatform
import dev.jellystructure.ravilo.ui.screens.SsoListener
import dev.jellystructure.ravilo.ui.screens.SsoPlatform

/** DEB-2233 (FR-11) — the private-use scheme the phone activity declares; the server's success page redirects to `<this>?attempt=…&h=…`. */
const val SSO_APP_SCHEME = "dev.jellystructure.ravilo"

@Composable
actual fun rememberSsoPlatform(): SsoPlatform? {
    if (isTvPlatform) return null
    val context = LocalContext.current
    return remember(context) { AndroidSsoPlatform(context) }
}

/** A Custom Tab over the phone's activity. The attempt is not kept here: the screen's saved state keeps it (FR-11). */
internal class AndroidSsoPlatform(private val context: Context) : SsoPlatform {
    private val app = object : SsoListener { override val returnTo = "app" }

    override fun listen(closeTabText: String): SsoListener = app

    override fun open(authorizeUrl: String) {
        val uri = Uri.parse(authorizeUrl)
        val tab = CustomTabsIntent.Builder().setShowTitle(true).build()
        tab.intent.data = uri
        // A Custom Tab started from the activity is part of its task; from anything else it needs a task of its own.
        if (context.findActivity() == null) tab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(tab.intent)
        } catch (_: android.content.ActivityNotFoundException) {
            // No Custom Tabs provider: any browser will do.
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}
