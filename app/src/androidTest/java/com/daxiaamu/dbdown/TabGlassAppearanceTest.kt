package com.daxiaamu.dbdown

import androidx.activity.compose.setContent
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TabGlassAppearanceTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()

    @Test fun capsuleHasOnlyItsOwnTintAtBothEndsAndInTransit() {
        val source=Color(0xFF204080)
        val container=Color(0xFF80C080)
        val primary=Color(0xFF008030)
        val tint=lerp(container,primary,0.30f)
        lateinit var pager: androidx.compose.foundation.pager.PagerState
        rule.runOnIdle { rule.activity.setContent {
            MaterialTheme(colorScheme=lightColorScheme(background=Color.White,primaryContainer=container,primary=primary)) {
                val haze=remember { HazeState() }
                pager=rememberPagerState { 2 }
                Box(Modifier.size(340.dp,180.dp)) {
                    HorizontalPager(pager,Modifier.fillMaxSize().hazeSource(haze).background(source)) {}
                    FloatingTabs(pager,haze,Modifier.align(Alignment.Center)) {}
                }
            }
        }
        }
        for(fraction in listOf(0f,0.5f,1f)) {
            rule.runOnIdle { runBlocking { pager.scrollToPage(if(fraction==1f) 1 else 0,if(fraction==1f) 0f else fraction) } }
            rule.waitForIdle()
            val island=rule.onNodeWithTag("tabIsland").fetchSemanticsNode().boundsInRoot
            val capsule=rule.onNodeWithTag("tabCapsule",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
            val pixels=rule.onNodeWithTag("tabIsland").captureToImage().toPixelMap()
            val x=((capsule.center.x-island.left)/island.width*pixels.width).toInt()
            val y=((capsule.top+capsule.height*0.15f-island.top)/island.height*pixels.height).toInt()
            val actual=pixels[x,y]
            for((expected,channel) in listOf(
                (source.red*0.54f+tint.red*0.46f) to actual.red,
                (source.green*0.54f+tint.green*0.46f) to actual.green,
                (source.blue*0.54f+tint.blue*0.46f) to actual.blue)) {
                assertEquals("Only capsule tint at position $fraction",expected,channel,0.035f)
            }
        }
    }
}
