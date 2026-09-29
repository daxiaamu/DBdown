package com.daxiaamu.dbdown

import com.daxiaamu.dbdown.update.UpdateSource
import org.junit.Assert.*
import org.junit.Test

class HomeMessagesTest {
    private fun json(interval: Int = 5, items: String = """[{"id":"a","text":"消息","url":"https://example.com/news"}]""") =
        """{"schemaVersion":1,"enabled":true,"intervalSeconds":$interval,"messages":$items}"""
    @Test fun readsTextLinkAndInterval() {
        val config = HomeMessages.parse(json(8))
        assertTrue(config.enabled)
        assertEquals(8, config.intervalSeconds)
        assertEquals("https://example.com/news", config.messages.single().url)
        assertNull(HomeMessages.parse(json(items = """[{"id":"a","text":"纯文本"}]""")).messages.single().url)
    }
    @Test fun masterSwitchHidesEvenWithoutMessages() {
        assertEquals(HomeMessages.Empty, HomeMessages.parse("""{"schemaVersion":1,"enabled":false}"""))
        assertTrue(HomeMessages.parse(json(items = "[]")).messages.isEmpty())
    }
    @Test fun rejectsBadIntervalsLinksAndDuplicateIds() {
        listOf(json(0), json(121), json(items = """[{"id":"a","text":"x","url":"javascript:alert(1)"}]"""),
            json(items = """[{"id":"a","text":"x"},{"id":"a","text":"y"}]"""),
            json(items = """[{"id":"a","text":" "}]""")).forEach { value ->
            assertTrue(runCatching { HomeMessages.parse(value) }.isFailure)
        }
    }
    @Test fun reusesUpdateEndpointFamiliesAndRepository() {
        val source = UpdateSource("daxiaamu/DBdown", "main")
        val updates = source.sources("updates/stable/latest.json")
        val messages = source.sources("config/home-messages.json")
        assertEquals(updates.map { it.family }, messages.map { it.family })
        assertEquals(updates.map { it.url.replace("updates/stable/latest.json", "config/home-messages.json") }, messages.map { it.url })
    }
}
