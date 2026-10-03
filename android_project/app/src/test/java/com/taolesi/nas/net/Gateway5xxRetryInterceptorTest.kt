package com.taolesi.nas.net

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 针对 Cloudflare 502 Bad Gateway 网关瞬态抖动愈合拦截器的专项单元测试
 */
class Gateway5xxRetryInterceptorTest {

    @Test
    fun test502BadGateway_AutoRetriesAndRecoversOnSecondAttempt() {
        var callCount = 0
        val fakeNetworkInterceptor = Interceptor { chain ->
            callCount++
            if (callCount == 1) {
                // 第 1 次返回 502 Bad Gateway
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(502)
                    .message("Bad Gateway")
                    .body("Cloudflare 502 Bad Gateway".toResponseBody("text/html".toMediaTypeOrNull()))
                    .build()
            } else {
                // 第 2 次成功自愈返回 207 Multi-Status
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(207)
                    .message("Multi-Status")
                    .body("<d:multistatus xmlns:d=\"DAV:\"></d:multistatus>".toResponseBody("application/xml".toMediaTypeOrNull()))
                    .build()
            }
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(WebDavClient.Gateway5xxRetryInterceptor(maxRetries = 2))
            .addInterceptor(fakeNetworkInterceptor)
            .build()

        val request = Request.Builder()
            .url("https://nas.example.com/remote.php/dav/files/admin/")
            .build()

        val response = client.newCall(request).execute()
        // 断言最终平滑自愈并返回 207，502 被底层自动消化！
        assertEquals(207, response.code)
        assertEquals(2, callCount)
    }

    @Test
    fun testCandidateEndpoints_DoesNotContainNakedRoot() {
        val candidates = DavUrlNormalizer.getCandidateEndpoints("https://nas.example.com", "admin")
        // 断言候选列表中绝不包含裸根路径
        assertTrue("必须包含 Nextcloud 现代路径", candidates.any { it.endsWith("/remote.php/dav/files/admin") })
        assertTrue("必须不包含裸根域名", candidates.none { it == "https://nas.example.com" })
    }
}
