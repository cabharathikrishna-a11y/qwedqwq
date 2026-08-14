package com.example.util

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File

/**
 * GoogleDriveUploadManager
 *
 * Dedicated manager for handling all upload operations to Google Drive across the app,
 * including full app data backups, focus timer data, public media files, and document exports.
 */
object GoogleDriveUploadManager {

    private const val TAG = "GoogleDriveUpload"
    private val client = OkHttpClient()
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    /**
     * Uploads full application data backup (JSON/ZIP) to Drive AppData folder or Drive root.
     */
    suspend fun uploadAppDataBackup(
        context: Context,
        database: AppDatabase,
        backupJsonString: String,
        onAuthResolutionRequired: (Intent) -> Unit = {}
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val token = GoogleDriveReadManager.getAccessToken(context, onAuthResolutionRequired)
                ?: return@withContext Pair(false, "Failed to acquire Google Drive access token.")

            val fileName = "app_data_backup.zip"
            val fileId = GoogleDriveReadManager.findFileId(token, fileName)
                ?: GoogleDriveWriteManager.createFileMetadata(token, fileName)
                ?: return@withContext Pair(false, "Could not create backup metadata in Google Drive.")

            val request = Request.Builder()
                .url("https://www.googleapis.com/upload/drive/v3/files/$fileId?uploadType=media")
                .addHeader("Authorization", "Bearer $token")
                .patch(backupJsonString.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.i(TAG, "Successfully uploaded app data backup to Google Drive.")
                    Pair(true, "App data uploaded successfully to Google Drive.")
                } else {
                    val err = response.body?.string() ?: ""
                    Log.e(TAG, "Failed to upload backup content: ${response.code} $err")
                    Pair(false, "Drive upload failed with status ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading app data backup to Drive", e)
            Pair(false, "Upload error: ${e.message}")
        }
    }

    /**
     * Uploads focus session records to Google Drive.
     */
    suspend fun uploadFocusData(
        context: Context,
        focusJsonString: String,
        onAuthResolutionRequired: (Intent) -> Unit = {}
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val token = GoogleDriveReadManager.getAccessToken(context, onAuthResolutionRequired)
                ?: return@withContext Pair(false, "No Google access token.")

            val fileName = "focus_backup.json"
            val fileId = GoogleDriveReadManager.findFileId(token, fileName)
                ?: GoogleDriveWriteManager.createFileMetadata(token, fileName)
                ?: return@withContext Pair(false, "Failed to initialize Drive file metadata.")

            val request = Request.Builder()
                .url("https://www.googleapis.com/upload/drive/v3/files/$fileId?uploadType=media")
                .addHeader("Authorization", "Bearer $token")
                .patch(focusJsonString.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Pair(true, "Focus data backup uploaded successfully.")
                } else {
                    Pair(false, "Upload failed HTTP ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Focus data upload error", e)
            Pair(false, "Focus upload error: ${e.message}")
        }
    }

    /**
     * Uploads a public media file directly to Google Drive and sets public read permissions.
     */
    suspend fun uploadPublicMediaFileDirect(
        context: Context,
        token: String,
        file: File,
        mimeType: String = "image/jpeg",
        categoryFolder: String = "General_Files"
    ): String? = withContext(Dispatchers.IO) {
        try {
            if (!file.exists()) {
                Log.e(TAG, "Media file does not exist: ${file.absolutePath}")
                return@withContext null
            }

            val vault = ensureVaultStructureAndReadme(token)
            val folderId = when (categoryFolder) {
                "Task_Attachments" -> vault?.taskAttachmentsId
                "Shared_Media" -> vault?.sharedMediaId
                "App_Backups" -> vault?.backupsId
                else -> vault?.generalFilesId
            }

            if (folderId != null) {
                return@withContext uploadPublicMediaFileToFolderDirect(context, token, file, folderId, mimeType)
            }

            // Create Metadata
            val metaJson = JSONObject().apply {
                put("name", file.name)
                put("mimeType", mimeType)
            }

            val metaReq = Request.Builder()
                .url("https://www.googleapis.com/drive/v3/files")
                .addHeader("Authorization", "Bearer $token")
                .post(metaJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            var createdFileId: String? = null
            client.newCall(metaReq).execute().use { res ->
                if (res.isSuccessful) {
                    val respObj = JSONObject(res.body?.string() ?: "{}")
                    createdFileId = respObj.optString("id")
                }
            }

            val fileId = createdFileId ?: return@withContext null

            // Upload Content
            val mediaType = mimeType.toMediaType()
            val uploadReq = Request.Builder()
                .url("https://www.googleapis.com/upload/drive/v3/files/$fileId?uploadType=media")
                .addHeader("Authorization", "Bearer $token")
                .patch(file.asRequestBody(mediaType))
                .build()

            client.newCall(uploadReq).execute().use { res ->
                if (!res.isSuccessful) {
                    Log.e(TAG, "Media content upload failed: ${res.code}")
                    return@withContext null
                }
            }

            // Set Public Permissions
            GoogleDriveWriteManager.makeFilePublic(token, fileId)

            val sharingUrl = "https://drive.google.com/uc?export=view&id=$fileId"
            Log.i(TAG, "Successfully uploaded public media file. Sharing URL: $sharingUrl")
            sharingUrl
        } catch (e: Exception) {
            Log.e(TAG, "Error in uploadPublicMediaFileDirect", e)
            null
        }
    }

    /**
     * Uploads a media file directly to a specific folder in Google Drive.
     */
    suspend fun uploadPublicMediaFileToFolderDirect(
        context: Context,
        token: String,
        file: File,
        folderId: String,
        mimeType: String = "image/jpeg"
    ): String? = withContext(Dispatchers.IO) {
        try {
            if (!file.exists()) return@withContext null

            val metaJson = JSONObject().apply {
                put("name", file.name)
                put("mimeType", mimeType)
                put("parents", org.json.JSONArray().put(folderId))
            }

            val metaReq = Request.Builder()
                .url("https://www.googleapis.com/drive/v3/files")
                .addHeader("Authorization", "Bearer $token")
                .post(metaJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            var createdFileId: String? = null
            client.newCall(metaReq).execute().use { res ->
                if (res.isSuccessful) {
                    val respObj = JSONObject(res.body?.string() ?: "{}")
                    createdFileId = respObj.optString("id")
                }
            }

            val fileId = createdFileId ?: return@withContext null

            val mediaType = mimeType.toMediaType()
            val uploadReq = Request.Builder()
                .url("https://www.googleapis.com/upload/drive/v3/files/$fileId?uploadType=media")
                .addHeader("Authorization", "Bearer $token")
                .patch(file.asRequestBody(mediaType))
                .build()

            client.newCall(uploadReq).execute().use { res ->
                if (!res.isSuccessful) return@withContext null
            }

            GoogleDriveWriteManager.makeFilePublicAndEditor(token, fileId)
            "https://drive.google.com/uc?export=view&id=$fileId"
        } catch (e: Exception) {
            Log.e(TAG, "Error in uploadPublicMediaFileToFolderDirect", e)
            null
        }
    }

    data class VaultFolders(
        val rootId: String,
        val backupsId: String,
        val taskAttachmentsId: String,
        val sharedMediaId: String,
        val generalFilesId: String
    )

    fun ensureVaultStructureAndReadme(token: String): VaultFolders? {
        val rootId = findOrCreateSharedFolder(token, "LifeOS_Cloud_Vault") ?: return null
        val backupsId = findOrCreateSubFolderInParent(token, rootId, "App_Backups") ?: rootId
        val taskAttachmentsId = findOrCreateSubFolderInParent(token, rootId, "Task_Attachments") ?: rootId
        val sharedMediaId = findOrCreateSubFolderInParent(token, rootId, "Shared_Media") ?: rootId
        val generalFilesId = findOrCreateSubFolderInParent(token, rootId, "General_Files") ?: rootId

        ensureReadmeFile(token, rootId)

        return VaultFolders(
            rootId = rootId,
            backupsId = backupsId,
            taskAttachmentsId = taskAttachmentsId,
            sharedMediaId = sharedMediaId,
            generalFilesId = generalFilesId
        )
    }

    private fun findOrCreateSubFolderInParent(token: String, parentId: String, subFolderName: String): String? {
        try {
            val query = "name = '$subFolderName' and '$parentId' in parents and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "https://www.googleapis.com/drive/v3/files?q=$encodedQuery&fields=files(id)"
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val files = JSONObject(body).getJSONArray("files")
                    if (files.length() > 0) {
                        return files.getJSONObject(0).getString("id")
                    }
                }
            }

            val createUrl = "https://www.googleapis.com/drive/v3/files"
            val body = JSONObject().apply {
                put("name", subFolderName)
                put("mimeType", "application/vnd.google-apps.folder")
                put("parents", org.json.JSONArray().apply { put(parentId) })
            }
            val createRequest = Request.Builder()
                .url(createUrl)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            client.newCall(createRequest).execute().use { response ->
                if (response.isSuccessful) {
                    return JSONObject(response.body?.string() ?: "").getString("id")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in findOrCreateSubFolderInParent $subFolderName", e)
        }
        return null
    }

    private fun ensureReadmeFile(token: String, rootFolderId: String) {
        try {
            val readmeName = "README_DO_NOT_DELETE.txt"
            val existingId = findFileInFolder(token, readmeName, rootFolderId)

            val readmeContent = """
===================================================================
⚠️ CRITICAL NOTICE: DO NOT DELETE OR MODIFY ANYTHING IN THIS FOLDER ⚠️
===================================================================

WHY IS THIS FOLDER STORED HERE?
This Google Drive folder serves as the official cloud synchronization vault, media asset storage,
and disaster recovery backup hub for your LifeOS & Focus App.

ORDERED FOLDER STRUCTURE & WHAT EACH ITEM REPRESENTS:
-------------------------------------------------------------------
1. README_DO_NOT_DELETE.txt
   This manifest file explaining the cloud architecture, folder structure, and safety rules.

2. App_Backups/
   Contains the latest encrypted database & app configuration backup package (lifeos_full_data_backup.zip).
   Only the LATEST backup is preserved; older duplicate backup versions are automatically purged promptly.

3. Task_Attachments/
   Stores all photo, video, audio, and document media attached directly to your tasks.
   Every task with media attachments points directly to these cloud files.

4. Shared_Media/
   Stores images, videos, and media files uploaded during peer messaging, journal entries, or shared sessions.

5. General_Files/
   Stores documents, notes, and user files uploaded through the in-app File Explorer.

CONSEQUENCES OF DELETING OR ALTERING FILES IN THIS FOLDER:
-------------------------------------------------------------------
❌ Deleting media files will break image, video, and document rendering in your tasks and chats.
❌ Deleting App_Backups will prevent cross-device synchronization and data restoration.
❌ Modifying file names manually will corrupt attachment links inside the application.
❌ Removing this directory forces the app to recreate missing structures and re-sync assets.

===================================================================
Managed Automatically by LifeOS Cloud Vault Sync Engine
===================================================================
            """.trimIndent()

            val fileId = existingId ?: createFileMetadataInFolder(token, readmeName, rootFolderId)
            if (fileId != null) {
                val uploadUrl = "https://www.googleapis.com/upload/drive/v3/files/$fileId?uploadType=media"
                val requestBody = readmeContent.toRequestBody("text/plain".toMediaType())
                val request = Request.Builder()
                    .url(uploadUrl)
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Content-Type", "text/plain; charset=utf-8")
                    .patch(requestBody)
                    .build()
                client.newCall(request).execute().close()
                GoogleDriveWriteManager.makeFilePublic(token, fileId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in ensureReadmeFile", e)
        }
    }

    fun findOrCreateSharedFolder(token: String, folderName: String): String? {
        try {
            val query = "name = '$folderName' and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "https://www.googleapis.com/drive/v3/files?q=$encodedQuery&fields=files(id)"
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val files = JSONObject(body).getJSONArray("files")
                    if (files.length() > 0) {
                        return files.getJSONObject(0).getString("id")
                    }
                }
            }

            val createUrl = "https://www.googleapis.com/drive/v3/files"
            val body = JSONObject().apply {
                put("name", folderName)
                put("mimeType", "application/vnd.google-apps.folder")
            }
            val createRequest = Request.Builder()
                .url(createUrl)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            client.newCall(createRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val newFolderId = JSONObject(response.body?.string() ?: "").getString("id")
                    GoogleDriveWriteManager.makeFilePublicAndEditor(token, newFolderId)
                    return newFolderId
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in findOrCreateSharedFolder $folderName", e)
        }
        return null
    }

    fun findFileInFolder(token: String, name: String, folderId: String): String? {
        try {
            val query = "name = '$name' and '$folderId' in parents and trashed = false"
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "https://www.googleapis.com/drive/v3/files?q=$encodedQuery&fields=files(id)"
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val files = JSONObject(body).getJSONArray("files")
                    if (files.length() > 0) {
                        return files.getJSONObject(0).getString("id")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding file $name in folder $folderId", e)
        }
        return null
    }

    fun createFileMetadataInFolder(token: String, name: String, folderId: String): String? {
        try {
            val createUrl = "https://www.googleapis.com/drive/v3/files"
            val body = JSONObject().apply {
                put("name", name)
                put("parents", org.json.JSONArray().apply { put(folderId) })
            }
            val createRequest = Request.Builder()
                .url(createUrl)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            client.newCall(createRequest).execute().use { response ->
                if (response.isSuccessful) {
                    return JSONObject(response.body?.string() ?: "").getString("id")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating file metadata $name in folder $folderId", e)
        }
        return null
    }
}
