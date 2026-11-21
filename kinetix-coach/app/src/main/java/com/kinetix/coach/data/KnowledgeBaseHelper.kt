package com.kinetix.coach.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File
import java.io.FileOutputStream

class KnowledgeBaseHelper(private val context: Context) {

    companion object {
        private const val TAG = "KinetixKB"
        private const val ASSET_DB_NAME = "knowledge_base.db"
        private const val DB_FILE_NAME = "kinetix_knowledge_v1.db"
        private const val COPY_BUFFER_BYTES = 256 * 1024
    }

    @Volatile
    private var db: SQLiteDatabase? = null
    private val lock = Any()

    private fun ensureOpen(): SQLiteDatabase {
        db?.let { if (it.isOpen) return it }
        synchronized(lock) {
            db?.let { if (it.isOpen) return it }
            val dbFile = context.getDatabasePath(DB_FILE_NAME)
            dbFile.parentFile?.mkdirs()

            val expectedSize = try {
                context.assets.openFd(ASSET_DB_NAME).use { it.length }
            } catch (_: Exception) {
                -1L
            }

            val needsCopy = !dbFile.exists() ||
                (expectedSize > 0 && dbFile.length() != expectedSize) ||
                dbFile.length() < 100_000L

            if (needsCopy) {
                copyAssetDatabase(dbFile)
            }

            val opened = SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            )
            db = opened
            return opened
        }
    }

    private fun copyAssetDatabase(target: File) {
        val temp = File(target.parentFile, "${target.name}.tmp")
        try {
            context.assets.open(ASSET_DB_NAME).use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            }
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            Log.i(TAG, "Knowledge base extracted: ${target.length()} bytes")
        } catch (e: Exception) {
            temp.delete()
            Log.e(TAG, "Failed extracting knowledge base", e)
            throw e
        }
    }

    fun getCategoryCounts(): Map<String, Int> {
        val database = ensureOpen()
        val result = linkedMapOf<String, Int>()
        var total = 0
        database.rawQuery("SELECT category, COUNT(*) FROM entries GROUP BY category", null).use { c ->
            while (c.moveToNext()) {
                val cat = c.getString(0)
                val cnt = c.getInt(1)
                result[cat] = cnt
                total += cnt
            }
        }
        result["all"] = total
        return result
    }

    fun getTotalCount(): Int {
        val database = ensureOpen()
        database.rawQuery("SELECT COUNT(*) FROM entries", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    fun loadSynonymsMap(): Map<String, List<String>> {
        val database = ensureOpen()
        val map = mutableMapOf<String, MutableList<String>>()
        database.rawQuery("SELECT term, expansion FROM synonyms", null).use { c ->
            while (c.moveToNext()) {
                val term = c.getString(0)
                val exp = c.getString(1).split(' ').filter { it.length >= 2 }
                map.getOrPut(term) { mutableListOf() }.addAll(exp)
            }
        }
        return map
    }

    fun getById(id: Long): KnowledgeEntry? {
        val database = ensureOpen()
        database.rawQuery(
            "SELECT id, slug, category, subcategory, title, summary, body, tags, metadata_json FROM entries WHERE id = ? LIMIT 1",
            arrayOf(id.toString())
        ).use { c ->
            return if (c.moveToFirst()) cursorToEntry(c) else null
        }
    }

    fun browse(category: String?, limit: Int = 60, offset: Int = 0): List<KnowledgeEntry> {
        val database = ensureOpen()
        val list = ArrayList<KnowledgeEntry>(limit)
        val sql: String
        val args: Array<String>
        if (category.isNullOrBlank() || category == "all") {
            sql = "SELECT id, slug, category, subcategory, title, summary, body, tags, metadata_json FROM entries ORDER BY id ASC LIMIT ? OFFSET ?"
            args = arrayOf(limit.toString(), offset.toString())
        } else {
            sql = "SELECT id, slug, category, subcategory, title, summary, body, tags, metadata_json FROM entries WHERE category = ? ORDER BY id ASC LIMIT ? OFFSET ?"
            args = arrayOf(category, limit.toString(), offset.toString())
        }
        database.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
                list.add(cursorToEntry(c))
            }
        }
        return list
    }

    fun searchFts(ftsMatchExpr: String, category: String? = null, limit: Int = 25): List<RagHit> {
        if (ftsMatchExpr.isBlank()) return emptyList()
        val database = ensureOpen()
        val hits = ArrayList<RagHit>(limit)
        val sql: String
        val args: Array<String>
        if (category.isNullOrBlank() || category == "all") {
            sql = """
                SELECT e.id, e.slug, e.category, e.subcategory, e.title, e.summary, e.body, e.tags, e.metadata_json,
                       bm25(entries_fts, 8.0, 4.0, 1.5, 5.0, 3.0, 2.0, 2.0) AS rank_score
                FROM entries_fts
                JOIN entries e ON e.id = entries_fts.rowid
                WHERE entries_fts MATCH ?
                ORDER BY rank_score ASC
                LIMIT ?
            """.trimIndent()
            args = arrayOf(ftsMatchExpr, limit.toString())
        } else {
            sql = """
                SELECT e.id, e.slug, e.category, e.subcategory, e.title, e.summary, e.body, e.tags, e.metadata_json,
                       bm25(entries_fts, 8.0, 4.0, 1.5, 5.0, 3.0, 2.0, 2.0) AS rank_score
                FROM entries_fts
                JOIN entries e ON e.id = entries_fts.rowid
                WHERE entries_fts MATCH ? AND e.category = ?
                ORDER BY rank_score ASC
                LIMIT ?
            """.trimIndent()
            args = arrayOf(ftsMatchExpr, category, limit.toString())
        }
        try {
            database.rawQuery(sql, args).use { c ->
                while (c.moveToNext()) {
                    val entry = cursorToEntry(c)
                    val bm25Raw = c.getDouble(9) // SQLite FTS5 bm25 is negative (more negative = better)
                    hits.add(RagHit(entry = entry, score = -bm25Raw))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "FTS query fallback triggered for expr='$ftsMatchExpr': ${e.message}")
        }
        return hits
    }

    fun searchLikeFallback(tokens: List<String>, category: String? = null, limit: Int = 15): List<RagHit> {
        if (tokens.isEmpty()) return emptyList()
        val database = ensureOpen()
        val first = "%${tokens.first()}%"
        val list = ArrayList<RagHit>(limit)
        val sql: String
        val args: Array<String>
        if (category.isNullOrBlank() || category == "all") {
            sql = """
                SELECT id, slug, category, subcategory, title, summary, body, tags, metadata_json
                FROM entries
                WHERE search_tokens LIKE ? OR title LIKE ?
                LIMIT ?
            """.trimIndent()
            args = arrayOf(first, first, limit.toString())
        } else {
            sql = """
                SELECT id, slug, category, subcategory, title, summary, body, tags, metadata_json
                FROM entries
                WHERE category = ? AND (search_tokens LIKE ? OR title LIKE ?)
                LIMIT ?
            """.trimIndent()
            args = arrayOf(category, first, first, limit.toString())
        }
        database.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
                list.add(RagHit(cursorToEntry(c), 1.0))
            }
        }
        return list
    }

    private fun cursorToEntry(c: Cursor): KnowledgeEntry = KnowledgeEntry(
        id = c.getLong(0),
        slug = c.getString(1),
        category = c.getString(2),
        subcategory = c.getString(3),
        title = c.getString(4),
        summary = c.getString(5),
        body = c.getString(6),
        tags = c.getString(7),
        metadataJson = c.getString(8)
    )
}
