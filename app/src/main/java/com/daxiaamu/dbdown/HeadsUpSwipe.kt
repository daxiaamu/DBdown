package com.daxiaamu.dbdown

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Drag updates are immediate; only release/rollback animates, and a new touch interrupts it. */
@Composable internal fun rememberHeadsUpSwipe(eventKey: String?, dismiss: () -> Unit): Modifier {
    val density=LocalDensity.current
    val threshold=with(density) { 48.dp.toPx() }
    val flingThreshold=with(density) { 800.dp.toPx() }
    var offset by remember(eventKey) { mutableFloatStateOf(0f) }
    var height by remember { mutableFloatStateOf(1f) }
    var settling by remember(eventKey) { mutableStateOf<Job?>(null) }
    val scope=rememberCoroutineScope()
    val onDismiss by rememberUpdatedState(dismiss)
    DisposableEffect(eventKey) { onDispose { settling?.cancel() } }
    val drag=rememberDraggableState { delta -> offset=(offset+delta).coerceIn(-height,0f) }
    return Modifier.onSizeChanged { height=it.height.toFloat().coerceAtLeast(1f) }
        // Placement keeps haze sampling aligned and avoids alpha layers clipping the outer shadow.
        .offset { IntOffset(0,offset.roundToInt()) }
        .draggable(drag,Orientation.Vertical,startDragImmediately=settling?.isActive==true,
            onDragStarted={ settling?.cancel() },
            onDragStopped={ velocity ->
                val close=offset <= -minOf(threshold,height*0.4f) ||
                    (offset < -threshold/6 && velocity < -flingThreshold)
                settling=scope.launch {
                    animate(offset,if(close) -height else 0f,
                        animationSpec=if(close) tween(160) else spring(dampingRatio=0.85f,stiffness=500f)) { value,_ -> offset=value }
                    if(close) onDismiss()
                }
            })
}
