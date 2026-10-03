package com.taolesi.nas

import com.taolesi.nas.net.DavResult
import com.taolesi.nas.net.WebDavClient
import kotlinx.coroutines.runBlocking
import org.junit.Test

class RealServerLiveConnectionTest {
    @Test
    fun testRealNas6Connection() = runBlocking {
        val client = WebDavClient(
            baseUrl = "http://nas.example.com",
            user = "admin",
            pass = "password123"
        )
        println("=== TESTING checkConnectionDetailed ===")
        val res1 = client.checkConnectionDetailed()
        println("checkConnectionDetailed: " + res1)

        println("=== TESTING probeConnectionFast ===")
        val res2 = client.probeConnectionFast()
        println("probeConnectionFast: " + res2)

        println("Active Endpoint: " + client.activeDavEndpoint)

        println("=== TESTING getStorageQuotaResult ===")
        val res3 = client.getStorageQuotaResult()
        println("getStorageQuotaResult: " + res3)

        println("=== TESTING fetchServerTelemetryDetailed ===")
        val res4 = client.fetchServerTelemetryDetailed()
        println("fetchServerTelemetryDetailed: " + res4)

        println("=== TESTING listFolder('') ===")
        val res5 = client.listFolder("")
        println("listFolder('') count: " + res5.size)
        for (item in res5.take(5)) {
            println("  Item: " + item.name + ", isDir=" + item.isDirectory + ", path=" + item.path)
        }
    }
}
