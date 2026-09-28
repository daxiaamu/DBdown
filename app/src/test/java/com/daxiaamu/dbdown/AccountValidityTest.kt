package com.daxiaamu.dbdown

import org.junit.Assert.assertEquals
import org.junit.Test

class AccountValidityTest {
    @Test fun biliExplicitAuthenticationResults() {
        assertEquals(AccountStatus.EXPIRED, accountVerdict(Platform.BILI, """{"code":-101}"""))
        assertEquals(AccountStatus.VALID, accountVerdict(Platform.BILI, """{"code":0,"data":{"isLogin":true}}"""))
        assertEquals(AccountStatus.EXPIRED, accountVerdict(Platform.BILI, """{"code":0,"data":{"isLogin":false}}"""))
    }
    @Test fun biliRestrictionsAreNotExpiry() {
        for(body in listOf("{\"code\":-403}", "{\"code\":-412}", "{\"code\":0}"))
            assertEquals(AccountStatus.UNKNOWN, accountVerdict(Platform.BILI, body))
    }
    @Test fun douyinExplicitSessionExpiry() {
        assertEquals(AccountStatus.EXPIRED, accountVerdict(Platform.DOUYIN,
            """{"message":"error","data":{"error_code":1,"description":"会话过期，请重新登录"}}"""))
    }
    @Test fun douyinWebSelfProfileSeparatesLoginFromChallenges() {
        assertEquals(AccountStatus.VALID, accountVerdict(Platform.DOUYIN,
            """{"status_code":0,"user":{"uid":"12345"}}"""))
        assertEquals(AccountStatus.EXPIRED, accountVerdict(Platform.DOUYIN,
            """{"status_code":8,"status_msg":"请先登录"}"""))
        for(body in listOf(
            """{"status_code":0,"user":{"uid":null}}""",
            """{"status_code":0,"user":{"uid":"0"}}""",
            """{"status_code":0}""",
            """{"status_code":12}""",
            """{"message":"error","data":{"error_code":3053}}""",
            """{"message":"success","data":{"uid":null}}"""))
            assertEquals(AccountStatus.UNKNOWN, accountVerdict(Platform.DOUYIN,body))
    }
    @Test fun douyinAuthenticatedIdentity() {
        assertEquals(AccountStatus.VALID, accountVerdict(Platform.DOUYIN,
            """{"message":"success","data":{"user_id":12345}}"""))
    }
    @Test fun douyinChallengesAndPermissionsAreNotExpiry() {
        for(body in listOf(
            """{"message":"error","data":{"error_code":16,"description":"该应用无权限"}}""",
            """{"message":"error","data":{"error_code":1,"description":"请求失败"}}""",
            """{"status_code":12,"status_msg":"DeviceId 异常"}""",
            """{"message":"success","data":{}}"""))
            assertEquals(AccountStatus.UNKNOWN, accountVerdict(Platform.DOUYIN, body))
    }
    @Test fun invalidAndEmptyResponsesAreUnknownForBothPlatforms() {
        for(platform in Platform.entries) for(body in listOf("", "<html>验证</html>", "{}", "null"))
            assertEquals(AccountStatus.UNKNOWN, accountVerdict(platform, body))
    }
}
