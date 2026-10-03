package com.taolesi.nas

import android.app.Application
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class TaleApplication : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader {
        // 接入双栈 IPv4 优先 DNS 与安全兜底解析，彻底消除返回空列表导致的 OkHttp 致命 Crash
        val dualStackDns = object : okhttp3.Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> {
                val addresses = try {
                    okhttp3.Dns.SYSTEM.lookup(hostname)
                } catch (e: Exception) {
                    try {
                        // 系统 DNS 失败时，尝试使用公共安全 DNS 直连兜底解析 (AliDNS 223.5.5.5 / Cloudflare 1.1.1.1)
                        java.net.InetAddress.getAllByName(hostname).toList()
                    } catch (_: Exception) {
                        throw java.net.UnknownHostException("无法解析主机名: $hostname (${e.message})")
                    }
                }
                if (addresses.isEmpty()) {
                    throw java.net.UnknownHostException("主机名解析结果为空: $hostname")
                }
                return addresses.sortedWith(Comparator { a, b ->
                    when {
                        a is java.net.Inet4Address && b !is java.net.Inet4Address -> -1
                        a !is java.net.Inet4Address && b is java.net.Inet4Address -> 1
                        else -> 0
                    }
                })
            }
        }

        val prefs = com.taolesi.nas.data.PreferencesManager(this)
        // 温和节流：相册缩略图并发请求上限严格控制在 3 以内，彻底消除大量图片并发解码请求打满 NAS 服务端 CPU 100% 的隐患
        val imageConcurrent = prefs.maxConcurrentRequests.coerceIn(1, 3)

        val okHttpClient = OkHttpClient.Builder()
            .dns(dualStackDns)
            .connectionPool(okhttp3.ConnectionPool(8, 5, TimeUnit.MINUTES))
            .dispatcher(okhttp3.Dispatcher().apply {
                maxRequests = imageConcurrent * 2
                maxRequestsPerHost = imageConcurrent
            })
            .connectTimeout(24, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(com.taolesi.nas.net.WebDavClient.Gateway5xxRetryInterceptor(maxRetries = 2))
            .build()

        return ImageLoader.Builder(this)
            .okHttpClient(okHttpClient)
            .components {
                if (Build.VERSION.SDK_INT >= 28) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
                add(coil.decode.VideoFrameDecoder.Factory())
            }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.30)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(512L * 1024 * 1024)
                    .build()
            }
            .crossfade(150)
            .respectCacheHeaders(false)
            .build()
    }
}