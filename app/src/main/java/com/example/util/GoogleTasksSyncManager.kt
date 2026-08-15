@file:Suppress("DEPRECATION")
package com.example.util

import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Photo
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import android.util.Base64
import android.util.Log
import com.example.data.AppDatabase
import com.example.data.Contact
import com.example.data.Task
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

object GoogleTasksSyncManager {
    private const val TAG = "GoogleTasksSync"
    private const val TASKS_SCOPE = "oauth2:https://www.googleapis.com/auth/tasks"

    private val client = OkHttpClient()
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    suspend fun getAccessToken(
        context: Context,
        onAuthResolutionRequired: (Intent) -> Unit = {}
    ): String? = withContext(Dispatchers.IO) {
        if (!GmsUtils.isGmsAvailable(context)) return@withContext null
        try {
            val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            var email = prefs.getString("selected_tasks_account", null)
            if (email.isNullOrBlank()) {
                val account = GmsUtils.getLastSignedInAccount(context)
                email = account?.email
            }
            if (email.isNullOrBlank()) {
                Log.w(TAG, "No Google account email found.")
                return@withContext null
            }
            GoogleAuthUtil.getToken(context, email, TASKS_SCOPE)
        } catch (recoverable: UserRecoverableAuthException) {
            Log.w(TAG, "User recoverable auth exception encountered for Tasks scope.", recoverable)
            recoverable.intent?.let { intent -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onAuthResolutionRequired(intent) } }
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error obtaining Google OAuth2 token for Tasks: ${e.message}", e)
            null
        }
    }

    /**
     * Performs a full 2-way sync for Google Tasks (tasks with NO date and time).
     */
    suspend fun syncTasks(
        context: Context,
        onAuthResolutionRequired: (Intent) -> Unit = {}
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val token = getAccessToken(context, onAuthResolutionRequired)
            ?: return@withContext Pair(false, "Authorization required. Please connect your Google account.")

        try {
            val database = AppDatabase.getInstance(context)
            val taskDao = database.taskDao()
            val allLocalTasks = taskDao.getAllTasks().first()

            // Filter local tasks that have NO date/time (dueDateString is empty)
            val localTasksNoDate = allLocalTasks.filter { it.dueDateString.isEmpty() }

            // ---- STEP 1: FETCH FROM GOOGLE TASKS ----
            val googleTasks = fetchGoogleTasks(token)
            val googleIdToTask = googleTasks.associateBy { it.id }

            var importedCount = 0
            var updatedCount = 0
            var exportedCount = 0
            var deletedCount = 0

            // Keep track of which Google tasks we matched to local tasks
            val matchedGoogleIds = mutableSetOf<String>()

            // ---- STEP 2: PROCESS GOOGLE TASKS AND UPDATE/CREATE LOCALLY ----
            for (gTask in googleTasks) {
                // Check if this Google task has been deleted locally
                val isDeletedLocally = DeletedTaskLogHelper.isGTaskIdDeletedLocally(context, gTask.id) ||
                        DeletedTaskLogHelper.isGoogleTaskDeletedLocally(context, gTask.title)

                if (isDeletedLocally) {
                    Log.d(TAG, "Sync: Google Task '${gTask.title}' (ID: ${gTask.id}) was deleted locally. Deleting from Google.")
                    try {
                        deleteGoogleTask(token, gTask.id)
                        deletedCount++
                    } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
                        Log.e(TAG, "Failed deleting GTask ${gTask.id}: ${e.message}", e)
                    }
                    continue
                }

                // Find local task by [GTaskId: ...] tag or fallback to title matching (for tasks without date)
                val matchedLocal = localTasksNoDate.find { task ->
                    task.description.contains("[GTaskId: ${gTask.id}]")
                } ?: localTasksNoDate.find { task ->
                    task.title.trim().equals(gTask.title.trim(), ignoreCase = true) &&
                    !task.description.contains("[GTaskId:")
                }

                if (matchedLocal != null) {
                    matchedGoogleIds.add(gTask.id)
                    
                    // Check if local description already has the [GTaskId: ...] tag
                    val hasTag = matchedLocal.description.contains("[GTaskId: ${gTask.id}]")
                    val cleanLocalDesc = getCleanDescription(matchedLocal.description)

                    // Determine if updates are needed
                    val isGoogleCompleted = gTask.status == "completed"
                    val isLocalCompleted = matchedLocal.isCompleted

                    var needsUpdateLocal = false
                    var needsUpdateGoogle = false

                    var updatedLocalTask = matchedLocal

                    // 1. Resolve completion status difference
                    if (isGoogleCompleted != isLocalCompleted) {
                        // If one is completed and the other isn't, we can sync completion.
                        // Let's assume the local completed state is the latest, unless the Google task was marked completed.
                        // To be safe, if either is completed, mark both completed, or sync Google -> Local if Google is completed.
                        if (isGoogleCompleted) {
                            updatedLocalTask = updatedLocalTask.copy(isCompleted = true)
                            needsUpdateLocal = true
                        } else {
                            // Local is completed, but Google is not. Update Google.
                            needsUpdateGoogle = true
                        }
                    }

                    // 2. Resolve Title / Notes difference
                    if (matchedLocal.title != gTask.title || cleanLocalDesc != gTask.notes) {
                        // If they differ, update Google task with local changes (as user interacts with the app primarily)
                        needsUpdateGoogle = true
                    }

                    // 3. Ensure local task has the [GTaskId: ...] tag
                    if (!hasTag) {
                        val newDesc = if (matchedLocal.description.isEmpty()) {
                            "[GTaskId: ${gTask.id}]"
                        } else {
                            "${matchedLocal.description}\n\n[GTaskId: ${gTask.id}]"
                        }
                        updatedLocalTask = updatedLocalTask.copy(description = newDesc)
                        needsUpdateLocal = true
                    }

                    if (needsUpdateLocal) {
                        taskDao.updateTask(updatedLocalTask)
                        updatedCount++
                    }

                    if (needsUpdateGoogle) {
                        val notesWithId = if (cleanLocalDesc.isEmpty()) {
                            "[AppTaskId: ${updatedLocalTask.id}]"
                        } else {
                            "$cleanLocalDesc\n\n[AppTaskId: ${updatedLocalTask.id}]"
                        }
                        updateGoogleTask(token, gTask.id, updatedLocalTask.title, notesWithId, if (updatedLocalTask.isCompleted) "completed" else "needsAction")
                    }
                } else {
                    // Google Task has no local counterpart, so import it as a new local task (with no date)
                    val notes = gTask.notes
                    val cleanNotes = notes.replace(Regex("""\[AppTaskId:\s*([^\]]+)\]"""), "").trim()
                    val finalDesc = if (cleanNotes.isEmpty()) {
                        "[GTaskId: ${gTask.id}]"
                    } else {
                        "$cleanNotes\n\n[GTaskId: ${gTask.id}]"
                    }

                    val newLocal = Task(
                        title = gTask.title,
                        description = finalDesc,
                        isCompleted = gTask.status == "completed",
                        listCategory = "Google Tasks",
                        dueDateString = ""
                    )
                    val insertedId = taskDao.insertTask(newLocal)
                    importedCount++
                    matchedGoogleIds.add(gTask.id)

                    // Update Google Task's notes with the newly inserted AppTaskId so we can track deletion
                    try {
                        val notesWithId = if (cleanNotes.isEmpty()) {
                            "[AppTaskId: $insertedId]"
                        } else {
                            "$cleanNotes\n\n[AppTaskId: $insertedId]"
                        }
                        updateGoogleTask(token, gTask.id, gTask.title, notesWithId, gTask.status)
                    } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
                        Log.e(TAG, "Failed to update Google Task notes with AppTaskId: ${e.message}", e)
                    }
                }
            }

            // ---- STEP 3: EXPORT NEW LOCAL TASKS TO GOOGLE TASKS ----
            for (local in localTasksNoDate) {
                val gTaskId = extractGoogleTaskId(local.description)
                if (gTaskId == null) {
                    // This is a new local task with NO date and NO Google Task ID yet! Export it.
                    val cleanDesc = getCleanDescription(local.description)
                    val notesWithId = if (cleanDesc.isEmpty()) {
                        "[AppTaskId: ${local.id}]"
                    } else {
                        "$cleanDesc\n\n[AppTaskId: ${local.id}]"
                    }
                    val status = if (local.isCompleted) "completed" else "needsAction"
                    val newGTaskId = createGoogleTask(token, local.title, notesWithId, status)
                    if (newGTaskId != null) {
                        val updatedDesc = if (local.description.isEmpty()) {
                            "[GTaskId: $newGTaskId]"
                        } else {
                            "${local.description}\n\n[GTaskId: $newGTaskId]"
                        }
                        taskDao.updateTask(local.copy(description = updatedDesc))
                        exportedCount++
                    }
                } else {
                    // Local task has a Google Task ID, but was it deleted on Google?
                    if (!matchedGoogleIds.contains(gTaskId)) {
                        // The task has a Google Task ID tag, but that ID was not returned by Google.
                        // This means the task was deleted on Google Tasks, so we delete it locally to keep them in sync.
                        taskDao.deleteTask(local)
                        deletedCount++
                    }
                }
            }

            // ---- STEP 4: DETECT LOCALLY DELETED TASKS AND DELETE THEM FROM GOOGLE ----
            // If there are Google Tasks that have [AppTaskId: ...] (if we used that), or if we want to be safe,
            // we can clean up Google Tasks that are no longer present in local tasks.
            // Wait, we didn't store AppTaskId in Google Tasks notes, but we can do that in the future to make delete sync 100% perfect.
            // For now, if local task with a GTaskId is deleted from the app's database, it won't be in localTasksNoDate.
            // But we can't easily know which GTaskId was deleted unless we track deletions, OR we can check if there are Google Tasks with notes containing "[AppTaskId: ID]" where ID is not in our database!
            // Let's add [AppTaskId: ID] to the notes we send to Google, so we can delete them on Google if the local task is deleted!
            // This is brilliant! Let's do that in createGoogleTask and updateGoogleTask.
            for (gTask in googleTasks) {
                val appTaskId = extractAppTaskId(gTask.notes)
                if (appTaskId != null) {
                    val localExists = allLocalTasks.any { it.id == appTaskId }
                    if (!localExists) {
                        // The local task was deleted by the user in our app. So delete it from Google Tasks!
                        deleteGoogleTask(token, gTask.id)
                        deletedCount++
                    }
                }
            }

            Pair(true, "Sync Complete! Imported $importedCount, Updated $updatedCount, Exported $exportedCount, Synced $deletedCount deletions.")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing tasks: ${e.message}", e)
            Pair(false, "Sync failed: ${e.localizedMessage}")
        }
    }

    data class GoogleTaskDetails(
        val id: String,
        val title: String,
        val notes: String,
        val status: String,
        val updated: String
    )

    private fun fetchGoogleTasks(token: String): List<GoogleTaskDetails> {
        val list = mutableListOf<GoogleTaskDetails>()
        var pageToken: String? = null
        
        do {
            val urlBuilder = StringBuilder("https://tasks.googleapis.com/v1/lists/@default/tasks?showCompleted=true&showHidden=true")
            pageToken?.let {
                urlBuilder.append("&pageToken=").append(java.net.URLEncoder.encode(it, "UTF-8"))
            }
            val request = Request.Builder()
                .url(urlBuilder.toString())
                .header("Authorization", "Bearer $token")
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e(TAG, "Failed to fetch Google tasks: code=${response.code}, msg=${response.message}")
                        return list
                    }
                    val bodyStr = response.body?.string() ?: ""
                    val json = JSONObject(bodyStr)
                    val items = json.optJSONArray("items")
                    if (items != null) {
                        for (i in 0 until items.length()) {
                            val item = items.getJSONObject(i)
                            val id = item.optString("id", "")
                            val title = item.optString("title", "")
                            val notes = item.optString("notes", "")
                            val status = item.optString("status", "needsAction")
                            val updated = item.optString("updated", "")

                            if (id.isNotEmpty()) {
                                list.add(GoogleTaskDetails(id, title, notes, status, updated))
                            }
                        }
                    }
                    pageToken = json.optString("nextPageToken", "").takeIf { it.isNotEmpty() }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching Google tasks page: ${e.message}", e)
                pageToken = null
            }
        } while (pageToken != null)

        return list
    }

    private fun createGoogleTask(token: String, title: String, notes: String, status: String): String? {
        val url = "https://tasks.googleapis.com/v1/lists/@default/tasks"
        val payload = JSONObject().apply {
            put("title", title)
            put("notes", notes)
            put("status", status)
        }
        val requestBody = payload.toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .post(requestBody)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Failed to create Google task: code=${response.code}, msg=${response.message}")
                    return null
                }
                val bodyStr = response.body?.string() ?: ""
                val json = JSONObject(bodyStr)
                return json.optString("id").takeIf { it.isNotEmpty() }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error creating Google task: ${e.message}", e)
        }
        return null
    }

    private fun updateGoogleTask(token: String, id: String, title: String, notes: String, status: String): Boolean {
        val url = "https://tasks.googleapis.com/v1/lists/@default/tasks/$id"
        val payload = JSONObject().apply {
            put("id", id)
            put("title", title)
            put("notes", notes)
            put("status", status)
        }
        val requestBody = payload.toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .put(requestBody)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Failed to update Google task: code=${response.code}, msg=${response.message}")
                    return false
                }
                return true
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error updating Google task: ${e.message}", e)
        }
        return false
    }

    private fun deleteGoogleTask(token: String, id: String): Boolean {
        val url = "https://tasks.googleapis.com/v1/lists/@default/tasks/$id"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .delete()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Failed to delete Google task: code=${response.code}, msg=${response.message}")
                    return false
                }
                return true
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting Google task: ${e.message}", e)
        }
        return false
    }

    // Helpers to parse tags
    fun extractGoogleTaskId(description: String): String? {
        val regex = Regex("""\[GTaskId:\s*([^\]]+)\]""")
        val match = regex.find(description)
        return match?.groupValues?.get(1)?.trim()
    }

    fun extractAppTaskId(notes: String): Int? {
        val regex = Regex("""\[AppTaskId:\s*([^\]]+)\]""")
        val match = regex.find(notes)
        return match?.groupValues?.get(1)?.trim()?.toIntOrNull()
    }

    fun getCleanDescription(description: String): String {
        // Remove [GTaskId: ...] and empty lines around it
        val cleaned = description.replace(Regex("""\[GTaskId:\s*([^\]]+)\]"""), "").trim()
        return cleaned
    }
}


// ==================== CONSOLIDATED CALENDAR & TASK UTILITIES ====================



// ==================== CONSOLIDATED FROM: DeletedTaskLogHelper.kt ====================



object DeletedTaskLogHelper {
    private const val PREFS_NAME = "deleted_tasks_log_prefs"
    private const val TAG = "DeletedTaskLog"

    fun logDeletedTask(context: Context, title: String, dueDate: String, gCalEventId: String?) {
        if (title.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        
        val cleanTitle = title.lowercase().trim()
        val titleDateKey = "title_date:${cleanTitle}_$dueDate"
        editor.putBoolean(titleDateKey, true)
        Log.d(TAG, "Logged deleted task by Title/Date: $titleDateKey")

        if (!gCalEventId.isNullOrEmpty()) {
            val eventIdKey = "gcal_event_id:$gCalEventId"
            editor.putBoolean(eventIdKey, true)
            Log.d(TAG, "Logged deleted task by GCalEventId: $eventIdKey")
        }
        
        editor.apply()
    }

    fun isTaskDeletedLocally(context: Context, title: String, dueDate: String): Boolean {
        if (title.isEmpty()) return false
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cleanTitle = title.lowercase().trim()
        val titleDateKey = "title_date:${cleanTitle}_$dueDate"
        return prefs.getBoolean(titleDateKey, false)
    }

    fun isGCalEventDeletedLocally(context: Context, gCalEventId: String): Boolean {
        if (gCalEventId.isEmpty()) return false
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val eventIdKey = "gcal_event_id:$gCalEventId"
        return prefs.getBoolean(eventIdKey, false)
    }
    
    fun removeDeletedTaskFromLog(context: Context, title: String, dueDate: String, gCalEventId: String?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        
        val cleanTitle = title.lowercase().trim()
        val titleDateKey = "title_date:${cleanTitle}_$dueDate"
        editor.remove(titleDateKey)
        
        if (!gCalEventId.isNullOrEmpty()) {
            val eventIdKey = "gcal_event_id:$gCalEventId"
            editor.remove(eventIdKey)
        }
        
        editor.apply()
    }

    // --- Google Tasks Deletion Logging Support ---
    fun logDeletedGoogleTask(context: Context, title: String, gTaskId: String?) {
        if (title.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        
        val cleanTitle = title.lowercase().trim()
        val titleKey = "g_task_title:${cleanTitle}"
        editor.putBoolean(titleKey, true)
        Log.d(TAG, "Logged deleted Google Task by Title: $titleKey")

        if (!gTaskId.isNullOrEmpty()) {
            val gTaskIdKey = "g_task_id:$gTaskId"
            editor.putBoolean(gTaskIdKey, true)
            Log.d(TAG, "Logged deleted Google Task by GTaskId: $gTaskIdKey")
        }
        
        editor.apply()
    }

    fun isGoogleTaskDeletedLocally(context: Context, title: String): Boolean {
        if (title.isEmpty()) return false
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cleanTitle = title.lowercase().trim()
        val titleKey = "g_task_title:${cleanTitle}"
        return prefs.getBoolean(titleKey, false)
    }

    fun isGTaskIdDeletedLocally(context: Context, gTaskId: String): Boolean {
        if (gTaskId.isEmpty()) return false
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val gTaskIdKey = "g_task_id:$gTaskId"
        return prefs.getBoolean(gTaskIdKey, false)
    }

    fun removeDeletedGoogleTaskFromLog(context: Context, title: String, gTaskId: String?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        
        val cleanTitle = title.lowercase().trim()
        val titleKey = "g_task_title:${cleanTitle}"
        editor.remove(titleKey)
        
        if (!gTaskId.isNullOrEmpty()) {
            val gTaskIdKey = "g_task_id:$gTaskId"
            editor.remove(gTaskIdKey)
        }
        
        editor.apply()
    }
}



// ==================== CONSOLIDATED FROM: GoogleCalendarSyncHelper.kt ====================



data class CalendarInfo(
    val id: Long,
    val accountName: String,
    val accountType: String,
    val displayName: String
)

data class SystemCalendarEvent(
    val id: Long,
    val title: String,
    val description: String,
    val startMillis: Long,
    val endMillis: Long,
    val isAllDay: Boolean,
    val dateStr: String,
    val calendarDisplayName: String,
    val isHolidayOrFestival: Boolean
)

object GoogleCalendarSyncHelper {

    private const val TAG = "GoogleCalendarSync"

    fun isHolidayCalendar(accountName: String, displayName: String): Boolean {
        val lowerAcc = accountName.lowercase(Locale.ROOT).trim()
        val lowerDisp = displayName.lowercase(Locale.ROOT).trim()
        return lowerDisp.contains("holiday") || lowerDisp.contains("festival") || lowerDisp.contains("vacation") ||
               lowerDisp.contains("birthday") || lowerDisp.contains("birthdays") || lowerDisp.contains("contact") ||
               lowerDisp.contains("observance") || lowerDisp.contains("festiv") ||
               lowerDisp.contains("panchang") || lowerDisp.contains("tithi") || lowerDisp.contains("cultural") ||
               lowerAcc.contains("#holiday@") || lowerAcc.contains("holiday@") || lowerAcc.contains("group.v.calendar.google.com") ||
               lowerAcc.contains("addressbook#contacts") || lowerAcc.contains("contact") || lowerAcc.contains("birthday") ||
               lowerAcc.contains("festiv")
    }

    fun isHolidayOrFestival(title: String, description: String = "", calendarName: String = "", accountName: String = ""): Boolean {
        if (calendarName.isNotEmpty() && isHolidayCalendar(accountName, calendarName)) return true
        if (accountName.isNotEmpty() && isHolidayCalendar(accountName, "")) return true
        val lowerTitle = title.lowercase(Locale.ROOT).trim()
        val lowerDesc = description.lowercase(Locale.ROOT).trim()

        val keywords = listOf(
            "birthday", "birthdays", "b'day", "bday", "anniversary", "anniversaries",
            "holiday", "festival", "festivals", "diwali", "deepavali", "christmas", "xmas", "eid", "holi",
            "independence day", "republic day", "gandhi jayanti", "thanksgiving", "new year", "good friday",
            "labor day", "labour day", "memorial day", "columbus day", "veterans day", "martin luther king", "presidents' day",
            "raksha bandhan", "dussehra", "dussera", "ram navami", "janmashtami", "ganesh chaturthi", "maha shivratri",
            "pongal", "makar sankranti", "onam", "baisakhi", "karwa chauth", "dhanteras", "bhai dooj", "ugadi",
            "gudi padwa", "chath puja", "chhat puja", "durga puja", "navratri", "easter", "halloween", "valentine",
            "st. patrick", "boxing day", "patriots' day", "flag day", "juneteenth", "indigenous peoples' day",
            "kwanzaa", "hanukkah", "passover", "rosh hashanah", "yom kippur", "vesak", "buddha purnima",
            "guru nanak", "mahavir jayanti", "milad un nabi", "muharram", "ashura", "public holiday", "bank holiday",
            "national holiday", "observance", "season's greetings", "vishu", "bihu", "lohri", "chaitra navratri",
            "ramadan", "eid ul-fitr", "eid al-fitr", "bakrid", "eid al-adha", "shab-e-barat", "milad-un-nabi",
            "durga ashtami", "maha navami", "vijayadashami", "kartik purnima", "guru gobind singh", "christmas eve",
            "new year's eve", "new year's day", "may day", "earth day", "international women's day", "mothers day",
            "fathers day", "childrens day", "teachers day", "panchang", "tithi", "ekadashi", "amavasya", "purnima",
            "sankranti", "pradosh", "navami", "ashtami", "chaturdashi", "jayanti", "utsav", "carnival", "solstice",
            "equinox", "national day", "commemoration", "federal holiday"
        )

        for (kw in keywords) {
            if (lowerTitle.contains(kw) || lowerDesc.contains(kw)) {
                return true
            }
        }

        return false
    }

    suspend fun purgeSyncedHolidayTasks(
        context: Context,
        localTasks: List<Task>,
        onDeleteTask: suspend (Task) -> Unit
    ) {
        for (task in localTasks) {
            val isFromGCal = task.description.contains("[GCalEventId:") || task.listCategory == "Google Calendar"
            if (isFromGCal && isHolidayOrFestival(task.title, task.description, task.listCategory)) {
                Log.d(TAG, "Purging existing holiday task: '${task.title}' (ID: ${task.id})")
                try {
                    onDeleteTask(task)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed purging holiday task ${task.id}: ${e.message}")
                }
            }
        }
    }

    fun fetchSystemCalendarEvents(context: Context, startMillis: Long, endMillis: Long): List<SystemCalendarEvent> {
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.READ_CALENDAR
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }

        val events = mutableListOf<SystemCalendarEvent>()
        val resolver = context.contentResolver

        val calendarMap = mutableMapOf<Long, Pair<String, String>>()
        try {
            val calCursor = resolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(
                    CalendarContract.Calendars._ID,
                    CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                    CalendarContract.Calendars.ACCOUNT_NAME
                ),
                null, null, null
            )
            calCursor?.use {
                while (it.moveToNext()) {
                    val calId = it.getLong(0)
                    val name = it.getString(1) ?: ""
                    val acc = it.getString(2) ?: ""
                    calendarMap[calId] = Pair(name, acc)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying calendar map: ${e.message}")
        }

        val selection = "(${CalendarContract.Events.DTSTART} >= ?) AND (${CalendarContract.Events.DTSTART} <= ?) AND (deleted != 1)"
        val selectionArgs = arrayOf(startMillis.toString(), endMillis.toString())
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.CALENDAR_ID
        )

        val sdfDate = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        try {
            val cursor = resolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${CalendarContract.Events.DTSTART} ASC"
            )
            cursor?.use {
                while (it.moveToNext()) {
                    val eventId = it.getLong(0)
                    val title = it.getString(1) ?: "Event"
                    val description = it.getString(2) ?: ""
                    val dtStart = it.getLong(3)
                    val dtEnd = it.getLong(4)
                    val allDay = (it.getInt(5) == 1)
                    val calId = it.getLong(6)
                    val (calName, calAcc) = calendarMap[calId] ?: Pair("", "")

                    val dateStr = sdfDate.format(Date(dtStart))
                    val isHoliday = isHolidayOrFestival(title, description, calName, calAcc)

                    events.add(
                        SystemCalendarEvent(
                            id = eventId,
                            title = title,
                            description = description,
                            startMillis = dtStart,
                            endMillis = dtEnd,
                            isAllDay = if (isHoliday) true else allDay,
                            dateStr = dateStr,
                            calendarDisplayName = if (calName.isNotEmpty()) calName else "Calendar",
                            isHolidayOrFestival = isHoliday
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching system calendar events: ${e.message}")
        }

        return events
    }

    // Helper to check and get a calendar ID (preferring Google account calendars or user's selected preferences)
    fun getOrCreateCalendarId(context: Context): Long? {
        val prefs = context.getSharedPreferences("app_calendar_prefs", Context.MODE_PRIVATE)
        val selectedAccount = prefs.getString("selected_calendar_account", null)
        val selectedName = prefs.getString("selected_calendar_name", null)
        val selectedId = prefs.getLong("selected_calendar_id", -1L)

        val resolver = context.contentResolver
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME
        )

        var cursor: Cursor? = null
        try {
            cursor = resolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                null,
                null,
                null
            )
            
            var matchedId: Long? = null
            var googleFallbackId: Long? = null
            var fallbackId: Long? = null

            cursor?.use {
                while (it.moveToNext()) {
                    val id = it.getLong(0)
                    val accountName = it.getString(1) ?: ""
                    val accountType = it.getString(2) ?: ""
                    val displayName = it.getString(3) ?: ""
                    
                    if (isHolidayCalendar(accountName, displayName)) {
                        continue
                    }

                    // Priority 1: Match saved calendar ID precisely
                    if (selectedId != -1L && id == selectedId) {
                        Log.d(TAG, "Found precise selected calendar ID match: $id")
                        return id
                    }
                    
                    // Priority 2: Match saved Account name & Display Name
                    if (selectedAccount != null && selectedName != null &&
                        accountName == selectedAccount && displayName == selectedName) {
                        matchedId = id
                    }
                    
                    // Priority 3: Fallbacks
                    if (accountType == "com.google" && googleFallbackId == null) {
                        googleFallbackId = id
                    }
                    if (fallbackId == null) {
                        fallbackId = id
                    }
                }
            }
            if (matchedId != null) {
                Log.d(TAG, "Found preference-matched calendar ID: $matchedId")
                return matchedId
            }
            if (googleFallbackId != null) {
                Log.d(TAG, "Found Google Account fallback calendar ID: $googleFallbackId")
                return googleFallbackId
            }
            if (fallbackId != null) {
                Log.d(TAG, "Found general fallback calendar ID: $fallbackId")
                return fallbackId
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission missing for querying calendars: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error querying calendars: ${e.message}", e)
        }

        return null
    }

    // Helper to query all available calendars on the device
    fun getAvailableCalendars(context: Context): List<CalendarInfo> {
        val list = mutableListOf<CalendarInfo>()
        val resolver = context.contentResolver
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME
        )
        
        var cursor: Cursor? = null
        try {
            cursor = resolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                null,
                null,
                null
            )
            cursor?.use {
                while (it.moveToNext()) {
                    val id = it.getLong(0)
                    val accountName = it.getString(1) ?: "Local"
                    val accountType = it.getString(2) ?: "Local"
                    val displayName = it.getString(3) ?: "My Calendar"
                    list.add(CalendarInfo(id, accountName, accountType, displayName))
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException querying calendars: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error querying calendars: ${e.message}", e)
        }
        return list
    }

    // Bidirectional sync
    suspend fun syncGoogleCalendar(
        context: Context,
        localTasks: List<Task>,
        onImportTask: suspend (String, String, Int, String) -> Long,
        onUpdateTask: suspend (Task) -> Unit,
        onDeleteTask: (suspend (Task) -> Unit)? = null
    ): String {
        if (onDeleteTask != null) {
            purgeSyncedHolidayTasks(context, localTasks, onDeleteTask)
        }

        val calendarId = getOrCreateCalendarId(context)
            ?: return "No calendar found on device. Please set up a Google account first."

        var importedCount = 0
        var exportedCount = 0

        val resolver = context.contentResolver
        val timeZone = TimeZone.getDefault().id

        // 1. IMPORT FROM GOOGLE CALENDAR
        val sdfDate = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        
        // Define query window: from 365 days ago to 365 days in the future
        val calendarStart = Calendar.getInstance()
        calendarStart.add(Calendar.DAY_OF_YEAR, -365)
        val startMillis = calendarStart.timeInMillis
        
        val calendarEnd = Calendar.getInstance()
        calendarEnd.add(Calendar.DAY_OF_YEAR, 365)
        val endMillis = calendarEnd.timeInMillis

        val selection = "(${CalendarContract.Events.CALENDAR_ID} = ?) AND (${CalendarContract.Events.DTSTART} >= ?) AND (${CalendarContract.Events.DTSTART} <= ?) AND (deleted != 1)"
        val selectionArgs = arrayOf(calendarId.toString(), startMillis.toString(), endMillis.toString())

        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.EVENT_LOCATION
        )

        var eventCursor: Cursor? = null
        try {
            eventCursor = resolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )

            eventCursor?.use { cursor ->
                while (cursor.moveToNext()) {
                    val eventId = cursor.getLong(0)
                    val title = cursor.getString(1) ?: "Google Event"
                    val description = cursor.getString(2) ?: ""
                    val dtStart = cursor.getLong(3)
                    val dtEnd = cursor.getLong(4)
                    val gcalLocation = try { cursor.getString(5) ?: "" } catch (e: Exception) { "" }

                    val eventDateStr = sdfDate.format(Date(dtStart))

                    // Do not sync festival/holiday events into Task list
                    if (isHolidayOrFestival(title, description)) {
                        Log.d(TAG, "Sync: Skipping festival/holiday event '$title'")
                        val matchedHolidayLocal = localTasks.find { task ->
                            task.description.contains("[GCalEventId: $eventId]") ||
                            (task.title.trim().equals(title.trim(), ignoreCase = true) && task.dueDateString == eventDateStr)
                        }
                        if (matchedHolidayLocal != null && onDeleteTask != null) {
                            onDeleteTask(matchedHolidayLocal)
                        }
                        continue
                    }

                    // Check if this event has been deleted locally
                    val isDeletedLocally = DeletedTaskLogHelper.isGCalEventDeletedLocally(context, eventId.toString()) ||
                            DeletedTaskLogHelper.isTaskDeletedLocally(context, title, eventDateStr)
                    
                    if (isDeletedLocally) {
                        Log.d(TAG, "Sync: Event '$title' on $eventDateStr (ID: $eventId) was deleted locally. Deleting from Google Calendar.")
                        try {
                            resolver.delete(
                                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
                                null,
                                null
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed deleting GCal Event $eventId: ${e.message}", e)
                        }
                        continue
                    }

                    // Extract AppTaskId if exists in description to check if local task was deleted
                    val appTaskIdRegex = Regex("""\[AppTaskId:\s*(\d+)\]""")
                    val appTaskIdMatch = appTaskIdRegex.find(description)
                    val appTaskId = appTaskIdMatch?.groupValues?.get(1)?.toIntOrNull()

                    if (appTaskId != null) {
                        val localExists = localTasks.any { it.id == appTaskId }
                        if (!localExists) {
                            // Local task was deleted, so delete corresponding Google Calendar Event
                            try {
                                resolver.delete(
                                    ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
                                    null,
                                    null
                                )
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed deleting GCal Event $eventId for deleted local task $appTaskId: ${e.message}", e)
                            }
                            continue
                        }
                    }

                    // Check if we already have this synced locally
                    val matchedLocal = localTasks.find { task ->
                        (appTaskId != null && task.id == appTaskId) ||
                        task.description.contains("[GCalEventId: $eventId]")
                    }

                    val alreadySynced = matchedLocal != null || localTasks.any { task ->
                        task.title.trim().equals(title.trim(), ignoreCase = true) && task.dueDateString == eventDateStr
                    }

                    if (matchedLocal != null) {
                        // Timing, Title, or Reminder sync from Google Calendar to local task
                        val gCalTime = parseTaskTime(matchedLocal.description)
                        val gCalDuration = parseTaskDuration(matchedLocal.description)

                        val newHourFormatter = SimpleDateFormat("hh:mm a", Locale.US)
                        val expectedTimeStr = newHourFormatter.format(Date(dtStart))
                        val expectedDuration = if (dtEnd > dtStart) {
                            ((dtEnd - dtStart) / 60000).toInt().coerceAtLeast(15)
                        } else {
                            30
                        }

                        val actualGCalTime = Calendar.getInstance().apply { timeInMillis = dtStart }
                        val actualHour = actualGCalTime.get(Calendar.HOUR_OF_DAY)
                        val actualMinute = actualGCalTime.get(Calendar.MINUTE)

                        val timeChanged = gCalTime == null || gCalTime.first != actualHour || gCalTime.second != actualMinute
                        val durationChanged = gCalDuration != expectedDuration
                        val dateChanged = matchedLocal.dueDateString != eventDateStr
                        val titleChanged = !matchedLocal.title.trim().equals(title.trim(), ignoreCase = true)

                        val remindersList = getEventReminders(context, eventId)
                        val currentRemindersList = getTaskRemindersInMinutes(matchedLocal.description)
                        val remindersChanged = remindersList.sorted() != currentRemindersList.sorted()

                        if (timeChanged || durationChanged || dateChanged || titleChanged || remindersChanged) {
                            var descriptionWithoutTags = matchedLocal.description
                            
                            // Remove existing tags if present
                            descriptionWithoutTags = descriptionWithoutTags.replace(Regex("""\[Time:\s*[^\]]+\]"""), "").trim()
                            descriptionWithoutTags = descriptionWithoutTags.replace(Regex("""\[Duration:\s*[^\]]+\]"""), "").trim()
                            descriptionWithoutTags = descriptionWithoutTags.replace(Regex("""\[Reminders:\s*[^\]]+\]"""), "").trim()
                            
                            descriptionWithoutTags = descriptionWithoutTags.trim()

                            // Rebuild description with new tags
                            val remindersTag = if (remindersList.isNotEmpty()) {
                                " [Reminders: ${remindersList.map { formatMinutesToReminderString(it) }.joinToString(", ")}]"
                            } else {
                                ""
                            }
                            
                            val updatedDesc = if (descriptionWithoutTags.isEmpty()) {
                                "[Time: $expectedTimeStr] [Duration: ${expectedDuration}m]$remindersTag"
                            } else {
                                "$descriptionWithoutTags\n[Time: $expectedTimeStr] [Duration: ${expectedDuration}m]$remindersTag"
                            }

                            val updatedTask = matchedLocal.copy(
                                title = title,
                                dueDateString = eventDateStr,
                                estimatedMinutes = expectedDuration,
                                description = updatedDesc
                            )
                            onUpdateTask(updatedTask)
                        }
                    } else if (!alreadySynced && !description.contains("[AppTaskId:")) {
                        // Estimate duration
                        val estMinutes = if (dtEnd > dtStart) {
                            ((dtEnd - dtStart) / 60000).toInt().coerceAtLeast(15)
                        } else {
                            30
                        }

                        val hourFormatter = SimpleDateFormat("hh:mm a", Locale.US)
                        val timeStr = hourFormatter.format(Date(dtStart))
                        
                        val remindersList = getEventReminders(context, eventId)
                        val remindersTag = if (remindersList.isNotEmpty()) {
                            " [Reminders: ${remindersList.map { formatMinutesToReminderString(it) }.joinToString(", ")}]"
                        } else {
                            ""
                        }

                        val cleanDesc = buildString {
                            if (description.isNotEmpty()) {
                                append(description)
                                if (!description.endsWith("\n")) append("\n")
                            }
                            append("[Time: $timeStr] [Duration: ${estMinutes}m]$remindersTag")
                            if (gcalLocation.isNotEmpty()) {
                                append("\n[Location: $gcalLocation]")
                            }
                            append("\n\n[GCalEventId: $eventId]")
                        }

                        val newTaskId = onImportTask(title, cleanDesc, estMinutes, eventDateStr)
                        importedCount++

                        // Update Google Calendar event description with the newly created local AppTaskId
                        try {
                            val updatedDescription = if (description.isEmpty()) {
                                "[AppTaskId: $newTaskId]"
                            } else {
                                "$description\n\n[AppTaskId: $newTaskId]"
                            }
                            val updateValues = ContentValues().apply {
                                put(CalendarContract.Events.DESCRIPTION, updatedDescription)
                            }
                            resolver.update(
                                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
                                updateValues,
                                null,
                                null
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed updating Google Calendar event $eventId with AppTaskId: ${e.message}", e)
                        }
                    }
                }
            }
        } catch (e: SecurityException) {
            return "Calendar permissions are required to sync Google Calendar."
        } catch (e: Exception) {
            Log.e(TAG, "Failed importing from Google Calendar: ${e.message}", e)
            return "Sync failed: ${e.message}"
        }

        // 2. EXPORT AND UPDATE TO GOOGLE CALENDAR
        for (task in localTasks) {
            if (task.dueDateString.isNotEmpty()) {
                if (!task.description.contains("[GCalEventId:")) {
                    // Export new event
                    try {
                        val dateParts = task.dueDateString.split("-")
                        if (dateParts.size == 3) {
                            val year = dateParts[0].toIntOrNull() ?: continue
                            val month = (dateParts[1].toIntOrNull() ?: continue) - 1
                            val day = dateParts[2].toIntOrNull() ?: continue

                            // Try parsing [Time: hh:mm AM/PM] or standard time from task description
                            var startHour = 9
                            var startMinute = 0
                            val parsedTime = parseTaskTime(task.description)
                            if (parsedTime != null) {
                                startHour = parsedTime.first
                                startMinute = parsedTime.second
                            }

                            val startCal = Calendar.getInstance().apply {
                                set(Calendar.YEAR, year)
                                set(Calendar.MONTH, month)
                                set(Calendar.DAY_OF_MONTH, day)
                                set(Calendar.HOUR_OF_DAY, startHour)
                                set(Calendar.MINUTE, startMinute)
                                set(Calendar.SECOND, 0)
                                set(Calendar.MILLISECOND, 0)
                            }

                            val durationMin = parseTaskDuration(task.description).coerceAtLeast(15)
                            val endCal = Calendar.getInstance().apply {
                                timeInMillis = startCal.timeInMillis + (durationMin * 60 * 1000L)
                            }

                            val reminderMins = getTaskRemindersInMinutes(task.description)
                            val locMatch = Regex("""\[Location:\s*([^\]]+)\]""").find(task.description)
                            val locValue = locMatch?.groupValues?.get(1)?.trim()

                            val values = ContentValues().apply {
                                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                                put(CalendarContract.Events.TITLE, task.title)
                                put(CalendarContract.Events.DESCRIPTION, "${task.description}\n\n[AppTaskId: ${task.id}]")
                                put(CalendarContract.Events.DTSTART, startCal.timeInMillis)
                                put(CalendarContract.Events.DTEND, endCal.timeInMillis)
                                put(CalendarContract.Events.EVENT_TIMEZONE, timeZone)
                                put(CalendarContract.Events.HAS_ALARM, if (reminderMins.isNotEmpty()) 1 else 0)
                                if (!locValue.isNullOrEmpty()) {
                                    put(CalendarContract.Events.EVENT_LOCATION, locValue)
                                }
                            }

                            val uri: Uri? = resolver.insert(CalendarContract.Events.CONTENT_URI, values)
                            if (uri != null) {
                                val newEventId = ContentUris.parseId(uri)
                                setEventReminders(context, newEventId, reminderMins)

                                // Dual Sync Add People: invite tagged contacts by email in calendar event
                                try {
                                    val localContacts = AppDatabase.getInstance(context).contactDao().getAllContacts().first()
                                    val tags = Regex("""@\w+""").findAll(task.description + " " + task.title)
                                        .map { it.value.lowercase().trim().replace("@", "") }.distinct().toList()
                                    for (tag in tags) {
                                        val contact = localContacts.find {
                                            it.firstName.lowercase() == tag ||
                                            it.email.substringBefore("@").replace(".", "_").lowercase() == tag
                                        }
                                        if (contact != null && contact.email.isNotEmpty()) {
                                            val attendeeValues = ContentValues().apply {
                                                put(CalendarContract.Attendees.EVENT_ID, newEventId)
                                                put(CalendarContract.Attendees.ATTENDEE_NAME, "${contact.firstName} ${contact.lastName}".trim())
                                                put(CalendarContract.Attendees.ATTENDEE_EMAIL, contact.email)
                                                put(CalendarContract.Attendees.ATTENDEE_RELATIONSHIP, CalendarContract.Attendees.RELATIONSHIP_ATTENDEE)
                                                put(CalendarContract.Attendees.ATTENDEE_TYPE, CalendarContract.Attendees.TYPE_REQUIRED)
                                                put(CalendarContract.Attendees.ATTENDEE_STATUS, CalendarContract.Attendees.ATTENDEE_STATUS_INVITED)
                                            }
                                            resolver.insert(CalendarContract.Attendees.CONTENT_URI, attendeeValues)
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed adding attendees to event $newEventId", e)
                                }

                                // Update our local task description to reflect GCal event id
                                val updatedDesc = if (task.description.isEmpty()) {
                                    "[GCalEventId: $newEventId]"
                                } else {
                                    "${task.description}\n\n[GCalEventId: $newEventId]"
                                }
                                onUpdateTask(task.copy(description = updatedDesc))
                                exportedCount++
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed exporting task '${task.title}': ${e.message}", e)
                    }
                } else {
                    // Update existing event to sync changes (e.g., end time/duration/reminders changes)
                    try {
                        val idRegex = Regex("""\[GCalEventId:\s*(\d+)\]""")
                        val match = idRegex.find(task.description)
                        val eventId = match?.groupValues?.get(1)?.toLongOrNull()
                        if (eventId != null) {
                            val dateParts = task.dueDateString.split("-")
                            if (dateParts.size == 3) {
                                val year = dateParts[0].toIntOrNull() ?: continue
                                val month = (dateParts[1].toIntOrNull() ?: continue) - 1
                                val day = dateParts[2].toIntOrNull() ?: continue

                                var startHour = 9
                                var startMinute = 0
                                val parsedTime = parseTaskTime(task.description)
                                if (parsedTime != null) {
                                    startHour = parsedTime.first
                                    startMinute = parsedTime.second
                                }

                                val startCal = Calendar.getInstance().apply {
                                    set(Calendar.YEAR, year)
                                    set(Calendar.MONTH, month)
                                    set(Calendar.DAY_OF_MONTH, day)
                                    set(Calendar.HOUR_OF_DAY, startHour)
                                    set(Calendar.MINUTE, startMinute)
                                    set(Calendar.SECOND, 0)
                                    set(Calendar.MILLISECOND, 0)
                                }

                                val durationMin = parseTaskDuration(task.description).coerceAtLeast(15)
                                val endCal = Calendar.getInstance().apply {
                                    timeInMillis = startCal.timeInMillis + (durationMin * 60 * 1000L)
                                }

                                val reminderMins = getTaskRemindersInMinutes(task.description)
                                val locMatch = Regex("""\[Location:\s*([^\]]+)\]""").find(task.description)
                                val locValue = locMatch?.groupValues?.get(1)?.trim()
                                val values = ContentValues().apply {
                                    put(CalendarContract.Events.TITLE, task.title)
                                    put(CalendarContract.Events.DESCRIPTION, "${task.description}\n\n[AppTaskId: ${task.id}]")
                                    put(CalendarContract.Events.DTSTART, startCal.timeInMillis)
                                    put(CalendarContract.Events.DTEND, endCal.timeInMillis)
                                    put(CalendarContract.Events.EVENT_TIMEZONE, timeZone)
                                    put(CalendarContract.Events.HAS_ALARM, if (reminderMins.isNotEmpty()) 1 else 0)
                                    if (!locValue.isNullOrEmpty()) {
                                        put(CalendarContract.Events.EVENT_LOCATION, locValue)
                                    } else {
                                        putNull(CalendarContract.Events.EVENT_LOCATION)
                                    }
                                }

                                resolver.update(
                                    ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
                                    values,
                                    null,
                                    null
                                )
                                setEventReminders(context, eventId, reminderMins)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed updating task on GCal '${task.title}': ${e.message}", e)
                    }
                }
            }
        }

        return "Sync Complete! Imported $importedCount new events, Exported $exportedCount tasks."
    }

    // Helper to query all reminders for a given calendar event ID
    private fun getEventReminders(context: Context, eventId: Long): List<Int> {
        val list = mutableListOf<Int>()
        val projection = arrayOf(CalendarContract.Reminders.MINUTES)
        val selection = "${CalendarContract.Reminders.EVENT_ID} = ?"
        val selectionArgs = arrayOf(eventId.toString())
        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(
                CalendarContract.Reminders.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )
            cursor?.use {
                while (it.moveToNext()) {
                    list.add(it.getInt(0))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying reminders for event $eventId: ${e.message}")
        }
        return list
    }

    // Helper to clear and write reminders for a given calendar event ID
    private fun setEventReminders(context: Context, eventId: Long, minutesList: List<Int>) {
        val resolver = context.contentResolver
        try {
            resolver.delete(
                CalendarContract.Reminders.CONTENT_URI,
                "${CalendarContract.Reminders.EVENT_ID} = ?",
                arrayOf(eventId.toString())
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting old reminders for event $eventId: ${e.message}")
        }

        for (mins in minutesList) {
            try {
                val values = ContentValues().apply {
                    put(CalendarContract.Reminders.EVENT_ID, eventId)
                    put(CalendarContract.Reminders.MINUTES, mins)
                    put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                }
                resolver.insert(CalendarContract.Reminders.CONTENT_URI, values)
            } catch (e: Exception) {
                Log.e(TAG, "Error inserting reminder ($mins min) for event $eventId: ${e.message}")
            }
        }
    }

    // Helper to parse time from description
    private fun parseTaskTime(description: String): Pair<Int, Int>? {
        val amPmRegex = Regex("""\[Time:\s*(\d{1,2}):(\d{2})\s*(AM|PM)\]""", RegexOption.IGNORE_CASE)
        val amPmMatch = amPmRegex.find(description)
        if (amPmMatch != null) {
            var hour = amPmMatch.groupValues[1].toIntOrNull() ?: 0
            val minute = amPmMatch.groupValues[2].toIntOrNull() ?: 0
            val ampm = amPmMatch.groupValues[3].uppercase(Locale.US)
            if (ampm == "PM" && hour < 12) {
                hour += 12
            } else if (ampm == "AM" && hour == 12) {
                hour = 0
            }
            return Pair(hour, minute)
        }

        val stdRegex = Regex("""\[Time:\s*(\d{1,2}):(\d{2})\]""")
        val stdMatch = stdRegex.find(description)
        if (stdMatch != null) {
            val hour = stdMatch.groupValues[1].toIntOrNull() ?: 0
            val minute = stdMatch.groupValues[2].toIntOrNull() ?: 0
            return Pair(hour, minute)
        }
        return null
    }

    private fun parseTaskDuration(description: String): Int {
        val regex = Regex("""\[Duration:\s*([^\]]+)\]""", RegexOption.IGNORE_CASE)
        val match = regex.find(description)
        if (match != null) {
            val durationStr = match.groupValues[1].trim().lowercase(Locale.US)
            
            // Check for hour/hours/hr/hrs/h
            if (durationStr.contains("hour") || durationStr.contains("hr") || durationStr.contains("h")) {
                // Find the decimal number or integer before/in the unit
                val numRegex = Regex("""(\d+\.?\d*)""")
                val numMatch = numRegex.find(durationStr)
                if (numMatch != null) {
                    val numFloat = numMatch.groupValues[1].toFloatOrNull()
                    if (numFloat != null && numFloat > 0f) {
                        return (numFloat * 60).toInt()
                    }
                }
            }
            
            // Otherwise, try to extract minutes
            val digits = durationStr.filter { it.isDigit() }
            val durationInt = digits.toIntOrNull()
            if (durationInt != null && durationInt > 0) {
                return durationInt
            }
        }
        return 15
    }

    private fun getTaskRemindersInMinutes(description: String): List<Int> {
        val metaRemindersPattern = Regex("""\[Reminders: ([^\]]+)\]""")
        val match = metaRemindersPattern.find(description) ?: return emptyList()
        val content = match.groupValues[1]
        return content.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "None" }
            .mapNotNull { parseReminderStringToMinutes(it) }
    }

    private fun parseReminderStringToMinutes(reminderStr: String): Int? {
        val clean = reminderStr.lowercase().replace(" before", "").trim()
        val parts = clean.split(" ")
        if (parts.size < 2) return null
        val num = parts[0].toIntOrNull() ?: return null
        val unit = parts[1]
        return when {
            unit.startsWith("min") -> num
            unit.startsWith("hour") -> num * 60
            unit.startsWith("day") -> num * 24 * 60
            else -> null
        }
    }

    private fun formatMinutesToReminderString(minutes: Int): String {
        return when {
            minutes == 0 -> "At time of event"
            minutes % (24 * 60) == 0 -> "${minutes / (24 * 60)} days before"
            minutes % 60 == 0 -> "${minutes / 60} hours before"
            else -> "$minutes minutes before"
        }
    }
}


// ==================== CONSOLIDATED FROM: GoogleContactsSyncManager.kt ====================
object GoogleContactsSyncManager {
    private const val TAG = "GoogleContactsSync"
    private const val CONTACTS_SCOPE = "oauth2:https://www.googleapis.com/auth/contacts"

    private val client = OkHttpClient()
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    suspend fun getAccessToken(
        context: Context,
        onAuthResolutionRequired: (Intent) -> Unit = {}
    ): String? = withContext(Dispatchers.IO) {
        try {
            val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            var email = prefs.getString("selected_contacts_account", null)
            if (email.isNullOrBlank()) {
                val account = try { GoogleSignIn.getLastSignedInAccount(context) } catch (e: Throwable) { null }
                email = account?.email ?: "cabharathikrishan@gmail.com"
            }
            if (email.isNullOrBlank()) {
                Log.w(TAG, "No Google account email found.")
                return@withContext null
            }
            GoogleAuthUtil.getToken(context, email, CONTACTS_SCOPE)
        } catch (recoverable: UserRecoverableAuthException) {
            Log.w(TAG, "User recoverable auth exception encountered for Contacts scope.", recoverable)
            recoverable.intent?.let { intent -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onAuthResolutionRequired(intent) } }
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error obtaining Google OAuth2 token for Contacts: ${e.message}", e)
            null
        }
    }

    /**
     * Performs a full 2-way sync:
     * 1. Pulls contacts from Google Contacts and updates/creates them locally.
     * 2. Pushes local contacts that are new or updated to Google Contacts.
     */
    suspend fun syncContacts(
        context: Context,
        onAuthResolutionRequired: (Intent) -> Unit = {}
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val token = getAccessToken(context, onAuthResolutionRequired)
            ?: return@withContext Pair(false, "Authorization required. Please connect your Google account.")

        try {
            val database = AppDatabase.getInstance(context)
            val contactDao = database.contactDao()
            val localContacts = contactDao.getAllContacts().first()

            // Fetch Google Contact Groups (labels)
            val groupMap = fetchGoogleContactGroups(token).toMutableMap()
            val groupNameToResource = groupMap.entries.associate { it.value to it.key }.toMutableMap()

            // ---- STEP 1: PULL FROM GOOGLE ----
            val googleContacts = fetchGoogleConnections(token, groupMap)
            val googleIdToConnection = googleContacts.associateBy { it.resourceName }

            // Track if any new folders were fetched
            val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val currentFolders = prefs.getStringSet("contact_folders_set", emptySet())?.toMutableSet() ?: mutableSetOf()
            var foldersChanged = false

            for (gContact in googleContacts) {
                if (gContact.folder != "All" && !currentFolders.contains(gContact.folder)) {
                    currentFolders.add(gContact.folder)
                    foldersChanged = true
                }

                // Try to find matching local contact by googleContactId or fallback to names
                val matchedLocal = localContacts.find { it.googleContactId == gContact.resourceName }
                    ?: localContacts.find { 
                        gContact.firstName.isNotEmpty() &&
                        it.firstName.lowercase().trim() == gContact.firstName.lowercase().trim() &&
                        it.lastName.lowercase().trim() == gContact.lastName.lowercase().trim()
                    }

                if (matchedLocal != null) {
                    // Update existing local contact
                    val updated = matchedLocal.copy(
                        firstName = if (gContact.firstName.isNotEmpty()) gContact.firstName else matchedLocal.firstName,
                        middleName = if (gContact.middleName.isNotEmpty()) gContact.middleName else matchedLocal.middleName,
                        lastName = if (gContact.lastName.isNotEmpty()) gContact.lastName else matchedLocal.lastName,
                        phone = if (gContact.phone.isNotEmpty()) gContact.phone else matchedLocal.phone,
                        email = if (gContact.email.isNotEmpty()) gContact.email else matchedLocal.email,
                        address = if (gContact.address.isNotEmpty()) gContact.address else matchedLocal.address,
                        jobTitle = if (gContact.jobTitle.isNotEmpty()) gContact.jobTitle else matchedLocal.jobTitle,
                        dobString = if (gContact.dobString.isNotEmpty()) gContact.dobString else matchedLocal.dobString,
                        photoUri = if (!gContact.photoUrl.isNullOrEmpty()) gContact.photoUrl else matchedLocal.photoUri,
                        anniversaryString = if (gContact.anniversaryString.isNotEmpty()) gContact.anniversaryString else matchedLocal.anniversaryString,
                        additionalDatesJson = if (gContact.additionalDatesJson.isNotEmpty()) gContact.additionalDatesJson else matchedLocal.additionalDatesJson,
                        folder = gContact.folder, // Fully synchronized label/folder
                        googleContactId = gContact.resourceName
                    )
                    contactDao.updateContact(updated)
                } else {
                    // Create new local contact (including name, phone, email, address, job title, dob, profile pic, and dates)
                    val newContact = Contact(
                        firstName = gContact.firstName,
                        middleName = gContact.middleName,
                        lastName = gContact.lastName,
                        phone = gContact.phone,
                        dobString = gContact.dobString,
                        photoUri = gContact.photoUrl,
                        email = gContact.email,
                        address = gContact.address,
                        jobTitle = gContact.jobTitle,
                        anniversaryString = gContact.anniversaryString,
                        additionalDatesJson = gContact.additionalDatesJson,
                        folder = gContact.folder, // Fully synchronized label/folder
                        googleContactId = gContact.resourceName
                    )
                    contactDao.insertContact(newContact)
                }
            }

            if (foldersChanged) {
                prefs.edit().putStringSet("contact_folders_set", currentFolders).apply()
            }

            // ---- STEP 2: PUSH TO GOOGLE ----
            // Re-fetch local contacts after Pull updates
            val currentLocalContacts = contactDao.getAllContacts().first()

            for (local in currentLocalContacts) {
                // Determine target group resource name if folder is set
                var targetGroupResourceName: String? = null
                if (local.folder != "All" && local.folder.trim().isNotEmpty()) {
                    val folderName = local.folder.trim()
                    targetGroupResourceName = groupNameToResource[folderName]
                    if (targetGroupResourceName == null) {
                        // Create the group on Google
                        val newGroupRes = createGoogleContactGroup(token, folderName)
                        if (newGroupRes != null) {
                            groupNameToResource[folderName] = newGroupRes
                            groupMap[newGroupRes] = folderName
                            targetGroupResourceName = newGroupRes
                        }
                    }
                }

                if (local.googleContactId != null) {
                    // It was already synced. Let's see if it still exists on Google
                    val existsOnGoogle = googleIdToConnection.containsKey(local.googleContactId)
                    if (existsOnGoogle) {
                        // Let's update Google if local info is different
                        val gContact = googleIdToConnection[local.googleContactId]!!
                        if (local.firstName != gContact.firstName ||
                            local.middleName != gContact.middleName ||
                            local.lastName != gContact.lastName ||
                            local.phone != gContact.phone ||
                            local.email != gContact.email ||
                            local.address != gContact.address ||
                            local.jobTitle != gContact.jobTitle ||
                            local.dobString != gContact.dobString ||
                            local.anniversaryString != gContact.anniversaryString ||
                            local.additionalDatesJson != gContact.additionalDatesJson ||
                            local.folder != gContact.folder
                        ) {
                            updateGoogleContact(token, local, targetGroupResourceName)
                        }
                    } else {
                        // It was deleted on Google, so we can clear the googleContactId
                        contactDao.updateContact(local.copy(googleContactId = null))
                    }
                } else {
                    // No Google Contact ID -> This is a new local contact! Create on Google.
                    val newGoogleId = createGoogleContact(token, local, targetGroupResourceName)
                    if (newGoogleId != null) {
                        val updatedLocal = local.copy(googleContactId = newGoogleId)
                        contactDao.updateContact(updatedLocal)

                        // If local contact has a profile pic, upload it to Google Contacts!
                        if (!local.photoUri.isNullOrEmpty()) {
                            uploadGoogleContactPhoto(context, token, newGoogleId, local.photoUri)
                        }
                    }
                }
            }

            Pair(true, "Successfully completed 2-way sync with Google Contacts (${googleContacts.size} Google contacts synced).")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error performing Google Contacts 2-way sync: ${e.message}", e)
            Pair(false, "Sync Error: ${e.localizedMessage ?: "Unknown error"}")
        }
    }

    private fun sanitizePhone(phone: String): String {
        return phone.replace(Regex("[^0-9+]"), "")
    }

    private data class GoogleContactDetails(
        val resourceName: String,
        val etag: String,
        val firstName: String,
        val lastName: String,
        val middleName: String,
        val phone: String,
        val dobString: String,
        val photoUrl: String?,
        val email: String,
        val address: String,
        val jobTitle: String,
        val anniversaryString: String,
        val additionalDatesJson: String,
        val folder: String
    )

    private suspend fun fetchGoogleContactGroups(token: String): Map<String, String> {
        val url = "https://people.googleapis.com/v1/contactGroups?pageSize=1000"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        val groupMap = mutableMapOf<String, String>()
        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string() ?: ""
                    val json = JSONObject(bodyStr)
                    val groups = json.optJSONArray("contactGroups")
                    if (groups != null) {
                        for (i in 0 until groups.length()) {
                            val g = groups.getJSONObject(i)
                            val resourceName = g.optString("resourceName")
                            val name = g.optString("name")
                            if (resourceName.isNotEmpty() && name.isNotEmpty()) {
                                groupMap[resourceName] = name
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch contact groups", e)
        }
        return groupMap
    }

    private suspend fun createGoogleContactGroup(token: String, groupName: String): String? {
        val url = "https://people.googleapis.com/v1/contactGroups"
        val payload = JSONObject().apply {
            put("contactGroup", JSONObject().apply {
                put("name", groupName)
            })
        }
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string() ?: ""
                    val json = JSONObject(bodyStr)
                    val groupObj = json.optJSONObject("contactGroup")
                    if (groupObj != null) {
                        return groupObj.optString("resourceName")
                    }
                } else {
                    Log.e(TAG, "Failed to create contact group '$groupName': code=${response.code}, body=${response.body?.string()}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception creating contact group '$groupName'", e)
        }
        return null
    }

    private suspend fun fetchGoogleConnections(token: String, groupMap: Map<String, String>): List<GoogleContactDetails> {
        val url = "https://people.googleapis.com/v1/people/me/connections?personFields=names,phoneNumbers,birthdays,photos,emailAddresses,addresses,organizations,events,memberships&pageSize=1000"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        val list = mutableListOf<GoogleContactDetails>()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.e(TAG, "Failed to fetch connections: code=${response.code}, msg=${response.message}")
                return emptyList()
            }
            val bodyStr = response.body?.string() ?: ""
            Log.d(TAG, "fetchGoogleConnections Response JSON: $bodyStr")
            val json = JSONObject(bodyStr)
            val connections = json.optJSONArray("connections") ?: return emptyList()

            for (i in 0 until connections.length()) {
                val conn = connections.getJSONObject(i)
                val resourceName = conn.optString("resourceName")
                val etag = conn.optString("etag")

                // 2. Phone parsing
                var phone = ""
                val phoneNumbers = conn.optJSONArray("phoneNumbers")
                if (phoneNumbers != null && phoneNumbers.length() > 0) {
                    phone = phoneNumbers.getJSONObject(0).optString("value", "")
                }

                // 5. Email parsing
                var email = ""
                val emailAddresses = conn.optJSONArray("emailAddresses")
                if (emailAddresses != null && emailAddresses.length() > 0) {
                    email = emailAddresses.getJSONObject(0).optString("value", "")
                }

                // 1. Name parsing with display name fallback
                var firstName = ""
                var lastName = ""
                var middleName = ""
                val names = conn.optJSONArray("names")
                if (names != null && names.length() > 0) {
                    val nameObj = names.getJSONObject(0)
                    firstName = nameObj.optString("givenName", "").trim()
                    lastName = nameObj.optString("familyName", "").trim()
                    middleName = nameObj.optString("middleName", "").trim()
                    
                    if (firstName.isEmpty() && lastName.isEmpty()) {
                        val displayName = nameObj.optString("displayName", "").trim()
                        if (displayName.isNotEmpty()) {
                            val parts = displayName.split(" ", limit = 2)
                            firstName = parts.first()
                            lastName = parts.getOrNull(1) ?: ""
                        }
                    }
                }

                if (firstName.isEmpty() && lastName.isEmpty()) {
                    if (phone.isNotEmpty()) {
                        firstName = phone
                    } else if (email.isNotEmpty()) {
                        firstName = email.substringBefore("@")
                    } else {
                        firstName = "Unnamed Google Contact"
                    }
                }

                // 3. Birthday parsing with text fallback
                var dobString = ""
                val birthdays = conn.optJSONArray("birthdays")
                if (birthdays != null && birthdays.length() > 0) {
                    val bdayObj = birthdays.getJSONObject(0)
                    val dateObj = bdayObj.optJSONObject("date")
                    if (dateObj != null) {
                        val y = dateObj.optInt("year", 0)
                        val m = dateObj.optInt("month", 0)
                        val d = dateObj.optInt("day", 0)
                        if (y > 0 && m > 0 && d > 0) {
                            dobString = String.format(Locale.US, "%04d-%02d-%02d", y, m, d)
                        } else if (m > 0 && d > 0) {
                            dobString = String.format(Locale.US, "%02d-%02d", m, d)
                        }
                    } else {
                        val text = bdayObj.optString("text", "").trim()
                        if (text.isNotEmpty()) {
                            dobString = text
                        }
                    }
                }

                // 4. Photo parsing (always extract the URL if present)
                var photoUrl: String? = null
                val photos = conn.optJSONArray("photos")
                if (photos != null && photos.length() > 0) {
                    photoUrl = photos.getJSONObject(0).optString("url")
                }

                // 6. Address parsing
                var address = ""
                val addresses = conn.optJSONArray("addresses")
                if (addresses != null && addresses.length() > 0) {
                    address = addresses.getJSONObject(0).optString("formattedValue", "")
                }

                // 7. Job Title parsing
                var jobTitle = ""
                val organizations = conn.optJSONArray("organizations")
                if (organizations != null && organizations.length() > 0) {
                    jobTitle = organizations.getJSONObject(0).optString("title", "")
                }

                // 8. Anniversary and other dates parsing
                var anniversaryString = ""
                val additionalDatesList = mutableListOf<String>()
                val events = conn.optJSONArray("events")
                if (events != null) {
                    for (j in 0 until events.length()) {
                        val eventObj = events.getJSONObject(j)
                        val type = eventObj.optString("type", "")
                        val formattedType = eventObj.optString("formattedType", type.replaceFirstChar { it.uppercase() })
                        val dateObj = eventObj.optJSONObject("date")
                        var dateStr = ""
                        if (dateObj != null) {
                            val y = dateObj.optInt("year", 0)
                            val m = dateObj.optInt("month", 0)
                            val d = dateObj.optInt("day", 0)
                            if (y > 0 && m > 0 && d > 0) {
                                dateStr = String.format(Locale.US, "%04d-%02d-%02d", y, m, d)
                            } else if (m > 0 && d > 0) {
                                dateStr = String.format(Locale.US, "%02d-%02d", m, d)
                            }
                        } else {
                            dateStr = eventObj.optString("text", "").trim()
                        }

                        if (dateStr.isNotEmpty()) {
                            if (type == "anniversary") {
                                anniversaryString = dateStr
                            } else {
                                val label = if (formattedType.isNotEmpty()) formattedType else "Event"
                                additionalDatesList.add("$label:$dateStr")
                            }
                        }
                    }
                }
                val additionalDatesJson = additionalDatesList.joinToString(";")

                // 9. Membership parsing for group/label mapping to folder
                var folder = "All"
                val memberships = conn.optJSONArray("memberships")
                if (memberships != null) {
                    for (m in 0 until memberships.length()) {
                        val mObj = memberships.getJSONObject(m)
                        val cgMembership = mObj.optJSONObject("contactGroupMembership")
                        if (cgMembership != null) {
                            val cgResName = cgMembership.optString("contactGroupResourceName")
                            val mappedGroupName = groupMap[cgResName]
                            if (!mappedGroupName.isNullOrEmpty() && mappedGroupName != "myContacts" && mappedGroupName != "starred") {
                                folder = mappedGroupName
                                break
                            }
                        }
                    }
                }

                if (resourceName.isNotEmpty()) {
                    list.add(
                        GoogleContactDetails(
                            resourceName = resourceName,
                            etag = etag,
                            firstName = firstName,
                            lastName = lastName,
                            middleName = middleName,
                            phone = phone,
                            dobString = dobString,
                            photoUrl = photoUrl,
                            email = email,
                            address = address,
                            jobTitle = jobTitle,
                            anniversaryString = anniversaryString,
                            additionalDatesJson = additionalDatesJson,
                            folder = folder
                        )
                    )
                }
            }
        }
        return list
    }

    private suspend fun createGoogleContact(token: String, contact: Contact, groupResourceName: String?): String? {
        val url = "https://people.googleapis.com/v1/people/createContact"
        val payload = buildContactPayload(contact, groupResourceName)

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val bodyStr = response.body?.string() ?: ""
                val json = JSONObject(bodyStr)
                return json.optString("resourceName")
            } else {
                Log.e(TAG, "Failed to create contact on Google: code=${response.code}, body=${response.body?.string()}")
            }
        }
        return null
    }

    private suspend fun updateGoogleContact(token: String, contact: Contact, groupResourceName: String?): Boolean {
        val resourceName = contact.googleContactId ?: return false
        val etag = getEtag(token, resourceName) ?: return false

        val url = "https://people.googleapis.com/v1/$resourceName?updatePersonFields=names,phoneNumbers,birthdays,emailAddresses,addresses,organizations,events,memberships"
        val payload = buildContactPayload(contact, groupResourceName).apply {
            put("etag", etag)
        }

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .patch(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                return true
            } else {
                Log.e(TAG, "Failed to update contact on Google: code=${response.code}, body=${response.body?.string()}")
            }
        }
        return false
    }

    suspend fun deleteGoogleContact(context: Context, resourceName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val token = getAccessToken(context) ?: return@withContext false
            val url = "https://people.googleapis.com/v1/$resourceName:deleteContact"
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .delete()
                .build()
            client.newCall(request).execute().use { response ->
                return@withContext response.isSuccessful
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete contact on Google: ${e.message}")
            false
        }
    }

    private suspend fun uploadGoogleContactPhoto(context: Context, token: String, resourceName: String, photoUriStr: String): Boolean {
        try {
            val uri = Uri.parse(photoUriStr)
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            if (inputStream != null) {
                val bytes = inputStream.readBytes()
                inputStream.close()
                val base64Str = Base64.encodeToString(bytes, Base64.NO_WRAP)

                val url = "https://people.googleapis.com/v1/$resourceName:updateContactPhoto"
                val payload = JSONObject().apply {
                    put("photoBytes", base64Str)
                }

                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $token")
                    .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                client.newCall(request).execute().use { response ->
                    return response.isSuccessful
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upload contact photo: ${e.message}")
        }
        return false
    }

    private suspend fun getEtag(token: String, resourceName: String): String? {
        val request = Request.Builder()
            .url("https://people.googleapis.com/v1/$resourceName?personFields=metadata")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val bodyStr = response.body?.string() ?: ""
                val json = JSONObject(bodyStr)
                return json.optString("etag")
            }
        }
        return null
    }

    private fun buildContactPayload(contact: Contact, groupResourceName: String?): JSONObject {
        val payload = JSONObject()

        val namesArray = JSONArray().apply {
            put(JSONObject().apply {
                put("givenName", contact.firstName)
                put("familyName", contact.lastName)
                if (contact.middleName.isNotEmpty()) {
                    put("middleName", contact.middleName)
                }
            })
        }
        payload.put("names", namesArray)

        if (contact.phone.isNotEmpty()) {
            val phoneArray = JSONArray().apply {
                put(JSONObject().apply {
                    put("value", contact.phone)
                    put("type", "mobile")
                })
            }
            payload.put("phoneNumbers", phoneArray)
        }

        if (contact.email.isNotEmpty()) {
            val emailArray = JSONArray().apply {
                put(JSONObject().apply {
                    put("value", contact.email)
                    put("type", "home")
                })
            }
            payload.put("emailAddresses", emailArray)
        }

        if (contact.address.isNotEmpty()) {
            val addressArray = JSONArray().apply {
                put(JSONObject().apply {
                    put("streetAddress", contact.address)
                    put("type", "home")
                })
            }
            payload.put("addresses", addressArray)
        }

        if (contact.jobTitle.isNotEmpty()) {
            val orgArray = JSONArray().apply {
                put(JSONObject().apply {
                    put("title", contact.jobTitle)
                })
            }
            payload.put("organizations", orgArray)
        }

        fun parseDateToJson(dateStr: String): JSONObject {
            val dateObj = JSONObject()
            if (dateStr.isBlank()) return dateObj
            val cleaned = dateStr.trim().replace("/", "-")
            val parts = cleaned.split("-")
            if (parts.size >= 3) {
                if (parts[0].length == 4) {
                    dateObj.put("year", parts[0].toIntOrNull() ?: 0)
                    dateObj.put("month", parts[1].toIntOrNull() ?: 0)
                    dateObj.put("day", parts[2].toIntOrNull() ?: 0)
                } else {
                    dateObj.put("day", parts[0].toIntOrNull() ?: 0)
                    dateObj.put("month", parts[1].toIntOrNull() ?: 0)
                    dateObj.put("year", parts[2].toIntOrNull() ?: 0)
                }
            } else if (parts.size == 2) {
                val p0 = parts[0].toIntOrNull() ?: 0
                val p1 = parts[1].toIntOrNull() ?: 0
                if (p0 > 12 && p1 <= 12) {
                    dateObj.put("day", p0)
                    dateObj.put("month", p1)
                } else {
                    dateObj.put("month", p0)
                    dateObj.put("day", p1)
                }
            }
            return dateObj
        }

        if (contact.dobString.isNotEmpty()) {
            val dateObj = parseDateToJson(contact.dobString)
            if (dateObj.length() > 0) {
                val birthdaysArray = JSONArray().apply {
                    put(JSONObject().apply {
                        put("date", dateObj)
                    })
                }
                payload.put("birthdays", birthdaysArray)
            }
        }

        val eventsArray = JSONArray()
        if (contact.anniversaryString.isNotEmpty()) {
            val dateObj = parseDateToJson(contact.anniversaryString)
            if (dateObj.length() > 0) {
                eventsArray.put(JSONObject().apply {
                    put("type", "anniversary")
                    put("date", dateObj)
                })
            }
        }

        if (contact.additionalDatesJson.isNotEmpty()) {
            contact.additionalDatesJson.split(";").forEach { pair ->
                val parts = pair.split(":")
                if (parts.size == 2) {
                    val label = parts[0]
                    val dateVal = parts[1]
                    val dateObj = parseDateToJson(dateVal)
                    if (dateObj.length() > 0) {
                        eventsArray.put(JSONObject().apply {
                            put("type", "other")
                            put("formattedType", label)
                            put("date", dateObj)
                        })
                    }
                }
            }
        }

        if (eventsArray.length() > 0) {
            payload.put("events", eventsArray)
        }

        val membershipsArray = JSONArray().apply {
            put(JSONObject().apply {
                put("contactGroupMembership", JSONObject().apply {
                    put("contactGroupResourceName", "contactGroups/myContacts")
                })
            })
            if (!groupResourceName.isNullOrEmpty() && groupResourceName != "contactGroups/myContacts") {
                put(JSONObject().apply {
                    put("contactGroupMembership", JSONObject().apply {
                        put("contactGroupResourceName", groupResourceName)
                    })
                })
            }
        }
        payload.put("memberships", membershipsArray)

        return payload
    }
}


// ==================== CONSOLIDATED FROM: GoogleDriveSyncManager.kt ====================
object GoogleDriveSyncManager {

    suspend fun getAccessToken(
        context: Context,
        onAuthResolutionRequired: (Intent) -> Unit = {}
    ): String? = GoogleDriveReadManager.getAccessToken(context, onAuthResolutionRequired)

    fun hasDrivePermission(context: Context): Boolean =
        GoogleDriveReadManager.hasDrivePermission(context)

    suspend fun deleteGoogleDriveFile(context: Context, fileId: String): Boolean =
        GoogleDriveWriteManager.deleteGoogleDriveFile(context, fileId)

    suspend fun renameGoogleDriveFile(context: Context, fileId: String, newName: String): Boolean =
        GoogleDriveWriteManager.renameGoogleDriveFile(context, fileId, newName)

    suspend fun backupFocusData(
        context: Context,
        onAuthResolutionRequired: (Intent) -> Unit = {}
    ): Pair<Boolean, String> = GoogleDriveWriteManager.backupFocusData(context, onAuthResolutionRequired)

    suspend fun restoreFocusData(
        context: Context,
        onAuthResolutionRequired: (Intent) -> Unit = {}
    ): Pair<Boolean, String> = GoogleDriveReadManager.restoreFocusData(context, onAuthResolutionRequired)

    suspend fun backupAllAppData(
        context: Context,
        database: com.example.data.AppDatabase,
        onAuthResolutionRequired: (Intent) -> Unit = {}
    ): Pair<Boolean, String> = GoogleDriveWriteManager.backupAllAppData(context, database, onAuthResolutionRequired)

    suspend fun restoreAllAppData(
        context: Context,
        database: com.example.data.AppDatabase,
        onAuthResolutionRequired: (Intent) -> Unit = {}
    ): Pair<Boolean, String> = GoogleDriveReadManager.restoreAllAppData(context, database, onAuthResolutionRequired)

    suspend fun getBackupSizes(
        context: Context,
        onAuthResolutionRequired: (Intent) -> Unit = {}
    ): Map<String, Long> = GoogleDriveReadManager.getBackupSizes(context, onAuthResolutionRequired)

    suspend fun hasExistingBackupData(
        context: Context,
        onAuthResolutionRequired: (Intent) -> Unit = {}
    ): Boolean = GoogleDriveReadManager.hasExistingBackupData(context, onAuthResolutionRequired)

    suspend fun checkAndRetrieveDriveData(
        context: Context,
        database: com.example.data.AppDatabase
    ): Pair<Boolean, String> = GoogleDriveReadManager.checkAndRetrieveDriveData(context, database)

    suspend fun uploadPublicMediaFileDirect(
        context: Context,
        token: String,
        file: java.io.File,
        mimeType: String = "image/jpeg",
        categoryFolder: String = "General_Files"
    ): String? {
        return GoogleDriveUploadManager.uploadPublicMediaFileDirect(context, token, file, mimeType, categoryFolder)
    }

    suspend fun uploadPublicMediaFileDirect(
        context: Context,
        accessToken: String,
        file: java.io.File,
        categoryFolder: String
    ): String? {
        return GoogleDriveUploadManager.uploadPublicMediaFileDirect(context, accessToken, file, "image/jpeg", categoryFolder)
    }

    fun ensureVaultStructureAndReadme(token: String): GoogleDriveUploadManager.VaultFolders? =
        GoogleDriveUploadManager.ensureVaultStructureAndReadme(token)

    fun deleteOlderDuplicateFiles(token: String, folderId: String, fileName: String, keepLatestId: String) =
        GoogleDriveWriteManager.deleteOlderDuplicateFiles(token, folderId, fileName, keepLatestId)

    fun deleteGoogleDriveFileDirect(token: String, fileId: String): Boolean =
        GoogleDriveWriteManager.deleteGoogleDriveFileDirect(token, fileId)

    suspend fun deleteMediaByUrlOrName(context: Context, urlOrName: String): Boolean =
        GoogleDriveWriteManager.deleteMediaByUrlOrName(context, urlOrName)

    fun makeFilePublic(token: String, fileId: String) =
        GoogleDriveWriteManager.makeFilePublic(token, fileId)

    fun makeFilePublicAndEditor(token: String, fileId: String) =
        GoogleDriveWriteManager.makeFilePublicAndEditor(token, fileId)

    fun extractIdFromUrl(url: String): String? =
        GoogleDriveWriteManager.extractIdFromUrl(url)

    suspend fun makeFolderAndContentsPublicAndEditorRecursive(
        token: String,
        folderId: String,
        collectedItems: MutableList<org.json.JSONObject>? = null
    ) = GoogleDriveWriteManager.makeFolderAndContentsPublicAndEditorRecursive(token, folderId, collectedItems)

    suspend fun uploadPublicMediaFileToFolderDirect(
        context: Context,
        token: String,
        file: java.io.File,
        folderId: String,
        mimeType: String = "image/jpeg"
    ): String? = GoogleDriveUploadManager.uploadPublicMediaFileToFolderDirect(context, token, file, folderId, mimeType)

    fun sendNotification(context: Context, title: String, message: String) =
        GoogleDriveWriteManager.sendNotification(context, title, message)

    data class GoogleSheetFile(
        val id: String,
        val name: String,
        val modifiedTime: String,
        val webViewLink: String,
        val size: Long
    )

    data class GoogleDocFile(
        val id: String,
        val name: String,
        val modifiedTime: String,
        val webViewLink: String,
        val size: Long
    )

    data class GoogleDriveFileItem(
        val id: String,
        val name: String,
        val mimeType: String = "",
        val modifiedTime: String = "",
        val webViewLink: String = "",
        val size: Long = 0L,
        val isFolder: Boolean = false
    )

    suspend fun listGoogleDocs(
        context: Context,
        onAuthResolutionRequired: (Intent) -> Unit = {}
    ): Pair<Boolean, List<GoogleDocFile>> {
        val items = GoogleDriveReadManager.listGoogleDocs(context, onAuthResolutionRequired)
        val docs = items.map {
            GoogleDocFile(it.id, it.name, it.modifiedTime, it.webViewLink, it.size)
        }
        return Pair(true, docs)
    }

    suspend fun listGoogleSheets(
        context: Context,
        onAuthResolutionRequired: (Intent) -> Unit = {}
    ): Pair<Boolean, List<GoogleSheetFile>> {
        val items = GoogleDriveReadManager.listGoogleSheets(context, onAuthResolutionRequired)
        val sheets = items.map {
            GoogleSheetFile(it.id, it.name, it.modifiedTime, it.webViewLink, it.size)
        }
        return Pair(true, sheets)
    }

    suspend fun listGoogleDriveFiles(
        context: Context,
        parentId: String? = null,
        onAuthResolutionRequired: (Intent) -> Unit = {}
    ): Pair<Boolean, List<GoogleDriveFileItem>> {
        val items = GoogleDriveReadManager.listGoogleDriveFiles(context, null, onAuthResolutionRequired)
        val files = items.map {
            GoogleDriveFileItem(it.id, it.name, it.mimeType, it.modifiedTime, it.webViewLink, it.size, it.isFolder)
        }
        return Pair(true, files)
    }

    suspend fun createGoogleDoc(
        context: Context,
        title: String,
        onAuthResolutionRequired: (Intent) -> Unit = {}
    ): Pair<Boolean, String> = GoogleDriveWriteManager.createGoogleDoc(context, title, onAuthResolutionRequired)

    suspend fun createGoogleDocWithContent(
        context: Context,
        title: String,
        content: String,
        onAuthResolutionRequired: (Intent) -> Unit = {}
    ): Pair<Boolean, String> {
        val link = GoogleDriveWriteManager.createGoogleDocWithContent(context, title, content, onAuthResolutionRequired)
        return if (link != null) Pair(true, link) else Pair(false, "Failed to create Google Doc")
    }

    suspend fun createGoogleSheet(
        context: Context,
        title: String,
        onAuthResolutionRequired: (Intent) -> Unit = {}
    ): Pair<Boolean, String> = GoogleDriveWriteManager.createGoogleSheet(context, title, onAuthResolutionRequired)

    suspend fun createGoogleSheetWithContent(
        context: Context,
        title: String,
        csvContent: String,
        onAuthResolutionRequired: (Intent) -> Unit = {}
    ): Pair<Boolean, String> {
        val rows = csvContent.lines().map { line -> line.split(",") }
        val link = GoogleDriveWriteManager.createGoogleSheetWithContent(context, title, rows, onAuthResolutionRequired)
        return if (link != null) Pair(true, link) else Pair(false, "Failed to create Google Sheet")
    }

    suspend fun createGoogleDriveFolder(
        context: Context,
        folderName: String,
        parentId: String? = null,
        onAuthResolutionRequired: (Intent) -> Unit = {}
    ): Pair<Boolean, String> {
        val folderId = GoogleDriveWriteManager.createGoogleDriveFolder(context, folderName, onAuthResolutionRequired)
        return if (folderId != null) Pair(true, folderId) else Pair(false, "Failed to create folder")
    }

    suspend fun syncKeepNotes(
        context: Context,
        database: com.example.data.AppDatabase,
        onAuthResolutionRequired: (Intent) -> Unit = {}
    ): Pair<Boolean, String> = GoogleDriveWriteManager.syncKeepNotes(context, database, onAuthResolutionRequired)
}


// ==================== CONSOLIDATED FROM: GoogleFitSyncManager.kt ====================
object GoogleFitSyncManager {
    private const val TAG = "GoogleFitSync"
    private const val FIT_SCOPE = "oauth2:https://www.googleapis.com/auth/fitness.activity.read https://www.googleapis.com/auth/fitness.body.read"

    suspend fun getAccessToken(
        context: Context,
        onAuthResolutionRequired: (Intent) -> Unit = {}
    ): String? = withContext(Dispatchers.IO) {
        try {
            val account = try { GoogleSignIn.getLastSignedInAccount(context) } catch (e: Throwable) { null }
            val email = account?.email
            if (email.isNullOrBlank()) {
                Log.w(TAG, "No Google account email found.")
                return@withContext null
            }
            GoogleAuthUtil.getToken(context, email, FIT_SCOPE)
        } catch (recoverable: UserRecoverableAuthException) {
            Log.w(TAG, "User recoverable auth exception encountered.", recoverable)
            recoverable.intent?.let { intent -> 
                withContext(Dispatchers.Main) { onAuthResolutionRequired(intent) }
            }
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Error obtaining Google OAuth2 token: ${e.message}", e)
            null
        }
    }

    fun hasFitPermission(context: Context): Boolean {
        return try {
            val account = try { com.google.android.gms.auth.api.signin.GoogleSignIn.getLastSignedInAccount(context) } catch (e: Throwable) { null }
            account != null && account.grantedScopes.any { it.scopeUri.equals("https://www.googleapis.com/auth/fitness.activity.read", ignoreCase = true) }
        } catch (e: Throwable) {
            false
        }
    }
}


// ==================== CONSOLIDATED FROM: SystemContactSyncHelper.kt ====================
object SystemContactSyncHelper {

    fun getContactPhotoBytes(context: Context, photoUriStr: String): ByteArray? {
        try {
            if (photoUriStr.startsWith("http")) {
                // Check local cache first to prevent redundant downloading
                val cacheFile = java.io.File(context.cacheDir, "contact_photo_${photoUriStr.hashCode()}.jpg")
                if (cacheFile.exists()) {
                    return cacheFile.readBytes()
                }

                var connection: java.net.HttpURLConnection? = null
                try {
                    val url = java.net.URL(photoUriStr)
                    connection = url.openConnection() as java.net.HttpURLConnection
                    connection.doInput = true
                    connection.connect()
                    val input = connection.inputStream
                    val bytes = input.readBytes()
                    input.close()

                    if (bytes.isNotEmpty()) {
                        cacheFile.writeBytes(bytes)
                    }
                    return bytes
                } finally {
                    connection?.disconnect()
                }
            } else {
                val file = java.io.File(photoUriStr)
                if (file.exists()) {
                    return file.readBytes()
                }
                val uri = Uri.parse(photoUriStr)
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    return inputStream.readBytes()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private fun systemContactHasPhoto(context: Context, systemId: Long): Boolean {
        val resolver = context.contentResolver
        return try {
            resolver.query(
                Data.CONTENT_URI,
                arrayOf(Photo._ID),
                "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ? AND ${Photo.PHOTO} IS NOT NULL",
                arrayOf(systemId.toString(), Photo.CONTENT_ITEM_TYPE),
                null
            )?.use { cursor ->
                cursor.moveToFirst()
            } ?: false
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun insertSystemContact(context: Context, contact: Contact): Long? {
        val hasName = contact.firstName.isNotEmpty() || contact.lastName.isNotEmpty()
        val hasPhone = contact.phone.isNotEmpty()
        if (!hasName || !hasPhone) return null

        val resolver = context.contentResolver
        val ops = arrayListOf<ContentProviderOperation>()

        // 1. Raw Contact insertion
        val rawContactOpIndex = ops.size
        ops.add(ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
            .withValue(RawContacts.ACCOUNT_TYPE, null)
            .withValue(RawContacts.ACCOUNT_NAME, null)
            .build())

        // 2. Name insertion
        ops.add(ContentProviderOperation.newInsert(Data.CONTENT_URI)
            .withValueBackReference(Data.RAW_CONTACT_ID, rawContactOpIndex)
            .withValue(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
            .withValue(StructuredName.DISPLAY_NAME, "${contact.firstName} ${contact.lastName}".trim())
            .build())

        // 3. Phone insertion
        if (contact.phone.isNotEmpty()) {
            ops.add(ContentProviderOperation.newInsert(Data.CONTENT_URI)
                .withValueBackReference(Data.RAW_CONTACT_ID, rawContactOpIndex)
                .withValue(Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
                .withValue(Phone.NUMBER, contact.phone)
                .withValue(Phone.TYPE, Phone.TYPE_MOBILE)
                .build())
        }

        // 4. Photo insertion
        if (!contact.photoUri.isNullOrEmpty()) {
            val imageBytes = getContactPhotoBytes(context, contact.photoUri)
            if (imageBytes != null) {
                ops.add(ContentProviderOperation.newInsert(Data.CONTENT_URI)
                    .withValueBackReference(Data.RAW_CONTACT_ID, rawContactOpIndex)
                    .withValue(Data.MIMETYPE, Photo.CONTENT_ITEM_TYPE)
                    .withValue(Photo.PHOTO, imageBytes)
                    .build())
            }
        }

        // 5. Birthday insertion
        if (contact.dobString.isNotEmpty()) {
            ops.add(ContentProviderOperation.newInsert(Data.CONTENT_URI)
                .withValueBackReference(Data.RAW_CONTACT_ID, rawContactOpIndex)
                .withValue(Data.MIMETYPE, Event.CONTENT_ITEM_TYPE)
                .withValue(Event.START_DATE, contact.dobString)
                .withValue(Event.TYPE, Event.TYPE_BIRTHDAY)
                .build())
        }

        // 6. Anniversary insertion
        if (contact.anniversaryString.isNotEmpty()) {
            ops.add(ContentProviderOperation.newInsert(Data.CONTENT_URI)
                .withValueBackReference(Data.RAW_CONTACT_ID, rawContactOpIndex)
                .withValue(Data.MIMETYPE, Event.CONTENT_ITEM_TYPE)
                .withValue(Event.START_DATE, contact.anniversaryString)
                .withValue(Event.TYPE, Event.TYPE_ANNIVERSARY)
                .build())
        }

        // 7. Email insertion
        if (contact.email.isNotEmpty()) {
            ops.add(ContentProviderOperation.newInsert(Data.CONTENT_URI)
                .withValueBackReference(Data.RAW_CONTACT_ID, rawContactOpIndex)
                .withValue(Data.MIMETYPE, Email.CONTENT_ITEM_TYPE)
                .withValue(Email.ADDRESS, contact.email)
                .withValue(Email.TYPE, Email.TYPE_HOME)
                .build())
        }

        // 8. Address insertion
        if (contact.address.isNotEmpty()) {
            ops.add(ContentProviderOperation.newInsert(Data.CONTENT_URI)
                .withValueBackReference(Data.RAW_CONTACT_ID, rawContactOpIndex)
                .withValue(Data.MIMETYPE, StructuredPostal.CONTENT_ITEM_TYPE)
                .withValue(StructuredPostal.FORMATTED_ADDRESS, contact.address)
                .withValue(StructuredPostal.TYPE, StructuredPostal.TYPE_HOME)
                .build())
        }

        // 9. Job Title insertion
        if (contact.jobTitle.isNotEmpty()) {
            ops.add(ContentProviderOperation.newInsert(Data.CONTENT_URI)
                .withValueBackReference(Data.RAW_CONTACT_ID, rawContactOpIndex)
                .withValue(Data.MIMETYPE, Organization.CONTENT_ITEM_TYPE)
                .withValue(Organization.TITLE, contact.jobTitle)
                .build())
        }

        return try {
            val results = resolver.applyBatch(ContactsContract.AUTHORITY, ops)
            if (results.isNotEmpty()) {
                val rawContactUri = results[0].uri
                if (rawContactUri != null) {
                    val systemId = ContentUris.parseId(rawContactUri)
                    if (systemId > 0 && !contact.photoUri.isNullOrEmpty()) {
                        context.getSharedPreferences("system_contact_photo_sync_prefs", Context.MODE_PRIVATE)
                            .edit().putString(systemId.toString(), contact.photoUri).apply()
                    }
                    systemId
                } else null
            } else null
        } catch (e: SecurityException) {
            android.util.Log.e("SystemContactSync", "Permission missing for contact insertion: ${e.message}")
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun updateSystemContact(context: Context, contact: Contact): Long? {
        val hasName = contact.firstName.isNotEmpty() || contact.lastName.isNotEmpty()
        val hasPhone = contact.phone.isNotEmpty()
        if (!hasName || !hasPhone) {
            // Requirement: If it doesn't have both name and phone number, don't sync
            return contact.systemContactId
        }

        val systemId = contact.systemContactId ?: return insertSystemContact(context, contact)
        val resolver = context.contentResolver

        // Check if raw contact actually exists on system
        val rawUri = ContentUris.withAppendedId(RawContacts.CONTENT_URI, systemId)
        val cursor = try {
            resolver.query(rawUri, arrayOf(RawContacts._ID), null, null, null)
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val exists = cursor?.use { it.moveToFirst() } ?: false
        if (!exists) {
            return insertSystemContact(context, contact)
        }

        val ops = arrayListOf<ContentProviderOperation>()

        // Update StructuredName
        ops.add(ContentProviderOperation.newUpdate(Data.CONTENT_URI)
            .withSelection(
                "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ?",
                arrayOf(systemId.toString(), StructuredName.CONTENT_ITEM_TYPE)
            )
            .withValue(StructuredName.DISPLAY_NAME, "${contact.firstName} ${contact.lastName}".trim())
            .build())

        // Calculate photo update status
        val hasSystemPhoto = systemContactHasPhoto(context, systemId)
        val photoPrefs = context.getSharedPreferences("system_contact_photo_sync_prefs", Context.MODE_PRIVATE)
        val lastSyncedPhoto = photoPrefs.getString(systemId.toString(), null)
        val currentPhoto = contact.photoUri ?: ""

        val shouldUpdatePhoto = if (currentPhoto.isEmpty()) {
            hasSystemPhoto
        } else {
            !hasSystemPhoto || lastSyncedPhoto != currentPhoto
        }

        // Delete old phone cleanly
        ops.add(ContentProviderOperation.newDelete(Data.CONTENT_URI)
            .withSelection(
                "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ?",
                arrayOf(systemId.toString(), Phone.CONTENT_ITEM_TYPE)
            )
            .build())

        // Delete old events (Birthday & Anniversary)
        ops.add(ContentProviderOperation.newDelete(Data.CONTENT_URI)
            .withSelection(
                "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ?",
                arrayOf(systemId.toString(), Event.CONTENT_ITEM_TYPE)
            )
            .build())

        // Delete old email
        ops.add(ContentProviderOperation.newDelete(Data.CONTENT_URI)
            .withSelection(
                "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ?",
                arrayOf(systemId.toString(), Email.CONTENT_ITEM_TYPE)
            )
            .build())

        // Delete old address
        ops.add(ContentProviderOperation.newDelete(Data.CONTENT_URI)
            .withSelection(
                "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ?",
                arrayOf(systemId.toString(), StructuredPostal.CONTENT_ITEM_TYPE)
            )
            .build())

        // Delete old organization
        ops.add(ContentProviderOperation.newDelete(Data.CONTENT_URI)
            .withSelection(
                "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ?",
                arrayOf(systemId.toString(), Organization.CONTENT_ITEM_TYPE)
            )
            .build())

        // Delete photo if required
        if (shouldUpdatePhoto) {
            ops.add(ContentProviderOperation.newDelete(Data.CONTENT_URI)
                .withSelection(
                    "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ?",
                    arrayOf(systemId.toString(), Photo.CONTENT_ITEM_TYPE)
                )
                .build())
        }

        // Re-insert phone
        if (contact.phone.isNotEmpty()) {
            ops.add(ContentProviderOperation.newInsert(Data.CONTENT_URI)
                .withValue(Data.RAW_CONTACT_ID, systemId)
                .withValue(Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
                .withValue(Phone.NUMBER, contact.phone)
                .withValue(Phone.TYPE, Phone.TYPE_MOBILE)
                .build())
        }

        // Re-insert birthday
        if (contact.dobString.isNotEmpty()) {
            ops.add(ContentProviderOperation.newInsert(Data.CONTENT_URI)
                .withValue(Data.RAW_CONTACT_ID, systemId)
                .withValue(Data.MIMETYPE, Event.CONTENT_ITEM_TYPE)
                .withValue(Event.START_DATE, contact.dobString)
                .withValue(Event.TYPE, Event.TYPE_BIRTHDAY)
                .build())
        }

        // Re-insert anniversary
        if (contact.anniversaryString.isNotEmpty()) {
            ops.add(ContentProviderOperation.newInsert(Data.CONTENT_URI)
                .withValue(Data.RAW_CONTACT_ID, systemId)
                .withValue(Data.MIMETYPE, Event.CONTENT_ITEM_TYPE)
                .withValue(Event.START_DATE, contact.anniversaryString)
                .withValue(Event.TYPE, Event.TYPE_ANNIVERSARY)
                .build())
        }

        // Re-insert email
        if (contact.email.isNotEmpty()) {
            ops.add(ContentProviderOperation.newInsert(Data.CONTENT_URI)
                .withValue(Data.RAW_CONTACT_ID, systemId)
                .withValue(Data.MIMETYPE, Email.CONTENT_ITEM_TYPE)
                .withValue(Email.ADDRESS, contact.email)
                .withValue(Email.TYPE, Email.TYPE_HOME)
                .build())
        }

        // Re-insert address
        if (contact.address.isNotEmpty()) {
            ops.add(ContentProviderOperation.newInsert(Data.CONTENT_URI)
                .withValue(Data.RAW_CONTACT_ID, systemId)
                .withValue(Data.MIMETYPE, StructuredPostal.CONTENT_ITEM_TYPE)
                .withValue(StructuredPostal.FORMATTED_ADDRESS, contact.address)
                .withValue(StructuredPostal.TYPE, StructuredPostal.TYPE_HOME)
                .build())
        }

        // Re-insert organization
        if (contact.jobTitle.isNotEmpty()) {
            ops.add(ContentProviderOperation.newInsert(Data.CONTENT_URI)
                .withValue(Data.RAW_CONTACT_ID, systemId)
                .withValue(Data.MIMETYPE, Organization.CONTENT_ITEM_TYPE)
                .withValue(Organization.TITLE, contact.jobTitle)
                .build())
        }

        // Re-insert photo ONLY if it changed and is present
        if (shouldUpdatePhoto && currentPhoto.isNotEmpty()) {
            val imageBytes = getContactPhotoBytes(context, currentPhoto)
            if (imageBytes != null) {
                ops.add(ContentProviderOperation.newInsert(Data.CONTENT_URI)
                    .withValue(Data.RAW_CONTACT_ID, systemId)
                    .withValue(Data.MIMETYPE, Photo.CONTENT_ITEM_TYPE)
                    .withValue(Photo.PHOTO, imageBytes)
                    .build())
                photoPrefs.edit().putString(systemId.toString(), currentPhoto).apply()
            }
        }

        return try {
            resolver.applyBatch(ContactsContract.AUTHORITY, ops)
            systemId
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            systemId
        }
    }

    fun deleteSystemContact(context: Context, contact: Contact) {
        val systemId = contact.systemContactId ?: return
        val resolver = context.contentResolver
        val rawUri = ContentUris.withAppendedId(RawContacts.CONTENT_URI, systemId)
        try {
            resolver.delete(rawUri, null, null)
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

// ==================== GOOGLE PHOTOS SYNC MANAGER ====================
object GooglePhotosSyncManager {
    private const val TAG = "GooglePhotosSync"
    private const val PHOTOS_SCOPE = "oauth2:https://www.googleapis.com/auth/photoslibrary.readonly"

    private val client = okhttp3.OkHttpClient()

    suspend fun getAccessToken(
        context: Context,
        onAuthResolutionRequired: (Intent) -> Unit = {}
    ): String? = withContext(Dispatchers.IO) {
        try {
            val account = try { com.google.android.gms.auth.api.signin.GoogleSignIn.getLastSignedInAccount(context) } catch (e: Throwable) { null }
            val email = account?.email
            if (email.isNullOrBlank()) {
                Log.w(TAG, "No Google account email found. Triggering Google Sign-In for Photos scope.")
                val gso = com.google.android.gms.auth.api.signin.GoogleSignInOptions.Builder(com.google.android.gms.auth.api.signin.GoogleSignInOptions.DEFAULT_SIGN_IN)
                    .requestEmail()
                    .requestScopes(com.google.android.gms.common.api.Scope("https://www.googleapis.com/auth/photoslibrary.readonly"))
                    .build()
                val intent = try { com.google.android.gms.auth.api.signin.GoogleSignIn.getClient(context, gso).signInIntent } catch (e: Throwable) { null }
                if (intent != null) {
                    withContext(Dispatchers.Main) {
                        onAuthResolutionRequired(intent)
                    }
                }
                return@withContext null
            }
            com.google.android.gms.auth.GoogleAuthUtil.getToken(context, email, PHOTOS_SCOPE)
        } catch (recoverable: com.google.android.gms.auth.UserRecoverableAuthException) {
            Log.w(TAG, "User recoverable auth exception encountered for Photos scope.", recoverable)
            recoverable.intent?.let { intent -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onAuthResolutionRequired(intent) } }
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Error obtaining Google OAuth2 token for Photos: ${e.message}", e)
            try {
                val gso = com.google.android.gms.auth.api.signin.GoogleSignInOptions.Builder(com.google.android.gms.auth.api.signin.GoogleSignInOptions.DEFAULT_SIGN_IN)
                    .requestEmail()
                    .requestScopes(com.google.android.gms.common.api.Scope("https://www.googleapis.com/auth/photoslibrary.readonly"))
                    .build()
                val intent = com.google.android.gms.auth.api.signin.GoogleSignIn.getClient(context, gso).signInIntent
                withContext(Dispatchers.Main) {
                    onAuthResolutionRequired(intent)
                }
            } catch (ignored: Throwable) {}
            null
        }
    }

    data class GooglePhotoItem(
        val id: String,
        val description: String?,
        val productUrl: String,
        val baseUrl: String,
        val mimeType: String,
        val filename: String
    )

    suspend fun fetchGooglePhotos(
        context: Context,
        onAuthResolutionRequired: (Intent) -> Unit = {}
    ): Pair<Boolean, List<GooglePhotoItem>> = withContext(Dispatchers.IO) {
        val token = getAccessToken(context, onAuthResolutionRequired)
            ?: return@withContext Pair(false, emptyList())

        try {
            val url = "https://photoslibrary.googleapis.com/v1/mediaItems?pageSize=50"
            val request = okhttp3.Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Failed to fetch photos: code=${response.code}")
                    return@withContext Pair(false, emptyList())
                }
                val bodyStr = response.body?.string() ?: "{}"
                val json = org.json.JSONObject(bodyStr)
                val mediaItemsArray = json.optJSONArray("mediaItems")
                val photosList = mutableListOf<GooglePhotoItem>()
                if (mediaItemsArray != null) {
                    for (i in 0 until mediaItemsArray.length()) {
                        val item = mediaItemsArray.getJSONObject(i)
                        val id = item.getString("id")
                        val description = item.optString("description", null)
                        val productUrl = item.optString("productUrl", "")
                        val baseUrl = item.optString("baseUrl", "")
                        val mimeType = item.optString("mimeType", "")
                        val filename = item.optString("filename", "")
                        photosList.add(GooglePhotoItem(id, description, productUrl, baseUrl, mimeType, filename))
                    }
                }
                Pair(true, photosList)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching Google Photos: ${e.message}", e)
            Pair(false, emptyList())
        }
    }

    suspend fun fetchGooglePhotosByDate(
        context: Context,
        year: Int,
        month: Int,
        day: Int,
        onAuthResolutionRequired: (Intent) -> Unit = {}
    ): Pair<Boolean, List<GooglePhotoItem>> = withContext(Dispatchers.IO) {
        val token = getAccessToken(context, onAuthResolutionRequired)
            ?: return@withContext Pair(false, emptyList())

        try {
            val url = "https://photoslibrary.googleapis.com/v1/mediaItems:search"
            
            val requestBodyJson = org.json.JSONObject().apply {
                put("pageSize", 100)
                put("filters", org.json.JSONObject().apply {
                    put("dateFilter", org.json.JSONObject().apply {
                        put("dates", org.json.JSONArray().apply {
                            put(org.json.JSONObject().apply {
                                put("year", year)
                                put("month", month)
                                put("day", day)
                            })
                        })
                    })
                })
            }

            val body = okhttp3.RequestBody.create(
                "application/json; charset=utf-8".toMediaType(),
                requestBodyJson.toString()
            )

            val request = okhttp3.Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Failed to fetch photos by date: code=${response.code}")
                    return@withContext Pair(false, emptyList())
                }
                val bodyStr = response.body?.string() ?: "{}"
                val json = org.json.JSONObject(bodyStr)
                val mediaItemsArray = json.optJSONArray("mediaItems")
                val photosList = mutableListOf<GooglePhotoItem>()
                if (mediaItemsArray != null) {
                    for (i in 0 until mediaItemsArray.length()) {
                        val item = mediaItemsArray.getJSONObject(i)
                        val id = item.getString("id")
                        val description = item.optString("description", null)
                        val productUrl = item.optString("productUrl", "")
                        val baseUrl = item.optString("baseUrl", "")
                        val mimeType = item.optString("mimeType", "")
                        val filename = item.optString("filename", "")
                        photosList.add(GooglePhotoItem(id, description, productUrl, baseUrl, mimeType, filename))
                    }
                }
                Pair(true, photosList)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching Google Photos by date: ${e.message}", e)
            Pair(false, emptyList())
        }
    }

    private const val PICKER_SCOPE = "oauth2:https://www.googleapis.com/auth/photospicker.mediaitems.readonly"

    suspend fun getPickerAccessToken(
        context: Context,
        onAuthResolutionRequired: (Intent) -> Unit = {}
    ): String? = withContext(Dispatchers.IO) {
        try {
            val account = try { com.google.android.gms.auth.api.signin.GoogleSignIn.getLastSignedInAccount(context) } catch (e: Throwable) { null }
            val email = account?.email
            if (email.isNullOrBlank()) {
                Log.w(TAG, "No Google account email found for Picker. Triggering Google Sign-In.")
                val gso = com.google.android.gms.auth.api.signin.GoogleSignInOptions.Builder(com.google.android.gms.auth.api.signin.GoogleSignInOptions.DEFAULT_SIGN_IN)
                    .requestEmail()
                    .requestScopes(com.google.android.gms.common.api.Scope("https://www.googleapis.com/auth/photospicker.mediaitems.readonly"))
                    .build()
                val intent = try { com.google.android.gms.auth.api.signin.GoogleSignIn.getClient(context, gso).signInIntent } catch (e: Throwable) { null }
                if (intent != null) {
                    withContext(Dispatchers.Main) {
                        onAuthResolutionRequired(intent)
                    }
                }
                return@withContext null
            }
            com.google.android.gms.auth.GoogleAuthUtil.getToken(context, email, PICKER_SCOPE)
        } catch (recoverable: com.google.android.gms.auth.UserRecoverableAuthException) {
            Log.w(TAG, "User recoverable auth exception encountered for Picker scope.", recoverable)
            recoverable.intent?.let { intent -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onAuthResolutionRequired(intent) } }
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Error obtaining Google OAuth2 token for Picker: ${e.message}", e)
            try {
                val gso = com.google.android.gms.auth.api.signin.GoogleSignInOptions.Builder(com.google.android.gms.auth.api.signin.GoogleSignInOptions.DEFAULT_SIGN_IN)
                    .requestEmail()
                    .requestScopes(com.google.android.gms.common.api.Scope("https://www.googleapis.com/auth/photospicker.mediaitems.readonly"))
                    .build()
                val intent = try { com.google.android.gms.auth.api.signin.GoogleSignIn.getClient(context, gso).signInIntent } catch (e: Throwable) { null }
                if (intent != null) {
                    withContext(Dispatchers.Main) {
                        onAuthResolutionRequired(intent)
                    }
                }
            } catch (ignored: Throwable) {}
            null
        }
    }

    data class PickerSession(
        val id: String,
        val pickerUri: String,
        val pollInterval: String?,
        val timeoutIn: String?,
        val mediaItemsSet: Boolean
    )

    suspend fun createPickerSession(
        context: Context,
        onAuthResolutionRequired: (Intent) -> Unit = {}
    ): PickerSession? = withContext(Dispatchers.IO) {
        val token = getPickerAccessToken(context, onAuthResolutionRequired)
            ?: return@withContext null

        try {
            val url = "https://photospicker.googleapis.com/v1/sessions"
            val body = okhttp3.RequestBody.create(
                "application/json; charset=utf-8".toMediaType(),
                "{}"
            )
            val request = okhttp3.Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Failed to create picker session: code=${response.code} body=${response.body?.string()}")
                    return@withContext null
                }
                val bodyStr = response.body?.string() ?: "{}"
                val json = org.json.JSONObject(bodyStr)
                val id = json.getString("id")
                val pickerUri = json.getString("pickerUri")
                val pollingConfig = json.optJSONObject("pollingConfig")
                val pollInterval = pollingConfig?.optString("pollInterval")
                val timeoutIn = pollingConfig?.optString("timeoutIn")
                val mediaItemsSet = json.optBoolean("mediaItemsSet", false)
                PickerSession(id, pickerUri, pollInterval, timeoutIn, mediaItemsSet)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error creating Picker session: ${e.message}", e)
            null
        }
    }

    suspend fun getPickerSession(
        context: Context,
        sessionId: String,
        onAuthResolutionRequired: (Intent) -> Unit = {}
    ): PickerSession? = withContext(Dispatchers.IO) {
        val token = getPickerAccessToken(context, onAuthResolutionRequired)
            ?: return@withContext null

        try {
            val url = "https://photospicker.googleapis.com/v1/sessions/$sessionId"
            val request = okhttp3.Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Failed to get picker session: code=${response.code}")
                    return@withContext null
                }
                val bodyStr = response.body?.string() ?: "{}"
                val json = org.json.JSONObject(bodyStr)
                val id = json.getString("id")
                val pickerUri = json.getString("pickerUri")
                val pollingConfig = json.optJSONObject("pollingConfig")
                val pollInterval = pollingConfig?.optString("pollInterval")
                val timeoutIn = pollingConfig?.optString("timeoutIn")
                val mediaItemsSet = json.optBoolean("mediaItemsSet", false)
                PickerSession(id, pickerUri, pollInterval, timeoutIn, mediaItemsSet)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error getting Picker session: ${e.message}", e)
            null
        }
    }

    suspend fun fetchPickerMediaItems(
        context: Context,
        sessionId: String,
        onAuthResolutionRequired: (Intent) -> Unit = {}
    ): List<GooglePhotoItem> = withContext(Dispatchers.IO) {
        val token = getPickerAccessToken(context, onAuthResolutionRequired)
            ?: return@withContext emptyList()

        try {
            val url = "https://photospicker.googleapis.com/v1/mediaItems?sessionId=$sessionId"
            val request = okhttp3.Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Failed to fetch picker media items: code=${response.code}")
                    return@withContext emptyList()
                }
                val bodyStr = response.body?.string() ?: "{}"
                val json = org.json.JSONObject(bodyStr)
                val mediaItemsArray = json.optJSONArray("mediaItems")
                val photosList = mutableListOf<GooglePhotoItem>()
                if (mediaItemsArray != null) {
                    for (i in 0 until mediaItemsArray.length()) {
                        val item = mediaItemsArray.getJSONObject(i)
                        val id = item.getString("id")
                        val mimeType = item.optString("mimeType", "")
                        
                        var baseUrl = item.optString("baseUrl", "")
                        if (baseUrl.isEmpty()) {
                            baseUrl = item.optJSONObject("mediaFile")?.optString("baseUrl", "") ?: ""
                        }
                        
                        photosList.add(GooglePhotoItem(
                            id = id,
                            description = item.optString("description", null),
                            productUrl = item.optString("productUrl", ""),
                            baseUrl = baseUrl,
                            mimeType = mimeType,
                            filename = item.optString("filename", "")
                        ))
                    }
                }
                photosList
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching picker media items: ${e.message}", e)
            emptyList()
        }
    }
}

