package com.example.api

import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.data.AppDatabase
import com.example.data.LocalHistoryVault
import com.example.util.FocusTimerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * LiveFcmSyncManager
 *
 * Special FCM Sync Manager for multi-device live synchronization.
 * When a user has multiple online devices active simultaneously, this manager ensures
 * 100% real-time state synchronization via single or chunked FCM messages (respecting the 4KB FCM payload limit).
 *
 * Includes built-in Deduplication / Anti-Double-Entry tracking so that FCM live deltas and Google Drive
 * backups do not create duplicate records in the database.
 */
object LiveFcmSyncManager {

    private const val TAG = "LiveFcmSyncManager"
    private const val FCM_CHUNK_SAFE_LIMIT_BYTES = 3400 // Safe limit below FCM 4096-byte limit

    // Deduplication set to store processed transaction/record UUIDs and prevent double-entry
    private val processedTransactions: MutableSet<String> = Collections.synchronizedSet(HashSet<String>())
    private val transactionTimestamps = ConcurrentHashMap<String, Long>()

    // Chunk Reassembly Cache: transactionId -> map of (chunkIndex -> chunkData)
    private val chunkAssemblyMap = ConcurrentHashMap<String, ConcurrentHashMap<Int, String>>()
    private val chunkMetaMap = ConcurrentHashMap<String, ChunkMetadata>()

    private data class ChunkMetadata(
        val totalChunks: Int,
        val payloadType: String,
        val createdAt: Long
    )

    /**
     * Checks if a transaction / record UUID has already been applied locally.
     */
    fun isTransactionAlreadyProcessed(id: String?): Boolean {
        if (id.isNullOrBlank()) return false
        cleanExpiredTransactions()
        return processedTransactions.contains(id)
    }

    /**
     * Marks a transaction or record UUID as processed to prevent double entry.
     */
    fun markTransactionProcessed(id: String?) {
        if (id.isNullOrBlank()) return
        processedTransactions.add(id)
        transactionTimestamps[id] = System.currentTimeMillis()
    }

    private fun cleanExpiredTransactions() {
        val now = System.currentTimeMillis()
        val ttlMs = 24 * 3600 * 1000L // 24 hours TTL
        val iterator = transactionTimestamps.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value > ttlMs) {
                processedTransactions.remove(entry.key)
                iterator.remove()
            }
        }
    }

    /**
     * Broadcasts a live delta sync payload to user's other active devices over FCM.
     * Handles payload size check and automatically splits into chunks if payload exceeds FCM limits.
     */
    fun broadcastLiveDeltaSync(
        context: Context,
        payloadType: String,
        payloadData: JSONObject,
        recordUuid: String = UUID.randomUUID().toString()
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Register transaction locally so this device won't process its own echo
                markTransactionProcessed(recordUuid)

                val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                val userEmail = prefs.getString("user_email", "") ?: ""
                if (userEmail.isBlank()) return@launch

                val jsonString = payloadData.toString()
                val rawBytes = jsonString.toByteArray(Charsets.UTF_8)

                if (rawBytes.size <= FCM_CHUNK_SAFE_LIMIT_BYTES) {
                    // Send single FCM message
                    sendSingleFcmSyncPacket(context, userEmail, payloadType, recordUuid, jsonString)
                } else {
                    // Split into multiple chunked FCM messages
                    val base64Encoded = Base64.encodeToString(rawBytes, Base64.NO_WRAP)
                    val chunkSize = 2500 // Chunk string size safe for FCM
                    val totalChunks = (base64Encoded.length + chunkSize - 1) / chunkSize

                    Log.i(TAG, "Payload size (${rawBytes.size} bytes) exceeds $FCM_CHUNK_SAFE_LIMIT_BYTES bytes. Splitting into $totalChunks FCM chunks.")

                    for (i in 0 until totalChunks) {
                        val startIdx = i * chunkSize
                        val endIdx = minOf(base64Encoded.length, startIdx + chunkSize)
                        val chunkStr = base64Encoded.substring(startIdx, endIdx)

                        sendChunkedFcmSyncPacket(
                            context = context,
                            userEmail = userEmail,
                            payloadType = payloadType,
                            transactionId = recordUuid,
                            chunkIndex = i,
                            totalChunks = totalChunks,
                            chunkData = chunkStr
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error broadcasting live FCM delta sync", e)
            }
        }
    }

    private fun sendSingleFcmSyncPacket(
        context: Context,
        userEmail: String,
        payloadType: String,
        transactionId: String,
        payloadJson: String
    ) {
        val fcmData = mapOf(
            "type" to "LIVE_DELTA_SYNC",
            "payload_type" to payloadType,
            "transaction_id" to transactionId,
            "chunk_index" to "0",
            "total_chunks" to "1",
            "payload_data" to payloadJson,
            "sender_email" to userEmail,
            "timestamp" to System.currentTimeMillis().toString()
        )

        // Dispatch via PeerFocusFcmNotifier / Firebase signaling engine
        PeerFocusFcmNotifier.sendCustomDataSignal(context, userEmail, fcmData)
        Log.d(TAG, "Dispatched single FCM Live Sync payload [$payloadType] (tx: $transactionId)")
    }

    private fun sendChunkedFcmSyncPacket(
        context: Context,
        userEmail: String,
        payloadType: String,
        transactionId: String,
        chunkIndex: Int,
        totalChunks: Int,
        chunkData: String
    ) {
        val fcmData = mapOf(
            "type" to "LIVE_DELTA_SYNC_CHUNK",
            "payload_type" to payloadType,
            "transaction_id" to transactionId,
            "chunk_index" to chunkIndex.toString(),
            "total_chunks" to totalChunks.toString(),
            "chunk_data" to chunkData,
            "sender_email" to userEmail,
            "timestamp" to System.currentTimeMillis().toString()
        )

        PeerFocusFcmNotifier.sendCustomDataSignal(context, userEmail, fcmData)
        Log.d(TAG, "Dispatched FCM Chunk $chunkIndex/$totalChunks for tx: $transactionId")
    }

    /**
     * Processes incoming FCM Live Sync signals (both single and chunked payloads).
     */
    fun handleIncomingFcmSyncPayload(context: Context, data: Map<String, String>) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val transactionId = data["transaction_id"] ?: return@launch
                val senderEmail = data["sender_email"] ?: ""

                // Anti-Double-Entry Check
                if (isTransactionAlreadyProcessed(transactionId)) {
                    Log.d(TAG, "Transaction $transactionId already processed locally. Skipping to prevent double entry.")
                    return@launch
                }

                val type = data["type"] ?: ""

                if (type == "LIVE_DELTA_SYNC") {
                    val payloadType = data["payload_type"] ?: ""
                    val payloadDataStr = data["payload_data"] ?: "{}"
                    val json = JSONObject(payloadDataStr)

                    markTransactionProcessed(transactionId)
                    applyDeltaToLocalState(context, payloadType, json, transactionId)

                } else if (type == "LIVE_DELTA_SYNC_CHUNK") {
                    val payloadType = data["payload_type"] ?: ""
                    val chunkIndex = data["chunk_index"]?.toIntOrNull() ?: 0
                    val totalChunks = data["total_chunks"]?.toIntOrNull() ?: 1
                    val chunkData = data["chunk_data"] ?: ""

                    // Reassemble chunks
                    val chunksMap = chunkAssemblyMap.computeIfAbsent(transactionId) { ConcurrentHashMap() }
                    chunksMap[chunkIndex] = chunkData
                    chunkMetaMap[transactionId] = ChunkMetadata(totalChunks, payloadType, System.currentTimeMillis())

                    if (chunksMap.size == totalChunks) {
                        Log.i(TAG, "All $totalChunks chunks received for tx: $transactionId. Reassembling payload...")

                        val sb = StringBuilder()
                        for (i in 0 until totalChunks) {
                            sb.append(chunksMap[i] ?: "")
                        }

                        val base64Str = sb.toString()
                        val rawBytes = Base64.decode(base64Str, Base64.NO_WRAP)
                        val jsonString = String(rawBytes, Charsets.UTF_8)
                        val payloadJson = JSONObject(jsonString)

                        // Cleanup assembly maps
                        chunkAssemblyMap.remove(transactionId)
                        chunkMetaMap.remove(transactionId)

                        markTransactionProcessed(transactionId)
                        applyDeltaToLocalState(context, payloadType, payloadJson, transactionId)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling incoming FCM live sync payload", e)
            }
        }
    }

    /**
     * Applies delta JSON directly to Room Database and local application states.
     */
    private suspend fun applyDeltaToLocalState(
        context: Context,
        payloadType: String,
        json: JSONObject,
        transactionId: String
    ) {
        val database = AppDatabase.getInstance(context)

        when (payloadType) {
            "FOCUS_SESSION_COMPLETED" -> {
                val recordUuid = json.optString("record_uuid", transactionId)
                val subject = json.optString("subject", "Focus Session")
                val focusMins = json.optInt("focus_minutes", 0)
                val focusSecs = json.optInt("focus_seconds", focusMins * 60)
                val dateStr = json.optString("date_string", com.example.util.SystemTimeService.getTodayString())
                val startMs = json.optLong("start_time_ms", System.currentTimeMillis() - (focusSecs * 1000L))
                val endMs = json.optLong("end_time_ms", System.currentTimeMillis())

                if (!isTransactionAlreadyProcessed(recordUuid)) {
                    markTransactionProcessed(recordUuid)
                    val sdf = java.text.SimpleDateFormat("hh:mm a", java.util.Locale.US)
                    database.localHistoryVaultDao().insertRecord(
                        LocalHistoryVault(
                            record_id = recordUuid,
                            date_string = dateStr,
                            subject = subject,
                            task_title = json.optString("task_title", subject),
                            start_time_ms = startMs,
                            end_time_ms = endMs,
                            total_focus_ms = focusSecs * 1000L,
                            duration_formatted = "${focusMins}m",
                            start_time_formatted = sdf.format(java.util.Date(startMs)),
                            end_time_formatted = sdf.format(java.util.Date(endMs)),
                            is_synced_to_firestore = 1
                        )
                    )
                    FocusTimerManager.init(context)
                    FocusTimerManager.reloadFocusRecordsFromDb(context)
                    Log.i(TAG, "Applied FCM live delta: FOCUS_SESSION_COMPLETED ($focusMins mins) for date $dateStr")
                }
            }

            "TIMER_STATE_UPDATE" -> {
                val isRunning = json.optBoolean("is_running", false)
                val secondsLeft = json.optInt("seconds_left", 1500)
                val isFocusPhase = json.optBoolean("is_focus_phase", true)

                FocusTimerManager.init(context)
                if (isRunning) {
                    FocusTimerManager.setTimerSecondsLeft(secondsLeft)
                }
                Log.i(TAG, "Applied FCM live delta: TIMER_STATE_UPDATE (Running=$isRunning, Left=$secondsLeft)")
            }

            "TASK_STATE_CHANGE" -> {
                val taskIdStr = json.optString("task_id", "")
                val completed = json.optBoolean("completed", false)
                val taskIdInt = taskIdStr.toIntOrNull()
                if (taskIdInt != null) {
                    try {
                        val task = database.taskDao().getTaskById(taskIdInt)
                        if (task != null) {
                            database.taskDao().updateTask(task.copy(isCompleted = completed))
                            Log.i(TAG, "Applied FCM live delta: TASK_STATE_CHANGE (TaskId=$taskIdInt, Completed=$completed)")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Could not update task item for ID $taskIdInt", e)
                    }
                }
            }

            else -> {
                Log.d(TAG, "Received custom FCM live sync delta [$payloadType]: $json")
            }
        }
    }
}
