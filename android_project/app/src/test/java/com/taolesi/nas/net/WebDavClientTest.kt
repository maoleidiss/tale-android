package com.taolesi.nas.net

import org.junit.Assert.*
import org.junit.Test
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

class WebDavClientTest {

    // =========================================================================
    // 1. DavUrlNormalizer 算法测试
    // =========================================================================

    @Test
    fun testCleanBaseUrl() {
        // Case 1: 缺少协议头，自动补齐 https://
        assertEquals("https://nas.example.com", DavUrlNormalizer.cleanBaseUrl("nas.example.com"))

        // Case 2: 包含尾部斜杠
        assertEquals("https://nas.example.com", DavUrlNormalizer.cleanBaseUrl("https://nas.example.com/"))

        // Case 3: 误粘贴 /remote.php/dav/files/<user>/
        assertEquals(
            "http://192.168.1.100:8080",
            DavUrlNormalizer.cleanBaseUrl("http://192.168.1.100:8080/remote.php/dav/files/admin/")
        )

        // Case 4: 误粘贴 /remote.php/webdav/ 且带空格
        assertEquals(
            "https://my-nas.org",
            DavUrlNormalizer.cleanBaseUrl("  https://my-nas.org/remote.php/webdav/  ")
        )

        // Case 5: 误粘贴 /remote.php
        assertEquals(
            "https://nas.domain.cc",
            DavUrlNormalizer.cleanBaseUrl("https://nas.domain.cc/remote.php/")
        )
    }

    @Test
    fun testBuildDavUrlCollectionAndFileRules() {
        val base = "https://nas.example.com"
        val user = "admin"

        // Case 1: 根目录必然以 / 结尾
        val rootUrl = DavUrlNormalizer.buildDavUrl(base, user, "", isCollection = true)
        assertEquals("https://nas.example.com/remote.php/dav/files/admin/", rootUrl)

        // Case 2: 子集合 (目录) 结尾强制带 /
        val collectionUrl = DavUrlNormalizer.buildDavUrl(base, user, "01-照片与相册", isCollection = true)
        assertTrue("集合 URL 必须以 '/' 结尾", collectionUrl.endsWith('/'))
        assertTrue("中文字符必须 URL 编码", collectionUrl.contains("%E7%85%A7%E7%89%87"))

        // Case 3: 文件结尾严格不带 /，且特殊字符 # 必须编码为 %23，空格编码为 %20
        val fileUrl = DavUrlNormalizer.buildDavUrl(base, user, "02-电影与影视/复仇者联盟 #1 (2012) [4K].mp4", isCollection = false)
        assertFalse("文件 URL 绝不能以 '/' 结尾", fileUrl.endsWith('/'))
        assertTrue("井号 # 必须被转义为 %23", fileUrl.contains("%23"))
        assertFalse("井号 # 绝不能以裸字符存在", fileUrl.contains("#"))
        assertTrue("空格必须被转义为 %20", fileUrl.contains("%20"))
        assertFalse("空格不应以 '+' 存在", fileUrl.contains("+"))

        // Case 4: 防御性剥离重复传入的 remote.php/dav/files/<user> 前缀
        val duplicateUrl = DavUrlNormalizer.buildDavUrl(base, user, "/remote.php/dav/files/admin/Photos/pic.jpg", isCollection = false)
        assertEquals("https://nas.example.com/remote.php/dav/files/admin/Photos/pic.jpg", duplicateUrl)
    }

    @Test
    fun testGenericWebDavCustomPathSupport() {
        // 1. hasCustomPath 判定
        assertTrue(DavUrlNormalizer.hasCustomPath("https://nas.example.com/dav"))
        assertTrue(DavUrlNormalizer.hasCustomPath("http://192.168.1.100:5005/webdav/"))
        assertTrue(DavUrlNormalizer.hasCustomPath("https://nas.domain.cc/alist/dav"))
        assertFalse(DavUrlNormalizer.hasCustomPath("https://nas.example.com"))
        assertFalse(DavUrlNormalizer.hasCustomPath("https://nas.example.com/"))
        assertFalse(DavUrlNormalizer.hasCustomPath("https://nas.example.com/remote.php/dav/files/admin/"))
        assertFalse(DavUrlNormalizer.hasCustomPath("https://nas.example.com/remote.php/webdav/"))

        // 2. 自定义路径直接以此为根，不拼 remote.php/dav/files/<user>
        val customRoot = DavUrlNormalizer.buildDavUrl("https://nas.example.com/dav", "admin", "", isCollection = true)
        assertEquals("https://nas.example.com/dav/", customRoot)

        val customFile = DavUrlNormalizer.buildDavUrl("https://nas.example.com/dav", "admin", "documents/report.pdf", isCollection = false)
        assertEquals("https://nas.example.com/dav/documents/report.pdf", customFile)

        // 3. 防御性剥离重复传入的自定义 path 前缀
        val deduplicated = DavUrlNormalizer.buildDavUrl("http://192.168.1.100:5005/webdav", "admin", "/webdav/Photos/pic.jpg", isCollection = false)
        assertEquals("http://192.168.1.100:5005/webdav/Photos/pic.jpg", deduplicated)

        // 4. 自适应根路径 customRootPrefix 优先覆盖
        val adaptiveUrl = DavUrlNormalizer.buildDavUrl(
            baseUrl = "https://nas.example.com",
            username = "admin",
            subPath = "test.txt",
            isCollection = false,
            customRootPrefix = "https://nas.example.com/custom_mount"
        )
        assertEquals("https://nas.example.com/custom_mount/test.txt", adaptiveUrl)
    }

    // =========================================================================
    // 2. DavResult 与错误模型测试
    // =========================================================================

    @Test
    fun testDavResultSuccessAndMap() {
        val success: DavResult<Int> = DavResult.Success(42)
        assertEquals(42, (success as DavResult.Success).data)
        assertEquals(42, success.getOrNull())
        assertEquals(42, success.getOrThrow())

        val mapped = success.map { "Score: $it" }
        assertTrue(mapped is DavResult.Success)
        assertEquals("Score: 42", (mapped as DavResult.Success).data)
    }

    @Test
    fun testDavResultFailureAndException() {
        val failure: DavResult<String> = DavResult.Failure(
            DavErrorType.AUTH_FAILED,
            "401 Unauthorized",
            httpCode = 401
        )
        assertNull(failure.getOrNull())
        val msg = (failure as DavResult.Failure).toUserFriendlyMessage()
        assertTrue("友好消息应提示凭据错误", msg.contains("401") || msg.contains("凭据"))

        try {
            failure.getOrThrow()
            fail("getOrThrow 应该抛出 DavException")
        } catch (e: DavException) {
            assertEquals(DavErrorType.AUTH_FAILED, e.errorType)
            assertEquals(401, e.httpCode)
        }
    }

    // =========================================================================
    // 3. XML 解析与防自身递归测试
    // =========================================================================

    @Test
    fun testParseFolderXmlExcludesSelfFirstItem() {
        // RFC 4918 典型响应：第一个项是请求的集合自身，第二个项才是真实子文件
        val xml = """<?xml version="1.0" encoding="utf-8"?>
        <d:multistatus xmlns:d="DAV:">
            <d:response>
                <d:href>/remote.php/dav/files/admin/01-%E7%85%A7%E7%89%87/</d:href>
                <d:propstat>
                    <d:prop>
                        <d:resourcetype><d:collection/></d:resourcetype>
                    </d:prop>
                    <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
            </d:response>
            <d:response>
                <d:href>/remote.php/dav/files/admin/01-%E7%85%A7%E7%89%87/IMG_001.jpg</d:href>
                <d:propstat>
                    <d:prop>
                        <d:displayname>IMG_001.jpg</d:displayname>
                        <d:getcontentlength>1048576</d:getcontentlength>
                        <d:getcontenttype>image/jpeg</d:getcontenttype>
                        <d:getlastmodified>Tue, 17 Mar 2026 12:00:00 GMT</d:getlastmodified>
                        <d:getetag>"abcdef123456"</d:getetag>
                        <d:resourcetype/>
                    </d:prop>
                    <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
            </d:response>
        </d:multistatus>
        """.trimIndent()

        val resources = WebDavClient.parseFolderXml(xml, "01-照片", "admin")

        // 验证：首个自身项被正确跳过，只解析出 1 个子文件！
        assertEquals("结果应仅包含 1 个子文件，首个自身目录必须被排除", 1, resources.size)
        val file = resources[0]
        assertEquals("IMG_001.jpg", file.name)
        assertFalse(file.isDirectory)
        assertEquals(1048576L, file.contentLength)
        assertEquals("image/jpeg", file.contentType)
        assertEquals("abcdef123456", file.etag)
    }

    @Test
    fun testParseFolderXmlSelfPatternDualProtection() {
        // 模拟极端情况：即便第一个项不是自身，后续项包含自身路径时也必须通过 selfPatterns 排除
        val xml = """<?xml version="1.0" encoding="utf-8"?>
        <d:multistatus xmlns:d="DAV:">
            <d:response>
                <d:href>/remote.php/dav/files/admin/01-%E7%85%A7%E7%89%87/IMG_002.jpg</d:href>
                <d:propstat>
                    <d:prop>
                        <d:getcontentlength>2048</d:getcontentlength>
                        <d:resourcetype/>
                    </d:prop>
                </d:propstat>
            </d:response>
            <d:response>
                <d:href>/remote.php/dav/files/admin/01-%E7%85%A7%E7%89%87/</d:href>
                <d:propstat>
                    <d:prop>
                        <d:resourcetype><d:collection/></d:resourcetype>
                    </d:prop>
                </d:propstat>
            </d:response>
        </d:multistatus>
        """.trimIndent()

        // 此时第一个项虽然被 RFC 规则跳过，但第二个项是自身路径，也会被 selfPatterns 拦截
        val resources = WebDavClient.parseFolderXml(xml, "01-照片", "admin")
        // 确保最终列表里没有自身目录
        val containsSelfDir = resources.any { it.name == "01-照片" || it.path.endsWith("/01-照片") }
        assertFalse("目录列表中绝不能包含自身目录，杜绝无限套娃递归", containsSelfDir)
    }

    @Test
    fun testParseQuotaXml() {
        val xml = """<?xml version="1.0" encoding="utf-8"?>
        <d:multistatus xmlns:d="DAV:">
            <d:response>
                <d:href>/remote.php/dav/files/admin/</d:href>
                <d:propstat>
                    <d:prop>
                        <d:quota-available-bytes>53687091200</d:quota-available-bytes>
                        <d:quota-used-bytes>10737418240</d:quota-used-bytes>
                    </d:prop>
                    <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
            </d:response>
        </d:multistatus>
        """.trimIndent()

        val quota = WebDavClient.parseQuotaXml(xml)
        assertEquals(10737418240L, quota.usedBytes)
        assertEquals(53687091200L, quota.availableBytes)
        assertEquals(64424509440L, quota.totalBytes)
    }

    // =========================================================================
    // 4. 异常与 HTTP 状态映射测试
    // =========================================================================

    @Test
    fun testExceptionMapping() {
        val timeoutRes = WebDavClient.mapException(SocketTimeoutException("Read timed out"))
        assertEquals(DavErrorType.TIMEOUT, timeoutRes.errorType)

        val hostRes = WebDavClient.mapException(UnknownHostException("nas.invalid.local"))
        assertEquals(DavErrorType.HOST_UNREACHABLE, hostRes.errorType)

        val connectRes = WebDavClient.mapException(ConnectException("Connection refused"))
        assertEquals(DavErrorType.HOST_UNREACHABLE, connectRes.errorType)

        val rstRes = WebDavClient.mapException(SocketException("Connection reset by peer: 10054"))
        assertEquals(DavErrorType.CONNECTION_RESET, rstRes.errorType)

        val sslRes = WebDavClient.mapException(SSLException("Handshake failed"))
        assertEquals(DavErrorType.CONNECTION_RESET, sslRes.errorType)
    }

    @Test
    fun testHttpResponseErrorMapping() {
        assertEquals(DavErrorType.AUTH_FAILED, WebDavClient.mapHttpResponseError(401).errorType)
        assertEquals(DavErrorType.PERMISSION_DENIED, WebDavClient.mapHttpResponseError(403).errorType)
        assertEquals(DavErrorType.NOT_FOUND, WebDavClient.mapHttpResponseError(404).errorType)
        assertEquals(DavErrorType.METHOD_NOT_ALLOWED, WebDavClient.mapHttpResponseError(405).errorType)
        assertEquals(DavErrorType.PAYLOAD_TOO_LARGE, WebDavClient.mapHttpResponseError(413).errorType)
        assertEquals(DavErrorType.SERVER_ERROR, WebDavClient.mapHttpResponseError(500).errorType)
        assertEquals(DavErrorType.SERVER_ERROR, WebDavClient.mapHttpResponseError(502).errorType)
        assertEquals(DavErrorType.SERVER_ERROR, WebDavClient.mapHttpResponseError(503).errorType)
    }

    // =========================================================================
    // 5. WebDavConfig 测试
    // =========================================================================

    @Test
    fun testWebDavConfigCredentials() {
        val config = WebDavConfig.create("https://nas.example.com", "admin", "secret123")
        assertTrue(config.isConfigured)
        assertTrue(config.basicAuthHeader.startsWith("Basic "))

        val emptyConfig = WebDavConfig.create("", "", "")
        assertFalse(emptyConfig.isConfigured)
        assertEquals("", emptyConfig.basicAuthHeader)
    }

    // =========================================================================
    // 6. Uptime 格式化与服务器指标规范化测试
    // =========================================================================

    @Test
    fun testFormatUptimeSeconds() {
        // seconds <= 0 返回 '--'
        assertEquals("--", WebDavClient.formatUptimeSeconds(0L))
        assertEquals("--", WebDavClient.formatUptimeSeconds(-1L))
        assertEquals("--", WebDavClient.formatUptimeSeconds(-86400L))

        // 小于 1 小时：返回 '${minutes}分钟'
        assertEquals("45分钟", WebDavClient.formatUptimeSeconds(2700L))
        assertEquals("0分钟", WebDavClient.formatUptimeSeconds(30L))

        // 小于 1 天但大于等于 1 小时：返回 '${hours}小时 ${minutes}分'
        assertEquals("4小时 15分", WebDavClient.formatUptimeSeconds(15300L))
        assertEquals("2小时 0分", WebDavClient.formatUptimeSeconds(7200L))

        // 大于等于 1 天：返回 '${days}天 ${hours}小时'
        assertEquals("18天 4小时", WebDavClient.formatUptimeSeconds(1571520L))
        assertEquals("1天 0小时", WebDavClient.formatUptimeSeconds(86400L))

        // 实例方法代理测试
        val client = WebDavClient("https://nas.example.com", "admin", "secret123")
        assertEquals("18天 4小时", client.formatUptimeSeconds(1571520L))
        assertEquals("--", client.formatUptimeSeconds(0L))
    }

    @Test
    fun testCpuloadPrecisionAndUptimeRegexExtraction() {
        val json = """
            {
                "ocs": {
                    "meta": {"status": "ok", "statuscode": 200},
                    "data": {
                        "nextcloud": {"system": {"version": "28.0.2"}},
                        "server": {
                            "cpuload": [0.15234, 0.2289, 0.1841, 0.999],
                            "uptime": 1572480,
                            "mem_total": 8388608,
                            "mem_free": 4194304
                        }
                    }
                }
            }
        """.trimIndent()

        // 验证 uptime 正则提取
        val uptimeSec = Regex("\"uptime\"\\s*:\\s*\"?(\\d+)\"?").find(json)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        assertEquals(1572480L, uptimeSec)
        assertEquals("18天 4小时", WebDavClient.formatUptimeSeconds(uptimeSec))

        // 验证 cpuload 精度规范化（保留 2 位小数）
        val loadMatch = Regex("\"cpuload\"\\s*:\\s*\\[([^\\]]+)\\]").find(json)
        val loadStr = loadMatch?.groupValues?.get(1)?.split(",")?.mapNotNull { it.trim().toFloatOrNull() }?.take(3)?.map { String.format(java.util.Locale.US, "%.2f", it) }?.joinToString(" / ")?.ifEmpty { "--" } ?: "--"
        assertEquals("0.15 / 0.23 / 0.18", loadStr)
    }

    @Test
    fun testWebDavClientSourceNoHardcodedTelemetryUptime() {
        // 读取 WebDavClient.kt 源码，确保彻底杜绝 '核心系统运行中' 静态文案
        val classFile = java.io.File("src/main/java/com/taolesi/nas/net/WebDavClient.kt")
        val altFile = java.io.File("app/src/main/java/com/taolesi/nas/net/WebDavClient.kt")
        val target = if (classFile.exists()) classFile else altFile
        assertTrue("必须能定位 WebDavClient.kt 源码文件", target.exists())
        val source = target.readText()
        assertFalse(
            "WebDavClient.kt 严禁残留硬编码的“核心系统运行中”静态文案",
            source.contains("核心系统运行中")
        )
    }

    @Test
    fun testFetchServerTelemetryDetailed_FallbackToBasicDataOn401() = kotlinx.coroutines.runBlocking {
        val serverSocket = java.net.ServerSocket(0)
        val port = serverSocket.localPort
        var basicDataHit = false

        val serverThread = Thread {
            try {
                // 1. 处理 /info 请求 -> 返回 401
                val sock1 = serverSocket.accept()
                val reader1 = sock1.getInputStream().bufferedReader()
                while (true) {
                    val line = reader1.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val body401 = """{"ocs":{"meta":{"statuscode":401}}}"""
                val resp1 = "HTTP/1.1 401 Unauthorized\r\nContent-Type: application/json\r\nContent-Length: ${body401.length}\r\nConnection: close\r\n\r\n$body401"
                sock1.getOutputStream().write(resp1.toByteArray())
                sock1.getOutputStream().flush()
                sock1.close()

                // 2. 处理 /basicdata 请求 -> 返回 200
                val sock2 = serverSocket.accept()
                val reader2 = sock2.getInputStream().bufferedReader()
                val reqLine2 = reader2.readLine() ?: ""
                if (reqLine2.contains("basicdata")) {
                    basicDataHit = true
                }
                while (true) {
                    val line = reader2.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val body200 = """{"ocs":{"meta":{"statuscode":200},"data":{"nextcloud":{"system":{"version":"28.0.3"}},"server":{"uptime":1572480}}}}"""
                val resp2 = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body200.length}\r\nConnection: close\r\n\r\n$body200"
                sock2.getOutputStream().write(resp2.toByteArray())
                sock2.getOutputStream().flush()
                sock2.close()
            } catch (_: Exception) {}
        }
        serverThread.start()

        try {
            val client = WebDavClient("http://127.0.0.1:$port", "user", "pass")
            val result = client.fetchServerTelemetryDetailed()
            serverThread.join(2000)

            assertTrue("结果必须为 Success", result is DavResult.Success)
            val telemetry = (result as DavResult.Success).data
            assertTrue("basicdata 端点必须在 info 401 后被请求", basicDataHit)
            assertEquals("18天 4小时", telemetry.uptimeText)
            assertEquals("Nextcloud 28.0.3", telemetry.serverBrand)
            assertTrue("必须为真实指标", telemetry.isRealServerMetrics)
        } finally {
            serverSocket.close()
        }
    }
}
