package com.newww.auracompanion.data

import android.content.Context
import com.newww.auracompanion.model.AuraAppState
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class AuraRepository(context: Context) {

    private val file = File(context.filesDir, "aura_companion_state_v1.json")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    @Synchronized
    fun load(): AuraAppState {
        if (!file.exists()) return AuraAppState()
        return runCatching {
            val loaded = json.decodeFromString<AuraAppState>(file.readText())
            if (loaded.characters.isEmpty()) AuraAppState() else loaded
        }.getOrElse { AuraAppState() }
    }

    @Synchronized
    fun save(state: AuraAppState) {
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
