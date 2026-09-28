package com.daxiaamu.dbdown

import org.json.JSONObject

enum class AccountStatus(val label: String) {
    SIGNED_OUT("未登录"), CHECKING("正在检查登录状态…"), VALID("已登录"),
    EXPIRED("登录已失效"), UNKNOWN("暂时无法验证登录状态")
}

/** Only explicit authentication responses count as expiry; challenges and transport errors do not. */
internal fun accountVerdict(platform: Platform, body: String): AccountStatus = runCatching {
    val json = JSONObject(body)
    val data = json.optJSONObject("data")
    when(platform) {
        Platform.BILI -> when {
            json.optInt("code", Int.MIN_VALUE) == -101 -> AccountStatus.EXPIRED
            json.optInt("code", Int.MIN_VALUE) == 0 && data?.opt("isLogin") == true -> AccountStatus.VALID
            json.optInt("code", Int.MIN_VALUE) == 0 && data?.opt("isLogin") == false -> AccountStatus.EXPIRED
            else -> AccountStatus.UNKNOWN
        }
        Platform.DOUYIN -> when {
            json.optString("message") == "error" && data?.optInt("error_code") == 1 &&
                data.optString("description").contains("会话过期") -> AccountStatus.EXPIRED
            json.optString("message") == "success" &&
                listOf("user_id", "user_id_str", "uid").any { data?.optString(it).orEmpty().let { id -> id.isNotBlank() && id != "0" } } -> AccountStatus.VALID
            else -> AccountStatus.UNKNOWN
        }
    }
}.getOrDefault(AccountStatus.UNKNOWN)
