package com.daxiaamu.dbdown

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountExpiryTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @Test fun expiredHistoryPromptsNavigatesAndCanBeIgnored() {
        rule.waitUntil(15000) { WebAccounts.statuses.value.size == 2 }
        assumeTrue("Never modify a signed-in user's session", WebAccounts.accounts.value.values.none { it })
        val prefs = rule.activity.getSharedPreferences("account_validity", 0)
        val original = prefs.all.toMap()
        try {
            prefs.edit().putBoolean("BILI_seen", true).putBoolean("DOUYIN_seen", true).commit()
            WebAccounts.refresh(true)
            rule.waitUntil(15000) { WebAccounts.expiredPrompt.value.size == 2 }
            rule.onNodeWithText("前往设置").performClick()
            rule.waitUntil { WebAccounts.expiredPrompt.value.isEmpty() }
            rule.runOnIdle { assertTrue(ViewModelProvider(rule.activity)[MainViewModel::class.java].settings) }
            rule.onAllNodesWithText("登录已失效").assertCountEquals(2)
            WebAccounts.refresh(true)
            rule.waitForIdle()
            assertTrue(WebAccounts.expiredPrompt.value.isEmpty())
            // Reset only the test history, then simulate another loss of saved credentials.
            prefs.edit().clear().commit(); WebAccounts.refresh(true)
            rule.waitUntil(15000) { WebAccounts.statuses.value.values.all { it == AccountStatus.SIGNED_OUT } }
            prefs.edit().putBoolean("BILI_seen", true).commit(); WebAccounts.refresh(true)
            rule.waitUntil(15000) { WebAccounts.expiredPrompt.value.isNotEmpty() }
            rule.onNodeWithText("忽略").performClick()
            rule.waitUntil { WebAccounts.expiredPrompt.value.isEmpty() }
            assertEquals(AccountStatus.EXPIRED, WebAccounts.statuses.value[Platform.BILI])
        } finally {
            val edit = prefs.edit().clear()
            original.forEach { (key, value) -> if(value is Boolean) edit.putBoolean(key, value) }
            edit.commit(); WebAccounts.dismissExpiry(); WebAccounts.refresh(true)
        }
    }
}
