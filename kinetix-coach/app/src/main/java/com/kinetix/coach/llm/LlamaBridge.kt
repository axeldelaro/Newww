package com.kinetix.coach.llm

fun interface TokenCallback {
    /** Return true to continue generation, false to stop immediately. */
    fun onToken(piece: String): Boolean
}

object LlamaBridge {
    @Volatile
    var isNativeLoaded: Boolean = false
        private set

    init {
        try {
            System.loadLibrary("kinetix_llama")
            isNativeLoaded = true
        } catch (t: Throwable) {
            isNativeLoaded = false
        }
    }

    external fun nativeInitBackend()

    external fun nativeLoadModel(modelPath: String, nCtx: Int, nThreads: Int): Long

    external fun nativeUpdateThreads(handle: Long, nThreads: Int)

    external fun nativeFormatChat(handle: Long, roles: Array<String>, contents: Array<String>): String?

    external fun nativeGenerate(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        temp: Float,
        topP: Float,
        topK: Int,
        repeatPenalty: Float,
        callback: TokenCallback
    ): Int

    external fun nativeCancel(handle: Long)

    external fun nativeUnloadModel(handle: Long)
}
