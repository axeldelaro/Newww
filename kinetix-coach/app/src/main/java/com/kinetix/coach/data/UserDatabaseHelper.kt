package com.kinetix.coach.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.kinetix.coach.progression.AthleteAttributes
import com.kinetix.coach.progression.AthleteProgressState
import com.kinetix.coach.progression.AthleteRank
import com.kinetix.coach.progression.DailyQuest
import com.kinetix.coach.progression.DayActivityEntry
import com.kinetix.coach.progression.MeritTitle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

class UserDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "kinetix_user.db"
        private const val DB_VERSION = 5
    }

    private val _sessions = MutableStateFlow<List<WorkoutSession>>(emptyList())
    val sessions: StateFlow<List<WorkoutSession>> = _sessions.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _profile = MutableStateFlow(AthleteProfile())
    val profile: StateFlow<AthleteProfile> = _profile.asStateFlow()

    private val _challengeRecords = MutableStateFlow<List<ChallengeRecord>>(emptyList())
    val challengeRecords: StateFlow<List<ChallengeRecord>> = _challengeRecords.asStateFlow()

    init {
        refreshAll()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE workout_sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp_ms INTEGER NOT NULL,
                exercise_id TEXT NOT NULL,
                reps INTEGER NOT NULL,
                duration_sec INTEGER NOT NULL,
                avg_form_score INTEGER NOT NULL,
                avg_rom_pct INTEGER NOT NULL,
                top_faults_json TEXT NOT NULL,
                notes TEXT NOT NULL,
                calories_kcal REAL NOT NULL DEFAULT 0.0,
                detected_variant TEXT NOT NULL DEFAULT '',
                estimated_max_reps INTEGER NOT NULL DEFAULT 0,
                peak_velocity_mps REAL NOT NULL DEFAULT 0.0,
                velocity_loss_pct INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE chat_messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp_ms INTEGER NOT NULL,
                is_user INTEGER NOT NULL,
                text TEXT NOT NULL,
                sources_json TEXT NOT NULL,
                tokens_per_sec REAL NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE athlete_profile (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                name TEXT NOT NULL,
                age INTEGER NOT NULL,
                sex TEXT NOT NULL,
                weight_kg REAL NOT NULL,
                height_cm INTEGER NOT NULL,
                goal TEXT NOT NULL,
                level TEXT NOT NULL,
                equipment TEXT NOT NULL,
                injuries TEXT NOT NULL,
                activity_factor REAL NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE app_prefs (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS challenge_records (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp_ms INTEGER NOT NULL,
                challenge_id TEXT NOT NULL,
                exercise_id TEXT NOT NULL,
                score_value REAL NOT NULL,
                score_label TEXT NOT NULL,
                duration_sec INTEGER NOT NULL,
                calories_kcal REAL NOT NULL
            )
            """.trimIndent()
        )
        createProgressionTables(db)
    }

    private fun createProgressionTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS athlete_progression (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                level INTEGER NOT NULL DEFAULT 1,
                total_xp INTEGER NOT NULL DEFAULT 0,
                rank_code TEXT NOT NULL DEFAULT 'E',
                rank_xp INTEGER NOT NULL DEFAULT 0,
                attr_force INTEGER NOT NULL DEFAULT 10,
                attr_endurance INTEGER NOT NULL DEFAULT 10,
                attr_agility INTEGER NOT NULL DEFAULT 10,
                attr_vitality INTEGER NOT NULL DEFAULT 10,
                attr_mastery INTEGER NOT NULL DEFAULT 10,
                streak_days INTEGER NOT NULL DEFAULT 0,
                longest_streak INTEGER NOT NULL DEFAULT 0,
                last_active_date TEXT NOT NULL DEFAULT '',
                today_date TEXT NOT NULL DEFAULT '',
                today_steps INTEGER NOT NULL DEFAULT 0,
                today_step_goal_rewarded INTEGER NOT NULL DEFAULT 0,
                today_step_buckets INTEGER NOT NULL DEFAULT 0,
                total_steps_lifetime INTEGER NOT NULL DEFAULT 0,
                total_quests_completed INTEGER NOT NULL DEFAULT 0,
                total_workouts_completed INTEGER NOT NULL DEFAULT 0,
                high_form_sessions INTEGER NOT NULL DEFAULT 0,
                vbt_sessions INTEGER NOT NULL DEFAULT 0,
                exercise_totals_json TEXT NOT NULL DEFAULT '{}',
                unlocked_titles_json TEXT NOT NULL DEFAULT '["ATHLETE_KINETIX"]',
                equipped_title_id TEXT NOT NULL DEFAULT 'ATHLETE_KINETIX',
                unlocked_badges_json TEXT NOT NULL DEFAULT '[]',
                today_quests_json TEXT NOT NULL DEFAULT '[]'
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS daily_activity_log (
                date_key TEXT PRIMARY KEY,
                steps INTEGER NOT NULL DEFAULT 0,
                goal_reached INTEGER NOT NULL DEFAULT 0,
                workout_done INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE workout_sessions ADD COLUMN calories_kcal REAL NOT NULL DEFAULT 0.0")
        }
        if (oldVersion < 3) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS challenge_records (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    timestamp_ms INTEGER NOT NULL,
                    challenge_id TEXT NOT NULL,
                    exercise_id TEXT NOT NULL,
                    score_value REAL NOT NULL,
                    score_label TEXT NOT NULL,
                    duration_sec INTEGER NOT NULL,
                    calories_kcal REAL NOT NULL
                )
                """.trimIndent()
            )
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE workout_sessions ADD COLUMN detected_variant TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE workout_sessions ADD COLUMN estimated_max_reps INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE workout_sessions ADD COLUMN peak_velocity_mps REAL NOT NULL DEFAULT 0.0")
            db.execSQL("ALTER TABLE workout_sessions ADD COLUMN velocity_loss_pct INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 5) {
            createProgressionTables(db)
        }
    }

    fun refreshAll() {
        _sessions.value = loadSessions()
        _messages.value = loadMessages()
        _profile.value = loadProfile()
        _challengeRecords.value = loadChallengeRecords()
    }

    fun insertSession(session: WorkoutSession): Long {
        val cv = ContentValues().apply {
            put("timestamp_ms", session.timestampMs)
            put("exercise_id", session.exerciseType.id)
            put("reps", session.reps)
            put("duration_sec", session.durationSec)
            put("avg_form_score", session.avgFormScore)
            put("avg_rom_pct", session.avgRomPct)
            put("top_faults_json", JSONArray(session.topFaults).toString())
            put("notes", session.notes)
            put("calories_kcal", session.caloriesKcal)
            put("detected_variant", session.detectedVariant)
            put("estimated_max_reps", session.estimatedMaxReps)
            put("peak_velocity_mps", session.peakVelocityMps)
            put("velocity_loss_pct", session.velocityLossPct)
        }
        val id = writableDatabase.insert("workout_sessions", null, cv)
        _sessions.value = loadSessions()
        return id
    }

    fun deleteSession(sessionId: Long) {
        writableDatabase.delete("workout_sessions", "id = ?", arrayOf(sessionId.toString()))
        _sessions.value = loadSessions()
    }

    private fun loadSessions(): List<WorkoutSession> {
        val list = mutableListOf<WorkoutSession>()
        readableDatabase.rawQuery(
            """
            SELECT id, timestamp_ms, exercise_id, reps, duration_sec, avg_form_score, avg_rom_pct,
                   top_faults_json, notes, calories_kcal, detected_variant, estimated_max_reps,
                   peak_velocity_mps, velocity_loss_pct
            FROM workout_sessions ORDER BY timestamp_ms DESC LIMIT 100
            """.trimIndent(),
            null
        ).use { c ->
            while (c.moveToNext()) {
                val faults = mutableListOf<String>()
                runCatching {
                    val arr = JSONArray(c.getString(7))
                    for (i in 0 until arr.length()) faults.add(arr.getString(i))
                }
                list.add(
                    WorkoutSession(
                        id = c.getLong(0),
                        timestampMs = c.getLong(1),
                        exerciseType = ExerciseType.fromId(c.getString(2)),
                        reps = c.getInt(3),
                        durationSec = c.getInt(4),
                        avgFormScore = c.getInt(5),
                        avgRomPct = c.getInt(6),
                        topFaults = faults,
                        notes = c.getString(8),
                        caloriesKcal = c.getDouble(9),
                        detectedVariant = c.getString(10),
                        estimatedMaxReps = c.getInt(11),
                        peakVelocityMps = c.getFloat(12),
                        velocityLossPct = c.getInt(13)
                    )
                )
            }
        }
        return list
    }

    fun insertMessage(msg: ChatMessage): Long {
        val srcArr = JSONArray()
        msg.sources.forEach { s ->
            srcArr.put(
                JSONObject().apply {
                    put("id", s.id)
                    put("title", s.title)
                    put("category", s.category)
                    put("subcategory", s.subcategory)
                }
            )
        }
        val cv = ContentValues().apply {
            put("timestamp_ms", msg.timestampMs)
            put("is_user", if (msg.isUser) 1 else 0)
            put("text", msg.text)
            put("sources_json", srcArr.toString())
            put("tokens_per_sec", msg.tokensPerSec)
        }
        val id = writableDatabase.insert("chat_messages", null, cv)
        _messages.value = loadMessages()
        return id
    }

    fun clearChat() {
        writableDatabase.delete("chat_messages", null, null)
        _messages.value = emptyList()
    }

    private fun loadMessages(): List<ChatMessage> {
        val list = mutableListOf<ChatMessage>()
        readableDatabase.rawQuery(
            "SELECT id, timestamp_ms, is_user, text, sources_json, tokens_per_sec FROM chat_messages ORDER BY id ASC LIMIT 200",
            null
        ).use { c ->
            while (c.moveToNext()) {
                val sources = mutableListOf<RagSourceRef>()
                runCatching {
                    val arr = JSONArray(c.getString(4))
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        sources.add(
                            RagSourceRef(
                                id = obj.getLong("id"),
                                title = obj.getString("title"),
                                category = obj.getString("category"),
                                subcategory = obj.optString("subcategory", "")
                            )
                        )
                    }
                }
                list.add(
                    ChatMessage(
                        id = c.getLong(0),
                        timestampMs = c.getLong(1),
                        isUser = c.getInt(2) == 1,
                        text = c.getString(3),
                        sources = sources,
                        tokensPerSec = c.getFloat(5),
                        isStreaming = false
                    )
                )
            }
        }
        return list
    }

    fun saveProfile(profile: AthleteProfile) {
        val cv = ContentValues().apply {
            put("id", 1)
            put("name", profile.name.trim().ifBlank { "Athlète" })
            put("age", profile.age.coerceIn(13, 95))
            put("sex", profile.sex)
            put("weight_kg", profile.weightKg.coerceIn(35f, 220f))
            put("height_cm", profile.heightCm.coerceIn(120, 230))
            put("goal", profile.goal)
            put("level", profile.level)
            put("equipment", profile.equipment)
            put("injuries", profile.injuries.trim().ifBlank { "Aucune" })
            put("activity_factor", profile.activityFactor)
        }
        writableDatabase.insertWithOnConflict("athlete_profile", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        _profile.value = loadProfile()
    }

    private fun loadProfile(): AthleteProfile {
        readableDatabase.rawQuery(
            "SELECT name, age, sex, weight_kg, height_cm, goal, level, equipment, injuries, activity_factor FROM athlete_profile WHERE id = 1",
            null
        ).use { c ->
            if (c.moveToFirst()) {
                return AthleteProfile(
                    name = c.getString(0),
                    age = c.getInt(1),
                    sex = c.getString(2),
                    weightKg = c.getFloat(3),
                    heightCm = c.getInt(4),
                    goal = c.getString(5),
                    level = c.getString(6),
                    equipment = c.getString(7),
                    injuries = c.getString(8),
                    activityFactor = c.getFloat(9)
                )
            }
        }
        return AthleteProfile()
    }

    fun insertChallengeRecord(record: ChallengeRecord): Long {
        val cv = ContentValues().apply {
            put("timestamp_ms", record.timestampMs)
            put("challenge_id", record.challengeId)
            put("exercise_id", record.exerciseId)
            put("score_value", record.scoreValue)
            put("score_label", record.scoreLabel)
            put("duration_sec", record.durationSec)
            put("calories_kcal", record.caloriesKcal)
        }
        val id = writableDatabase.insert("challenge_records", null, cv)
        _challengeRecords.value = loadChallengeRecords()
        return id
    }

    fun bestRecordFor(
        challengeId: String,
        exerciseId: String,
        lowerIsBetter: Boolean = challengeId == "TARGET_REPS_RACE"
    ): ChallengeRecord? {
        val order = if (lowerIsBetter) "ASC" else "DESC"
        readableDatabase.rawQuery(
            """
            SELECT id, timestamp_ms, challenge_id, exercise_id, score_value, score_label, duration_sec, calories_kcal
            FROM challenge_records
            WHERE challenge_id = ? AND exercise_id = ?
            ORDER BY score_value $order, timestamp_ms DESC
            LIMIT 1
            """.trimIndent(),
            arrayOf(challengeId, exerciseId)
        ).use { c ->
            if (c.moveToFirst()) {
                return ChallengeRecord(
                    id = c.getLong(0),
                    timestampMs = c.getLong(1),
                    challengeId = c.getString(2),
                    exerciseId = c.getString(3),
                    scoreValue = c.getDouble(4),
                    scoreLabel = c.getString(5),
                    durationSec = c.getInt(6),
                    caloriesKcal = c.getDouble(7)
                )
            }
        }
        return null
    }

    private fun loadChallengeRecords(): List<ChallengeRecord> {
        val list = mutableListOf<ChallengeRecord>()
        readableDatabase.rawQuery(
            """
            SELECT id, timestamp_ms, challenge_id, exercise_id, score_value, score_label, duration_sec, calories_kcal
            FROM challenge_records
            ORDER BY timestamp_ms DESC
            LIMIT 150
            """.trimIndent(),
            null
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    ChallengeRecord(
                        id = c.getLong(0),
                        timestampMs = c.getLong(1),
                        challengeId = c.getString(2),
                        exerciseId = c.getString(3),
                        scoreValue = c.getDouble(4),
                        scoreLabel = c.getString(5),
                        durationSec = c.getInt(6),
                        caloriesKcal = c.getDouble(7)
                    )
                )
            }
        }
        return list
    }

    fun getPref(key: String, defaultValue: String): String {
        readableDatabase.rawQuery("SELECT value FROM app_prefs WHERE key = ?", arrayOf(key)).use { c ->
            return if (c.moveToFirst()) c.getString(0) else defaultValue
        }
    }

    fun setPref(key: String, value: String) {
        val cv = ContentValues().apply {
            put("key", key)
            put("value", value)
        }
        writableDatabase.insertWithOnConflict("app_prefs", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun loadProgressionState(): AthleteProgressState? {
        val recentDays = mutableListOf<DayActivityEntry>()
        runCatching {
            readableDatabase.rawQuery(
                "SELECT date_key, steps, goal_reached, workout_done FROM daily_activity_log ORDER BY date_key DESC LIMIT 14",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    recentDays.add(
                        DayActivityEntry(
                            dateKey = c.getString(0),
                            steps = c.getInt(1).coerceAtLeast(0),
                            goalReached = c.getInt(2) == 1,
                            workoutDone = c.getInt(3) == 1
                        )
                    )
                }
            }
        }

        return runCatching {
            readableDatabase.rawQuery(
                """
                SELECT level, total_xp, rank_code, rank_xp,
                       attr_force, attr_endurance, attr_agility, attr_vitality, attr_mastery,
                       streak_days, longest_streak, last_active_date, today_date,
                       today_steps, today_step_goal_rewarded, today_step_buckets, total_steps_lifetime,
                       total_quests_completed, total_workouts_completed, high_form_sessions, vbt_sessions,
                       exercise_totals_json, unlocked_titles_json, equipped_title_id,
                       unlocked_badges_json, today_quests_json
                FROM athlete_progression WHERE id = 1
                """.trimIndent(),
                null
            ).use { c ->
                if (!c.moveToFirst()) return@use null

                val totalsMap = linkedMapOf<String, Int>()
                runCatching {
                    val obj = JSONObject(c.getString(21))
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        totalsMap[k] = obj.optInt(k, 0).coerceAtLeast(0)
                    }
                }

                val titles = linkedSetOf(MeritTitle.ATHLETE_KINETIX.id)
                runCatching {
                    val arr = JSONArray(c.getString(22))
                    for (i in 0 until arr.length()) {
                        val id = arr.optString(i)
                        if (id.isNotBlank()) titles.add(id)
                    }
                }

                val badges = linkedSetOf<String>()
                runCatching {
                    val arr = JSONArray(c.getString(24))
                    for (i in 0 until arr.length()) {
                        val id = arr.optString(i)
                        if (id.isNotBlank()) badges.add(id)
                    }
                }

                val quests = mutableListOf<DailyQuest>()
                runCatching {
                    val arr = JSONArray(c.getString(25))
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val exId = o.optString("exerciseId", "")
                        quests.add(
                            DailyQuest(
                                id = o.optString("id", "q_$i"),
                                dateKey = o.optString("dateKey", ""),
                                titleFr = o.optString("titleFr", "Quête"),
                                subtitleFr = o.optString("subtitleFr", ""),
                                exerciseType = if (exId.isBlank()) null else ExerciseType.fromId(exId),
                                modifierLabelFr = o.optString("modifierLabelFr", "Classique"),
                                formatLabelFr = o.optString("formatLabelFr", ""),
                                targetAmount = o.optInt("targetAmount", 20).coerceAtLeast(1),
                                currentProgress = o.optInt("currentProgress", 0).coerceAtLeast(0),
                                unitFr = o.optString("unitFr", "reps"),
                                xpReward = o.optInt("xpReward", 45).coerceAtLeast(10),
                                attributeKey = o.optString("attributeKey", "force"),
                                completed = o.optBoolean("completed", false)
                            )
                        )
                    }
                }

                val rank = AthleteRank.fromCode(c.getString(2))
                val rankXp = c.getInt(3).coerceAtLeast(0)
                val equippedId = c.getString(23).takeIf { it in titles } ?: MeritTitle.ATHLETE_KINETIX.id

                AthleteProgressState(
                    level = c.getInt(0).coerceAtLeast(1),
                    totalXp = c.getInt(1).coerceAtLeast(0),
                    rank = rank,
                    rankXp = rankXp,
                    rankExamReady = rank != AthleteRank.S && rankXp >= rank.targetRankXp,
                    attributes = AthleteAttributes(
                        force = c.getInt(4).coerceAtLeast(10),
                        endurance = c.getInt(5).coerceAtLeast(10),
                        agility = c.getInt(6).coerceAtLeast(10),
                        vitality = c.getInt(7).coerceAtLeast(10),
                        mastery = c.getInt(8).coerceAtLeast(10)
                    ),
                    streakDays = c.getInt(9).coerceAtLeast(0),
                    longestStreak = c.getInt(10).coerceAtLeast(0),
                    lastActiveDateKey = c.getString(11).orEmpty(),
                    todayDateKey = c.getString(12).orEmpty(),
                    todaySteps = c.getInt(13).coerceAtLeast(0),
                    todayStepGoalRewarded = c.getInt(14) == 1,
                    todayStepBucketsAwarded = c.getInt(15).coerceAtLeast(0),
                    totalStepsLifetime = c.getLong(16).coerceAtLeast(0L),
                    totalQuestsCompleted = c.getInt(17).coerceAtLeast(0),
                    totalWorkoutsCompleted = c.getInt(18).coerceAtLeast(0),
                    highFormSessionsCount = c.getInt(19).coerceAtLeast(0),
                    vbtSessionsCount = c.getInt(20).coerceAtLeast(0),
                    exerciseTotals = totalsMap,
                    unlockedTitleIds = titles,
                    equippedTitleId = equippedId,
                    unlockedBadgeIds = badges,
                    todayQuests = quests,
                    recentDays = recentDays
                )
            }
        }.getOrNull()
    }

    fun saveProgressionState(state: AthleteProgressState) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val totalsJson = JSONObject().apply {
                state.exerciseTotals.forEach { (k, v) -> put(k, v) }
            }.toString()
            val titlesJson = JSONArray(state.unlockedTitleIds.toList()).toString()
            val badgesJson = JSONArray(state.unlockedBadgeIds.toList()).toString()
            val questsJson = JSONArray().apply {
                state.todayQuests.forEach { q ->
                    put(
                        JSONObject().apply {
                            put("id", q.id)
                            put("dateKey", q.dateKey)
                            put("titleFr", q.titleFr)
                            put("subtitleFr", q.subtitleFr)
                            put("exerciseId", q.exerciseType?.id ?: "")
                            put("modifierLabelFr", q.modifierLabelFr)
                            put("formatLabelFr", q.formatLabelFr)
                            put("targetAmount", q.targetAmount)
                            put("currentProgress", q.currentProgress)
                            put("unitFr", q.unitFr)
                            put("xpReward", q.xpReward)
                            put("attributeKey", q.attributeKey)
                            put("completed", q.completed)
                        }
                    )
                }
            }.toString()

            val cv = ContentValues().apply {
                put("id", 1)
                put("level", state.level)
                put("total_xp", state.totalXp)
                put("rank_code", state.rank.code)
                put("rank_xp", state.rankXp)
                put("attr_force", state.attributes.force)
                put("attr_endurance", state.attributes.endurance)
                put("attr_agility", state.attributes.agility)
                put("attr_vitality", state.attributes.vitality)
                put("attr_mastery", state.attributes.mastery)
                put("streak_days", state.streakDays)
                put("longest_streak", state.longestStreak)
                put("last_active_date", state.lastActiveDateKey)
                put("today_date", state.todayDateKey)
                put("today_steps", state.todaySteps)
                put("today_step_goal_rewarded", if (state.todayStepGoalRewarded) 1 else 0)
                put("today_step_buckets", state.todayStepBucketsAwarded)
                put("total_steps_lifetime", state.totalStepsLifetime)
                put("total_quests_completed", state.totalQuestsCompleted)
                put("total_workouts_completed", state.totalWorkoutsCompleted)
                put("high_form_sessions", state.highFormSessionsCount)
                put("vbt_sessions", state.vbtSessionsCount)
                put("exercise_totals_json", totalsJson)
                put("unlocked_titles_json", titlesJson)
                put("equipped_title_id", state.equippedTitleId)
                put("unlocked_badges_json", badgesJson)
                put("today_quests_json", questsJson)
            }
            db.insertWithOnConflict("athlete_progression", null, cv, SQLiteDatabase.CONFLICT_REPLACE)

            for (day in state.recentDays) {
                val dayCv = ContentValues().apply {
                    put("date_key", day.dateKey)
                    put("steps", day.steps)
                    put("goal_reached", if (day.goalReached) 1 else 0)
                    put("workout_done", if (day.workoutDone) 1 else 0)
                }
                db.insertWithOnConflict("daily_activity_log", null, dayCv, SQLiteDatabase.CONFLICT_REPLACE)
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
