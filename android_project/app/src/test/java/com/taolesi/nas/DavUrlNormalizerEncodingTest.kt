package com.taolesi.nas

import com.taolesi.nas.net.DavUrlNormalizer
import org.junit.Assert.*
import org.junit.Test

class DavUrlNormalizerEncodingTest {

    @Test
    fun testChinesePathUrlEncoding() {
        val base = "http://nas.example.com"
        val rawPath = "/remote.php/webdav/01-照片与相册/相机相册/测试图片 01.jpg"

        val normalized = DavUrlNormalizer.toSafeHttpUrl(base, rawPath)
        println("Original: $rawPath")
        println("Normalized: $normalized")

        // 验证：协议与端口无损
        assertTrue(normalized.startsWith("http://nas.example.com/"))
        // 验证：中文字符必须被编码
        assertFalse(normalized.contains("照片与相册"))
        assertTrue(normalized.contains("%E7%85%A7%E7%89%87%E4%B8%8E%E7%9B%B8%E5%86%8C"))
        // 验证：空格必须被转义为 %20 而非加号
        assertFalse(normalized.contains("+"))
        assertTrue(normalized.contains("%20"))
        // 验证：路径中的斜杠不能被转义为 %2F
        assertFalse(normalized.contains("%2Fremote.php"))
        assertTrue(normalized.contains("/remote.php/webdav/"))
    }

    @Test
    fun testSchemeAutoDetection() {
        // 如果用户输入未带 scheme，非标端口 8085 或常见 http 端口应智能推断 http://
        assertEquals("http://nas.example.com", DavUrlNormalizer.cleanBaseUrl("nas.example.com"))
        assertEquals("http://192.168.1.100:8080", DavUrlNormalizer.cleanBaseUrl("192.168.1.100:8080"))
        assertEquals("https://nas.example.com", DavUrlNormalizer.cleanBaseUrl("nas.example.com"))
        assertEquals("https://nas.example.com:443", DavUrlNormalizer.cleanBaseUrl("nas.example.com:443"))
        assertEquals("http://nas.example.com", DavUrlNormalizer.cleanBaseUrl("http://nas.example.com/"))
    }
}
