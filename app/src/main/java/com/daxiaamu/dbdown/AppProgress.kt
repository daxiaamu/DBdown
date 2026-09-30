package com.daxiaamu.dbdown

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Shared quiet progress treatment: continuous rounded track, no endpoint dot or animated waves. */
@Composable internal fun AppProgressBar(modifier: Modifier = Modifier, progress: (() -> Float)? = null) {
    val value=progress?.invoke()?.coerceIn(0f,1f)
    val animated by animateFloatAsState(value ?: 0f,tween(220),label="progress")
    val phase=if(value == null) loadingPhase() else 0f
    val color=MaterialTheme.colorScheme.primary
    Canvas(modifier.fillMaxWidth().height(3.dp).progressSemantics(value)) {
        val stroke=size.height
        val left=stroke/2
        val right=(size.width-left).coerceAtLeast(left)
        fun segment(start: Float,end: Float,color: Color) {
            if(end>start) drawLine(color,Offset(left+(right-left)*start,stroke/2),
                Offset(left+(right-left)*end,stroke/2),stroke,StrokeCap.Round)
        }
        segment(0f,1f,color.copy(alpha=0.12f))
        if(value != null) segment(0f,animated,color)
        else {
            val head=phase*1.35f
            segment((head-0.35f).coerceIn(0f,1f),head.coerceIn(0f,1f),color)
        }
    }
}

@Composable internal fun AppProgressSpinner(modifier: Modifier = Modifier, progress: (() -> Float)? = null,
    color: Color = MaterialTheme.colorScheme.primary, strokeWidth: Dp = 2.dp) {
    val value=progress?.invoke()?.coerceIn(0f,1f)
    val animated by animateFloatAsState(value ?: 0f,tween(220),label="ringProgress")
    val phase=if(value == null) loadingPhase() else 0f
    Canvas(modifier.size(24.dp).progressSemantics(value)) {
        val stroke=strokeWidth.toPx()
        val diameter=(size.minDimension-stroke).coerceAtLeast(0f)
        val origin=Offset((size.width-diameter)/2,(size.height-diameter)/2)
        val bounds=Size(diameter,diameter)
        val style=Stroke(stroke,cap=StrokeCap.Round)
        drawArc(color.copy(alpha=0.12f),0f,360f,false,origin,bounds,style=style)
        drawArc(color,if(value == null) phase*360f-90f else -90f,
            if(value == null) 90f else animated*360f,false,origin,bounds,style=style)
    }
}

@Composable private fun loadingPhase(): Float {
    val transition=rememberInfiniteTransition(label="loading")
    val phase by transition.animateFloat(0f,1f,infiniteRepeatable(tween(1100,easing=LinearEasing)),label="phase")
    return phase
}

private fun Modifier.progressSemantics(value: Float?)=semantics {
    progressBarRangeInfo=value?.let { ProgressBarRangeInfo(it,0f..1f) } ?: ProgressBarRangeInfo.Indeterminate
}
