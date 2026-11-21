package com.newww.luminaterrarium.data

import android.content.Context
import com.newww.luminaterrarium.model.TerrariumAppState
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class TerrariumRepository(context: Context) {

    private val file = File(context.filesDir, "terrarium_state_v1.json")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    @Synchronized
    fun load(): TerrariumAppState {
        if (!file.exists()) return TerrariumAppState()
        return runCatching {
            json.decodeFromString<TerrariumAppState>(file.readText())
        }.getOrElse { TerrariumAppState() }
    }

    @Synchronized
    fun save(state: TerrariumAppState) {
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
