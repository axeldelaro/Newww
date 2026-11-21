package com.kinetix.coach.rag

import com.kinetix.coach.data.KnowledgeBaseHelper
import com.kinetix.coach.data.KnowledgeEntry
import com.kinetix.coach.data.RagHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RagEngine(private val kb: KnowledgeBaseHelper) {

    @Volatile
    private var cachedSynonyms: Map<String, List<String>>? = null
    private val synLock = Any()

    private fun getSynonyms(): Map<String, List<String>> {
        cachedSynonyms?.let { return it }
        synchronized(synLock) {
            cachedSynonyms?.let { return it }
            val loaded = kb.loadSynonymsMap()
            cachedSynonyms = loaded
            return loaded
        }
    }

    suspend fun retrieve(
        query: String,
        topK: Int = 4,
        categoryFilter: String? = null
    ): List<RagHit> = withContext(Dispatchers.IO) {
        val tokens = RagQueryProcessor.extractTokens(query)
        if (tokens.isEmpty()) return@withContext emptyList()

        val synonyms = getSynonyms()
        val strictExpr = RagQueryProcessor.buildFtsMatchExpression(query, synonyms, strictAnd = true)
        val broadExpr = RagQueryProcessor.buildFtsMatchExpression(query, synonyms, strictAnd = false)

        val candidates = mutableListOf<RagHit>()
        if (strictExpr.isNotBlank()) {
            candidates.addAll(kb.searchFts(strictExpr, categoryFilter, limit = maxOf(20, topK * 5)))
        }
        if (candidates.size < topK && broadExpr.isNotBlank() && broadExpr != strictExpr) {
            candidates.addAll(kb.searchFts(broadExpr, categoryFilter, limit = maxOf(24, topK * 6)))
        }
        if (candidates.isEmpty()) {
            candidates.addAll(kb.searchLikeFallback(tokens, categoryFilter, limit = maxOf(15, topK * 3)))
        }

        RagQueryProcessor.rerankHits(query, candidates, synonyms, topK)
    }

    suspend fun searchEncyclopedia(
        query: String,
        categoryFilter: String?,
        limit: Int = 60
    ): List<KnowledgeEntry> = withContext(Dispatchers.IO) {
        if (query.isBlank()) {
            return@withContext kb.browse(categoryFilter, limit = limit, offset = 0)
        }
        retrieve(query = query, topK = limit, categoryFilter = categoryFilter).map { it.entry }
    }

    suspend fun getEntryById(id: Long): KnowledgeEntry? = withContext(Dispatchers.IO) {
        kb.getById(id)
    }

    suspend fun getCategoryCounts(): Map<String, Int> = withContext(Dispatchers.IO) {
        kb.getCategoryCounts()
    }
}
