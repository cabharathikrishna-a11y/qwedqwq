package com.example.util

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * Unified Internal Storage Manager for Life OS.
 * Handles fast, memory-safe, and structured file reads/writes, media caching,
 * data persistence, and FileProvider URI resolution across all application modules.
 */
object InternalStorageManager {

    private const val TAG = "InternalStorageManager"
    private const val DEFAULT_BUFFER_SIZE = 32 * 1024 // 32KB buffer for high-speed transfer

    /**
     * Memory-safe buffered stream copier.
     */
    fun copyStream(input: InputStream, output: java.io.OutputStream, bufferSize: Int = DEFAULT_BUFFER_SIZE): Long {
        val buffer = ByteArray(bufferSize)
        var totalBytes = 0L
        var bytesRead: Int
        while (input.read(buffer).also { bytesRead = it } != -1) {
            output.write(buffer, 0, bytesRead)
            totalBytes += bytesRead
        }
        output.flush()
        return totalBytes
    }

    /**
     * High-speed file copy using buffered streams.
     */
    fun copyFile(source: File, destination: File): Boolean {
        if (!source.exists()) return false
        return try {
            destination.parentFile?.mkdirs()
            FileInputStream(source).use { input ->
                FileOutputStream(destination).use { output ->
                    copyStream(input, output)
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy file ${source.name} to ${destination.name}", e)
            false
        }
    }

    /**
     * Categories/Subdirectories for organizing app data cleanly inside internal storage.
     */
    object Category {
        const val JOURNAL = "journal_media"
        const val NOTES = "notes_attachments"
        const val CONTACTS = "contact_avatars"
        const val CHAT = "chat_media"
        const val BACKUPS = "db_backups"
        const val CACHE = "app_cache"
        const val GENERAL = "general_files"
    }

    /**
     * Data class summarizing storage space statistics.
     */
    data class StorageStats(
        val usedBytes: Long,
        val freeBytes: Long,
        val totalBytes: Long,
        val appFilesCount: Int
    ) {
        val usedMb: Double get() = usedBytes / (1024.0 * 1024.0)
        val freeMb: Double get() = freeBytes / (1024.0 * 1024.0)
        val totalMb: Double get() = totalBytes / (1024.0 * 1024.0)
    }

    /**
     * Retrieves the root internal app directory, creating it if necessary.
     */
    fun getRootDir(context: Context): File {
        return StorageHelper.getAppFilesDir(context)
    }

    /**
     * Resolves or creates a specific sub-folder inside the internal storage.
     */
    fun getFolder(context: Context, category: String): File {
        val root = getRootDir(context)
        val dir = File(root, category)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Returns a target File reference safely, ensuring parent directories exist.
     */
    fun getFile(context: Context, category: String, fileName: String): File {
        val folder = getFolder(context, category)
        return File(folder, fileName)
    }

    /**
     * Fast string content saver to internal storage.
     */
    suspend fun writeText(
        context: Context,
        category: String,
        fileName: String,
        content: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val file = getFile(context, category, fileName)
            file.writeText(content, StandardCharsets.UTF_8)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write text to file $fileName in $category", e)
            false
        }
    }

    /**
     * Fast string content reader from internal storage.
     */
    suspend fun readText(
        context: Context,
        category: String,
        fileName: String
    ): String? = withContext(Dispatchers.IO) {
        try {
            val file = getFile(context, category, fileName)
            if (file.exists() && file.isFile) {
                file.readText(StandardCharsets.UTF_8)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read text from file $fileName in $category", e)
            null
        }
    }

    /**
     * Saves raw ByteArray to internal storage with fast buffered output stream.
     */
    suspend fun writeBytes(
        context: Context,
        category: String,
        fileName: String,
        data: ByteArray
    ): File? = withContext(Dispatchers.IO) {
        try {
            val file = getFile(context, category, fileName)
            FileOutputStream(file).use { out ->
                out.write(data)
                out.flush()
            }
            file
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write bytes to $fileName in $category", e)
            null
        }
    }

    /**
     * Reads raw ByteArray from internal storage.
     */
    suspend fun readBytes(
        context: Context,
        category: String,
        fileName: String
    ): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val file = getFile(context, category, fileName)
            if (file.exists() && file.isFile) {
                file.readBytes()
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read bytes from $fileName in $category", e)
            null
        }
    }

    /**
     * Efficiently streams input stream into internal storage file (supports large videos & media).
     */
    suspend fun saveFromStream(
        context: Context,
        category: String,
        fileName: String,
        inputStream: InputStream
    ): File? = withContext(Dispatchers.IO) {
        try {
            val file = getFile(context, category, fileName)
            FileOutputStream(file).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                }
                output.flush()
            }
            file
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save stream to $fileName in $category", e)
            null
        }
    }

    /**
     * Copies external content URI (from camera, file picker, intent) to internal storage sandbox.
     */
    suspend fun copyUriToInternal(
        context: Context,
        category: String,
        sourceUri: Uri,
        preferredFileName: String? = null
    ): File? = withContext(Dispatchers.IO) {
        try {
            val file = StorageHelper.copyFileToInternalSandbox(context, sourceUri)
            if (file != null && preferredFileName != null) {
                val targetFile = getFile(context, category, preferredFileName)
                file.copyTo(targetFile, overwrite = true)
                file.delete()
                targetFile
            } else {
                file
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy URI $sourceUri to internal category $category", e)
            null
        }
    }

    /**
     * Gets a FileProvider Uri safely for sharing files or opening external intents.
     */
    fun getShareableUri(context: Context, file: File): Uri? {
        return try {
            val authority = "${context.packageName}.fileprovider"
            FileProvider.getUriForFile(context, authority, file)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate FileProvider URI for ${file.absolutePath}", e)
            null
        }
    }

    /**
     * Lists all files residing in a particular category folder.
     */
    fun listFiles(context: Context, category: String): List<File> {
        val folder = getFolder(context, category)
        return folder.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    /**
     * Deletes a specific file from internal storage.
     */
    fun deleteFile(context: Context, category: String, fileName: String): Boolean {
        val file = getFile(context, category, fileName)
        return if (file.exists()) file.delete() else false
    }

    /**
     * Clears all temporary cache files safely.
     */
    suspend fun clearCache(context: Context): Long = withContext(Dispatchers.IO) {
        var bytesFreed = 0L
        try {
            val cacheFolder = getFolder(context, Category.CACHE)
            cacheFolder.listFiles()?.forEach { file ->
                if (file.isFile) {
                    bytesFreed += file.length()
                    file.delete()
                }
            }
            val appCache = context.cacheDir
            appCache.listFiles()?.forEach { file ->
                if (file.isFile) {
                    bytesFreed += file.length()
                    file.delete()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing internal cache", e)
        }
        bytesFreed
    }

    /**
     * Calculates storage space statistics for internal storage.
     */
    fun getStorageStats(context: Context): StorageStats {
        val root = getRootDir(context)
        val freeBytes = root.freeSpace
        val totalBytes = root.totalSpace
        var usedBytes = 0L
        var count = 0

        fun calculateSize(dir: File) {
            dir.listFiles()?.forEach { f ->
                if (f.isFile) {
                    usedBytes += f.length()
                    count++
                } else if (f.isDirectory) {
                    calculateSize(f)
                }
            }
        }

        calculateSize(root)

        return StorageStats(
            usedBytes = usedBytes,
            freeBytes = freeBytes,
            totalBytes = totalBytes,
            appFilesCount = count
        )
    }
}
