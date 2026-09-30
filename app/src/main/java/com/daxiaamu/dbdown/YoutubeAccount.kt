package com.daxiaamu.dbdown

import org.json.JSONObject
import org.json.JSONTokener

/** Parse only explicit first-party config, never login words in page text or video titles. */
internal fun youtubeAccountVerdict(page: String): AccountStatus {
    val flags = Regex("""ytcfg\.set\s*\(\s*(?=\{)""").findAll(page).mapNotNull { match ->
        runCatching { (JSONTokener(page.substring(match.range.last + 1)).nextValue() as? JSONObject)?.opt("LOGGED_IN") as? Boolean }.getOrNull()
    }.toSet()
    return when(flags.singleOrNull()) {
        true -> AccountStatus.VALID
        false -> AccountStatus.EXPIRED
        null -> AccountStatus.UNKNOWN
    }
}

internal fun youtubePlayerResponse(page: String): JSONObject? {
    val match = Regex("""(?:var\s+ytInitialPlayerResponse\s*=|window\["ytInitialPlayerResponse"\]\s*=)\s*(?=\{)""").find(page) ?: return null
    return runCatching { JSONTokener(page.substring(match.range.last + 1)).nextValue() as? JSONObject }.getOrNull()
}
