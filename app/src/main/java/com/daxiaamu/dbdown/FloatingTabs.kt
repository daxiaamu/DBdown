package com.daxiaamu.dbdown

import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.MutatePriority
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

private val TabInset = 7.dp
private val TabWidth = 118.dp
private val TabHeight = 50.dp

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
    pager: PagerState, haze: HazeState, modifier: Modifier = Modifier, onSelect: (Int) -> Unit
) {
    val density = LocalDensity.current
    val travel = with(density) { TabWidth.toPx() }
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
    Box(modifier.width(TabWidth * 2 + TabInset * 2).testTag("tabIsland")
        .pointerInput(Unit) {
            // Observe without consuming: capsule drags retain their own gesture arbitration.
            try {
                awaitPointerEventScope {
                    while(true) pointerPressed = awaitPointerEvent(PointerEventPass.Initial).changes.any { it.pressed }
                }
            } finally { pointerPressed = false }
        }
        .graphicsLayer { scaleX = islandScale; scaleY = islandScale }
        .shadow(4.dp,CircleShape).clip(CircleShape)) {
        TabGlassBackdrop(haze,progress,rtl,Modifier.matchParentSize())
        Box(Modifier.padding(TabInset).height(TabHeight).selectableGroup()
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
                .width(TabWidth).fillMaxHeight().clip(CircleShape)
                .testTag("tabCapsule"))
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

/** One backdrop blur, then mutually exclusive tints. No glass layer exists beneath the capsule. */
@Composable private fun TabGlassBackdrop(haze: HazeState, progress: Float, rtl: Boolean, modifier: Modifier) {
    val colors=MaterialTheme.colorScheme
    val capsuleTint=lerp(colors.primaryContainer,colors.primary,0.30f)
    val cutout=remember { Path() }
    val style=HazeStyle(backgroundColor=colors.background,tint=HazeTint(Color.Transparent),
        blurRadius=26.dp,noiseFactor=0f)
    Box(modifier.drawWithContent {
        drawContent()
        val inset=TabInset.roundToPx().toFloat()
        val travel=TabWidth.toPx()
        val offset=(progress*travel).roundToInt().toFloat()
        val left=inset+if(rtl) travel-offset else offset
        cutout.reset()
        cutout.addRoundRect(RoundRect(left,inset,left+TabWidth.roundToPx(),size.height-inset,
            CornerRadius((size.height-2*inset)/2)))
        // Only flat tints are clipped, after Haze finishes drawing its render layer.
        clipPath(cutout,ClipOp.Difference) { drawRect(colors.background.copy(alpha=0.46f)) }
        drawPath(cutout,capsuleTint.copy(alpha=0.46f))
    }.hazeEffect(haze,style=style))
}
