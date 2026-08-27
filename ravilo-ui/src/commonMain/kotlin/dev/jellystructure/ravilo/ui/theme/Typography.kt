package dev.jellystructure.ravilo.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import jellystructure.ravilo_ui.generated.resources.Res
import jellystructure.ravilo_ui.generated.resources.sora
import jellystructure.ravilo_ui.generated.resources.space_grotesk
import org.jetbrains.compose.resources.Font

// Both fonts are variable-weight TTFs. Each Font() entry pointing to the same file
// but with a different FontWeight lets the runtime select the correct wght axis value.

// R211 — `Font()` is itself @Composable (it resolves a compose-resources font entry), so it can't be
// called from inside remember{}'s calculation block (`@DisallowComposableCalls` forbids it — this was
// tried and doesn't compile). What CAN be remembered is the `FontFamily` wrapper built from its
// results: `Font()`'s return value is a stable, equals()-comparable descriptor, so
// `remember(a, b) { FontFamily(a, b) }` skips reallocating the wrapper (+ its internal list) on a
// recomposition where neither `Font()` call's result actually changed — which is every recomposition,
// since these are startup-time constants. Removes the FontFamily-level churn on Home's constantly-
// recomposing Ken-Burns/focus-scale/scroll paths; the two Font() calls themselves still run each time
// (unavoidable — they need @Composable context), but per the type's own doc comment that's a cheap
// resource-id/weight lookup, not a file read.
val SpaceGrotesk: FontFamily
    @Composable get() {
        val semiBold = Font(Res.font.space_grotesk, weight = FontWeight.SemiBold)
        val bold = Font(Res.font.space_grotesk, weight = FontWeight.Bold)
        return remember(semiBold, bold) { FontFamily(semiBold, bold) }
    }

val Sora: FontFamily
    @Composable get() {
        val normal = Font(Res.font.sora, weight = FontWeight.Normal)
        val medium = Font(Res.font.sora, weight = FontWeight.Medium)
        val semiBold = Font(Res.font.sora, weight = FontWeight.SemiBold)
        return remember(normal, medium, semiBold) { FontFamily(normal, medium, semiBold) }
    }
