package com.daxiaamu.dbdown

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Platform marks are drawn directly, without an enclosing tile or badge. */
@Composable internal fun PlatformIcon(platform: Platform, modifier: Modifier = Modifier) {
    val ink = if(isSystemInDarkTheme()) Color.White else Color(0xFF15171C)
    Canvas(modifier.size(22.dp).semantics { contentDescription = platform.label }) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            when(platform) {
                Platform.BILI -> {
                    val pink = Color(0xFFFB7299)
                    drawRoundRect(pink, Offset(2f, 6f), Size(20f, 15f), CornerRadius(3f), style = Stroke(2f))
                    drawLine(pink, Offset(7f, 2f), Offset(10f, 6f), 2f, StrokeCap.Round)
                    drawLine(pink, Offset(17f, 2f), Offset(14f, 6f), 2f, StrokeCap.Round)
                    drawLine(pink, Offset(7f, 11f), Offset(7f, 15f), 2f, StrokeCap.Round)
                    drawLine(pink, Offset(17f, 11f), Offset(17f, 15f), 2f, StrokeCap.Round)
                    drawLine(pink, Offset(10f, 17f), Offset(14f, 17f), 1.5f, StrokeCap.Round)
                }
                Platform.WEIBO -> {
                    val red=Color(0xFFE6162D)
                    drawOval(red,Offset(2f,8f),Size(19f,13f))
                    drawOval(Color.White,Offset(5f,11f),Size(12f,8f))
                    drawOval(ink,Offset(8f,12f),Size(6f,5f))
                    drawCircle(Color.White,1.2f,Offset(10f,14f))
                    drawArc(Color(0xFFFF8200),-100f,110f,false,Offset(13f,1f),Size(10f,10f),style=Stroke(2f,cap=StrokeCap.Round))
                    drawArc(Color(0xFFFF8200),-100f,110f,false,Offset(15f,4f),Size(5f,5f),style=Stroke(1.6f,cap=StrokeCap.Round))
                    drawLine(red,Offset(4f,10f),Offset(8f,5f),3f,StrokeCap.Round)
                }
                Platform.YOUTUBE -> {
                    drawRoundRect(Color(0xFFFF0033), Offset(1f, 4f), Size(22f, 16f), CornerRadius(5f))
                    drawPath(Path().apply { moveTo(10f, 8f); lineTo(16f, 12f); lineTo(10f, 16f); close() }, Color.White)
                }
                Platform.DOUYIN -> {
                    val note = Path().apply {
                        moveTo(13f, 2f); lineTo(17f, 2f); cubicTo(17f, 5f, 19f, 7f, 22f, 7f)
                        lineTo(22f, 11f); cubicTo(20f, 11f, 18f, 10f, 17f, 9f)
                        lineTo(17f, 16f); cubicTo(17f, 24f, 4f, 24f, 4f, 16f)
                        cubicTo(4f, 12f, 7f, 10f, 11f, 10f); lineTo(11f, 14f)
                        cubicTo(7f, 13f, 6f, 19f, 10f, 19f); cubicTo(12f, 19f, 13f, 18f, 13f, 16f); close()
                    }
                    translate(-1f, -0.6f) { drawPath(note, Color(0xFF25F4EE)) }
                    translate(0.7f, 0.6f) { drawPath(note, Color(0xFFFE2C55)) }
                    drawPath(note, ink)
                }
            }
        }
    }
}
