package com.example.api

import android.content.Context
import android.os.Build
import android.util.Log
import com.example.data.*
import com.example.util.NetworkChecker
import com.google.firebase.FirebaseApp
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * SameUserMultiDeviceSyncManager
 *
 * Dedicated manager that unifies and controls live cross-device synchronization
 * for the SAME user logged into multiple devices simultaneously.
 *
 * Capabilities:
 * - Device Presence & Connectivity Maintenance (.info/connected + NetworkChecker)
 * - Auto-reconnect & automatic differential fetch upon network reconnection
 * - Live real-time bidirectional sync for Tasks, Journal, Files, Finance, Keep Notes, and User Settings
 * - Conflict resolution & deduplication across multiple logged-in devices
 * - Exposes real-time sync & device connection state via [syncInfoState]
 */
object SameUserMultiDeviceSyncManager {
    private const val TAG = "SameUserMultiDeviceSync"

    enum class SyncState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        SYNCING,
        ERROR
    }

    data class MultiDeviceSyncInfo(
        val activeDeviceCount: Int = 1,
        val lastSyncedMs: Long = 0L,
        val syncState: SyncState = SyncState.DISCONNECTED,
        val lastSyncMessage: String = "Idle"
    )

    private val _syncInfoState = MutableStateFlow(MultiDeviceSyncInfo())
    val syncInfoState: StateFlow<MultiDeviceSyncInfo> = _syncInfoState.asStateFlow()

    private val isListening = AtomicBoolean(false)
    private var activeUserEmail: String = ""

    private var tasksListener: ValueEventListener? = null
    private var journalListener: ValueEventListener? = null
    private var filesListener: ValueEventListener? = null
    private var financeListener: ValueEventListener? = null
    private var keepNotesListener: ValueEventListener? = null
    private var moviesListener: ValueEventListener? = null
    private var settingsSignalListener: ValueEventListener? = null
    private var connectedInfoListener: ValueEventListener? = null
    private var activeDevicesListener: ValueEventListener? = null

    private var lastSyncedSettingsTimestamp = 0L

    fun getDeviceId(context: Context): String {
        return DevicePresenceManager.getDeviceKey(context)
    }

    fun sanitizeEmail(email: String): String {
        return DevicePresenceManager.sanitizeEmail(email)
    }

    /**
     * Starts live synchronization listeners for the same user across all active devices.
     */
    fun startLiveSync(context: Context, email: String, database: AppDatabase) {
        val sanitized = sanitizeEmail(email)
        if (sanitized.isBlank()) return

        if (isListening.get() && activeUserEmail == sanitized) {
            Log.d(TAG, "Already listening for multi-device sync for $sanitized")
            return
        }

        val dbUrl = FirebaseConfig.getDatabaseUrl(context)
        if (dbUrl.isEmpty()) {
            Log.w(TAG, "Firebase DB URL empty. Skipping multi-device live sync setup.")
            return
        }

        try {
            stopLiveSync(context)
            activeUserEmail = sanitized

            updateSyncState(SyncState.CONNECTING, "Initializing multi-device listeners...")

            val rtdb = FirebaseDatabase.getInstance(dbUrl)
            val userRef = rtdb.getReference("FOCUS_TIMMER").child("USER").child(sanitized)
            val deviceKey = getDeviceId(context)

            // Register device presence and active session tracking
            DevicePresenceManager.registerPresence(context, email)

            // 1. Connection state (.info/connected) monitor for auto-recovery
            val connListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val connected = snapshot.getValue(Boolean::class.java) ?: false
                    if (connected) {
                        Log.i(TAG, "⚡ Realtime Database connected for multi-device sync ($sanitized)")
                        updateSyncState(SyncState.CONNECTED, "Connected to cloud")
                        // Automatically fetch and reconcile diffs on reconnect
                        fetchAndSyncAllData(context, email, database)
                    } else {
                        Log.w(TAG, "⚠️ Realtime Database disconnected for $sanitized")
                        updateSyncState(SyncState.DISCONNECTED, "Offline / Connection Lost")
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Connection info listener cancelled: ${error.message}")
                }
            }
            rtdb.getReference(".info/connected").addValueEventListener(connListener)
            connectedInfoListener = connListener

            // 2. Devices count listener (Tracks active devices for this user)
            val devListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (snapshot.exists()) {
                        val activeCount = snapshot.children.count { child ->
                            val loginStatus = child.child("Login_status").getValue(Boolean::class.java) ?: false
                            loginStatus
                        }
                        val countToReport = maxOf(1, activeCount)
                        _syncInfoState.value = _syncInfoState.value.copy(activeDeviceCount = countToReport)
                    }
                }

                override fun onCancelled(error: DatabaseError) {}
            }
            userRef.child("DEVICES_LOGGED_IN").addValueEventListener(devListener)
            activeDevicesListener = devListener

            // 3. TASKS_LIVE Listener
            val tListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    CoroutineScope(Dispatchers.IO).launch {
                        reconcileTasks(database, snapshot, deviceKey)
                    }
                }
                override fun onCancelled(error: DatabaseError) {}
            }
            userRef.child("TASKS_LIVE").addValueEventListener(tListener)
            tasksListener = tListener

            // 4. JOURNAL_LIVE Listener
            val jListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    CoroutineScope(Dispatchers.IO).launch {
                        reconcileJournal(database, snapshot, deviceKey)
                    }
                }
                override fun onCancelled(error: DatabaseError) {}
            }
            userRef.child("JOURNAL_LIVE").addValueEventListener(jListener)
            journalListener = jListener

            // 5. FILE_EXPLORER_LIVE Listener
            val fListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    CoroutineScope(Dispatchers.IO).launch {
                        reconcileFiles(database, snapshot, deviceKey)
                    }
                }
                override fun onCancelled(error: DatabaseError) {}
            }
            userRef.child("FILE_EXPLORER_LIVE").addValueEventListener(fListener)
            filesListener = fListener

            // 6. FINANCE_LIVE Listener
            val finListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    CoroutineScope(Dispatchers.IO).launch {
                        reconcileFinance(database, snapshot, deviceKey)
                    }
                }
                override fun onCancelled(error: DatabaseError) {}
            }
            userRef.child("FINANCE_LIVE").addValueEventListener(finListener)
            financeListener = finListener

            // 7. KEEP_NOTES Listener
            val knListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    CoroutineScope(Dispatchers.IO).launch {
                        reconcileKeepNotes(database, snapshot, deviceKey)
                    }
                }
                override fun onCancelled(error: DatabaseError) {}
            }
            userRef.child("KEEP_NOTES").addValueEventListener(knListener)
            keepNotesListener = knListener

            // 8. MOVIES_LIVE Listener
            val movListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (!snapshot.exists()) return
                    try {
                        val rawVal = snapshot.value
                        val jsonStr = when (rawVal) {
                            is String -> rawVal
                            else -> null
                        }
                        if (!jsonStr.isNullOrEmpty()) {
                            val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                            val currentLocal = prefs.getString("movie_tracker_json_data", "")
                            if (currentLocal != jsonStr) {
                                prefs.edit().putString("movie_tracker_json_data", jsonStr).apply()
                                Log.i(TAG, "🎬 Reconciled MOVIES_LIVE across devices into SharedPreferences")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error reconciling MOVIES_LIVE", e)
                    }
                }
                override fun onCancelled(error: DatabaseError) {}
            }
            userRef.child("MOVIES_LIVE").addValueEventListener(movListener)
            moviesListener = movListener

            // 8. SETTINGS_SYNC_SIGNAL Listener
            val sigListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (!snapshot.exists()) return
                    val originDeviceId = snapshot.child("originDeviceId").getValue(String::class.java) ?: ""
                    val timestamp = snapshot.child("timestamp").getValue(Long::class.java) ?: 0L

                    if (originDeviceId.isNotEmpty() && originDeviceId != deviceKey && timestamp > lastSyncedSettingsTimestamp) {
                        Log.i(TAG, "Received SETTINGS_SYNC_SIGNAL from device $originDeviceId. Pulling settings...")
                        pullSettingsFromCloud(context, email)
                    }
                }

                override fun onCancelled(error: DatabaseError) {}
            }
            userRef.child("SETTINGS_SYNC_SIGNAL").addValueEventListener(sigListener)
            settingsSignalListener = sigListener

            isListening.set(true)
            Log.d(TAG, "Successfully started SameUserMultiDeviceSyncManager for $sanitized (device=$deviceKey)")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting SameUserMultiDeviceSyncManager", e)
            updateSyncState(SyncState.ERROR, "Sync initialization failed")
        }
    }

    /**
     * Stops all active live listeners.
     */
    fun stopLiveSync(context: Context) {
        if (!isListening.get() && activeUserEmail.isBlank()) return
        try {
            val dbUrl = FirebaseConfig.getDatabaseUrl(context)
            if (dbUrl.isNotEmpty() && activeUserEmail.isNotBlank()) {
                val sanitized = sanitizeEmail(activeUserEmail)
                val rtdb = FirebaseDatabase.getInstance(dbUrl)
                val userRef = rtdb.getReference("FOCUS_TIMMER").child("USER").child(sanitized)

                connectedInfoListener?.let { rtdb.getReference(".info/connected").removeEventListener(it) }
                activeDevicesListener?.let { userRef.child("DEVICES_LOGGED_IN").removeEventListener(it) }
                tasksListener?.let { userRef.child("TASKS_LIVE").removeEventListener(it) }
                journalListener?.let { userRef.child("JOURNAL_LIVE").removeEventListener(it) }
                filesListener?.let { userRef.child("FILE_EXPLORER_LIVE").removeEventListener(it) }
                financeListener?.let { userRef.child("FINANCE_LIVE").removeEventListener(it) }
                keepNotesListener?.let { userRef.child("KEEP_NOTES").removeEventListener(it) }
                moviesListener?.let { userRef.child("MOVIES_LIVE").removeEventListener(it) }
                settingsSignalListener?.let { userRef.child("SETTINGS_SYNC_SIGNAL").removeEventListener(it) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping SameUserMultiDeviceSyncManager listeners", e)
        } finally {
            connectedInfoListener = null
            activeDevicesListener = null
            tasksListener = null
            journalListener = null
            filesListener = null
            financeListener = null
            keepNotesListener = null
            moviesListener = null
            settingsSignalListener = null
            isListening.set(false)
            activeUserEmail = ""
            updateSyncState(SyncState.DISCONNECTED, "Stopped")
        }
    }

    /**
     * Performs a full cloud fetch & reconciliation across all entity types.
     */
    fun fetchAndSyncAllData(context: Context, email: String, database: AppDatabase, onComplete: (() -> Unit)? = null) {
        val sanitized = sanitizeEmail(email)
        if (sanitized.isBlank()) return

        val dbUrl = FirebaseConfig.getDatabaseUrl(context)
        if (dbUrl.isEmpty()) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!NetworkChecker.isOnline(context)) {
                    Log.d(TAG, "Device is offline. Skipping fetchAndSyncAllData.")
                    return@launch
                }

                updateSyncState(SyncState.SYNCING, "Fetching latest cloud state...")
                val deviceKey = getDeviceId(context)
                val rtdb = FirebaseDatabase.getInstance(dbUrl)
                val userRef = rtdb.getReference("FOCUS_TIMMER").child("USER").child(sanitized)

                userRef.child("TASKS_LIVE").get().addOnSuccessListener { snapshot ->
                    CoroutineScope(Dispatchers.IO).launch { reconcileTasks(database, snapshot, deviceKey) }
                }
                userRef.child("JOURNAL_LIVE").get().addOnSuccessListener { snapshot ->
                    CoroutineScope(Dispatchers.IO).launch { reconcileJournal(database, snapshot, deviceKey) }
                }
                userRef.child("FILE_EXPLORER_LIVE").get().addOnSuccessListener { snapshot ->
                    CoroutineScope(Dispatchers.IO).launch { reconcileFiles(database, snapshot, deviceKey) }
                }
                userRef.child("FINANCE_LIVE").get().addOnSuccessListener { snapshot ->
                    CoroutineScope(Dispatchers.IO).launch { reconcileFinance(database, snapshot, deviceKey) }
                }
                userRef.child("KEEP_NOTES").get().addOnSuccessListener { snapshot ->
                    CoroutineScope(Dispatchers.IO).launch { reconcileKeepNotes(database, snapshot, deviceKey) }
                }

                pullSettingsFromCloud(context, email)
                DevicePresenceManager.adoptHighestTodayFocusMsFromOtherDevices(context, email)

                updateSyncState(SyncState.CONNECTED, "All devices in sync")
                _syncInfoState.value = _syncInfoState.value.copy(lastSyncedMs = System.currentTimeMillis())

                withContext(Dispatchers.Main) {
                    onComplete?.invoke()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in fetchAndSyncAllData", e)
                updateSyncState(SyncState.ERROR, "Fetch error: ${e.message}")
            }
        }
    }

    // =========================================================================
    // PUSH / UPLOAD OPERATIONS
    // =========================================================================

    fun pushTaskToCloud(context: Context, email: String, task: Task, isDeleted: Boolean = false) {
        val sanitized = sanitizeEmail(email)
        if (sanitized.isBlank()) return
        val dbUrl = FirebaseConfig.getDatabaseUrl(context)
        if (dbUrl.isEmpty()) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val rtdb = FirebaseDatabase.getInstance(dbUrl)
                val key = createTaskKey(task)
                val ref = rtdb.getReference("FOCUS_TIMMER")
                    .child("USER").child(sanitized)
                    .child("TASKS_LIVE").child(key)

                if (isDeleted) {
                    ref.setValue(null)
                } else {
                    val payload = mapOf(
                        "key" to key,
                        "title" to task.title,
                        "description" to task.description,
                        "estimatedMinutes" to task.estimatedMinutes,
                        "actualMinutes" to task.actualMinutes,
                        "isCompleted" to task.isCompleted,
                        "listCategory" to task.listCategory,
                        "priority" to task.priority,
                        "dueDateString" to task.dueDateString,
                        "orderIndex" to task.orderIndex,
                        "nagModeEnabled" to task.nagModeEnabled,
                        "originDeviceId" to getDeviceId(context),
                        "updatedAt" to System.currentTimeMillis()
                    )
                    ref.setValue(payload)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to push Task to cloud", e)
            }
        }
    }

    fun pushJournalToCloud(context: Context, email: String, entry: JournalEntry, isDeleted: Boolean = false) {
        val sanitized = sanitizeEmail(email)
        if (sanitized.isBlank()) return
        val dbUrl = FirebaseConfig.getDatabaseUrl(context)
        if (dbUrl.isEmpty()) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val rtdb = FirebaseDatabase.getInstance(dbUrl)
                val key = createJournalKey(entry)
                val ref = rtdb.getReference("FOCUS_TIMMER")
                    .child("USER").child(sanitized)
                    .child("JOURNAL_LIVE").child(key)

                if (isDeleted) {
                    ref.setValue(null)
                } else {
                    val payload = mapOf(
                        "key" to key,
                        "title" to entry.title,
                        "text" to entry.text,
                        "dateString" to entry.dateString,
                        "timestamp" to entry.timestamp,
                        "attachmentsJson" to entry.attachmentsJson,
                        "originDeviceId" to getDeviceId(context),
                        "updatedAt" to System.currentTimeMillis()
                    )
                    ref.setValue(payload)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to push Journal to cloud", e)
            }
        }
    }

    fun pushFileToCloud(context: Context, email: String, file: AppFile, isDeleted: Boolean = false) {
        val sanitized = sanitizeEmail(email)
        if (sanitized.isBlank()) return
        val dbUrl = FirebaseConfig.getDatabaseUrl(context)
        if (dbUrl.isEmpty()) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val rtdb = FirebaseDatabase.getInstance(dbUrl)
                val key = createFileKey(file)
                val ref = rtdb.getReference("FOCUS_TIMMER")
                    .child("USER").child(sanitized)
                    .child("FILE_EXPLORER_LIVE").child(key)

                if (isDeleted) {
                    ref.setValue(null)
                } else {
                    val payload = mapOf(
                        "key" to key,
                        "name" to file.name,
                        "path" to file.path,
                        "size" to file.size,
                        "mimeType" to file.mimeType,
                        "uriString" to file.uriString,
                        "timestamp" to file.timestamp,
                        "isFavorite" to file.isFavorite,
                        "originDeviceId" to getDeviceId(context),
                        "updatedAt" to System.currentTimeMillis()
                    )
                    ref.setValue(payload)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to push File to cloud", e)
            }
        }
    }

    fun pushFinanceToCloud(context: Context, email: String, transaction: FinanceTransaction, isDeleted: Boolean = false) {
        val sanitized = sanitizeEmail(email)
        if (sanitized.isBlank()) return
        val dbUrl = FirebaseConfig.getDatabaseUrl(context)
        if (dbUrl.isEmpty()) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val rtdb = FirebaseDatabase.getInstance(dbUrl)
                val key = createFinanceKey(transaction)
                val ref = rtdb.getReference("FOCUS_TIMMER")
                    .child("USER").child(sanitized)
                    .child("FINANCE_LIVE").child(key)

                if (isDeleted) {
                    ref.setValue(null)
                } else {
                    val payload = mapOf(
                        "key" to key,
                        "type" to transaction.type,
                        "amount" to transaction.amount,
                        "fromCategory" to (transaction.fromCategory ?: ""),
                        "toCategory" to (transaction.toCategory ?: ""),
                        "note" to transaction.note,
                        "timestamp" to transaction.timestamp,
                        "originDeviceId" to getDeviceId(context),
                        "updatedAt" to System.currentTimeMillis()
                    )
                    ref.setValue(payload)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to push Finance to cloud", e)
            }
        }
    }

    fun pushKeepNoteToCloud(context: Context, email: String, note: KeepNote, isDeleted: Boolean = false) {
        val sanitized = sanitizeEmail(email)
        if (sanitized.isBlank()) return
        val dbUrl = FirebaseConfig.getDatabaseUrl(context)
        if (dbUrl.isEmpty()) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val rtdb = FirebaseDatabase.getInstance(dbUrl)
                val noteKey = generateNoteKey(note)
                val ref = rtdb.getReference("FOCUS_TIMMER")
                    .child("USER")
                    .child(sanitized)
                    .child("KEEP_NOTES")
                    .child(noteKey)

                if (isDeleted) {
                    ref.setValue(null)
                } else {
                    val payload = mapOf(
                        "noteKey" to noteKey,
                        "title" to note.title,
                        "content" to note.content,
                        "timestamp" to note.timestamp,
                        "isPinned" to note.isPinned,
                        "colorHex" to note.colorHex,
                        "websiteUrl" to (note.websiteUrl ?: ""),
                        "customLogoUrl" to (note.customLogoUrl ?: ""),
                        "originDeviceId" to getDeviceId(context),
                        "updatedAt" to System.currentTimeMillis()
                    )
                    ref.setValue(payload)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to push KeepNote to cloud", e)
            }
        }
    }

    fun deleteNoteFromCloud(context: Context, email: String, note: KeepNote) {
        pushKeepNoteToCloud(context, email, note, isDeleted = true)
    }

    fun pushSettingsToCloud(context: Context, email: String) {
        val sanitized = sanitizeEmail(email)
        if (sanitized.isBlank()) return

        val deviceKey = getDeviceId(context)
        val now = System.currentTimeMillis()
        lastSyncedSettingsTimestamp = now

        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!NetworkChecker.isOnline(context)) return@launch

                val firestore = FirebaseFirestore.getInstance(FirebaseApp.getInstance(), "main")

                val appPrefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).all
                val appSettings = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE).all
                val countdownPrefs = context.getSharedPreferences("countdown_settings_prefs", Context.MODE_PRIVATE).all
                val strictPrefs = context.getSharedPreferences("strict_mode_prefs", Context.MODE_PRIVATE).all
                val calendarPrefs = context.getSharedPreferences("app_calendar_prefs", Context.MODE_PRIVATE).all

                val settingsPayload = mapOf(
                    "updatedAt" to now,
                    "originDeviceId" to deviceKey,
                    "email" to email,
                    "app_prefs" to serializePrefMap(appPrefs),
                    "app_settings" to serializePrefMap(appSettings),
                    "countdown_settings_prefs" to serializePrefMap(countdownPrefs),
                    "strict_mode_prefs" to serializePrefMap(strictPrefs),
                    "app_calendar_prefs" to serializePrefMap(calendarPrefs)
                )

                firestore.collection("users")
                    .document(sanitized)
                    .collection("user_settings")
                    .document("settings_config")
                    .set(settingsPayload, SetOptions.merge())
                    .await()

                val dbUrl = FirebaseConfig.getDatabaseUrl(context)
                if (dbUrl.isNotEmpty()) {
                    val database = FirebaseDatabase.getInstance(dbUrl)
                    val signalRef = database.getReference("FOCUS_TIMMER")
                        .child("USER")
                        .child(sanitized)
                        .child("SETTINGS_SYNC_SIGNAL")

                    val signalData = mapOf(
                        "timestamp" to now,
                        "originDeviceId" to deviceKey,
                        "type" to "SETTINGS_UPDATED"
                    )

                    signalRef.setValue(signalData).await()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error pushing settings to cloud", e)
            }
        }
    }

    fun pullSettingsFromCloud(context: Context, email: String, onComplete: (() -> Unit)? = null) {
        val sanitized = sanitizeEmail(email)
        if (sanitized.isBlank()) return
        val deviceKey = getDeviceId(context)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!NetworkChecker.isOnline(context)) return@launch

                val firestore = FirebaseFirestore.getInstance(FirebaseApp.getInstance(), "main")
                val docSnap = firestore.collection("users")
                    .document(sanitized)
                    .collection("user_settings")
                    .document("settings_config")
                    .get()
                    .await()

                if (!docSnap.exists()) return@launch

                val originDevice = docSnap.getString("originDeviceId") ?: ""
                val updatedAt = docSnap.getLong("updatedAt") ?: 0L

                if (originDevice == deviceKey && updatedAt <= lastSyncedSettingsTimestamp) return@launch

                lastSyncedSettingsTimestamp = updatedAt

                applyPrefMap(context, "app_prefs", docSnap.get("app_prefs") as? Map<*, *>)
                applyPrefMap(context, "app_settings", docSnap.get("app_settings") as? Map<*, *>)
                applyPrefMap(context, "countdown_settings_prefs", docSnap.get("countdown_settings_prefs") as? Map<*, *>)
                applyPrefMap(context, "strict_mode_prefs", docSnap.get("strict_mode_prefs") as? Map<*, *>)
                applyPrefMap(context, "app_calendar_prefs", docSnap.get("app_calendar_prefs") as? Map<*, *>)

                withContext(Dispatchers.Main) {
                    onComplete?.invoke()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error pulling settings from cloud", e)
            }
        }
    }

    // =========================================================================
    // RECONCILIATION & DEDUPLICATION LOGIC
    // =========================================================================

    private suspend fun reconcileTasks(database: AppDatabase, snapshot: DataSnapshot, myDeviceId: String) {
        try {
            val taskDao = database.taskDao()
            val localTasks = taskDao.getAllTasksDirect()

            val remoteMap = mutableMapOf<String, TaskSnapshotItem>()
            if (snapshot.exists()) {
                for (child in snapshot.children) {
                    val title = child.child("title").getValue(String::class.java) ?: continue
                    val dueDateString = child.child("dueDateString").getValue(String::class.java) ?: ""
                    val listCategory = child.child("listCategory").getValue(String::class.java) ?: "Inbox"
                    val isCompleted = child.child("isCompleted").getValue(Boolean::class.java) ?: false
                    val description = child.child("description").getValue(String::class.java) ?: ""
                    val priority = child.child("priority").getValue(String::class.java) ?: "MEDIUM"
                    val estimatedMinutes = child.child("estimatedMinutes").getValue(Int::class.java) ?: 30
                    val actualMinutes = child.child("actualMinutes").getValue(Int::class.java) ?: 0

                    val sig = createTaskSig(title, dueDateString, listCategory)
                    remoteMap[sig] = TaskSnapshotItem(
                        title = title,
                        description = description,
                        dueDateString = dueDateString,
                        listCategory = listCategory,
                        isCompleted = isCompleted,
                        priority = priority,
                        estimatedMinutes = estimatedMinutes,
                        actualMinutes = actualMinutes
                    )
                }
            }

            // Deduplicate local DB tasks
            val localMap = mutableMapOf<String, Task>()
            val duplicatesToDelete = mutableListOf<Task>()

            for (local in localTasks) {
                val sig = createTaskSig(local.title, local.dueDateString, local.listCategory)
                if (localMap.containsKey(sig)) {
                    duplicatesToDelete.add(local)
                } else {
                    localMap[sig] = local
                }
            }

            for (dup in duplicatesToDelete) {
                taskDao.deleteTask(dup)
            }

            for ((sig, remote) in remoteMap) {
                val existing = localMap[sig]
                if (existing == null) {
                    val newTask = Task(
                        title = remote.title,
                        description = remote.description,
                        dueDateString = remote.dueDateString,
                        listCategory = remote.listCategory,
                        isCompleted = remote.isCompleted,
                        priority = remote.priority,
                        estimatedMinutes = remote.estimatedMinutes,
                        actualMinutes = remote.actualMinutes
                    )
                    taskDao.insertTask(newTask)
                } else {
                    if (existing.isCompleted != remote.isCompleted ||
                        existing.description != remote.description ||
                        existing.priority != remote.priority
                    ) {
                        val updated = existing.copy(
                            isCompleted = remote.isCompleted,
                            description = remote.description,
                            priority = remote.priority
                        )
                        taskDao.updateTask(updated)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reconciling Tasks", e)
        }
    }

    private suspend fun reconcileJournal(database: AppDatabase, snapshot: DataSnapshot, myDeviceId: String) {
        try {
            val journalDao = database.journalDao()
            val localEntries = journalDao.getAllJournalEntriesDirect()

            val remoteMap = mutableMapOf<String, JournalSnapshotItem>()
            if (snapshot.exists()) {
                for (child in snapshot.children) {
                    val title = child.child("title").getValue(String::class.java) ?: ""
                    val text = child.child("text").getValue(String::class.java) ?: ""
                    val dateString = child.child("dateString").getValue(String::class.java) ?: ""
                    val timestamp = child.child("timestamp").getValue(Long::class.java) ?: System.currentTimeMillis()
                    val attachmentsJson = child.child("attachmentsJson").getValue(String::class.java) ?: ""

                    val sig = createJournalSig(title, text, dateString)
                    remoteMap[sig] = JournalSnapshotItem(
                        title = title,
                        text = text,
                        dateString = dateString,
                        timestamp = timestamp,
                        attachmentsJson = attachmentsJson
                    )
                }
            }

            val localMap = mutableMapOf<String, JournalEntry>()
            val duplicatesToDelete = mutableListOf<JournalEntry>()

            for (local in localEntries) {
                val sig = createJournalSig(local.title, local.text, local.dateString)
                if (localMap.containsKey(sig)) {
                    duplicatesToDelete.add(local)
                } else {
                    localMap[sig] = local
                }
            }

            for (dup in duplicatesToDelete) {
                journalDao.deleteJournalEntry(dup)
            }

            for ((sig, remote) in remoteMap) {
                if (!localMap.containsKey(sig)) {
                    val newEntry = JournalEntry(
                        title = remote.title,
                        text = remote.text,
                        dateString = remote.dateString,
                        timestamp = remote.timestamp,
                        attachmentsJson = remote.attachmentsJson
                    )
                    journalDao.insertJournalEntry(newEntry)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reconciling Journal", e)
        }
    }

    private suspend fun reconcileFiles(database: AppDatabase, snapshot: DataSnapshot, myDeviceId: String) {
        try {
            val fileDao = database.appFileDao()
            val localFiles = fileDao.getAllFilesDirect()

            val remoteMap = mutableMapOf<String, FileSnapshotItem>()
            if (snapshot.exists()) {
                for (child in snapshot.children) {
                    val name = child.child("name").getValue(String::class.java) ?: continue
                    val path = child.child("path").getValue(String::class.java) ?: ""
                    val size = child.child("size").getValue(Long::class.java) ?: 0L
                    val mimeType = child.child("mimeType").getValue(String::class.java) ?: "*/*"
                    val uriString = child.child("uriString").getValue(String::class.java) ?: ""
                    val isFavorite = child.child("isFavorite").getValue(Boolean::class.java) ?: false
                    val timestamp = child.child("timestamp").getValue(Long::class.java) ?: System.currentTimeMillis()

                    val sig = createFileSig(name, path)
                    remoteMap[sig] = FileSnapshotItem(
                        name = name,
                        path = path,
                        size = size,
                        mimeType = mimeType,
                        uriString = uriString,
                        isFavorite = isFavorite,
                        timestamp = timestamp
                    )
                }
            }

            val localMap = mutableMapOf<String, AppFile>()
            val duplicatesToDelete = mutableListOf<AppFile>()

            for (local in localFiles) {
                val sig = createFileSig(local.name, local.path)
                if (localMap.containsKey(sig)) {
                    duplicatesToDelete.add(local)
                } else {
                    localMap[sig] = local
                }
            }

            for (dup in duplicatesToDelete) {
                fileDao.deleteFile(dup)
            }

            for ((sig, remote) in remoteMap) {
                if (!localMap.containsKey(sig)) {
                    val newFile = AppFile(
                        name = remote.name,
                        path = remote.path,
                        size = remote.size,
                        mimeType = remote.mimeType,
                        uriString = remote.uriString,
                        timestamp = remote.timestamp,
                        isFavorite = remote.isFavorite
                    )
                    fileDao.insertFile(newFile)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reconciling Files", e)
        }
    }

    private suspend fun reconcileFinance(database: AppDatabase, snapshot: DataSnapshot, myDeviceId: String) {
        try {
            val financeDao = database.financeTransactionDao()
            val localTransactions = financeDao.getAllTransactionsDirect()

            val remoteMap = mutableMapOf<String, FinanceSnapshotItem>()
            if (snapshot.exists()) {
                for (child in snapshot.children) {
                    val type = child.child("type").getValue(String::class.java) ?: "EXPENSE"
                    val amount = child.child("amount").getValue(Double::class.java) ?: 0.0
                    val note = child.child("note").getValue(String::class.java) ?: ""
                    val timestamp = child.child("timestamp").getValue(Long::class.java) ?: System.currentTimeMillis()
                    val fromCategory = child.child("fromCategory").getValue(String::class.java)
                    val toCategory = child.child("toCategory").getValue(String::class.java)

                    val sig = createFinanceSig(type, amount, note, timestamp)
                    remoteMap[sig] = FinanceSnapshotItem(
                        type = type,
                        amount = amount,
                        note = note,
                        timestamp = timestamp,
                        fromCategory = fromCategory,
                        toCategory = toCategory
                    )
                }
            }

            val localMap = mutableMapOf<String, FinanceTransaction>()
            val duplicatesToDelete = mutableListOf<FinanceTransaction>()

            for (local in localTransactions) {
                val sig = createFinanceSig(local.type, local.amount, local.note, local.timestamp)
                if (localMap.containsKey(sig)) {
                    duplicatesToDelete.add(local)
                } else {
                    localMap[sig] = local
                }
            }

            for (dup in duplicatesToDelete) {
                financeDao.deleteTransaction(dup)
            }

            for ((sig, remote) in remoteMap) {
                if (!localMap.containsKey(sig)) {
                    val newTransaction = FinanceTransaction(
                        memberId = 0,
                        type = remote.type,
                        amount = remote.amount,
                        note = remote.note,
                        timestamp = remote.timestamp,
                        fromCategory = remote.fromCategory,
                        toCategory = remote.toCategory
                    )
                    financeDao.insertTransaction(newTransaction)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reconciling Finance", e)
        }
    }

    private suspend fun reconcileKeepNotes(database: AppDatabase, snapshot: DataSnapshot, myDeviceId: String) {
        try {
            val keepNoteDao = database.keepNoteDao()
            val localNotes = keepNoteDao.getAllKeepNotesDirect()

            val remoteNotesMap = mutableMapOf<String, RemoteNoteItem>()
            if (snapshot.exists()) {
                for (child in snapshot.children) {
                    val key = child.key ?: continue
                    val title = child.child("title").getValue(String::class.java) ?: ""
                    val content = child.child("content").getValue(String::class.java) ?: ""
                    val timestamp = child.child("timestamp").getValue(Long::class.java) ?: System.currentTimeMillis()
                    val isPinned = child.child("isPinned").getValue(Boolean::class.java) ?: false
                    val colorHex = child.child("colorHex").getValue(String::class.java) ?: "#202124"
                    val websiteUrl = child.child("websiteUrl").getValue(String::class.java)
                    val customLogoUrl = child.child("customLogoUrl").getValue(String::class.java)
                    val isDeleted = child.child("isDeleted").getValue(Boolean::class.java) ?: false

                    val signature = createNoteSignature(title, content)
                    remoteNotesMap[signature] = RemoteNoteItem(
                        key = key,
                        title = title,
                        content = content,
                        timestamp = timestamp,
                        isPinned = isPinned,
                        colorHex = colorHex,
                        websiteUrl = websiteUrl?.takeIf { it.isNotBlank() },
                        customLogoUrl = customLogoUrl?.takeIf { it.isNotBlank() },
                        isDeleted = isDeleted
                    )
                }
            }

            val localNotesMapBySig = mutableMapOf<String, KeepNote>()
            val duplicatesToDelete = mutableListOf<KeepNote>()

            for (local in localNotes) {
                val sig = createNoteSignature(local.title, local.content)
                if (localNotesMapBySig.containsKey(sig)) {
                    duplicatesToDelete.add(local)
                } else {
                    localNotesMapBySig[sig] = local
                }
            }

            for (dup in duplicatesToDelete) {
                keepNoteDao.deleteKeepNote(dup)
            }

            for ((sig, remote) in remoteNotesMap) {
                if (remote.isDeleted) {
                    localNotesMapBySig[sig]?.let { localNote ->
                        keepNoteDao.deleteKeepNote(localNote)
                    }
                    continue
                }

                val existing = localNotesMapBySig[sig]
                if (existing == null) {
                    val newNote = KeepNote(
                        title = remote.title,
                        content = remote.content,
                        timestamp = remote.timestamp,
                        isPinned = remote.isPinned,
                        colorHex = remote.colorHex,
                        isSynced = true,
                        websiteUrl = remote.websiteUrl,
                        customLogoUrl = remote.customLogoUrl
                    )
                    keepNoteDao.insertKeepNote(newNote)
                } else {
                    if (existing.isPinned != remote.isPinned ||
                        existing.colorHex != remote.colorHex ||
                        existing.websiteUrl != remote.websiteUrl ||
                        existing.customLogoUrl != remote.customLogoUrl ||
                        !existing.isSynced
                    ) {
                        val updated = existing.copy(
                            isPinned = remote.isPinned,
                            colorHex = remote.colorHex,
                            isSynced = true,
                            websiteUrl = remote.websiteUrl,
                            customLogoUrl = remote.customLogoUrl
                        )
                        keepNoteDao.updateKeepNote(updated)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reconciling Keep Notes", e)
        }
    }

    // Helper Signatures & Keys
    private fun createTaskSig(title: String, dueDateString: String, listCategory: String): String {
        return "${title.trim().lowercase()}|${dueDateString.trim()}|${listCategory.trim().lowercase()}"
    }

    private fun createTaskKey(task: Task): String {
        val hash = abs(createTaskSig(task.title, task.dueDateString, task.listCategory).hashCode())
        return "task_${hash}"
    }

    private fun createJournalSig(title: String, text: String, dateString: String): String {
        return "${dateString.trim()}|${title.trim().lowercase()}|${text.trim().lowercase()}"
    }

    private fun createJournalKey(entry: JournalEntry): String {
        val hash = abs(createJournalSig(entry.title, entry.text, entry.dateString).hashCode())
        return "journal_${entry.timestamp}_${hash}"
    }

    private fun createFileSig(name: String, path: String): String {
        return "${path.trim().lowercase()}/${name.trim().lowercase()}"
    }

    private fun createFileKey(file: AppFile): String {
        val hash = abs(createFileSig(file.name, file.path).hashCode())
        return "file_${hash}"
    }

    private fun createFinanceSig(type: String, amount: Double, note: String, timestamp: Long): String {
        return "${type.trim()}|$amount|${note.trim().lowercase()}|$timestamp"
    }

    private fun createFinanceKey(transaction: FinanceTransaction): String {
        val hash = abs(createFinanceSig(transaction.type, transaction.amount, transaction.note, transaction.timestamp).hashCode())
        return "finance_${transaction.timestamp}_${hash}"
    }

    fun createNoteSignature(title: String, content: String): String {
        return "${title.trim()}|${content.trim()}"
    }

    fun generateNoteKey(note: KeepNote): String {
        val sig = createNoteSignature(note.title, note.content)
        val hash = abs(sig.hashCode())
        return "note_${note.timestamp}_${hash}"
    }

    private fun updateSyncState(state: SyncState, message: String) {
        _syncInfoState.value = _syncInfoState.value.copy(
            syncState = state,
            lastSyncMessage = message
        )
    }

    private fun serializePrefMap(map: Map<String, *>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for ((k, v) in map) {
            if (v != null) {
                result[k] = v.toString()
            }
        }
        return result
    }

    private fun applyPrefMap(context: Context, prefName: String, dataMap: Map<*, *>?) {
        if (dataMap == null) return
        val prefs = context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
        val editor = prefs.edit()

        for ((k, v) in dataMap) {
            val key = k.toString()
            val strVal = v.toString()

            if (strVal == "true" || strVal == "false") {
                editor.putBoolean(key, strVal.toBoolean())
            } else if (strVal.toLongOrNull() != null) {
                editor.putLong(key, strVal.toLong())
            } else if (strVal.toIntOrNull() != null) {
                editor.putInt(key, strVal.toInt())
            } else if (strVal.toFloatOrNull() != null) {
                editor.putFloat(key, strVal.toFloat())
            } else {
                editor.putString(key, strVal)
            }
        }
        editor.apply()
    }

    private data class TaskSnapshotItem(
        val title: String,
        val description: String,
        val dueDateString: String,
        val listCategory: String,
        val isCompleted: Boolean,
        val priority: String,
        val estimatedMinutes: Int,
        val actualMinutes: Int
    )

    private data class JournalSnapshotItem(
        val title: String,
        val text: String,
        val dateString: String,
        val timestamp: Long,
        val attachmentsJson: String
    )

    private data class FileSnapshotItem(
        val name: String,
        val path: String,
        val size: Long,
        val mimeType: String,
        val uriString: String,
        val isFavorite: Boolean,
        val timestamp: Long
    )

    private data class FinanceSnapshotItem(
        val type: String,
        val amount: Double,
        val note: String,
        val timestamp: Long,
        val fromCategory: String?,
        val toCategory: String?
    )

    private data class RemoteNoteItem(
        val key: String,
        val title: String,
        val content: String,
        val timestamp: Long,
        val isPinned: Boolean,
        val colorHex: String,
        val websiteUrl: String?,
        val customLogoUrl: String?,
        val isDeleted: Boolean
    )
}
