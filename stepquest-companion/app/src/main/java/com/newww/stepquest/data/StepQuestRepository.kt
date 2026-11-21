package com.newww.stepquest.data

import android.content.Context
import com.newww.stepquest.model.StepQuestState
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class StepQuestRepository(context: Context) {

    private val file = File(context.filesDir, "stepquest_state_v1.json")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    @Synchronized
    fun load(): StepQuestState {
        if (!file.exists()) return StepQuestState()
        return runCatching {
            json.decodeFromString<StepQuestState>(file.readText())
        }.getOrElse { StepQuestState() }
    }

    @Synchronized
    fun save(state: StepQuestState) {
        runCatching {
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(state))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }
}
