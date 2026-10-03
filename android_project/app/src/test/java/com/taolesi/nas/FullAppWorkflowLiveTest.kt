package com.taolesi.nas

import com.taolesi.nas.net.DavResult
import com.taolesi.nas.net.WebDavClient
import kotlinx.coroutines.runBlocking
import org.junit.Test

class FullAppWorkflowLiveTest {
    @Test
    fun testFullAppFlow() = runBlocking {
        val baseUrl = "http://nas.example.com"
        val user = "admin"
        val pass = "password123"

        val client = WebDavClient(baseUrl, user, pass)

        println("=== Step 1: Probe ===")
        val probeRes = client.probeConnectionFast()
        println("Probe: $probeRes, activeEndpoint: ${client.activeDavEndpoint}")

        println("=== Step 2: Storage Quota ===")
        val quotaRes = client.getStorageQuotaResult()
        println("Quota: $quotaRes")

        println("=== Step 3: Telemetry ===")
        val telemetryRes = client.fetchServerTelemetryDetailed()
        println("Telemetry: $telemetryRes")

        println("=== Step 4: List Root Files ===")
        val rootFiles = client.listFolderResult("")
        println("Root files count: ${if (rootFiles is DavResult.Success) rootFiles.data.size else rootFiles}")

        println("=== Step 5: Test Photo Incremental Scan logic ===")
        if (rootFiles is DavResult.Success) {
            val coreFolders = rootFiles.data.filter { it.isDirectory }
            for (f in coreFolders) {
                val sub = client.listFolderResult(f.name)
                println("Folder ${f.name} -> ${if (sub is DavResult.Success) sub.data.size else sub}")
                if (sub is DavResult.Success) {
                    for (subItem in sub.data) {
                        if (subItem.isDirectory) {
                            val deep = client.listFolderResult("${f.name}/${subItem.name}")
                            println("   SubFolder ${f.name}/${subItem.name} -> ${if (deep is DavResult.Success) deep.data.size else deep}")
                        }
                    }
                }
            }
        }
    }
}
