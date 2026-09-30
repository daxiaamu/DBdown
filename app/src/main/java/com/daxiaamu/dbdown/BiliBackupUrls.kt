package com.daxiaamu.dbdown

import org.json.JSONObject

internal fun biliBackupUrls(stream: JSONObject): List<String> {
    val values = stream.optJSONArray("backupUrl") ?: stream.optJSONArray("backup_url") ?: return emptyList()
    return (0 until values.length()).mapNotNull { index ->
        values.optString(index).takeIf { it.startsWith("https://") || it.startsWith("http://") }
            ?.replaceFirst(Regex("^http://"), "https://")
    }.distinct()
}
