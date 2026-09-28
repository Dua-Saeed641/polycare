package org.polycare.app.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
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
     * Decodes [uri] downscaled to at most [maxDim] on the long side (a full camera photo is far
     * more pixels than OCR needs and risks OOM), plus the EXIF rotation to pass to ML Kit
     * (`InputImage.fromBitmap(bitmap, rotationDegrees)` — simpler than rotating pixels ourselves).
     */
    fun loadForOcr(context: Context, uri: Uri, maxDim: Int = 2048): Pair<Bitmap, Int>? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxDim || bounds.outHeight / (sample * 2) >= maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null

        val degrees = resolver.openInputStream(uri)?.use { stream ->
            when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } ?: 0

        return bitmap to degrees
    }
}
