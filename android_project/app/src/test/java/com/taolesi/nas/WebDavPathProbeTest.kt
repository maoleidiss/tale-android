package com.taolesi.nas

import org.junit.Assert.*
import org.junit.Test
import java.net.URI

class WebDavPathProbeTest {

    @Test
    fun testUrlParsing() {
        val uri1 = URI.create("https://nas.example.com")
        assertEquals("", uri1.path)

        val uri2 = URI.create("https://nas.example.com/")
        assertEquals("/", uri2.path)

        val uri3 = URI.create("https://nas.example.com/dav")
        assertEquals("/dav", uri3.path)

        val uri4 = URI.create("http://192.168.1.100:5005/webdav/")
        assertEquals("/webdav/", uri4.path)

        val uri5 = URI.create("https://nas.example.com/remote.php/dav/files/admin")
        assertEquals("/remote.php/dav/files/admin", uri5.path)
    }
}
