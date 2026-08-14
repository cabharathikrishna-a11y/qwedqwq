package com.example.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.example.data.JournalEntry
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

object WidgetPhotoManager {

    private const val TAG = "WidgetPhotoManager"

    fun getWidgetPhotoDir(context: Context): File {
        val dir = File(context.filesDir, "widget_photos")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun cleanPhotoUrl(photoUrl: String): String {
        var trimmed = photoUrl.trim()
        if (trimmed.startsWith("photo:", ignoreCase = true)) {
            trimmed = trimmed.substring(6).trim()
        }
        return trimmed
    }

    fun isPhotoAttachment(attach: String): Boolean {
        val trimmed = attach.trim()
        if (trimmed.isEmpty()) return false
        val lower = trimmed.lowercase()

        if (lower.startsWith("author:") || lower.startsWith("folderlink:") ||
            lower.startsWith("loc:") || lower.startsWith("audio:") || lower.startsWith("video:")) {
            return false
        }

        return lower.startsWith("photo:") ||
                lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                lower.endsWith(".png") || lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".bmp") ||
                lower.startsWith("file://") || lower.startsWith("content://") || lower.startsWith("data:image/") ||
                lower.startsWith("http://") || lower.startsWith("https://") ||
                (lower.startsWith("/") && (lower.contains("image") || lower.contains("photo") || lower.contains("dcim") || lower.contains("pictures") || lower.contains("download") || lower.contains("cache")))
    }

    fun getHashForPhotoUrl(photoUrl: String): String {
        val cleaned = cleanPhotoUrl(photoUrl)
        return try {
            val digest = MessageDigest.getInstance("MD5")
            digest.update(cleaned.toByteArray(Charsets.UTF_8))
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            cleaned.hashCode().toString().replace("-", "n")
        }
    }

    fun getWidgetCopyFile(context: Context, photoUrl: String): File {
        val hash = getHashForPhotoUrl(photoUrl)
        return File(getWidgetPhotoDir(context), "widget_copy_$hash.jpg")
    }

    fun ensureWidgetCopy(
        context: Context,
        photoUrl: String,
        targetWidthPx: Int = 360,
        targetHeightPx: Int = 240
    ): File? {
        val cleaned = cleanPhotoUrl(photoUrl)
        if (cleaned.isEmpty() || !isPhotoAttachment(photoUrl)) return null

        val targetFile = getWidgetCopyFile(context, photoUrl)
        if (targetFile.exists() && targetFile.length() > 0) {
            return targetFile
        }

        return try {
            val decodedBitmap = decodeOriginalPhotoBitmap(context, cleaned, targetWidthPx, targetHeightPx)
            if (decodedBitmap != null) {
                targetFile.outputStream().use { out ->
                    decodedBitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                }
                Log.d(TAG, "Created widget copy at ${targetFile.absolutePath} for $cleaned")
                targetFile
            } else {
                Log.e(TAG, "Failed to decode original bitmap for widget copy: $cleaned")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating widget copy for $cleaned: ${e.message}", e)
            null
        }
    }

    fun deleteWidgetCopy(context: Context, photoUrl: String) {
        try {
            val file = getWidgetCopyFile(context, photoUrl)
            if (file.exists()) {
                val deleted = file.delete()
                Log.d(TAG, "Deleted widget copy ${file.absolutePath}: $deleted")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete widget copy for $photoUrl: ${e.message}")
        }
    }

    fun deleteWidgetCopiesForEntry(context: Context, entry: JournalEntry) {
        if (entry.attachmentsJson.isEmpty()) return
        val attachments = entry.attachmentsJson.split(";;")
        for (attach in attachments) {
            if (isPhotoAttachment(attach)) {
                deleteWidgetCopy(context, attach)
            }
        }
    }

    fun cleanupOrphanedWidgetCopies(context: Context, currentEntries: List<JournalEntry>) {
        try {
            val activeHashes = mutableSetOf<String>()
            for (entry in currentEntries) {
                if (entry.attachmentsJson.isEmpty()) continue
                val attachments = entry.attachmentsJson.split(";;")
                for (attach in attachments) {
                    if (isPhotoAttachment(attach)) {
                        activeHashes.add(getHashForPhotoUrl(attach))
                    }
                }
            }

            val dir = getWidgetPhotoDir(context)
            val files = dir.listFiles() ?: return
            for (file in files) {
                if (file.name.startsWith("widget_copy_") && file.name.endsWith(".jpg")) {
                    val fileHash = file.name.removePrefix("widget_copy_").removeSuffix(".jpg")
                    if (!activeHashes.contains(fileHash)) {
                        val deleted = file.delete()
                        Log.d(TAG, "Cleaned up orphaned widget copy ${file.name}: $deleted")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during widget photo cleanup: ${e.message}")
        }
    }

    fun syncWidgetCopiesForEntries(context: Context, entries: List<JournalEntry>) {
        for (entry in entries) {
            if (entry.attachmentsJson.isEmpty()) continue
            val attachments = entry.attachmentsJson.split(";;")
            for (attach in attachments) {
                if (isPhotoAttachment(attach)) {
                    ensureWidgetCopy(context, attach)
                }
            }
        }
        cleanupOrphanedWidgetCopies(context, entries)
    }

    private fun decodeOriginalPhotoBitmap(
        context: Context,
        trimmedPath: String,
        targetWidthPx: Int,
        targetHeightPx: Int
    ): Bitmap? {
        if (trimmedPath.isEmpty()) return null

        var rawBitmap: Bitmap? = null
        var exifRotation = 0

        try {
            if (trimmedPath.startsWith("content://")) {
                val uri = Uri.parse(trimmedPath)
                try {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        exifRotation = getRotationFromExif(stream)
                    }
                } catch (_: Exception) {}

                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val bytes = stream.readBytes()
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    options.inSampleSize = calculateInSampleSize(options, targetWidthPx, targetHeightPx)
                    options.inJustDecodeBounds = false
                    rawBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                }
            } else if (trimmedPath.startsWith("/") || trimmedPath.startsWith("file://")) {
                val path = trimmedPath.removePrefix("file://")
                val file = File(path)
                if (file.exists() && file.length() > 0) {
                    try {
                        file.inputStream().use { stream ->
                            exifRotation = getRotationFromExif(stream)
                        }
                    } catch (_: Exception) {}

                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.absolutePath, options)
                    options.inSampleSize = calculateInSampleSize(options, targetWidthPx, targetHeightPx)
                    options.inJustDecodeBounds = false
                    rawBitmap = BitmapFactory.decodeFile(file.absolutePath, options)
                }
            } else if (trimmedPath.startsWith("http://") || trimmedPath.startsWith("https://")) {
                val cacheFile = File(context.cacheDir, "journal_temp_down_${Math.abs(trimmedPath.hashCode())}.png")
                if (cacheFile.exists() && cacheFile.length() > 0) {
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(cacheFile.absolutePath, options)
                    options.inSampleSize = calculateInSampleSize(options, targetWidthPx, targetHeightPx)
                    options.inJustDecodeBounds = false
                    rawBitmap = BitmapFactory.decodeFile(cacheFile.absolutePath, options)
                } else {
                    val url = java.net.URL(trimmedPath)
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 4000
                    conn.readTimeout = 4000
                    conn.doInput = true
                    conn.connect()
                    val bytes = conn.inputStream.use { it.readBytes() }
                    conn.disconnect()
                    cacheFile.writeBytes(bytes)

                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    options.inSampleSize = calculateInSampleSize(options, targetWidthPx, targetHeightPx)
                    options.inJustDecodeBounds = false
                    rawBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                }
            } else if (trimmedPath.startsWith("base64:") || trimmedPath.startsWith("data:image/") || (trimmedPath.length > 80 && !trimmedPath.contains(" "))) {
                val rawData = when {
                    trimmedPath.startsWith("base64:") -> trimmedPath.substringAfter("base64:")
                    trimmedPath.contains("base64,") -> trimmedPath.substringAfter("base64,")
                    else -> trimmedPath
                }
                val bytes = Base64.decode(rawData, Base64.DEFAULT)
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                options.inSampleSize = calculateInSampleSize(options, targetWidthPx, targetHeightPx)
                options.inJustDecodeBounds = false
                rawBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            } else {
                val uri = Uri.parse(trimmedPath)
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val bytes = stream.readBytes()
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    options.inSampleSize = calculateInSampleSize(options, targetWidthPx, targetHeightPx)
                    options.inJustDecodeBounds = false
                    rawBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                }
            }

            if (rawBitmap != null) {
                val orientedBmp = rotateBitmapIfNeeded(rawBitmap!!, exifRotation)
                return renderScaledRoundedBitmap(orientedBmp, targetWidthPx, targetHeightPx, 24f)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding original photo $trimmedPath: ${e.message}")
        }
        return null
    }

    private fun getRotationFromExif(inputStream: InputStream): Int {
        return try {
            val exif = ExifInterface(inputStream)
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (_: Exception) { 0 }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqHeight) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun rotateBitmapIfNeeded(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        return try {
            val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) bitmap.recycle()
            rotated
        } catch (_: Exception) {
            bitmap
        }
    }

    private fun renderScaledRoundedBitmap(src: Bitmap, targetW: Int, targetH: Int, cornerRadiusPx: Float): Bitmap {
        val output = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        val scale = maxOf(targetW.toFloat() / src.width, targetH.toFloat() / src.height)
        val dx = (targetW - src.width * scale) / 2f
        val dy = (targetH - src.height * scale) / 2f

        val matrix = Matrix()
        matrix.setScale(scale, scale)
        matrix.postTranslate(dx, dy)

        val rectF = RectF(0f, 0f, targetW.toFloat(), targetH.toFloat())
        canvas.drawRoundRect(rectF, cornerRadiusPx, cornerRadiusPx, paint)

        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(src, matrix, paint)

        return output
    }
}
