package com.example.api

import android.content.Context
import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object PeerFocusFcmNotifier {
    private const val TAG = "PeerFocusFcmNotifier"

    /**
     * Sends custom data FCM signal payload for live multi-device synchronization.
     */
    fun sendCustomDataSignal(context: Context, email: String, fcmData: Map<String, String>) {
        if (email.isBlank()) return
        try {
            val database = com.google.firebase.database.FirebaseDatabase.getInstance()
            val sanitizedEmail = DevicePresenceManager.sanitizeEmail(email)
            val nowMs = System.currentTimeMillis()
            val queueRef = database.getReference("FOCUS_TIMMER").child("FCM_QUEUE")
            val pushKey = queueRef.push().key ?: nowMs.toString()
            queueRef.child(pushKey).setValue(fcmData)

            val inboxRef = database.getReference("FOCUS_TIMMER")
                .child("USER")
                .child(sanitizedEmail)
                .child("FCM_INBOX")
            inboxRef.child(pushKey).setValue(fcmData)
            Log.d(TAG, "Queued custom FCM data signal to user $sanitizedEmail")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending custom FCM data signal", e)
        }
    }

    /**
     * Subscribes the current device to FCM topics for target push notifications:
     * 1) Personal user topic: "user_{sanitizedEmail}"
     * 2) General study group topic: "peer_focus_alerts"
     */
    fun subscribeToTopics(context: Context, email: String) {
        if (email.isBlank() || !com.example.util.GmsUtils.isGmsAvailable(context)) return
        try {
            val sanitized = DevicePresenceManager.sanitizeEmail(email)
            val userTopic = "user_$sanitized"

            FirebaseMessaging.getInstance().subscribeToTopic(userTopic)
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        Log.d(TAG, "Successfully subscribed to FCM personal topic: $userTopic")
                    } else {
                        Log.w(TAG, "FCM subscription optional or disabled for $userTopic")
                    }
                }

            FirebaseMessaging.getInstance().subscribeToTopic("peer_focus_alerts")
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        Log.d(TAG, "Successfully subscribed to FCM general peer_focus_alerts topic")
                    } else {
                        Log.w(TAG, "FCM subscription optional or disabled for peer_focus_alerts")
                    }
                }
        } catch (e: Throwable) {
            Log.w(TAG, "FCM topic subscription unavailable: ${e.message}")
        }
    }

    /**
     * Broadcasts an FCM focus start payload to friends and study group members
     * via Firebase Realtime Database FCM_INBOX and FCM_QUEUE nodes, enabling background FCM push delivery.
     */
    fun notifyFriendsOnFocusStart(
        context: Context,
        email: String,
        taskName: String,
        tagName: String
    ) {
        if (email.isBlank()) return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dbUrl = FirebaseConfig.getDatabaseUrl(context)
                if (dbUrl.isEmpty()) return@launch

                val database = FirebaseDatabase.getInstance(dbUrl)
                val sanitizedMyEmail = DevicePresenceManager.sanitizeEmail(email)

                // Get User Display Name
                val appPrefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                val currentUsername = appPrefs.getString("current_username", "Guest") ?: "Guest"
                val cachedNickname = appPrefs.getString("user_nickname_$currentUsername", "") ?: ""
                val cachedName = appPrefs.getString("user_name_$currentUsername", "") ?: ""
                val displayName = if (cachedNickname.isNotEmpty()) cachedNickname else if (cachedName.isNotEmpty()) cachedName else currentUsername

                val cleanTask = if (taskName.isNotBlank()) taskName else "Focus Session"
                val cleanTag = if (tagName.isNotBlank()) tagName else "Study"

                // Collect target recipients: room participants + friends list
                val targetRecipients = mutableSetOf<String>()

                // Add room participants if in a study room
                val roomState = FocusLockerManager.uiState.value
                roomState.participants.forEach { part ->
                    val partEmail = part.email.lowercase().trim()
                    if (partEmail.isNotBlank() && !partEmail.equals(email.lowercase().trim(), ignoreCase = true)) {
                        targetRecipients.add(partEmail)
                    }
                }

                // Add regular friends from RTDB
                val friendsRef = database.getReference("FOCUS_TIMMER")
                    .child("USER")
                    .child(sanitizedMyEmail)
                    .child("FRIENDS_LIST")

                friendsRef.addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        if (snapshot.exists()) {
                            for (child in snapshot.children) {
                                val key = child.key ?: continue
                                val valueStr = child.getValue(String::class.java)
                                val friendId = if (valueStr != null && valueStr.contains("@")) {
                                    valueStr.lowercase().trim()
                                } else {
                                    key.lowercase().trim()
                                }
                                if (friendId.isNotBlank() && !friendId.equals(email.lowercase().trim(), ignoreCase = true)) {
                                    targetRecipients.add(friendId)
                                }
                            }
                        }

                        dispatchStartPayloadToRecipients(
                            database,
                            email,
                            displayName,
                            cleanTask,
                            cleanTag,
                            targetRecipients.toList()
                        )
                    }

                    override fun onCancelled(error: DatabaseError) {
                        dispatchStartPayloadToRecipients(
                            database,
                            email,
                            displayName,
                            cleanTask,
                            cleanTag,
                            targetRecipients.toList()
                        )
                    }
                })

            } catch (e: Exception) {
                Log.e(TAG, "Error in notifyFriendsOnFocusStart", e)
            }
        }
    }

    private fun dispatchStartPayloadToRecipients(
        database: FirebaseDatabase,
        myEmail: String,
        displayName: String,
        taskName: String,
        tagName: String,
        recipients: List<String>
    ) {
        val nowMs = System.currentTimeMillis()

        // 1. Write to centralized FCM_QUEUE for backend/Cloud Functions dispatch
        val queueRef = database.getReference("FOCUS_TIMMER").child("FCM_QUEUE")
        val queuePushKey = queueRef.push().key ?: nowMs.toString()
        val queuePayload = mapOf(
            "type" to "PEER_FOCUS_START",
            "sender_id" to myEmail,
            "peer_name" to displayName,
            "task_name" to taskName,
            "tag_name" to tagName,
            "recipients" to recipients,
            "title" to "$displayName started focusing! 🎯",
            "body" to "$displayName is focusing on \"$taskName\" ($tagName). Join them now!",
            "timestamp" to nowMs
        )
        queueRef.child(queuePushKey).setValue(queuePayload)

        // 2. Dispatch to each target recipient's personal FCM inbox node
        for (friendEmail in recipients) {
            val friendSanitized = DevicePresenceManager.sanitizeEmail(friendEmail)
            val inboxRef = database.getReference("FOCUS_TIMMER")
                .child("USER")
                .child(friendSanitized)
                .child("FCM_INBOX")

            val pushKey = inboxRef.push().key ?: nowMs.toString()
            val inboxPayload = mapOf(
                "type" to "PEER_FOCUS_START",
                "sender_id" to myEmail,
                "peer_name" to displayName,
                "task_name" to taskName,
                "tag_name" to tagName,
                "title" to "$displayName started focusing! 🎯",
                "body" to "$displayName is focusing on \"$taskName\" ($tagName). Join them now!",
                "timestamp" to nowMs
            )
            inboxRef.child(pushKey).setValue(inboxPayload)
        }

        Log.d(TAG, "Dispatched PEER_FOCUS_START FCM notification payload for $myEmail ($displayName) to ${recipients.size} recipients.")
    }

    /**
     * Broadcasts an FCM focus stop payload to friends and study group members
     * to automatically dismiss the focus notification on their devices.
     */
    fun notifyFriendsOnFocusStop(context: Context, email: String) {
        if (email.isBlank()) return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dbUrl = FirebaseConfig.getDatabaseUrl(context)
                if (dbUrl.isEmpty()) return@launch

                val database = FirebaseDatabase.getInstance(dbUrl)
                val nowMs = System.currentTimeMillis()

                // Queue stop signal in FCM_QUEUE
                val queueRef = database.getReference("FOCUS_TIMMER").child("FCM_QUEUE")
                val pushKey = queueRef.push().key ?: nowMs.toString()
                val queuePayload = mapOf(
                    "type" to "PEER_FOCUS_STOP",
                    "sender_id" to email,
                    "timestamp" to nowMs
                )
                queueRef.child(pushKey).setValue(queuePayload)

                Log.d(TAG, "Dispatched PEER_FOCUS_STOP FCM notification payload for $email.")
            } catch (e: Exception) {
                Log.e(TAG, "Error in notifyFriendsOnFocusStop", e)
            }
        }
    }
}
