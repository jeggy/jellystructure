package dev.jellystructure.ravilo.ui.seams

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import dev.jellystructure.ravilo.ui.RaviloAppContext

/** `Display.HdrCapabilities` needs API 24+; `HDR_TYPE_HDR10_PLUS` needs API 29+ (minSdk here is 21). */
actual fun detectHdrSupport(): HdrSupport {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return HdrSupport.NONE
    val ctx: Context = RaviloAppContext.get()
    val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return HdrSupport.NONE
    val display = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return HdrSupport.NONE
    val types = display.hdrCapabilities?.supportedHdrTypes ?: return HdrSupport.NONE
    val hdr10 = types.contains(Display.HdrCapabilities.HDR_TYPE_HDR10) ||
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && types.contains(Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS))
    val hlg = types.contains(Display.HdrCapabilities.HDR_TYPE_HLG)
    return HdrSupport(hdr10 = hdr10, hlg = hlg)
}
