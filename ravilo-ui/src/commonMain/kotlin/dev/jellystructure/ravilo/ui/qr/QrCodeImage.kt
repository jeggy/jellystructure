package dev.jellystructure.ravilo.ui.qr

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import kotlin.math.floor

/**
 * DEB-2233 (FR-5) — [text] as a QR on a Compose Canvas: black modules on a white tile with the standard's four-module quiet
 * zone inside it. Black on white in every skin on purpose (the design round: a scanner wants the contrast, and the bright tile
 * is what draws the eye). The caller sets a square size. A module is a whole number of pixels, so none is blurred or
 * lost to rounding — a QR that is a pixel off scans worse; the leftover is split evenly around the symbol.
 * Draws nothing when [text] is too long to encode.
 */
@Composable
fun QrCodeImage(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) { runCatching { QrEncoder.encode(text) }.getOrNull() } ?: return
    Canvas(modifier) {
        drawRect(Color.White)
        val cells = matrix.size + 2 * QUIET_ZONE
        val module = floor(minOf(size.width, size.height) / cells)
        if (module < 1f) return@Canvas
        val left = floor((size.width - module * matrix.size) / 2f)
        val top = floor((size.height - module * matrix.size) / 2f)
        val cell = Size(module, module)
        for (y in 0 until matrix.size) for (x in 0 until matrix.size) {
            if (matrix.isDark(x, y)) drawRect(Color.Black, Offset(left + x * module, top + y * module), cell)
        }
    }
}

private const val QUIET_ZONE = 4
