package com.fll.pushtogithub

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import java.io.FileOutputStream

/**
 * High-performance L1 (Memory) and L2 (Disk) Image Cache for rendered Scratch
 * code previews and diagrams.
 *
 * Eliminates redundant network calls and SVG rendering overhead, allowing
 * previews to display instantly (0ms) on scroll and activity navigation.
 */
object ImageCache {

    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = maxMemory / 8 // Use 1/8th of available app memory for image cache

    private val memoryCache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return bitmap.byteCount / 1024
        }
    }

    /** Get cached bitmap from L1 Memory or L2 Disk cache. */
    fun get(context: Context, key: String): Bitmap? {
        // 1. Check L1 Memory Cache (Instant 0ms)
        val memBitmap = memoryCache.get(key)
        if (memBitmap != null && !memBitmap.isRecycled) {
            return memBitmap
        }

        // 2. Check L2 Disk Cache
        val diskFile = getDiskCacheFile(context, key)
        if (diskFile.exists()) {
            val diskBitmap = runCatching { BitmapFactory.decodeFile(diskFile.absolutePath) }.getOrNull()
            if (diskBitmap != null) {
                memoryCache.put(key, diskBitmap)
                return diskBitmap
            }
        }

        return null
    }

    /** Save bitmap to L1 Memory and L2 Disk cache. */
    fun put(context: Context, key: String, bitmap: Bitmap) {
        if (bitmap.isRecycled) return

        // 1. Put in Memory Cache
        memoryCache.put(key, bitmap)

        // 2. Put in Disk Cache
        runCatching {
            val diskFile = getDiskCacheFile(context, key)
            FileOutputStream(diskFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }
    }

    private fun getDiskCacheFile(context: Context, key: String): File {
        val dir = File(context.cacheDir, "preview_cache")
        if (!dir.exists()) dir.mkdirs()
        val safeKey = key.replace(Regex("[^a-zA-Z0-9._\\-]"), "_")
        return File(dir, "$safeKey.png")
    }
}
