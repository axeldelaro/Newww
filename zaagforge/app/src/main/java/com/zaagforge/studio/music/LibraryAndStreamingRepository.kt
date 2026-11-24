package com.zaagforge.studio.music

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.zaagforge.studio.model.AudioTrackItem
import com.zaagforge.studio.model.DeezerSearchTrack
import com.zaagforge.studio.model.ParsedStreamingLink
import com.zaagforge.studio.model.ResolvedStreamingTrack
import com.zaagforge.studio.model.StreamingProvider
import com.zaagforge.studio.model.TrackSourceType
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer

/**
 * Unified local audio library + hybrid streaming bridge:
 * - Local MediaStore.Audio scanner (MP3, WAV, FLAC, M4A, OGG)
 * - 2 Built-in C++20 Demo Tracks (instant zero-setup remix testing)
 * - Deezer Public API search & official 30s MP3 preview downloader (no API key required)
 * - Spotify / SoundCloud / Deezer share-link parser & public oEmbed resolver with automatic
 *   local MP3 matching + 1-tap Deezer 30s preview fallback.
 */
object LibraryAndStreamingRepository {

    val builtInDemos: List<AudioTrackItem> = listOf(
        AudioTrackItem(
            id = "demo_synthpop_128",
            title = "Neon Skyline (Demo Vocal-Synth 128 BPM)",
            artist = "ZAAGFORGE Built-In",
            durationMs = 32_000L,
            sourceType = TrackSourceType.BUILTIN_DEMO,
            demoStyleIndex = 0
        ),
        AudioTrackItem(
            id = "demo_trap_140",
            title = "Midnight Voltage (Demo Dark Trap 140 BPM)",
            artist = "ZAAGFORGE Built-In",
            durationMs = 32_000L,
            sourceType = TrackSourceType.BUILTIN_DEMO,
            demoStyleIndex = 1
        )
    )

    fun scanLocalAudioTracks(context: Context): List<AudioTrackItem> {
        val result = ArrayList<AudioTrackItem>()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.RELATIVE_PATH
        )
        val selection = "${MediaStore.Audio.Media.DURATION} >= ?"
        val selectionArgs = arrayOf("10000")
        val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"

        runCatching {
            context.contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val relCol = c.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)

                while (c.moveToNext() && result.size < 250) {
                    val rel = if (relCol >= 0) c.getString(relCol).orEmpty() else ""
                    if (rel.contains("ZaagForge", ignoreCase = true)) continue
                    val id = c.getLong(idCol)
                    val title = c.getString(titleCol)?.takeIf { it.isNotBlank() } ?: "Audio #$id"
                    val rawArtist = c.getString(artistCol).orEmpty()
                    val artist = if (rawArtist.isBlank() || rawArtist == "<unknown>") "Artiste local" else rawArtist
                    val dur = c.getLong(durCol)
                    val uri = Uri.withAppendedPath(collection, id.toString())
                    result.add(
                        AudioTrackItem(
                            id = "local_$id",
                            title = title,
                            artist = artist,
                            durationMs = dur,
                            sourceType = TrackSourceType.LOCAL_MEDIASTORE,
                            uriString = uri.toString()
                        )
                    )
                }
            }
        }
        return result
    }

    fun searchDeezer(query: String, limit: Int = 15): List<DeezerSearchTrack> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val encoded = URLEncoder.encode(trimmed, "UTF-8")
        val jsonStr = httpGet("https://api.deezer.com/search?q=$encoded&limit=$limit") ?: return emptyList()
        val root = JSONObject(jsonStr)
        val data = root.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<DeezerSearchTrack>(data.length())
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val preview = item.optString("preview", "")
            if (preview.isBlank()) continue
            val artistObj = item.optJSONObject("artist")
            val albumObj = item.optJSONObject("album")
            out.add(
                DeezerSearchTrack(
                    id = item.optLong("id", i.toLong()),
                    title = item.optString("title", "Titre inconnu"),
                    artist = artistObj?.optString("name", "Artiste inconnu") ?: "Artiste inconnu",
                    album = albumObj?.optString("title", "") ?: "",
                    durationSec = item.optInt("duration", 30),
                    previewUrl = preview,
                    coverUrl = albumObj?.optString("cover_medium", "") ?: ""
                )
            )
        }
        return out
    }

    fun downloadPreviewToCache(context: Context, previewUrl: String, trackId: Long): File {
        val cacheDir = File(context.cacheDir, "deezer_previews").apply { mkdirs() }
        val target = File(cacheDir, "dz_$trackId.mp3")
        val conn = (URL(previewUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "ZaagForge/1.0")
        }
        try {
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("Téléchargement aperçu Deezer échoué (HTTP ${conn.responseCode}).")
            }
            conn.inputStream.use { inp ->
                FileOutputStream(target).use { out -> inp.copyTo(out) }
            }
        } finally {
            conn.disconnect()
        }
        return target
    }

    /** Pure-Kotlin testable URL extractor & provider classifier for Spotify / SoundCloud / Deezer links. */
    fun parseStreamingLink(rawText: String): ParsedStreamingLink? {
        val urlMatch = Regex("https?://\\S+").find(rawText.trim())?.value ?: return null
        val cleanUrl = urlMatch.trimEnd('.', ',', ';', ')', ']', '"', '\'')
        val lower = cleanUrl.lowercase()

        return when {
            lower.contains("open.spotify.com") || lower.contains("spotify.link") || lower.contains("spotify.com") -> {
                val trackId = Regex("/track/([A-Za-z0-9]+)").find(cleanUrl)?.groupValues?.getOrNull(1)
                ParsedStreamingLink(StreamingProvider.SPOTIFY, cleanUrl, trackId)
            }
            lower.contains("soundcloud.com") || lower.contains("on.soundcloud.com") -> {
                ParsedStreamingLink(StreamingProvider.SOUNDCLOUD, cleanUrl, null)
            }
            lower.contains("deezer.com") || lower.contains("deezer.page.link") -> {
                val trackId = Regex("/track/([0-9]+)").find(cleanUrl)?.groupValues?.getOrNull(1)
                ParsedStreamingLink(StreamingProvider.DEEZER, cleanUrl, trackId)
            }
            else -> ParsedStreamingLink(StreamingProvider.UNKNOWN, cleanUrl, null)
        }
    }

    /** Pure-Kotlin normalized fuzzy matcher to pair a shared Spotify/SoundCloud/Deezer track with a local MP3. */
    fun findBestLocalMatch(
        title: String,
        artist: String,
        localTracks: List<AudioTrackItem>
    ): AudioTrackItem? {
        val targetTokens = tokenize("$title $artist")
        val titleTokens = tokenize(title)
        if (titleTokens.isEmpty() || localTracks.isEmpty()) return null

        var bestItem: AudioTrackItem? = null
        var bestScore = 0f
        for (item in localTracks) {
            val candTokens = tokenize("${item.title} ${item.artist}")
            if (candTokens.isEmpty()) continue
            val titleHits = titleTokens.count { it in candTokens }.toFloat() / titleTokens.size
            val allHits = targetTokens.count { it in candTokens }.toFloat() / targetTokens.size.coerceAtLeast(1)
            val score = titleHits * 0.70f + allHits * 0.30f
            if (score > bestScore) {
                bestScore = score
                bestItem = item
            }
        }
        return if (bestScore >= 0.58f) bestItem else null
    }

    fun resolveStreamingLink(
        rawText: String,
        localTracks: List<AudioTrackItem>
    ): ResolvedStreamingTrack? {
        val parsed = parseStreamingLink(rawText) ?: return null
        var title = ""
        var artist = ""
        var deezerDirect: DeezerSearchTrack? = null

        when (parsed.provider) {
            StreamingProvider.DEEZER -> {
                val id = parsed.trackId
                if (!id.isNullOrBlank()) {
                    val jsonStr = httpGet("https://api.deezer.com/track/$id")
                    if (jsonStr != null) {
                        val obj = JSONObject(jsonStr)
                        title = obj.optString("title", "")
                        artist = obj.optJSONObject("artist")?.optString("name", "") ?: ""
                        val preview = obj.optString("preview", "")
                        if (preview.isNotBlank()) {
                            deezerDirect = DeezerSearchTrack(
                                id = obj.optLong("id", id.toLongOrNull() ?: 0L),
                                title = title.ifBlank { "Deezer #$id" },
                                artist = artist.ifBlank { "Deezer" },
                                album = obj.optJSONObject("album")?.optString("title", "") ?: "",
                                durationSec = obj.optInt("duration", 30),
                                previewUrl = preview,
                                coverUrl = obj.optJSONObject("album")?.optString("cover_medium", "") ?: ""
                            )
                        }
                    }
                }
            }
            StreamingProvider.SPOTIFY -> {
                val enc = URLEncoder.encode(parsed.cleanUrl, "UTF-8")
                val jsonStr = httpGet("https://open.spotify.com/oembed?url=$enc")
                if (jsonStr != null) {
                    val obj = JSONObject(jsonStr)
                    val rawTitle = obj.optString("title", "")
                    val author = obj.optString("author_name", "")
                    title = rawTitle
                    artist = author
                }
            }
            StreamingProvider.SOUNDCLOUD -> {
                val enc = URLEncoder.encode(parsed.cleanUrl, "UTF-8")
                val jsonStr = httpGet("https://soundcloud.com/oembed?format=json&url=$enc")
                if (jsonStr != null) {
                    val obj = JSONObject(jsonStr)
                    val rawTitle = obj.optString("title", "")
                    val author = obj.optString("author_name", "")
                    // SoundCloud oEmbed often returns "Track by Artist"
                    val cleanedTitle = rawTitle.substringBefore(" by ").trim()
                    title = cleanedTitle.ifBlank { rawTitle }
                    artist = author
                }
            }
            StreamingProvider.UNKNOWN -> Unit
        }

        if (title.isBlank()) return null

        val localMatch = findBestLocalMatch(title, artist, localTracks)
        val deezerFallback = deezerDirect ?: searchDeezer("$title $artist".trim(), limit = 3).firstOrNull()

        return ResolvedStreamingTrack(
            provider = parsed.provider,
            title = title,
            artist = artist.ifBlank { parsed.provider.name },
            sourceUrl = parsed.cleanUrl,
            matchedLocalTrack = localMatch,
            deezerFallback = deezerFallback
        )
    }

    internal fun tokenize(text: String): Set<String> {
        val norm = Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .lowercase()
            .replace(Regex("\\([^)]*\\)|\\[[^]]*]"), " ")
            .replace(Regex("[^a-z0-9 ]"), " ")
        return norm.split(Regex("\\s+"))
            .filter { it.length >= 2 && it !in STOP_WORDS }
            .toSet()
    }

    private val STOP_WORDS = setOf("feat", "ft", "official", "video", "audio", "remix", "edit", "radio", "the", "and")

    private fun httpGet(urlStr: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 10_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "ZaagForge/1.0")
            }
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
