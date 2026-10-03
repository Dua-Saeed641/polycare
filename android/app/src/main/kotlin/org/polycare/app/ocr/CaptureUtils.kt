package org.polycare.app.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.File

/** Where a captured photo goes before OCR reads it back; matches `res/xml/file_paths.xml`. */
object CaptureUtils {

    fun newPhotoFile(context: Context): File {
        val dir = File(context.cacheDir, "scans").apply { mkdirs() }
        return File(dir, "scan_${System.currentTimeMillis()}.jpg")
    }

    fun photoUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /**
     * Reads [uri] into memory once (avoids multiple ContentResolver opens, which can fail on some
     * OEM cameras that write asynchronously and on gallery URIs that don't allow re-opening),
     * then decodes a downscaled bitmap (long side ≤ [maxDim]) with EXIF rotation applied.
     * Returns null if the URI cannot be read, is empty, or cannot be decoded as an image.
     */
    fun loadForOcr(context: Context, uri: Uri, maxDim: Int = 2048): Pair<Bitmap, Int>? {
        // Read the entire URI content into memory in one pass.
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null

        // First pass: get the image dimensions without allocating a bitmap.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // Compute the largest power-of-two sample that keeps the long side within maxDim.
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxDim || bounds.outHeight / (sample * 2) >= maxDim) sample *= 2

        // Second pass: decode the (possibly downsampled) bitmap.
        val bitmap = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null

        // Read EXIF orientation from the same byte buffer (no extra I/O).
        val degrees = runCatching {
            ByteArrayInputStream(bytes).use { stream ->
                when (ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL,
                )) {
                    ExifInterface.ORIENTATION_ROTATE_90  -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            }
        }.getOrDefault(0)

        // Apply rotation in-place so callers never have to guess whether the bitmap is
        // upright — ML Kit still accepts a rotated bitmap but it's cleaner this way.
        val rotated = if (degrees == 0) bitmap else {
            val m = Matrix().apply { postRotate(degrees.toFloat()) }
            val r = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
            if (r !== bitmap) bitmap.recycle()
            r
        }
        return rotated to 0  // rotation already baked in, report 0 degrees to caller
    }
}
