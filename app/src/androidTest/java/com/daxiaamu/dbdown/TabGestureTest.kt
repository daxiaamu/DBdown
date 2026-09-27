package com.daxiaamu.dbdown

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TabGestureTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val vm get() = ViewModelProvider(rule.activity)[MainViewModel::class.java]
    private fun capsuleX() = rule.onNodeWithTag("tabCapsule", useUnmergedTree = true).fetchSemanticsNode().positionInRoot.x
    private fun homeX() = rule.onNodeWithTag("homePage").fetchSemanticsNode().positionInRoot.x
    private fun pageWidth() = rule.onNodeWithTag("pages").fetchSemanticsNode().size.width.toFloat()
    private fun travel() = rule.onNodeWithTag("tab0").fetchSemanticsNode().size.width.toFloat()
    private fun settle() { rule.mainClock.autoAdvance = true; rule.waitForIdle() }

    @Test fun pageDragMovesCapsuleBeforeReleaseAndSettlesBothWays() {
        val x = capsuleX()
        val distance = travel()
        val width = pageWidth()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("pages").performTouchInput {
            down(Offset(width * .85f, height * .7f))
            moveBy(Offset(-width * .35f, 0f), 300)
        }
        rule.mainClock.advanceTimeBy(32)
        val fraction = (capsuleX() - x) / distance
        assertTrue("Capsule must move while finger is still down: $fraction", fraction in .2f.. .45f)
        assertEquals("Page and capsule share progress", fraction, -homeX() / width, .035f)
        rule.onNodeWithTag("pages").performTouchInput { moveBy(Offset(-width * .4f, 0f), 250); up() }
        settle()
        rule.onNodeWithTag("tab1").assertIsSelected()
        rule.onNodeWithTag("pages").performTouchInput { swipeRight() }
        rule.waitForIdle()
        rule.onNodeWithTag("tab0").assertIsSelected()
    }

    @Test fun capsuleDragTracksPageAndShortDragSnapsBack() {
        val start = capsuleX()
        val distance = travel()
        val pageWidthPx = pageWidth()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("tabIsland").performTouchInput {
            down(Offset(width * .25f, height / 2f))
            moveBy(Offset(distance * .35f, 0f), 400)
        }
        rule.mainClock.advanceTimeBy(32)
        val fraction = (capsuleX() - start) / distance
        assertTrue("Capsule follows partial drag", fraction in .15f.. .45f)
        assertEquals("Dragging capsule moves page before release", fraction, -homeX() / pageWidthPx, .035f)
        rule.onNodeWithTag("tabIsland").performTouchInput { advanceEventTime(300); up() }
        settle()
        rule.onNodeWithTag("tab0").assertIsSelected()
        assertEquals(start, capsuleX(), 1f)
        rule.onNodeWithTag("tabIsland").performTouchInput {
            swipe(Offset(width * .25f, height / 2f), Offset(width * .8f, height / 2f), 500)
        }
        rule.waitForIdle()
        rule.onNodeWithTag("tab1").assertIsSelected()
        rule.onNodeWithTag("tabIsland").performTouchInput {
            swipe(Offset(width * .75f, height / 2f), Offset(width * .2f, height / 2f), 500)
        }
        rule.waitForIdle()
        rule.onNodeWithTag("tab0").assertIsSelected()
    }

    @Test fun reversingDuringAnimationCancelsOldTargetAndKeepsModelInSync() {
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("tab1").performClick()
        rule.mainClock.advanceTimeBy(64)
        rule.onNodeWithTag("tabIsland").performTouchInput {
            down(Offset(width * .65f, height / 2f))
            moveBy(Offset(-width * .5f, 0f), 300)
            up()
        }
        settle()
        rule.onNodeWithTag("tab0").assertIsSelected()
        rule.runOnIdle { assertEquals("Interrupted command cannot leave stale tab state", 0, vm.tab) }
        rule.onNodeWithTag("tabIsland").performTouchInput {
            down(Offset(width * .25f, height / 2f))
            moveBy(Offset(width * .38f, 0f), 400)
            cancel()
        }
        rule.waitForIdle()
        // Cancellation still settles to a full page; a new gesture remains usable.
        val position = (capsuleX() - rule.onNodeWithTag("tab0").fetchSemanticsNode().positionInRoot.x) / travel()
        assertTrue("Cancelled drag must not leave half a page", position < .01f || position > .99f)
        rule.onNodeWithTag("tab0").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("tab1").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("tab1").assertIsSelected()
    }

    @Test fun physicalTapDuringAnimationImmediatelyReversesFromCurrentPosition() {
        val origin = capsuleX()
        val distance = travel()
        rule.mainClock.autoAdvance = false
        // Real pointer events: semantic performClick bypasses the parent's drag interception.
        rule.onNodeWithTag("tab1").performTouchInput { click() }
        rule.mainClock.advanceTimeBy(96)
        val before = capsuleX()
        assertTrue("Must interrupt an unfinished animation", before > origin + 1 && before < origin + distance - 1)
        var request = 0
        rule.runOnIdle { request = vm.tabRequest }
        rule.onNodeWithTag("tab0").performTouchInput { click() }
        rule.runOnIdle { assertEquals("Tap must not be swallowed by draggable", request + 1, vm.tabRequest) }
        rule.mainClock.advanceTimeBy(64)
        assertTrue("Capsule must reverse before old animation completes", capsuleX() < before)
        settle()
        rule.onNodeWithTag("tab0").assertIsSelected()
        rule.runOnIdle { assertEquals(0, vm.tab) }

        rule.mainClock.autoAdvance = false
        repeat(5) { index ->
            rule.onNodeWithTag("tab${if(index % 2 == 0) 1 else 0}").performTouchInput { click() }
            rule.mainClock.advanceTimeBy(48)
        }
        settle()
        rule.onNodeWithTag("tab1").assertIsSelected()
        rule.runOnIdle { assertEquals(1, vm.tab) }
    }

    @Test fun clicksCommandsSettingsAndRecreationKeepSelection() {
        rule.onNodeWithTag("tab1").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("tab1").assertIsSelected()
        rule.runOnIdle { assertEquals(1, vm.tab); vm.settings = true }
        rule.runOnIdle { vm.settings = false }
        rule.onNodeWithTag("tab1").assertIsSelected()
        rule.runOnIdle { vm.tab = 0 }
        rule.waitForIdle()
        rule.onNodeWithTag("tab0").assertIsSelected()
        rule.onNodeWithTag("pages").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        rule.activityRule.scenario.recreate()
        rule.waitForIdle()
        rule.onNodeWithTag("tab1").assertIsSelected()
        rule.runOnIdle { vm.tab = 0; vm.tab = 1 }
        rule.waitForIdle()
        rule.onNodeWithTag("tab1").assertIsSelected()
    }
}
