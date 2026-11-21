package com.kinetix.coach.ui.encyclopedia

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kinetix.coach.KinetixApp
import com.kinetix.coach.data.KnowledgeCategory
import com.kinetix.coach.data.KnowledgeEntry
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EncyclopediaUiState(
    val query: String = "",
    val selectedCategory: KnowledgeCategory = KnowledgeCategory.ALL,
    val entries: List<KnowledgeEntry> = emptyList(),
    val categoryCounts: Map<String, Int> = emptyMap(),
    val isLoading: Boolean = true,
    val lastSearchMs: Long = 0L,
    val selectedEntry: KnowledgeEntry? = null
)

class EncyclopediaViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as KinetixApp

    private val _uiState = MutableStateFlow(EncyclopediaUiState())
    val uiState: StateFlow<EncyclopediaUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            val counts = runCatching { app.ragEngine.getCategoryCounts() }.getOrDefault(emptyMap())
            val initial = runCatching {
                app.ragEngine.searchEncyclopedia(query = "", categoryFilter = null, limit = 80)
            }.getOrDefault(emptyList())
            _uiState.update {
                it.copy(
                    categoryCounts = counts,
                    entries = initial,
                    isLoading = false
                )
            }
        }
    }

    fun onQueryChanged(newQuery: String) {
        _uiState.update { it.copy(query = newQuery) }
        triggerSearch(debounceMs = 110L)
    }

    fun onCategorySelected(category: KnowledgeCategory) {
        if (_uiState.value.selectedCategory == category) return
        _uiState.update { it.copy(selectedCategory = category) }
        triggerSearch(debounceMs = 0L)
    }

    fun clearQuery() {
        _uiState.update { it.copy(query = "") }
        triggerSearch(debounceMs = 0L)
    }

    fun selectEntry(entry: KnowledgeEntry) {
        _uiState.update { it.copy(selectedEntry = entry) }
    }

    fun dismissEntry() {
        _uiState.update { it.copy(selectedEntry = null) }
    }

    private fun triggerSearch(debounceMs: Long) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            if (debounceMs > 0L) delay(debounceMs)
            val currentQuery = _uiState.value.query.trim()
            val catKey = _uiState.value.selectedCategory.dbKey.takeUnless { it == "all" }
            val startNs = System.nanoTime()
            val results = runCatching {
                app.ragEngine.searchEncyclopedia(
                    query = currentQuery,
                    categoryFilter = catKey,
                    limit = 80
                )
            }.getOrDefault(emptyList())
            val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
            _uiState.update {
                it.copy(
                    entries = results,
                    isLoading = false,
                    lastSearchMs = if (currentQuery.isNotBlank()) elapsedMs else 0L
                )
            }
        }
    }
}
