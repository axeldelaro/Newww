package com.strikecam.arcade.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pure local SQLite persistence for StrikeCam Arcade high scores, ranks, combos, and career stats.
 */
class ArcadeDatabase(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "strikecam_arcade.db"
        private const val DB_VERSION = 1
        private const val TABLE_SCORES = "arcade_scores"
    }

    private val _recentScores = MutableStateFlow<List<ArcadeScoreRecord>>(emptyList())
    val recentScores: StateFlow<List<ArcadeScoreRecord>> = _recentScores.asStateFlow()

    private val _careerStats = MutableStateFlow(CareerStats())
    val careerStats: StateFlow<CareerStats> = _careerStats.asStateFlow()

    init {
        refreshFlows()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_SCORES (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                track_id TEXT NOT NULL,
                track_title TEXT NOT NULL,
                source_type TEXT NOT NULL,
                mode TEXT NOT NULL,
                difficulty TEXT NOT NULL,
                score INTEGER NOT NULL,
                rank TEXT NOT NULL,
                max_combo INTEGER NOT NULL,
                accuracy_pct REAL NOT NULL,
                perfect_count INTEGER NOT NULL,
                great_count INTEGER NOT NULL,
                good_count INTEGER NOT NULL,
                dodge_count INTEGER NOT NULL,
                miss_count INTEGER NOT NULL,
                avg_reaction_ms REAL NOT NULL,
                total_punches INTEGER NOT NULL,
                calories_kcal REAL NOT NULL,
                timestamp_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_track_diff ON $TABLE_SCORES(track_id, difficulty, score DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun insertScore(record: ArcadeScoreRecord): Long {
        val cv = ContentValues().apply {
            put("track_id", record.trackId)
            put("track_title", record.trackTitle)
            put("source_type", record.sourceType)
            put("mode", record.mode.name)
            put("difficulty", record.difficulty.name)
            put("score", record.score)
            put("rank", record.rank.name)
            put("max_combo", record.maxCombo)
            put("accuracy_pct", record.accuracyPct)
            put("perfect_count", record.perfectCount)
            put("great_count", record.greatCount)
            put("good_count", record.goodCount)
            put("dodge_count", record.dodgeCount)
            put("miss_count", record.missCount)
            put("avg_reaction_ms", record.avgReactionMs)
            put("total_punches", record.totalPunches)
            put("calories_kcal", record.caloriesKcal)
            put("timestamp_ms", record.timestampMs)
        }
        val id = writableDatabase.insert(TABLE_SCORES, null, cv)
        refreshFlows()
        return id
    }

    fun bestScoreFor(trackId: String, difficulty: Difficulty): ArcadeScoreRecord? {
        readableDatabase.rawQuery(
            "SELECT * FROM $TABLE_SCORES WHERE track_id = ? AND difficulty = ? ORDER BY score DESC LIMIT 1",
            arrayOf(trackId, difficulty.name)
        ).use { c ->
            return if (c.moveToFirst()) readRecord(c) else null
        }
    }

    private fun refreshFlows() {
        val db = readableDatabase
        val list = ArrayList<ArcadeScoreRecord>()
        db.rawQuery("SELECT * FROM $TABLE_SCORES ORDER BY timestamp_ms DESC LIMIT 40", null).use { c ->
            while (c.moveToNext()) {
                list.add(readRecord(c))
            }
        }
        _recentScores.value = list

        var sessions = 0
        var totalPunches = 0
        var totalKcal = 0f
        var bestScore = 0
        var bestCombo = 0
        var bestRank: ArcadeRank? = null

        db.rawQuery(
            "SELECT COUNT(*), COALESCE(SUM(total_punches),0), COALESCE(SUM(calories_kcal),0), COALESCE(MAX(score),0), COALESCE(MAX(max_combo),0) FROM $TABLE_SCORES",
            null
        ).use { c ->
            if (c.moveToFirst()) {
                sessions = c.getInt(0)
                totalPunches = c.getInt(1)
                totalKcal = c.getFloat(2)
                bestScore = c.getInt(3)
                bestCombo = c.getInt(4)
            }
        }

        if (list.isNotEmpty()) {
            bestRank = list.minByOrNull { it.rank.ordinal }?.rank
        }

        _careerStats.value = CareerStats(
            totalSessions = sessions,
            totalPunches = totalPunches,
            totalCaloriesKcal = totalKcal,
            bestScore = bestScore,
            bestCombo = bestCombo,
            bestRank = bestRank
        )
    }

    private fun readRecord(c: android.database.Cursor): ArcadeScoreRecord = ArcadeScoreRecord(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        trackId = c.getString(c.getColumnIndexOrThrow("track_id")),
        trackTitle = c.getString(c.getColumnIndexOrThrow("track_title")),
        sourceType = c.getString(c.getColumnIndexOrThrow("source_type")),
        mode = runCatching { GameMode.valueOf(c.getString(c.getColumnIndexOrThrow("mode"))) }.getOrDefault(GameMode.SOLO_ARCADE),
        difficulty = runCatching { Difficulty.valueOf(c.getString(c.getColumnIndexOrThrow("difficulty"))) }.getOrDefault(Difficulty.PRO),
        score = c.getInt(c.getColumnIndexOrThrow("score")),
        rank = runCatching { ArcadeRank.valueOf(c.getString(c.getColumnIndexOrThrow("rank"))) }.getOrDefault(ArcadeRank.C),
        maxCombo = c.getInt(c.getColumnIndexOrThrow("max_combo")),
        accuracyPct = c.getFloat(c.getColumnIndexOrThrow("accuracy_pct")),
        perfectCount = c.getInt(c.getColumnIndexOrThrow("perfect_count")),
        greatCount = c.getInt(c.getColumnIndexOrThrow("great_count")),
        goodCount = c.getInt(c.getColumnIndexOrThrow("good_count")),
        dodgeCount = c.getInt(c.getColumnIndexOrThrow("dodge_count")),
        missCount = c.getInt(c.getColumnIndexOrThrow("miss_count")),
        avgReactionMs = c.getFloat(c.getColumnIndexOrThrow("avg_reaction_ms")),
        totalPunches = c.getInt(c.getColumnIndexOrThrow("total_punches")),
        caloriesKcal = c.getFloat(c.getColumnIndexOrThrow("calories_kcal")),
        timestampMs = c.getLong(c.getColumnIndexOrThrow("timestamp_ms"))
    )
}
