package dev.jellystructure.ravilo.ui.music

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import dev.jellystructure.ravilo.ui.RaviloAppContext

@Composable
actual fun rememberNotificationAsk(): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return remember(launcher) {
        {
            if (Build.VERSION.SDK_INT >= 33 && MusicDeviceStore.get("notif_asked") == null) {
                val granted = ContextCompat.checkSelfPermission(RaviloAppContext.get(), Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                MusicDeviceStore.put("notif_asked", "1")
                if (!granted) runCatching { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
            }
        }
    }
}
