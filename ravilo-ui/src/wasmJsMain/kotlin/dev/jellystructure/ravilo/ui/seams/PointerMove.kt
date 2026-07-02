package dev.jellystructure.ravilo.ui.seams

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent

@OptIn(ExperimentalComposeUiApi::class)
actual fun Modifier.wakeOnPointerMove(onMove: () -> Unit): Modifier =
    this.onPointerEvent(PointerEventType.Move) { onMove() }
