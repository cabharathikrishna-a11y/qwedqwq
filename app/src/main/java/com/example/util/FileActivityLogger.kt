package com.example.util

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class FileActivityLog(
    val id: String = UUID.randomUUID().toString(),
    val fileId: String, // e.g. "FILE-0001"
    val fileName: String,
    val actionType: String, // "UPLOAD", "DELETE", "COPY", "MOVE", "RENAME"
    val sourceFolder: String, // "Private / App Data", "Private / Personal", "Shared"
    val destinationFolder: String = "", // for MOVE / COPY
    val userEmail: String = "bharathikrishna9440@gmail.com",
    val userName: String = "Bharathi Krishna",
    val fileSizeFormatted: String = "1.2 MB",
    val fileFormat: String = "PDF Document",
    val googleDocUrl: String = DEFAULT_GOOGLE_DOC_URL,
    val timestamp: Long = System.currentTimeMillis(),
    val dateDayString: String = formatDateWithDay(System.currentTimeMillis())
) {
    companion object {
        const val DEFAULT_GOOGLE_DOC_URL = "https://docs.google.com/document/d/1mU2FIUURdhj9I5cyuJwewFSUKGyW2m_a_uncn2XCX9M/edit?usp=sharing"

        fun formatDateWithDay(timeMs: Long): String {
            val sdf = SimpleDateFormat("EEEE, MMM dd, yyyy 'at' hh:mm:ss a", Locale.getDefault())
            return sdf.format(Date(timeMs))
        }

        fun fromJsonObject(obj: JSONObject): FileActivityLog {
            return FileActivityLog(
                id = obj.optString("id", UUID.randomUUID().toString()),
                fileId = obj.optString("fileId", "FILE-0001"),
                fileName = obj.optString("fileName", "Document.pdf"),
                actionType = obj.optString("actionType", "UPLOAD"),
                sourceFolder = obj.optString("sourceFolder", "Private / Personal"),
                destinationFolder = obj.optString("destinationFolder", ""),
                userEmail = obj.optString("userEmail", "bharathikrishna9440@gmail.com"),
                userName = obj.optString("userName", "Bharathi Krishna"),
                fileSizeFormatted = obj.optString("fileSizeFormatted", "1.0 MB"),
                fileFormat = obj.optString("fileFormat", "File"),
                googleDocUrl = obj.optString("googleDocUrl", DEFAULT_GOOGLE_DOC_URL),
                timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                dateDayString = obj.optString("dateDayString", formatDateWithDay(System.currentTimeMillis()))
            )
        }
    }

    fun toJsonObject(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("fileId", fileId)
            put("fileName", fileName)
            put("actionType", actionType)
            put("sourceFolder", sourceFolder)
            put("destinationFolder", destinationFolder)
            put("userEmail", userEmail)
            put("userName", userName)
            put("fileSizeFormatted", fileSizeFormatted)
            put("fileFormat", fileFormat)
            put("googleDocUrl", googleDocUrl)
            put("timestamp", timestamp)
            put("dateDayString", dateDayString)
        }
    }
}

object FileActivityLogger {
    private const val PREFS_NAME = "file_activity_logs_prefs"
    private const val KEY_LOGS = "logs_json_array"
    private const val KEY_SEQUENTIAL_COUNTER = "seq_file_counter"
    private const val KEY_FILE_ID_MAP = "file_id_mapping"

    const val GOOGLE_DOC_URL = "https://docs.google.com/document/d/1mU2FIUURdhj9I5cyuJwewFSUKGyW2m_a_uncn2XCX9M/edit?usp=sharing"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    @Synchronized
    fun allocateSequentialFileId(context: Context, fileKey: String): String {
        val prefs = getPrefs(context)
        val idMapJsonStr = prefs.getString(KEY_FILE_ID_MAP, "{}") ?: "{}"
        try {
            val mapObj = JSONObject(idMapJsonStr)
            if (mapObj.has(fileKey)) {
                return mapObj.getString(fileKey)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Allocate new sequential ID
        var currentCounter = prefs.getInt(KEY_SEQUENTIAL_COUNTER, 1)
        val allocatedId = String.format(Locale.US, "FILE-%04d", currentCounter)
        
        // Save new counter and mapping
        try {
            val mapObj = JSONObject(idMapJsonStr)
            mapObj.put(fileKey, allocatedId)
            prefs.edit()
                .putInt(KEY_SEQUENTIAL_COUNTER, currentCounter + 1)
                .putString(KEY_FILE_ID_MAP, mapObj.toString())
                .apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return allocatedId
    }

    fun getLogs(context: Context): List<FileActivityLog> {
        val prefs = getPrefs(context)
        val jsonStr = prefs.getString(KEY_LOGS, null) ?: return getSampleInitialLogs()
        val list = mutableListOf<FileActivityLog>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(FileActivityLog.fromJsonObject(obj))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return if (list.isEmpty()) getSampleInitialLogs() else list.sortedByDescending { it.timestamp }
    }

    @Synchronized
    fun addLog(context: Context, log: FileActivityLog) {
        val currentLogs = getLogs(context).toMutableList()
        currentLogs.add(0, log) // Newest on top
        saveLogs(context, currentLogs)
    }

    fun logAction(
        context: Context,
        fileKey: String,
        fileName: String,
        actionType: String, // UPLOAD, DELETE, COPY, MOVE, RENAME
        sourceFolder: String,
        destinationFolder: String = "",
        userEmail: String = "bharathikrishna9440@gmail.com",
        userName: String = "Bharathi Krishna",
        fileSizeFormatted: String = "1.5 MB",
        fileFormat: String = "Document"
    ): FileActivityLog {
        val fileId = allocateSequentialFileId(context, fileKey)
        val log = FileActivityLog(
            fileId = fileId,
            fileName = fileName,
            actionType = actionType,
            sourceFolder = sourceFolder,
            destinationFolder = destinationFolder,
            userEmail = userEmail,
            userName = userName,
            fileSizeFormatted = fileSizeFormatted,
            fileFormat = fileFormat,
            googleDocUrl = GOOGLE_DOC_URL,
            timestamp = System.currentTimeMillis(),
            dateDayString = FileActivityLog.formatDateWithDay(System.currentTimeMillis())
        )
        addLog(context, log)
        return log
    }

    private fun saveLogs(context: Context, logs: List<FileActivityLog>) {
        val prefs = getPrefs(context)
        val arr = JSONArray()
        for (log in logs) {
            arr.put(log.toJsonObject())
        }
        prefs.edit().putString(KEY_LOGS, arr.toString()).apply()
    }

    fun clearLogs(context: Context) {
        getPrefs(context).edit().remove(KEY_LOGS).apply()
    }

    private fun getSampleInitialLogs(): List<FileActivityLog> {
        val now = System.currentTimeMillis()
        val hour = 3600000L
        return listOf(
            FileActivityLog(
                fileId = "FILE-0001",
                fileName = "Project_Blueprint.pdf",
                actionType = "UPLOAD",
                sourceFolder = "Private / Personal",
                userEmail = "bharathikrishna9440@gmail.com",
                userName = "Bharathi Krishna",
                fileSizeFormatted = "2.4 MB",
                fileFormat = "PDF Document",
                timestamp = now - (2 * hour),
                dateDayString = FileActivityLog.formatDateWithDay(now - (2 * hour))
            ),
            FileActivityLog(
                fileId = "FILE-0002",
                fileName = "Journal_Entry_Audio.mp3",
                actionType = "UPLOAD",
                sourceFolder = "Private / App Data",
                userEmail = "bharathikrishna9440@gmail.com",
                userName = "Bharathi Krishna",
                fileSizeFormatted = "4.1 MB",
                fileFormat = "Audio File",
                timestamp = now - (5 * hour),
                dateDayString = FileActivityLog.formatDateWithDay(now - (5 * hour))
            ),
            FileActivityLog(
                fileId = "FILE-0003",
                fileName = "Group_Trip_Itinerary.docx",
                actionType = "COPY",
                sourceFolder = "Private / Personal",
                destinationFolder = "Shared",
                userEmail = "bharathikrishna9440@gmail.com",
                userName = "Bharathi Krishna",
                fileSizeFormatted = "1.8 MB",
                fileFormat = "Word Document",
                timestamp = now - (12 * hour),
                dateDayString = FileActivityLog.formatDateWithDay(now - (12 * hour))
            ),
            FileActivityLog(
                fileId = "FILE-0004",
                fileName = "Budget_Spreadsheet_2026.xlsx",
                actionType = "MOVE",
                sourceFolder = "Private / App Data",
                destinationFolder = "Shared",
                userEmail = "bharathikrishna9440@gmail.com",
                userName = "Bharathi Krishna",
                fileSizeFormatted = "850 KB",
                fileFormat = "Excel Sheet",
                timestamp = now - (24 * hour),
                dateDayString = FileActivityLog.formatDateWithDay(now - (24 * hour))
            )
        )
    }
}
