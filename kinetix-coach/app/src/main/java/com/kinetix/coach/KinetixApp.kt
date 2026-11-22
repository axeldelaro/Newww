package com.kinetix.coach

import android.app.Application
import com.kinetix.coach.data.KnowledgeBaseHelper
import com.kinetix.coach.data.ModelAssetManager
import com.kinetix.coach.data.ModelPrepareState
import com.kinetix.coach.data.UserDatabaseHelper
import com.kinetix.coach.llm.LlamaEngine
import com.kinetix.coach.progression.ProgressionRepository
import com.kinetix.coach.rag.RagEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class KinetixApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var kbHelper: KnowledgeBaseHelper
        private set

    lateinit var userDb: UserDatabaseHelper
        private set

    lateinit var progressionRepo: ProgressionRepository
        private set

    lateinit var ragEngine: RagEngine
        private set

    lateinit var modelAssetManager: ModelAssetManager
        private set

    lateinit var llamaEngine: LlamaEngine
        private set

    override fun onCreate() {
        super.onCreate()
        kbHelper = KnowledgeBaseHelper(this)
        userDb = UserDatabaseHelper(this)
        progressionRepo = ProgressionRepository(this, userDb, appScope)
        ragEngine = RagEngine(kbHelper)
        modelAssetManager = ModelAssetManager(this, userDb)
        llamaEngine = LlamaEngine()

        // Warm up SQLite KB copy & extract embedded Gemma-3 1B GGUF on background IO immediately
        appScope.launch {
            runCatching { kbHelper.getTotalCount() }
            val prep = modelAssetManager.ensureActiveModelReady()
            if (prep is ModelPrepareState.Ready) {
                val savedThreads = userDb.getPref("llm_threads", LlamaEngine.recommendedThreads().toString())
                    .toIntOrNull() ?: LlamaEngine.recommendedThreads()
                llamaEngine.loadModel(prep.modelPath, prep.displayName, threads = savedThreads)
            }
        }
    }
}
