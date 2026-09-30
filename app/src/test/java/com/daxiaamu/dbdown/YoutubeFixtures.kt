package com.daxiaamu.dbdown

import org.json.JSONObject

internal fun selectYoutubeFixture(players: List<JSONObject>, link: VideoLink): VideoInfo? {
    val matching = players.filter { youtubeMatchingPlayer(it,link) }
    val streams = matching.flatMap { youtubeDirectStreams(it,link) { _,url -> url } }
    val selection = youtubeSelectStreams(streams) { it } ?: return null
    return youtubeVideoInfo(link,matching.first().getJSONObject("videoDetails").optString("title"),selection)
}
internal fun selectYoutubePageFixture(page: String, link: VideoLink): VideoInfo? =
    youtubePlayerResponse(page)?.let { selectYoutubeFixture(listOf(it),link) }
