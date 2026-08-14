package com.example.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern

object DriveUrlUtil {

    private const val TAG = "DriveUrlUtil"

    data class DriveAttachmentInfo(
        val type: String, // "voice", "audio", "video", "image", "doc"
        val directUrl: String,
        val fileName: String,
        val durationSec: Int = 0,
        val fileSizeKb: Int = 0
    )

    data class DriveMediaInfo(
        val fileName: String,
        val fileSize: Long,
        val mimeType: String,
        val directUrl: String,
        val isFolder: Boolean,
        val fileId: String?
    )

    /**
     * Resolves metadata (file name, size, mime type, direct download url, folder status)
     * from any Google Drive link or direct cloud URL.
     */
    suspend fun resolveDriveMediaInfo(
        context: Context,
        rawUrl: String,
        suggestedTitle: String? = null
    ): DriveMediaInfo = withContext(Dispatchers.IO) {
        val trimmed = rawUrl.trim()
        val isFolder = trimmed.contains("drive.google.com/drive/folders") ||
                trimmed.contains("drive.google.com/drive/u/0/folders") ||
                trimmed.contains("drive.google.com/folderview") ||
                trimmed.contains("/folders/")

        var fileId: String? = null
        if (trimmed.contains("/folders/")) {
            fileId = trimmed.substringAfter("/folders/").substringBefore("?").substringBefore("/")
        } else if (trimmed.contains("/file/d/")) {
            fileId = trimmed.substringAfter("/file/d/").substringBefore("?").substringBefore("/")
        } else if (trimmed.contains("id=")) {
            fileId = trimmed.substringAfter("id=").substringBefore("&")
        }

        if (isFolder) {
            val folderName = suggestedTitle?.ifBlank { null }
                ?: if (!fileId.isNullOrBlank()) "Drive_Folder_${fileId.take(8)}" else "Google_Drive_Folder"
            return@withContext DriveMediaInfo(
                fileName = folderName,
                fileSize = 0L,
                mimeType = "application/vnd.google-apps.folder-link",
                directUrl = trimmed,
                isFolder = true,
                fileId = fileId
            )
        }

        val directUrl = toDirectDownloadUrl(trimmed)
        var detectedName = suggestedTitle?.ifBlank { null }
        var detectedSize = 0L
        var detectedMime = when {
            trimmed.contains("docs.google.com/document") -> "application/vnd.google-apps.document"
            trimmed.contains("docs.google.com/spreadsheets") -> "application/vnd.google-apps.spreadsheet"
            trimmed.contains("docs.google.com/presentation") -> "application/vnd.google-apps.presentation"
            trimmed.contains("youtube.com") || trimmed.contains("youtu.be") -> "video/youtube-link"
            trimmed.contains("zoom.us") -> "application/zoom-link"
            trimmed.lowercase().endsWith(".pdf") -> "application/pdf"
            trimmed.lowercase().endsWith(".mp4") -> "video/mp4"
            trimmed.lowercase().endsWith(".mp3") -> "audio/mpeg"
            trimmed.lowercase().endsWith(".jpg") || trimmed.lowercase().endsWith(".jpeg") -> "image/jpeg"
            trimmed.lowercase().endsWith(".png") -> "image/png"
            trimmed.lowercase().endsWith(".zip") -> "application/zip"
            else -> "application/octet-stream"
        }

        try {
            val urlObj = URL(directUrl)
            val conn = urlObj.openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.requestMethod = "HEAD"
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; LifeOS)")
            conn.connect()

            val contentLength = conn.contentLengthLong
            if (contentLength > 0) {
                detectedSize = contentLength
            }

            val contentType = conn.contentType
            if (!contentType.isNullOrBlank()) {
                val cleanType = contentType.substringBefore(";").trim()
                if (cleanType.isNotBlank() && cleanType != "text/html") {
                    detectedMime = cleanType
                }
            }

            val disposition = conn.getHeaderField("Content-Disposition")
            if (!disposition.isNullOrBlank() && disposition.contains("filename=")) {
                val extracted = disposition.substringAfter("filename=").replace("\"", "").trim()
                if (extracted.isNotBlank() && detectedName.isNullOrBlank()) {
                    detectedName = extracted
                }
            }
            conn.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "HEAD request failed for $directUrl, attempting fallback resolution: ${e.message}")
        }

        if (detectedName.isNullOrBlank()) {
            detectedName = when {
                !fileId.isNullOrBlank() -> "Drive_File_${fileId.take(8)}"
                trimmed.contains("/") -> trimmed.substringAfterLast("/").substringBefore("?").ifBlank { "drive_media_asset" }
                else -> "drive_media_asset"
            }
        }

        // Add appropriate extension if missing
        if (!detectedName!!.contains(".")) {
            val ext = when (detectedMime) {
                "application/pdf" -> ".pdf"
                "video/mp4" -> ".mp4"
                "audio/mpeg", "audio/mp3" -> ".mp3"
                "image/jpeg" -> ".jpg"
                "image/png" -> ".png"
                "application/vnd.google-apps.document" -> ".gdoc"
                "application/vnd.google-apps.spreadsheet" -> ".gsheet"
                "application/zip" -> ".zip"
                else -> ""
            }
            if (ext.isNotEmpty()) {
                detectedName = "$detectedName$ext"
            }
        }

        if (detectedSize == 0L) {
            // Default reasonable fallback estimation if server doesn't return content-length
            detectedSize = 2500000L
        }

        return@withContext DriveMediaInfo(
            fileName = detectedName!!,
            fileSize = detectedSize,
            mimeType = detectedMime,
            directUrl = directUrl,
            isFolder = false,
            fileId = fileId
        )
    }

    /**
     * Downloads media from Google Drive / URL to app sandbox, and optionally deletes
     * any duplicate or original local file from device storage if setting is enabled.
     */
    suspend fun downloadDriveMediaAndCleanLocal(
        context: Context,
        directUrl: String,
        fileName: String,
        deleteOriginalDeviceMedia: Boolean
    ): File? = withContext(Dispatchers.IO) {
        try {
            val appFilesDir = StorageHelper.getAppFilesDir(context)
            val cleanName = File(fileName).name.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
            val targetFile = File(appFilesDir, cleanName)

            var downloadSuccess = false
            if (targetFile.exists() && targetFile.length() > 0) {
                downloadSuccess = true
            } else {
                val connection = URL(directUrl).openConnection() as HttpURLConnection
                connection.connectTimeout = 20000
                connection.readTimeout = 20000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; LifeOS)")
                connection.connect()

                if (connection.responseCode in 200..299) {
                    connection.inputStream.use { input ->
                        FileOutputStream(targetFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    downloadSuccess = true
                    Log.d(TAG, "Successfully downloaded Drive media to ${targetFile.absolutePath}")
                } else {
                    Log.e(TAG, "Failed to download Drive media: HTTP ${connection.responseCode}")
                }
            }

            if (downloadSuccess && deleteOriginalDeviceMedia) {
                // Perform device cleanup: delete cached temp files matching this name or in cache
                try {
                    val cacheFiles = context.cacheDir.listFiles() ?: emptyArray()
                    for (f in cacheFiles) {
                        if (f.isFile && (f.name == fileName || f.name.contains(cleanName) || f.name.startsWith("raw_upload_"))) {
                            f.delete()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error cleaning cache files: ${e.message}")
                }
            }

            return@withContext if (downloadSuccess) targetFile else null
        } catch (e: Exception) {
            Log.e(TAG, "Error in downloadDriveMediaAndCleanLocal: ${e.message}", e)
            return@withContext null
        }
    }

    /**
     * Formats a Drive sharing attachment into a standard formatted text string payload.
     * Format: [ATTACHMENT:type|url|fileName|durationSec|fileSizeKb]
     */
    fun formatAttachmentText(
        type: String,
        driveSharingUrl: String,
        fileName: String,
        durationSec: Int = 0,
        fileSizeKb: Int = 0
    ): String {
        val directUrl = toDirectDownloadUrl(driveSharingUrl)
        return "[ATTACHMENT:$type|$directUrl|$fileName|$durationSec|$fileSizeKb]"
    }

    /**
     * Parses a text message to detect if it is a formatted Drive attachment string.
     */
    fun parseAttachmentText(rawText: String): DriveAttachmentInfo? {
        if (!rawText.startsWith("[ATTACHMENT:") || !rawText.endsWith("]")) {
            // Also check for raw drive link containing keywords
            if ((rawText.contains("drive.google.com") || rawText.contains("docs.google.com"))) {
                val direct = toDirectDownloadUrl(rawText.trim())
                val lower = rawText.lowercase()
                val type = when {
                    lower.endsWith(".mp3") || lower.contains("audio") || lower.contains("voice") -> "voice"
                    lower.endsWith(".mp4") || lower.contains("video") -> "video"
                    lower.endsWith(".jpg") || lower.endsWith(".png") || lower.contains("image") -> "image"
                    else -> "doc"
                }
                return DriveAttachmentInfo(type = type, directUrl = direct, fileName = "drive_file")
            }
            return null
        }

        try {
            val inner = rawText.substring("[ATTACHMENT:".length, rawText.length - 1)
            val parts = inner.split("|")
            if (parts.size >= 3) {
                val type = parts[0]
                val directUrl = toDirectDownloadUrl(parts[1])
                val fileName = parts[2]
                val durationSec = parts.getOrNull(3)?.toIntOrNull() ?: 0
                val fileSizeKb = parts.getOrNull(4)?.toIntOrNull() ?: 0
                return DriveAttachmentInfo(
                    type = type,
                    directUrl = directUrl,
                    fileName = fileName,
                    durationSec = durationSec,
                    fileSizeKb = fileSizeKb
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing attachment text: $rawText", e)
        }
        return null
    }

    /**
     * Converts Google Drive and Adobe Acrobat web view/sharing links into direct export download URLs.
     * Example input: https://drive.google.com/file/d/1A2B3C4D5E6F7G8H9/view?usp=sharing
     * Output: https://drive.google.com/uc?export=download&id=1A2B3C4D5E6F7G8H9
     * Example Adobe: https://acrobat.adobe.com/link/track?uri=urn:aaid:sc:US:1234
     * Output: https://acrobat.adobe.com/link/content/urn:aaid:sc:US:1234
     */
    fun toDirectDownloadUrl(url: String): String {
        if (url.isBlank()) return url
        val trimmed = url.trim()

        // 1. Google Drive / Docs conversion
        if (trimmed.contains("drive.google.com") || trimmed.contains("docs.google.com")) {
            val patternFileD = Pattern.compile("/file/d/([a-zA-Z0-9_-]+)")
            val matcherFileD = patternFileD.matcher(trimmed)
            if (matcherFileD.find()) {
                val fileId = matcherFileD.group(1)
                if (!fileId.isNullOrEmpty()) {
                    return "https://drive.google.com/uc?export=download&id=$fileId"
                }
            }

            val patternIdParam = Pattern.compile("[?&]id=([a-zA-Z0-9_-]+)")
            val matcherIdParam = patternIdParam.matcher(trimmed)
            if (matcherIdParam.find()) {
                val fileId = matcherIdParam.group(1)
                if (!fileId.isNullOrEmpty()) {
                    return "https://drive.google.com/uc?export=download&id=$fileId"
                }
            }
            return trimmed
        }

        // 2. Adobe Acrobat / Document Cloud conversion
        if (trimmed.contains("adobe.com") || trimmed.contains("adobe.ly")) {
            val patternUrn = Pattern.compile("(urn:aaid:sc:[a-zA-Z0-9_-]+:[a-zA-Z0-9_-]+)")
            val matcherUrn = patternUrn.matcher(trimmed)
            if (matcherUrn.find()) {
                val urn = matcherUrn.group(1)
                if (!urn.isNullOrEmpty()) {
                    return "https://acrobat.adobe.com/link/content/$urn"
                }
            }
            return trimmed
        }

        return trimmed
    }

    /**
     * Checks if a given text string or URL represents a Google Drive, Adobe, or Cloud PDF link.
     */
    fun isCloudPdfUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val lower = url.lowercase().trim()
        return lower.contains("drive.google.com") ||
                lower.contains("docs.google.com") ||
                lower.contains("acrobat.adobe.com") ||
                lower.contains("documentcloud.adobe.com") ||
                lower.contains("adobe.ly") ||
                lower.contains("adobe.com") ||
                lower.endsWith(".pdf") ||
                lower.contains("/pdf")
    }

    /**
     * Extracts http or https URL from shared raw text.
     */
    fun extractUrlFromText(text: String): String? {
        if (text.isBlank()) return null
        val pattern = Pattern.compile("https?://[a-zA-Z0-9_.-]+(?:/[^\\s]*)?")
        val matcher = pattern.matcher(text)
        return if (matcher.find()) matcher.group(0) else null
    }

    /**
     * Silently downloads media/doc/voice payloads to local device storage and returns local File path.
     */
    suspend fun downloadMediaToLocal(
        context: Context,
        contentUrl: String,
        suggestedFileName: String?
    ): File? = withContext(Dispatchers.IO) {
        try {
            val directUrlStr = toDirectDownloadUrl(contentUrl)
            val mediaDir = File(context.filesDir, "chat_media").apply { if (!exists()) mkdirs() }
            val fileName = suggestedFileName?.ifBlank { null } ?: "media_${System.currentTimeMillis()}.dat"
            val targetFile = File(mediaDir, fileName)

            if (targetFile.exists() && targetFile.length() > 0) {
                return@withContext targetFile
            }

            val connection = URL(directUrlStr).openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.instanceFollowRedirects = true
            connection.connect()

            if (connection.responseCode in 200..299) {
                connection.inputStream.use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                }
                Log.d(TAG, "Successfully downloaded chat media to ${targetFile.absolutePath}")
                return@withContext targetFile
            } else {
                Log.e(TAG, "Download failed with HTTP code ${connection.responseCode} for $directUrlStr")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading chat media from $contentUrl", e)
        }
        return@withContext null
    }
}
