package com.daxiaamu.dbdown

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.DragScope
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

// Both gestures mutate the pager under its scroll mutex, so grabbing the capsule
// interrupts a running page animation immediately instead of queuing behind it.
private class CapsuleDragState(private val pager: PagerState, private val travelPx: Float) : DraggableState {
    private val scale get() = (pager.layoutInfo.pageSize + pager.layoutInfo.pageSpacing) / travelPx
    override suspend fun drag(dragPriority: MutatePriority, block: suspend DragScope.() -> Unit) {
        pager.scroll(dragPriority) {
            val scroll = this
            block(object : DragScope {
                override fun dragBy(pixels: Float) { scroll.scrollBy(pixels * scale) }
            })
        }
    }
    override fun dispatchRawDelta(delta: Float) { pager.dispatchRawDelta(delta * scale) }
}

@Composable internal fun FloatingTabs(
    pager: PagerState, modifier: Modifier = Modifier, onSelect: (Int) -> Unit
) {
    val density = LocalDensity.current
    val travel = with(density) { 118.dp.toPx() }
    val flingThreshold = with(density) { 180.dp.toPx() }
    val dragState = remember(pager, travel) { CapsuleDragState(pager, travel) }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val progress by remember(pager) {
        derivedStateOf { (pager.currentPage + pager.currentPageOffsetFraction).coerceIn(0f, 1f) }
    }
    val colors = MaterialTheme.colorScheme
    val interactions = remember { MutableInteractionSource() }
    val tabPressed by interactions.collectIsPressedAsState()
    var pointerPressed by remember { mutableStateOf(false) }
    val pressed = pointerPressed || tabPressed
    val islandScale by animateFloatAsState(
        targetValue = if(pressed) .96f else 1f,
        animationSpec = if(pressed) tween(100) else spring(dampingRatio = .8f, stiffness = 650f),
        label = "tabIslandPress"
    )
    Surface(modifier.width(250.dp).testTag("tabIsland")
        .pointerInput(Unit) {
            // Observe without consuming: capsule drags retain their own gesture arbitration.
            try {
                awaitPointerEventScope {
                    while(true) pointerPressed = awaitPointerEvent(PointerEventPass.Initial).changes.any { it.pressed }
                }
            } finally { pointerPressed = false }
        }
        .graphicsLayer { scaleX = islandScale; scaleY = islandScale }, shape = CircleShape,
        color = colors.surface, shadowElevation = 10.dp) {
        Box(Modifier.padding(7.dp).height(50.dp).selectableGroup()
            .draggable(state = dragState, orientation = Orientation.Horizontal,
                // Keep taps available during animation; capture only after horizontal touch slop.
                reverseDirection = rtl, startDragImmediately = false,
                onDragStopped = { velocity ->
                    val position = pager.currentPage + pager.currentPageOffsetFraction
                    val target = if(abs(velocity) > flingThreshold) {
                        if(velocity > 0) 1 else 0
                    } else position.roundToInt().coerceIn(0, 1)
                    pager.animateScrollToPage(target)
                })) {
            Box(Modifier.offset { IntOffset((progress * travel).roundToInt(), 0) }
                .width(118.dp).fillMaxHeight().clip(CircleShape)
                .background(colors.primaryContainer).testTag("tabCapsule"))
            Row(Modifier.fillMaxSize()) {
                listOf("首页", "下载").forEachIndexed { index, label ->
                    val weight = if(index == 0) 1f - progress else progress
                    val tint = lerp(colors.onSurfaceVariant, colors.onPrimaryContainer, weight)
                    Row(Modifier.weight(1f).fillMaxHeight().clip(CircleShape)
                        .testTag("tab$index")
                        .selectable(selected = pager.currentPage == index, role = Role.Tab,
                            interactionSource = interactions, indication = null,
                            onClick = { onSelect(index) }),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                        Glyph(if(index == 0) "home" else "download", tint = tint)
                        Text(label, style = MaterialTheme.typography.labelLarge, color = tint)
                    }
                }
            }
        }
    }
}
