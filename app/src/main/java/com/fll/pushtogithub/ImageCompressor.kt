package com.fll.pushtogithub

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import java.io.ByteArrayOutputStream

/**
 * Smart image compression for camera photos and large uploaded images.
 *
 * Scales down large camera photos (e.g., 4000x3000px 12MB images) to max 1920px
 * while maintaining aspect ratio and compressing to high-quality JPEG (85%).
 * Reduces file sizes from ~12MB down to ~300KB-800KB with zero visible loss.
 */
object ImageCompressor {

    private const val MAX_DIMENSION = 1920 // Max width or height in pixels

    fun compress(imageBytes: ByteArray, maxDimension: Int = MAX_DIMENSION, quality: Int = 85): ByteArray {
        if (imageBytes.isEmpty()) return imageBytes

        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, options)

        val width = options.outWidth
        val height = options.outHeight

        if (width <= 0 || height <= 0) return imageBytes

        // If image is already smaller than maxDimension and under 1MB, keep original
        if (width <= maxDimension && height <= maxDimension && imageBytes.size < 1_000_000) {
            return imageBytes
        }

        // Calculate sample size scaling
        var sampleSize = 1
        while (width / sampleSize > maxDimension || height / sampleSize > maxDimension) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
        }

        val decodedBitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, decodeOptions)
            ?: return imageBytes

        val scaledBitmap = scaleBitmapToMax(decodedBitmap, maxDimension)

        val outputStream = ByteArrayOutputStream()
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)

        if (scaledBitmap != decodedBitmap) {
            scaledBitmap.recycle()
        }
        decodedBitmap.recycle()

        return outputStream.toByteArray()
    }

    private fun scaleBitmapToMax(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        if (width <= maxDimension && height <= maxDimension) return bitmap

        val scale = if (width >= height) {
            maxDimension.toFloat() / width
        } else {
            maxDimension.toFloat() / height
        }

        val matrix = Matrix().apply {
            postScale(scale, scale)
        }

        return Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true)
    }
}
