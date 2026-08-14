package com.example.api

import android.content.Context
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.example.util.TimeEngine
import com.example.util.childMs
import com.example.util.parseToMs
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap

object PeerLiveSphereManager {
    private const val TAG = "PeerLiveSphereManager"

    private val _peerLiveStates = MutableStateFlow<Map<String, PeerLiveState>>(emptyMap())
    val peerLiveStates: StateFlow<Map<String, PeerLiveState>> = _peerLiveStates.asStateFlow()

    private val activeListeners = ConcurrentHashMap<String, Pair<DatabaseReference, ValueEventListener>>()
    private val lastNotifiedSessionStart = ConcurrentHashMap<String, Long>()

    private var friendsListRef: DatabaseReference? = null
    private var friendsListListener: ValueEventListener? = null

    private var fcmInboxRef: DatabaseReference? = null
    private var fcmInboxListener: com.google.firebase.database.ChildEventListener? = null

    private var sharedRoomsRef: DatabaseReference? = null
    private var sharedRoomsListener: ValueEventListener? = null

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
    private var roomCollectJob: kotlinx.coroutines.Job? = null

    private var regularFriends = emptyList<String>()
    private var activeRoomFriends = emptyList<String>()
    private var myUserEmail: String = ""

    private fun isUpdatedToday(lastUpdatedMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (lastUpdatedMs <= 0L) return false

        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val todayStrLocal = sdf.format(Date(nowMs))
        val updatedStrLocal = sdf.format(Date(lastUpdatedMs))
        return todayStrLocal == updatedStrLocal
    }

    private fun updateActiveSphereListenersCombined(
        context: Context,
        database: FirebaseDatabase,
        myEmail: String
    ) {
        // Combined set of all unique friends: regular friends + all participants of joined rooms
        val uniquePeers = mutableSetOf<String>()
        regularFriends.forEach { uniquePeers.add(it.lowercase().trim()) }
        activeRoomFriends.forEach { uniquePeers.add(it.lowercase().trim()) }

        // Make sure the current user is added so "my self" can also be observed via PeerLiveSphere if appropriate,
        // and keeping in sync with user request: "i should see my self and also my friends too from all the rooms i joined"
        if (myEmail.isNotBlank()) {
            uniquePeers.add(myEmail.lowercase().trim())
        }

        val peerList = uniquePeers.toList()
        Log.d(TAG, "Syncing combined Live Sphere peers (friends + rooms + self): $peerList")
        syncFriendListeners(context, database, peerList)
    }

    fun startListeningToFriends(context: Context, myEmail: String) {
        if (myEmail.isBlank()) return
        myUserEmail = myEmail.lowercase().trim()
        cleanUpListeners()

        // Ensure FCM push topics are subscribed for user and study groups
        PeerFocusFcmNotifier.subscribeToTopics(context, myEmail)

        try {
            val dbUrl = FirebaseConfig.getDatabaseUrl(context)
            if (dbUrl.isEmpty()) {
                Log.e(TAG, "Database URL is empty, cannot listen to friends.")
                return
            }
            val database = FirebaseDatabase.getInstance(dbUrl)
            val mySanitized = DevicePresenceManager.sanitizeEmail(myEmail)

            // 1. Listen to the user's regular Friends List
            val fRef = database.getReference("FOCUS_TIMMER")
                .child("USER")
                .child(mySanitized)
                .child("FRIENDS_LIST")

            friendsListRef = fRef

            val friendsListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val newFriends = mutableListOf<String>()
                    if (snapshot.exists()) {
                        for (child in snapshot.children) {
                            val key = child.key ?: continue
                            val valueStr = child.getValue(String::class.java)
                            val friendId = if (valueStr != null && valueStr.contains("@")) {
                                valueStr.lowercase().trim()
                            } else if (key.contains("@") || key.contains("_")) {
                                key.lowercase().trim()
                            } else {
                                key.lowercase().trim()
                            }
                            if (friendId.isNotBlank()) {
                                newFriends.add(friendId)
                            }
                        }
                    }

                    regularFriends = newFriends.map { it.lowercase().trim() }.distinct()
                    Log.d(TAG, "Regular friends list updated: $regularFriends")
                    updateActiveSphereListenersCombined(context, database, myEmail)
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Friends list listener cancelled", error.toException())
                }
            }

            fRef.addValueEventListener(friendsListener)
            friendsListListener = friendsListener

            // 2. Observe the current room participants directly via FocusLockerManager's reactive room state
            // This eliminates downloading the entire SHARED_ROOMS tree, dramatically reducing Firebase data usage.
            roomCollectJob = scope.launch {
                FocusLockerManager.uiState.collect { roomState ->
                    Log.d(TAG, "Room UI state collected in PeerLiveSphereManager: ${roomState.roomId}")
                    val roomFriends = roomState.participants.map { it.email.lowercase().trim() }.distinct()
                    activeRoomFriends = roomFriends
                    Log.d(TAG, "Dynamic room friends updated from local room state: $activeRoomFriends")
                    updateActiveSphereListenersCombined(context, database, myEmail)
                }
            }

            // 3. Listen to FCM_INBOX node for immediate peer focus start/stop notifications
            val inboxRef = database.getReference("FOCUS_TIMMER")
                .child("USER")
                .child(mySanitized)
                .child("FCM_INBOX")
            fcmInboxRef = inboxRef

            val inboxListener = object : com.google.firebase.database.ChildEventListener {
                override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                    try {
                        if (snapshot.exists()) {
                            val type = snapshot.child("type").getValue(String::class.java) ?: ""
                            val senderId = snapshot.child("sender_id").getValue(String::class.java) ?: ""
                            val myEmailNorm = myUserEmail.lowercase().trim()

                            if ((type == "PEER_FOCUS_START" || type == "FRIEND_FOCUS_START" || type == "peer_focus") &&
                                senderId.isNotBlank() && !senderId.equals(myEmailNorm, ignoreCase = true)) {
                                val peerName = snapshot.child("peer_name").getValue(String::class.java) ?: getFallbackDisplayName(senderId)
                                val taskName = snapshot.child("task_name").getValue(String::class.java) ?: "Focus Session"
                                val tagName = snapshot.child("tag_name").getValue(String::class.java) ?: "Study"

                                showPeerFocusNotification(context, senderId, peerName, taskName, tagName)
                            } else if ((type == "PEER_FOCUS_STOP" || type == "FRIEND_FOCUS_STOP") && senderId.isNotBlank()) {
                                cancelPeerFocusNotification(context, senderId)
                            }
                            // Clean up inbox item after processing
                            snapshot.ref.removeValue()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing FCM_INBOX payload in PeerLiveSphereManager", e)
                    }
                }
                override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
                override fun onChildRemoved(snapshot: DataSnapshot) {}
                override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
                override fun onCancelled(error: DatabaseError) {}
            }

            inboxRef.addChildEventListener(inboxListener)
            fcmInboxListener = inboxListener

        } catch (e: Exception) {
            Log.e(TAG, "Error starting listening to friends and rooms", e)
        }
    }

    private fun syncFriendListeners(context: Context, database: FirebaseDatabase, friends: List<String>) {
        val iterator = activeListeners.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val friendId = entry.key
            if (!friends.contains(friendId)) {
                val (ref, listener) = entry.value
                ref.removeEventListener(listener)
                iterator.remove()
                
                val current = _peerLiveStates.value.toMutableMap()
                current.remove(friendId)
                _peerLiveStates.value = current
                Log.d(TAG, "Removed listener and state for: $friendId")
            }
        }

        for (friendId in friends) {
            if (!activeListeners.containsKey(friendId)) {
                try {
                    val friendSanitized = DevicePresenceManager.sanitizeEmail(friendId)
                    val activeRef = database.getReference("FOCUS_TIMMER")
                        .child("USER")
                        .child(friendSanitized)
                        .child("ACTIVE_FOCUS_TIMER")

                    val listener = object : ValueEventListener {
                        override fun onDataChange(snapshot: DataSnapshot) {
                            if (!snapshot.exists()) {
                                updatePeerState(friendId, null)
                                return
                            }

                            val timerSnapshot = snapshot
                            val currentTask = timerSnapshot.child("Current_Task").getValue(String::class.java) ?: "Relaxing"
                            val currentTag = timerSnapshot.child("Current_Tag").getValue(String::class.java) ?: "Study"
                            val timerMode = timerSnapshot.child("Timer_Mode").getValue(String::class.java) ?: "pomodoro"
                            val rawStatus = timerSnapshot.child("Status").getValue(String::class.java) ?: "Relaxing"
                            val nowMs = System.currentTimeMillis()
                            val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(nowMs))

                            val rawLastUpdated = parseToMs(timerSnapshot.child("Last_Updated").value)
                            val lastUpdated = if (rawLastUpdated > 0L) rawLastUpdated else 0L
                            val isTimerStale = lastUpdated > 0L && (nowMs - lastUpdated) > 12 * 60 * 60 * 1000L
                            val status = if (isTimerStale && (rawStatus.equals("Focusing", ignoreCase = true) || rawStatus.equals("Running", ignoreCase = true) || rawStatus.equals("Studying", ignoreCase = true))) "Relaxing" else rawStatus
                            val isTimerUpdatedToday = TimeEngine.isUpdatedToday(rawLastUpdated, nowMs)
                            val timerRawTodayMs = timerSnapshot.childMs("Todays_Focus_Ms", "todayFocusMs")
                            val todayFocusMsFromTimer = if (isTimerUpdatedToday) timerRawTodayMs else 0L

                            val leaderboardPeer = com.example.api.ArenaLeaderboardEngine.leaderboardFlow.value.find { lb ->
                                val email1Norm = lb.email.lowercase().replace(".", "").replace("_", "").trim()
                                val email2Norm = friendId.lowercase().replace(".", "").replace("_", "").trim()
                                email1Norm == email2Norm
                            }

                            val displayName = leaderboardPeer?.displayName?.takeIf { it.isNotBlank() }
                                ?: timerSnapshot.child("User_Display_Name").getValue(String::class.java)
                                ?: timerSnapshot.child("displayName").getValue(String::class.java)
                                ?: timerSnapshot.child("nickname").getValue(String::class.java)
                                ?: getFallbackDisplayName(friendId)

                            val emojiVal = leaderboardPeer?.customEmoji?.takeIf { it.isNotBlank() }
                                ?: timerSnapshot.child("User_Emoji").getValue(String::class.java)
                                ?: timerSnapshot.child("emoji").getValue(String::class.java)
                                ?: ""

                            val timelineList = mutableListOf<TimelineEvent>()
                            val timelineSnapshot = timerSnapshot.child("Timeline")
                            if (timelineSnapshot.exists()) {
                                for (child in timelineSnapshot.children) {
                                    val devId = child.child("deviceId").getValue(String::class.java) ?: ""
                                    val ev = child.child("event").getValue(String::class.java) ?: ""
                                    val ts = child.child("timestamp").getValue(Long::class.java) ?: 0L
                                    timelineList.add(TimelineEvent(devId, ev, ts))
                                }
                            }

                            val baseCompletedTodayMs = todayFocusMsFromTimer

                            val state = PeerLiveState(
                                userId = friendId,
                                displayName = displayName,
                                currentTag = currentTag,
                                currentTask = currentTask,
                                timerMode = timerMode,
                                status = status,
                                timeline = timelineList,
                                lastUpdated = lastUpdated,
                                customEmoji = emojiVal,
                                devices = emptyMap(),
                                todayFocusMs = baseCompletedTodayMs
                            )

                            try {
                                val isFocusingNow = status.equals("Focusing", ignoreCase = true) ||
                                        status.equals("Running", ignoreCase = true) ||
                                        status.equals("Studying", ignoreCase = true)

                                if (friendId.equals(myUserEmail, ignoreCase = true)) {
                                    scope.launch(Dispatchers.IO) {
                                        try {
                                            DevicePresenceManager.adoptHighestTodayFocusMsFromOtherDevices(context, friendId)
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Error auto-adopting higher focus stats in PeerLiveSphereManager", e)
                                        }
                                    }
                                } else {
                                    if (isFocusingNow) {
                                        val isLocalPaused = com.example.util.FocusTimerManager.isPaused.value
                                        val isLocalBreak = com.example.util.FocusTimerManager.isTimerRunning.value && !com.example.util.FocusTimerManager.isFocusPhase.value
                                        val isLocalRelaxing = !com.example.util.FocusTimerManager.isTimerRunning.value && !com.example.util.FocusTimerManager.isStopwatchActive.value && !com.example.util.FocusTimerManager.isPaused.value
                                        val isLocalNotFocusing = isLocalPaused || isLocalBreak || isLocalRelaxing

                                        val lastNotified = lastNotifiedSessionStart[friendId] ?: 0L

                                        // Notify whenever a friend is actively focusing and the local user is idle/relaxing,
                                        // provided we haven't already notified for this exact update timestamp.
                                        if (isLocalNotFocusing && lastNotified != lastUpdated) {
                                            lastNotifiedSessionStart[friendId] = lastUpdated
                                            showPeerFocusNotification(context, friendId, displayName, currentTask, currentTag)
                                        }
                                    } else {
                                        // Friend ended timer, stopped focusing or changed status -> Remove notification
                                        cancelPeerFocusNotification(context, friendId)
                                        lastNotifiedSessionStart.remove(friendId)
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error checking focus transition: ${e.message}", e)
                            }

                            updatePeerState(friendId, state)
                        }

                        override fun onCancelled(error: DatabaseError) {
                            Log.e(TAG, "Listener cancelled for friend: $friendId", error.toException())
                        }
                    }

                    activeRef.addValueEventListener(listener)
                    activeListeners[friendId] = Pair(activeRef, listener)
                    Log.d(TAG, "Added listener for friend: $friendId")
                } catch (e: Exception) {
                    Log.e(TAG, "Error setting up listener for friend: $friendId", e)
                }
            }
        }
    }

    private fun updatePeerState(friendId: String, state: PeerLiveState?) {
        val targetState = state ?: PeerLiveState(
            userId = friendId,
            displayName = getFallbackDisplayName(friendId),
            currentTag = "Study",
            currentTask = "Relaxing",
            timerMode = "pomodoro",
            status = "Relaxing",
            timeline = emptyList(),
            lastUpdated = com.example.util.TimeEngine.getTrueTimeMs()
        )
        val current = _peerLiveStates.value
        val existing = current[friendId]
        if (existing == targetState) {
            return
        }
        val updatedMap = current.toMutableMap()
        updatedMap[friendId] = targetState
        _peerLiveStates.value = updatedMap
    }

    fun cleanUpListeners() {
        try {
            roomCollectJob?.cancel()
            roomCollectJob = null

            friendsListRef?.let { ref ->
                friendsListListener?.let { listener ->
                    ref.removeEventListener(listener)
                }
            }
            friendsListRef = null
            friendsListListener = null

            fcmInboxRef?.let { ref ->
                fcmInboxListener?.let { listener ->
                    ref.removeEventListener(listener)
                }
            }
            fcmInboxRef = null
            fcmInboxListener = null

            sharedRoomsRef?.let { ref ->
                sharedRoomsListener?.let { listener ->
                    ref.removeEventListener(listener)
                }
            }
            sharedRoomsRef = null
            sharedRoomsListener = null

            for ((_, pair) in activeListeners) {
                val (ref, listener) = pair
                ref.removeEventListener(listener)
            }
            activeListeners.clear()
            _peerLiveStates.value = emptyMap()

            regularFriends = emptyList()
            activeRoomFriends = emptyList()
            myUserEmail = ""

            Log.d(TAG, "Successfully cleaned up all peer Firebase listeners.")
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up peer listeners", e)
        }
    }

    private fun getFallbackDisplayName(userId: String): String {
        val clean = userId.substringBefore("@").replace(".", "_")
        val prefix = clean.substringBefore("_")
        return prefix.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString()
        }
    }

    fun showPeerFocusNotification(
        context: Context,
        friendId: String,
        peerName: String,
        taskName: String,
        tagName: String
    ) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val channelId = "peer_focus_channel"
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(
                    channelId,
                    "Study Group Alerts",
                    android.app.NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Alerts when members of your study group start focusing"
                    enableLights(true)
                    enableVibration(true)
                }
                notificationManager.createNotificationChannel(channel)
            }

            val openAppIntent = android.content.Intent(context, com.example.MainActivity::class.java).apply {
                flags = android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("NAVIGATE_TO", "TIMER")
                putExtra("SHOW_TIMER_PAGE", true)
            }
            val pendingIntent = android.app.PendingIntent.getActivity(
                context,
                123456 + friendId.lowercase().hashCode(),
                openAppIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )

            val title = "$peerName started focusing! 🎯"
            val body = "$peerName is focusing on \"$taskName\" ($tagName). Join them now!"

            val builder = androidx.core.app.NotificationCompat.Builder(context, channelId)
                .setSmallIcon(com.example.R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(body)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)

            val notifId = 123456 + friendId.lowercase().hashCode()
            notificationManager.notify(notifId, builder.build())
            Log.d(TAG, "Posted peer focus notification for $friendId with ID: $notifId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show peer focus notification: ${e.message}", e)
        }
    }

    fun cancelPeerFocusNotification(context: Context, friendId: String) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val notifId = 123456 + friendId.lowercase().hashCode()
            notificationManager.cancel(notifId)
            Log.d(TAG, "Cancelled/dismissed peer focus notification for $friendId with ID: $notifId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel peer focus notification for $friendId: ${e.message}", e)
        }
    }
}
