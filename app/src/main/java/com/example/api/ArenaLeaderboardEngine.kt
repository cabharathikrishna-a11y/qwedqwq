package com.example.api

import android.content.Context
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.example.data.AppDatabase
import com.example.util.TimeEngine
import com.example.util.childMs
import com.example.util.parseToMs
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class ArenaRankModel(
    val email: String,
    val displayName: String,
    val totalFocusMs: Long,
    val activeStreak: Int,
    val xpScore: Int,
    val topSubject: String,
    val isMe: Boolean = false,
    val rank: Int = 0,
    val customEmoji: String = "",
    val todayFocusMs: Long = 0L
)

object ArenaLeaderboardEngine {
    private const val TAG = "ArenaLeaderboardEngine"

    private val _leaderboardFlow = MutableStateFlow<List<ArenaRankModel>>(emptyList())
    val leaderboardFlow: StateFlow<List<ArenaRankModel>> = _leaderboardFlow.asStateFlow()

    private var friendsListener: ValueEventListener? = null
    private var friendsRef: com.google.firebase.database.DatabaseReference? = null
    private val activeWeeklyListeners = java.util.concurrent.ConcurrentHashMap<String, Pair<com.google.firebase.database.DatabaseReference, ValueEventListener>>()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var peerStatesCollectJob: Job? = null
    private var computeJob: Job? = null
    @Volatile private var lastLocalStatsRefreshMs: Long = 0L

    // Temporary storage for individual peer raw weekly stats
    private val rawWeeklyStatsMap = java.util.concurrent.ConcurrentHashMap<String, PeerWeeklyRawStats>()
    private var appContext: Context? = null
    @Volatile private var myCachedPast7Ms: Long = 0L
    @Volatile private var myCachedPast30Ms: Long = 0L
    @Volatile private var myCachedAllTimeMs: Long = 0L

    private fun refreshMyLocalStats(ctx: Context, myEmail: String) {
        if (myEmail.isBlank()) return
        scope.launch {
            try {
                val db = com.example.data.AppDatabase.getInstance(ctx)
                val history = db.localHistoryVaultDao().getAllHistoryDirect().filter {
                    it.userEmail.isBlank() || it.userEmail.lowercase().trim() == myEmail.lowercase().trim()
                }
                val records = com.example.util.FocusTimerManager.loadFocusRecords(ctx)
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                
                val past7Dates = (0..6).map {
                    val cal = java.util.Calendar.getInstance()
                    cal.add(java.util.Calendar.DAY_OF_YEAR, -it)
                    sdf.format(cal.time)
                }.toSet()
                val history7Ms = history.filter { past7Dates.contains(it.date_string) }.sumOf { it.total_focus_ms }
                val timer7Ms = records.sumOf { r ->
                    past7Dates.sumOf { d -> com.example.util.FocusTimerManager.getOverlapSecondsForDate(r, d).toLong() }
                } * 1000L
                myCachedPast7Ms = maxOf(history7Ms, timer7Ms)

                val past30Dates = (0..29).map {
                    val cal = java.util.Calendar.getInstance()
                    cal.add(java.util.Calendar.DAY_OF_YEAR, -it)
                    sdf.format(cal.time)
                }.toSet()
                val history30Ms = history.filter { past30Dates.contains(it.date_string) }.sumOf { it.total_focus_ms }
                val timer30Ms = records.sumOf { r ->
                    past30Dates.sumOf { d -> com.example.util.FocusTimerManager.getOverlapSecondsForDate(r, d).toLong() }
                } * 1000L
                myCachedPast30Ms = maxOf(history30Ms, timer30Ms)

                val historyAllMs = history.sumOf { it.total_focus_ms }
                val timerAllMs = records.sumOf { it.durationSeconds.toLong() } * 1000L
                myCachedAllTimeMs = maxOf(historyAllMs, timerAllMs)
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing my local stats", e)
            }
        }
    }

    private data class PeerWeeklyRawStats(
        val email: String,
        val displayName: String,
        val totalFocusMs: Long,
        val activeStreak: Int,
        val topSubject: String,
        val customEmoji: String = "",
        val xpScore: Int = 0,
        val lastUpdated: Long = 0L,
        val baseOverallXp: Int = 0,
        val unconsumedShieldsCount: Int = 0,
        val rawTodayFocusMs: Long = 0L,
        val past7DaysFocusMs: Long = 0L,
        val past30DaysFocusMs: Long = 0L,
        val allTimeFocusMs: Long = 0L
    )

    private var activePeriod: String = "TODAY"

    private fun isUpdatedToday(lastUpdatedMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (lastUpdatedMs <= 0L) return false

        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val todayStrLocal = sdf.format(Date(nowMs))
        val updatedStrLocal = sdf.format(Date(lastUpdatedMs))
        return todayStrLocal == updatedStrLocal
    }

    private fun normalizeEmailKey(raw: String): String {
        val clean = raw.lowercase().trim()
        if (clean.isBlank()) return ""
        if (clean.contains("@")) {
            val user = clean.substringBefore("@")
            val domain = clean.substringAfter("@").replace("_", ".")
            return "$user@$domain"
        } else if (clean.contains("_")) {
            val lastIdx = clean.lastIndexOf("_")
            if (lastIdx > 0) {
                val prefix = clean.substring(0, lastIdx)
                val ext = clean.substring(lastIdx + 1)
                val user = prefix.substringBefore("_")
                val domain = prefix.substringAfter("_", "")
                if (domain.isNotEmpty()) {
                    return "$user@$domain.$ext"
                }
            }
        }
        return clean
    }

    fun startListening(context: Context, myEmail: String, period: String = "TODAY") {
        activePeriod = period
        appContext = context.applicationContext
        if (myEmail.isBlank()) {
            Log.e(TAG, "Cannot start leaderboard listening: blank email")
            return
        }
        
        try {
            val dbUrl = FirebaseConfig.getDatabaseUrl(context)
            if (dbUrl.isEmpty()) {
                Log.e(TAG, "Database URL is empty, cannot load leaderboard.")
                return
            }

            val database = FirebaseDatabase.getInstance(dbUrl)
            
            // Cleanup existing listeners if any
            stopListening()

            // 1. Core Reactive Sync with PeerLiveSphereManager's active peers
            peerStatesCollectJob = scope.launch {
                PeerLiveSphereManager.peerLiveStates
                    .map { it.keys.map { k -> k.lowercase().trim() }.sorted() }
                    .distinctUntilChanged()
                    .collect { peerKeys ->
                        val peerEmails = mutableListOf<String>()
                        peerEmails.addAll(peerKeys)
                        if (myEmail.isNotBlank()) {
                            peerEmails.add(myEmail.lowercase().trim())
                        }
                        val deduplicatedEmails = peerEmails.distinct()
                        Log.d(TAG, "Leaderboard syncing weekly listeners dynamically based on active Live Sphere peers: $deduplicatedEmails")
                        syncWeeklyListeners(context, database, deduplicatedEmails, myEmail)
                    }
            }

            // 2. Listen to Centralized LEADERBOARD room directly
            val leaderboardRoomRef = database.getReference("FOCUS_TIMMER").child("LEADERBOARD")
            val leaderboardRoomListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (!snapshot.exists()) return
                    val activeFriendKeys = PeerLiveSphereManager.peerLiveStates.value.keys.map { it.lowercase().trim() }.toSet()
                    val myCleanEmail = myEmail.lowercase().trim()
                    val mySanitized = DevicePresenceManager.sanitizeEmail(myEmail)

                    for (child in snapshot.children) {
                        val sanitizedKey = child.key ?: continue
                        val rawEmail = child.child("email").getValue(String::class.java)
                            ?: child.child("userEmail").getValue(String::class.java)
                            ?: sanitizedKey
                        val email = rawEmail.lowercase().trim()
                        val normKey = normalizeEmailKey(email)

                        val isMe = (email == myCleanEmail || sanitizedKey == mySanitized || normKey == myCleanEmail)
                        val isFriend = activeFriendKeys.contains(normKey) || activeFriendKeys.contains(email) || activeFriendKeys.contains(sanitizedKey) || activeFriendKeys.any { k -> k == normKey || normalizeEmailKey(k) == normKey }

                        // Strictly restrict leaderboard entries to only the current user and friends in Friends Focus
                        if (!isMe && !isFriend) {
                            continue
                        }

                        val activeStreak = child.child("ActiveStreak").getValue(Int::class.java) ?: 0
                        val rawName = child.child("displayName").getValue(String::class.java)
                            ?: child.child("DisplayName").getValue(String::class.java)
                        val displayName = if (!rawName.isNullOrBlank()) rawName else email.substringBefore("@")

                        val rawEmoji = child.child("CustomEmoji").getValue(String::class.java)
                            ?: child.child("customEmoji").getValue(String::class.java)
                            ?: ""

                        val nowMs = System.currentTimeMillis()
                        val rawLastUpdated = parseToMs(child.child("Last_Updated").value)
                        val isUpdatedToday = TimeEngine.isUpdatedToday(rawLastUpdated, nowMs)
                        val lastUpdated = if (rawLastUpdated > 0L) rawLastUpdated else 0L

                        val rawTodayLbMs = child.childMs("Todays_Focus_Ms", "todayFocusMs", "TODAY/Total_Focus_Ms")
                        val todayLbMs = if (isUpdatedToday) rawTodayLbMs else 0L
                        val past7LbMs = child.childMs("Past_7_Days_Focus_Ms", "PAST_7_DAYS/Total_Focus_Ms")
                        val past30LbMs = child.childMs("Past_30_Days_Focus_Ms", "PAST_30_DAYS/Total_Focus_Ms")
                        val allTimeLbMs = child.childMs("All_Time_Focus_Ms", "ALL_TIME/Total_Focus_Ms")

                        if (email == myCleanEmail || sanitizedKey == mySanitized) {
                            checkAndReconcileLeaderboardDiscrepancy(myEmail, todayLbMs)
                        }

                        val existing = rawWeeklyStatsMap[normKey]
                        val updatedTodayMs = todayLbMs
                        val updatedPast7Ms = if (past7LbMs > 0L) past7LbMs else (existing?.past7DaysFocusMs ?: 0L)
                        val updatedPast30Ms = if (past30LbMs > 0L) past30LbMs else (existing?.past30DaysFocusMs ?: 0L)
                        val updatedAllTimeMs = if (allTimeLbMs > 0L) allTimeLbMs else (existing?.allTimeFocusMs ?: 0L)

                        val totalFocusMsForPeriod = when (activePeriod) {
                            "TODAY" -> updatedTodayMs
                            "PAST_7_DAYS" -> updatedPast7Ms
                            "PAST_30_DAYS" -> updatedPast30Ms
                            else -> updatedAllTimeMs
                        }

                        rawWeeklyStatsMap[normKey] = PeerWeeklyRawStats(
                            email = normKey,
                            displayName = if (displayName.isNotBlank() && displayName != normKey.substringBefore("@")) displayName else (existing?.displayName ?: displayName),
                            totalFocusMs = totalFocusMsForPeriod,
                            activeStreak = maxOf(existing?.activeStreak ?: 0, activeStreak),
                            topSubject = if (existing?.topSubject.isNullOrBlank() || existing?.topSubject == "None") "None" else existing.topSubject,
                            customEmoji = if (rawEmoji.isNotBlank()) rawEmoji else (existing?.customEmoji ?: ""),
                            xpScore = maxOf(existing?.xpScore ?: 0, 0),
                            lastUpdated = maxOf(existing?.lastUpdated ?: 0L, lastUpdated),
                            baseOverallXp = 0,
                            unconsumedShieldsCount = existing?.unconsumedShieldsCount ?: 0,
                            rawTodayFocusMs = updatedTodayMs,
                            past7DaysFocusMs = updatedPast7Ms,
                            past30DaysFocusMs = updatedPast30Ms,
                            allTimeFocusMs = updatedAllTimeMs
                        )
                    }
                    computeAndEmitLeaderboard(myEmail)
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "LEADERBOARD room listener cancelled", error.toException())
                }
            }
            leaderboardRoomRef.addValueEventListener(leaderboardRoomListener)
            activeWeeklyListeners["LEADERBOARD_ROOM"] = Pair(leaderboardRoomRef, leaderboardRoomListener)

            // 3. Also listen to Friends List directly as a live database trigger
            val mySanitized = DevicePresenceManager.sanitizeEmail(myEmail)
            val fRef = database.getReference("FOCUS_TIMMER")
                .child("USER")
                .child(mySanitized)
                .child("FRIENDS_LIST")

            friendsRef = fRef

            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val peerEmails = mutableListOf<String>()
                    peerEmails.add(myEmail.lowercase().trim()) // Always include myself

                    // Extract all friend emails
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
                                peerEmails.add(friendId)
                            }
                        }
                    }

                    // Also pull from current room participants dynamically
                    val roomState = FocusLockerManager.uiState.value
                    roomState.participants.forEach {
                        peerEmails.add(it.email.lowercase().trim())
                    }

                    val deduplicatedEmails = peerEmails.map { it.lowercase().trim() }.distinct()
                    Log.d(TAG, "Leaderboard direct DB listener sync: $deduplicatedEmails")
                    syncWeeklyListeners(context, database, deduplicatedEmails, myEmail)
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Friends list listener cancelled for leaderboard", error.toException())
                }
            }

            fRef.addValueEventListener(listener)
            friendsListener = listener

        } catch (e: Exception) {
            Log.e(TAG, "Error starting leaderboard listening", e)
        }
    }

    fun stopListening() {
        try {
            peerStatesCollectJob?.cancel()
            peerStatesCollectJob = null
        } catch (e: Exception) {
            // ignore
        }

        try {
            friendsRef?.removeEventListener(friendsListener ?: return)
        } catch (e: Exception) {
            // ignore
        }
        friendsRef = null
        friendsListener = null

        // Remove all weekly listeners
        for ((ref, listener) in ArrayList(activeWeeklyListeners.values)) {
            try {
                ref.removeEventListener(listener)
            } catch (e: Exception) {
                // ignore
            }
        }
        activeWeeklyListeners.clear()
        rawWeeklyStatsMap.clear()
        _leaderboardFlow.value = emptyList()
    }

    private fun syncWeeklyListeners(
        context: Context,
        database: FirebaseDatabase,
        peerEmails: List<String>,
        myEmail: String
    ) {
        // 1. Remove listeners for peers no longer in our set
        val iterator = activeWeeklyListeners.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val email = entry.key
            if (!peerEmails.contains(email)) {
                val (ref, listener) = entry.value
                try {
                    ref.removeEventListener(listener)
                } catch (e: Exception) {
                    // ignore
                }
                iterator.remove()
                rawWeeklyStatsMap.remove(email)
            }
        }

        // 2. Add listeners for new peers
        for (email in peerEmails) {
            if (!activeWeeklyListeners.containsKey(email)) {
                try {
                    val sanitized = DevicePresenceManager.sanitizeEmail(email)
                    val weeklyRef = database.getReference("FOCUS_TIMMER")
                        .child("USER")
                        .child(sanitized)
                        .child("ARENA")

                    val listener = object : ValueEventListener {
                        override fun onDataChange(snapshot: DataSnapshot) {
                            val arenaSnapshot = snapshot
                            val normKey = normalizeEmailKey(email)
                            val existing = rawWeeklyStatsMap[normKey]

                            if (!snapshot.exists()) {
                                if (existing == null) {
                                    val defaultName = if (email == myEmail) {
                                        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                                        val username = prefs.getString("current_username", "") ?: ""
                                        val cachedNickname = prefs.getString("user_nickname_$username", "") ?: ""
                                        val cachedName = prefs.getString("user_name_$username", "") ?: ""
                                        if (cachedNickname.isNotEmpty()) cachedNickname else if (cachedName.isNotEmpty()) cachedName else if (username.isNotEmpty() && username != "Guest") username else email.substringBefore("@")
                                    } else {
                                        email.substringBefore("@")
                                    }
                                    val cachedEmoji = if (email == myEmail) {
                                        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                                        val username = prefs.getString("current_username", "") ?: ""
                                        prefs.getString("user_emoji_$username", "") ?: ""
                                    } else {
                                        PeerLiveSphereManager.peerLiveStates.value[email]?.customEmoji ?: ""
                                    }
                                    rawWeeklyStatsMap[normKey] = PeerWeeklyRawStats(
                                        email = normKey,
                                        displayName = defaultName,
                                        totalFocusMs = 0L,
                                        activeStreak = 0,
                                        topSubject = "None",
                                        customEmoji = cachedEmoji
                                    )
                                    computeAndEmitLeaderboard(myEmail)
                                }
                                return
                            }

                            val activeStreak = arenaSnapshot.child("ActiveStreak").getValue(Int::class.java) ?: 0
                            val rawName = arenaSnapshot.child("DisplayName").getValue(String::class.java)
                            val displayName = if (!rawName.isNullOrBlank()) rawName else email.substringBefore("@")

                            val rawEmoji = arenaSnapshot.child("CustomEmoji").getValue(String::class.java)
                                ?: arenaSnapshot.child("ProfileUrl").getValue(String::class.java)
                                ?: arenaSnapshot.child("ProfilePictureUrl").getValue(String::class.java)
                                ?: arenaSnapshot.child("profile_url").getValue(String::class.java)
                                ?: arenaSnapshot.child("avatar_url").getValue(String::class.java)
                                ?: arenaSnapshot.child("AvatarUrl").getValue(String::class.java)
                                ?: (if (email == myEmail) {
                                    val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                                    val username = prefs.getString("current_username", "") ?: ""
                                    prefs.getString("user_emoji_$username", "") ?: ""
                                } else {
                                    PeerLiveSphereManager.peerLiveStates.value[email]?.customEmoji ?: ""
                                })

                            val topSubjectName = arenaSnapshot.child("Top_Subject").getValue(String::class.java)
                                ?: arenaSnapshot.child("topSubject").getValue(String::class.java)
                                ?: "None"

                            val arenaLastUpdated = parseToMs(arenaSnapshot.child("Last_Updated").value)
                            val nowMs = System.currentTimeMillis()
                            val isArenaUpdatedToday = TimeEngine.isUpdatedToday(arenaLastUpdated, nowMs)

                            val todayLbMs = run {
                                val rawMs = arenaSnapshot.childMs("Todays_Focus_Ms", "todayFocusMs", "TODAY/Total_Focus_Ms")
                                if (isArenaUpdatedToday) rawMs else 0L
                            }
                            val past7LbMs = arenaSnapshot.childMs("Past_7_Days_Focus_Ms", "PAST_7_DAYS/Total_Focus_Ms")
                            val past30LbMs = arenaSnapshot.childMs("Past_30_Days_Focus_Ms", "PAST_30_DAYS/Total_Focus_Ms")
                            val allTimeLbMs = arenaSnapshot.childMs("All_Time_Focus_Ms", "ALL_TIME/Total_Focus_Ms")

                            val lastUpdated = if (arenaLastUpdated > 0L) arenaLastUpdated else 0L
                            val baseXp = arenaSnapshot.child("XpScore").getValue(Int::class.java) ?: 0

                            var unconsumedShieldsCount = 0
                            val shieldsSnapshot = snapshot.child("SHIELDS")
                            if (shieldsSnapshot.exists()) {
                                for (shieldChild in shieldsSnapshot.children) {
                                    val isConsumed = shieldChild.child("Is_Consumed").getValue(Boolean::class.java)
                                        ?: shieldChild.child("is_consumed").getValue(Boolean::class.java)
                                        ?: false
                                    if (!isConsumed) {
                                        unconsumedShieldsCount++
                                    }
                                }
                            }

                            val updatedTodayMs = todayLbMs
                            val updatedPast7Ms = if (past7LbMs > 0L) past7LbMs else (existing?.past7DaysFocusMs ?: 0L)
                            val updatedPast30Ms = if (past30LbMs > 0L) past30LbMs else (existing?.past30DaysFocusMs ?: 0L)
                            val updatedAllTimeMs = if (allTimeLbMs > 0L) allTimeLbMs else (existing?.allTimeFocusMs ?: 0L)

                            val totalFocusMsForPeriod = when (activePeriod) {
                                "TODAY" -> updatedTodayMs
                                "PAST_7_DAYS" -> updatedPast7Ms
                                "PAST_30_DAYS" -> updatedPast30Ms
                                else -> updatedAllTimeMs
                            }

                            rawWeeklyStatsMap[normKey] = PeerWeeklyRawStats(
                                email = normKey,
                                displayName = if (displayName.isNotBlank() && displayName != normKey.substringBefore("@")) displayName else (existing?.displayName ?: displayName),
                                totalFocusMs = totalFocusMsForPeriod,
                                activeStreak = maxOf(existing?.activeStreak ?: 0, activeStreak),
                                topSubject = if (topSubjectName != "None") topSubjectName else (existing?.topSubject ?: "None"),
                                customEmoji = if (!rawEmoji.isNullOrBlank()) rawEmoji else (existing?.customEmoji ?: ""),
                                xpScore = maxOf(existing?.xpScore ?: 0, baseXp),
                                lastUpdated = maxOf(existing?.lastUpdated ?: 0L, lastUpdated),
                                baseOverallXp = maxOf(existing?.baseOverallXp ?: 0, baseXp),
                                unconsumedShieldsCount = unconsumedShieldsCount,
                                rawTodayFocusMs = updatedTodayMs,
                                past7DaysFocusMs = updatedPast7Ms,
                                past30DaysFocusMs = updatedPast30Ms,
                                allTimeFocusMs = updatedAllTimeMs
                            )
                            computeAndEmitLeaderboard(myEmail)
                        }

                        override fun onCancelled(error: DatabaseError) {
                            Log.e(TAG, "Weekly stats listener cancelled for $email", error.toException())
                        }
                    }

                    weeklyRef.addValueEventListener(listener)
                    activeWeeklyListeners[email] = Pair(weeklyRef, listener)

                } catch (e: Exception) {
                    Log.e(TAG, "Error starting weekly stats listener for $email", e)
                }
            }
        }

        // Trigger an initial calculation in case some exist but no changes are fired
        computeAndEmitLeaderboard(myEmail)
    }

    private fun computeAndEmitLeaderboard(myEmail: String) {
        computeJob?.cancel()
        computeJob = scope.launch(Dispatchers.Default) {
            val activeFriendKeys = PeerLiveSphereManager.peerLiveStates.value.keys.map { it.lowercase().trim() }.toSet()
            val myCleanEmail = myEmail.lowercase().trim()
            val mySanitized = DevicePresenceManager.sanitizeEmail(myEmail)

            val rawList = ArrayList(rawWeeklyStatsMap.values)
                .filter {
                    val normEmail = it.email.lowercase().trim()
                    val isMe = normEmail == myCleanEmail || normEmail == mySanitized
                    val isFriend = activeFriendKeys.contains(normEmail) || activeFriendKeys.any { k -> k == normEmail || normalizeEmailKey(k) == normEmail }
                    (isMe || isFriend) &&
                    it.displayName.lowercase() != "guest" &&
                    !it.email.lowercase().contains("guest")
                }
                .sortedByDescending { it.totalFocusMs }
                .distinctBy { it.displayName.lowercase().trim() }
                .toList()
            if (rawList.isEmpty()) {
                _leaderboardFlow.value = emptyList()
                return@launch
            }

            val nowMs = System.currentTimeMillis()
            if (appContext != null && myEmail.isNotBlank() && (nowMs - lastLocalStatsRefreshMs > 60_000L)) {
                lastLocalStatsRefreshMs = nowMs
                refreshMyLocalStats(appContext!!, myEmail)
            }

            // Calculate XP and create ArenaRankModel
            val rankModels = rawList.map { raw ->
                val liveState = com.example.api.PeerLiveSphereManager.peerLiveStates.value[raw.email]
                
                val isUpdatedToday = isUpdatedToday(raw.lastUpdated, nowMs)
                val isMe = raw.email.lowercase().trim() == myEmail.lowercase().trim()

                val baseTodayMs = if (isMe) {
                    val todayStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(nowMs))
                    val completedTodaySecs = com.example.util.FocusTimerManager.focusRecords.value.sumOf { com.example.util.FocusTimerManager.getOverlapSecondsForDate(it, todayStr) }
                    val pendingSecs = com.example.util.FocusTimerManager.pendingFocusReview.value?.let { com.example.util.FocusTimerManager.getOverlapSecondsForDate(it, todayStr) } ?: 0
                    (completedTodaySecs + pendingSecs) * 1000L
                } else {
                    raw.rawTodayFocusMs
                }
                val localActiveMs = if (isMe) {
                    val isTimerRunning = com.example.util.FocusTimerManager.isTimerRunning.value
                    val isStopwatchActive = com.example.util.FocusTimerManager.isStopwatchActive.value
                    val isPaused = com.example.util.FocusTimerManager.isPaused.value
                    val accum = com.example.util.FocusTimerManager.accumulatedSessionTimeMs.value
                    val chunk = com.example.util.FocusTimerManager.getCurrentChunkMs()
                    if ((isTimerRunning || isStopwatchActive || isPaused) && com.example.util.FocusTimerManager.pendingFocusReview.value == null) {
                        accum + chunk
                    } else 0L
                } else 0L

                val statusClean = liveState?.status?.lowercase()?.trim() ?: ""
                val isLiveFocusingStatus = statusClean.contains("focus") || statusClean.contains("run") || statusClean.contains("study") || statusClean.contains("work")
                val remoteActiveMs = if (liveState != null && isLiveFocusingStatus) {
                    com.example.api.TimelineSyncEngine.calculateAccumulatedFocusMs(liveState.timeline, liveState.status)
                } else 0L

                val activeSessionFocusMs = maxOf(localActiveMs, remoteActiveMs)
                val todayTotalLiveMs = baseTodayMs + maxOf(0L, activeSessionFocusMs)

                val effectivePast7Ms = if (isMe) maxOf(raw.past7DaysFocusMs, myCachedPast7Ms) else raw.past7DaysFocusMs
                val effectivePast30Ms = if (isMe) maxOf(raw.past30DaysFocusMs, myCachedPast30Ms) else raw.past30DaysFocusMs
                val effectiveAllTimeMs = if (isMe) maxOf(raw.allTimeFocusMs, myCachedAllTimeMs) else raw.allTimeFocusMs

                val totalFocusMs = when (activePeriod) {
                    "TODAY" -> todayTotalLiveMs
                    "PAST_7_DAYS" -> effectivePast7Ms + maxOf(0L, activeSessionFocusMs)
                    "PAST_30_DAYS" -> effectivePast30Ms + maxOf(0L, activeSessionFocusMs)
                    else -> effectiveAllTimeMs + maxOf(0L, activeSessionFocusMs)
                }

                val (decayedStreak, decayedXp) = if (raw.email == myEmail) {
                    Pair(raw.activeStreak, raw.xpScore)
                } else {
                    if (raw.lastUpdated > 0L) {
                        getDecayedStreakAndXp(raw.lastUpdated, raw.activeStreak, raw.xpScore, raw.unconsumedShieldsCount)
                    } else {
                        Pair(raw.activeStreak, raw.xpScore)
                    }
                }

                val xpScore = if (raw.email == myEmail) {
                    calculateXp(effectiveAllTimeMs, raw.activeStreak)
                } else {
                    calculateXp(raw.allTimeFocusMs, decayedStreak)
                }

                ArenaRankModel(
                    email = raw.email,
                    displayName = raw.displayName,
                    totalFocusMs = totalFocusMs,
                    activeStreak = decayedStreak,
                    xpScore = xpScore,
                    topSubject = raw.topSubject,
                    isMe = (raw.email == myEmail),
                    customEmoji = raw.customEmoji,
                    todayFocusMs = todayTotalLiveMs
                )
            }

            // Sort descending by totalFocusMs, fallback to XP
            val sortedList = rankModels.sortedWith(
                compareByDescending<ArenaRankModel> { it.totalFocusMs }
                    .thenByDescending { it.xpScore }
            )

            // Assign ranks (1-indexed)
            val finalRankedList = sortedList.mapIndexed { index, model ->
                model.copy(rank = index + 1)
            }

            _leaderboardFlow.value = finalRankedList
        }
    }

    fun recomputeLeaderboard(myEmail: String) {
        computeAndEmitLeaderboard(myEmail)
    }

    fun getDecayedStreakAndXp(
        lastUpdated: Long,
        baseStreak: Int,
        baseXp: Int,
        unconsumedShieldsCount: Int
    ): Pair<Int, Int> {
        val now = System.currentTimeMillis()
        val daysPassed = ((now - lastUpdated) / (24L * 3600L * 1000L)).toInt()
        
        if (daysPassed <= 0) {
            return Pair(baseStreak, baseXp)
        }
        
        if (daysPassed >= 7) {
            return Pair(0, 0)
        }
        
        var currentStreak = baseStreak
        var shieldsLeft = unconsumedShieldsCount
        
        for (day in 1..daysPassed) {
            if (currentStreak >= 1) {
                if (shieldsLeft > 0) {
                    shieldsLeft--
                } else {
                    currentStreak = 0
                }
            } else {
                currentStreak = 0
            }
        }
        
        val revisedXp = if (baseStreak == currentStreak) {
            baseXp
        } else {
            val baseMultiplier = 1.0 + (0.1 * baseStreak)
            val revisedMultiplier = 1.0 + (0.1 * currentStreak)
            ((baseXp.toDouble() / baseMultiplier) * revisedMultiplier).toInt()
        }
        
        return Pair(currentStreak, revisedXp)
    }

    fun calculateXp(focusMs: Long, streak: Int): Int {
        val focusMins = focusMs / 60000L
        val tenHoursMins = 10 * 60L // 600
        val eightHoursMins = 8 * 60L // 480

        val ctx = appContext
        val myEmail = try {
            if (ctx != null) {
                val googleAccount = com.example.util.GmsUtils.getLastSignedInAccount(ctx)
                val prefs = ctx.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                val savedUsername = prefs.getString("current_username", "Guest") ?: "Guest"
                googleAccount?.email ?: prefs.getString("user_email_$savedUsername", "") ?: "$savedUsername@gmail.com"
            } else {
                ""
            }
        } catch (e: Throwable) {
            ""
        }

        val deductedXp = try {
            ctx?.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                ?.getInt("deducted_xp_${myEmail}", 0) ?: 0
        } catch (e: Exception) {
            0
        }

        val xpOffset = try {
            ctx?.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                ?.getInt("xp_offset_penalty_${myEmail}", 0) ?: 0
        } catch (e: Exception) {
            0
        }

        val extraCredits = try {
            ctx?.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                ?.getInt("extra_credits_xp", 0) ?: 0
        } catch (e: Exception) {
            0
        }

        fun getBaseXpWithRate(rate: Double): Double {
            return if (focusMins >= eightHoursMins) {
                val baseEarned = eightHoursMins.toDouble() / rate
                val excessMins = focusMins - eightHoursMins
                val extraEarned = excessMins.toDouble() / 10.0
                baseEarned + extraEarned
            } else {
                focusMins.toDouble() / rate
            }
        }

        val baseXp = getBaseXpWithRate(15.0)
        var totalBaseXp = baseXp + extraCredits - (deductedXp + xpOffset)

        if (totalBaseXp < 0.0) {
            totalBaseXp = 0.0
        }

        return (totalBaseXp * (1.0 + (0.1 * streak))).let { 
            if (it.isNaN() || it.isInfinite() || it < 0.0) 0 else it.toInt()
        }
    }

    private var lastDiscrepancyCheckMs = 0L

    fun checkAndReconcileLeaderboardDiscrepancy(email: String, rtdbLeaderboardTodayMs: Long) {
        val ctx = appContext ?: return
        if (email.isBlank()) return
        val now = System.currentTimeMillis()
        if (now - lastDiscrepancyCheckMs < 3000L) return
        lastDiscrepancyCheckMs = now

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = com.example.data.AppDatabase.getInstance(ctx)
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                val todayStr = sdf.format(java.util.Date())
                val targetEmail = email.lowercase().trim()
                val allHistory = db.localHistoryVaultDao().getAllHistoryDirect().filter {
                    it.userEmail.isBlank() || it.userEmail.lowercase().trim() == targetEmail
                }
                val localTodayMs = allHistory.filter { it.date_string == todayStr }.sumOf { it.total_focus_ms }

                val focusRecords = com.example.util.FocusTimerManager.loadFocusRecords(ctx)
                val focusTimerSecs = focusRecords.sumOf { r ->
                    com.example.util.FocusTimerManager.getOverlapSecondsForDate(r, todayStr)
                }
                val isTimerRunning = com.example.util.FocusTimerManager.isTimerRunning.value
                val isStopwatchActive = com.example.util.FocusTimerManager.isStopwatchActive.value
                val activeSessionMs = if ((isTimerRunning || isStopwatchActive) && com.example.util.FocusTimerManager.pendingFocusReview.value == null) {
                    com.example.util.FocusTimerManager.accumulatedSessionTimeMs.value
                } else 0L
                val localTrueTodayMs = maxOf(localTodayMs, focusTimerSecs * 1000L) + activeSessionMs

                val diffMs = rtdbLeaderboardTodayMs - localTrueTodayMs
                if (diffMs >= 60000L) {
                    Log.i(TAG, "⚡ Leaderboard discrepancy detected! RTDB LEADERBOARD Todays_Focus_Ms (${rtdbLeaderboardTodayMs}ms) > Local SQL DB (${localTrueTodayMs}ms). Triggering Firestore download & sync...")
                    FirestoreArchiver.pullAndSyncFocusHistoryFromFirestore(ctx, email)

                    // Recalculate true local focus after Firestore sync
                    val postSyncHistory = db.localHistoryVaultDao().getAllHistoryDirect().filter {
                        it.userEmail.isBlank() || it.userEmail.lowercase().trim() == targetEmail
                    }
                    val postSyncLocalTodayMs = postSyncHistory.filter { it.date_string == todayStr }.sumOf { it.total_focus_ms }
                    val postSyncFocusRecords = com.example.util.FocusTimerManager.loadFocusRecords(ctx)
                    val postSyncTimerSecs = postSyncFocusRecords.sumOf { r ->
                        com.example.util.FocusTimerManager.getOverlapSecondsForDate(r, todayStr)
                    }
                    val postSyncTrueTodayMs = maxOf(postSyncLocalTodayMs, postSyncTimerSecs * 1000L) + activeSessionMs

                    Log.i(TAG, "⚡ Discrepancy checked. Updating RTDB Todays_Focus_Ms to local ground truth: ${postSyncTrueTodayMs}ms (was ${rtdbLeaderboardTodayMs}ms)")
                    WeeklyStatsUpdater.updateWeeklyStats(ctx, email, postSyncTrueTodayMs, "")
                    DevicePresenceManager.updateDeviceFocusStats(ctx, email)
                } else if (diffMs <= -60000L) {
                    Log.i(TAG, "⚡ Leaderboard discrepancy detected! Local SQL DB (${localTrueTodayMs}ms) > RTDB LEADERBOARD Todays_Focus_Ms (${rtdbLeaderboardTodayMs}ms). Triggering upload to Firestore & RTDB...")
                    com.example.util.StateReconciliationHelper.runUnifiedReconciliation(ctx, db)
                    WeeklyStatsUpdater.updateWeeklyStats(ctx, email, localTrueTodayMs, "")
                    DevicePresenceManager.updateDeviceFocusStats(ctx, email)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in checkAndReconcileLeaderboardDiscrepancy for $email", e)
            }
        }
    }
}
