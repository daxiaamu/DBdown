package com.daxiaamu.dbdown

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class HomeMessageCarouselTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun cyclesRepeatedlyAndPausesWhenInactive() {
        val active = mutableStateOf(true)
        val config = HomeMessages(true, 2, listOf(
            HomeMessage("first", "First notice", null),
            HomeMessage("second", "Linked notice", "https://github.com/daxiaamu/DBdown")))
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { rule.activity.setContent {
            MaterialTheme { HomeMessageCarousel(config, active.value) }
        } }
        rule.mainClock.advanceTimeBy(32)
        rule.onNodeWithText("First notice").assertIsDisplayed()
        rule.mainClock.advanceTimeBy(2800)
        rule.onNodeWithText("Linked notice").assertIsDisplayed()
        rule.onNodeWithContentDescription("打开链接", useUnmergedTree = true).assertIsDisplayed()
        rule.runOnIdle { active.value = false }
        rule.mainClock.advanceTimeBy(6000)
        rule.onNodeWithText("Linked notice").assertIsDisplayed()
        rule.runOnIdle { active.value = true }
        rule.mainClock.advanceTimeBy(2800)
        rule.onNodeWithText("First notice").assertIsDisplayed()
        rule.mainClock.advanceTimeBy(2800)
        rule.onNodeWithText("Linked notice").assertIsDisplayed()
    }
}
