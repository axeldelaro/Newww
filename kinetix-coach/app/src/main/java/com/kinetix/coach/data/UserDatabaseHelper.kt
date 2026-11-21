package com.kinetix.coach.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

class UserDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "kinetix_user.db"
        private const val DB_VERSION = 1
    }

    private val _sessions = MutableStateFlow<List<WorkoutSession>>(emptyList())
    val sessions: StateFlow<List<WorkoutSession>> = _sessions.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _profile = MutableStateFlow(AthleteProfile())
    val profile: StateFlow<AthleteProfile> = _profile.asStateFlow()

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
                notes TEXT NOT NULL
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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun refreshAll() {
        _sessions.value = loadSessions()
        _messages.value = loadMessages()
        _profile.value = loadProfile()
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
            "SELECT id, timestamp_ms, exercise_id, reps, duration_sec, avg_form_score, avg_rom_pct, top_faults_json, notes FROM workout_sessions ORDER BY timestamp_ms DESC LIMIT 100",
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
                        notes = c.getString(8)
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
}
