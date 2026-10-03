package com.taolesi.nas

import com.taolesi.nas.net.DavResource
import com.taolesi.nas.net.DavResult
import com.taolesi.nas.net.WebDavClient
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

class RealServerDeepAuditTest {
    @Test
    fun testDeepOperations() = runBlocking {
        val client = WebDavClient(
            baseUrl = "http://nas.example.com",
            user = "admin",
            pass = "password123"
        )

        // 1. Probe
        val probe = client.probeConnectionFast()
        println("1. Probe result: $probe, Active endpoint: ${client.activeDavEndpoint}")

        // 2. Test recursive photo scan on the server
        println("2. Listing 01-照片与相册...")
        val p1 = client.listFolder("01-照片与相册")
        println("   01-照片与相册 item count: ${p1.size}")
        for (item in p1) {
            println("     - ${item.name} (isDir=${item.isDirectory}, path=${item.path})")
            if (item.isDirectory) {
                val sub = client.listFolder("01-照片与相册/${item.name}")
                println("       sub-items: ${sub.size}")
                for (s in sub.take(3)) {
                    println("         * ${s.name} (${s.contentLength} bytes, path=${s.path})")
                }
            }
        }

        // 3. Test 手机备份
        println("3. Listing 手机备份...")
        val backup = client.listFolder("手机备份")
        println("   手机备份 item count: ${backup.size}")
        for (item in backup) {
            println("     - ${item.name} (isDir=${item.isDirectory}, path=${item.path})")
            if (item.isDirectory) {
                val sub = client.listFolder("手机备份/${item.name}")
                println("       sub-items in ${item.name}: ${sub.size}")
                for (s in sub) {
                    println("         * ${s.name} (isDir=${s.isDirectory}, path=${s.path})")
                }
            }
        }

        // 4. Test download a small file
        println("4. Test download file stream...")
        val rootItems = client.listFolder("")
        val sampleFile = rootItems.firstOrNull { !it.isDirectory }
        if (sampleFile != null) {
            println("   Downloading ${sampleFile.name} (path=${sampleFile.path})...")
            val resp = client.downloadFileStream(sampleFile.name)
            println("   Download status: ${resp.code}, isSuccessful: ${resp.isSuccessful}")
            if (resp.isSuccessful) {
                val bytes = resp.body?.bytes()
                println("   Downloaded bytes: ${bytes?.size}")
            }
        }

        // 5. Check thumbnail URL generation and accessibility
        println("5. Test thumbnail URL...")
        val testPhotoPath = "/remote.php/dav/files/admin/01-照片与相册/test.jpg"
        val thumbUrl = client.getThumbnailUrl(testPhotoPath, 360, 360)
        println("   Generated thumbUrl: $thumbUrl")
    }
}
