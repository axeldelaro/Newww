package com.strikecam.arcade.music

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import com.strikecam.arcade.data.ArcadeTrack
import com.strikecam.arcade.data.StreamingPlatform
import com.strikecam.arcade.data.TrackSource
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class ParsedStreamUrl(
    val platform: StreamingPlatform,
    val cleanUrl: String,
    val trackId: String? = null
)

/**
 * Discovers playable music across all 3 StrikeCam Arcade sources:
 * 1. Local MP3/WAV/FLAC + automatic priority discovery of ZAAGFORGE remixes in `Music/ZaagForge/`
 * 2. Deezer Public API search + 30s official MP3 preview download for sample-accurate FFT beatmaps
 * 3. Spotify / Deezer / SoundCloud share-link resolver + external app launcher for Live Sync Mode
 */
object TrackDiscoveryRepository {

    fun scanLocalTracks(context: Context): Pair<List<ArcadeTrack>, List<ArcadeTrack>> {
        val zaagRemixes = ArrayList<ArcadeTrack>()
        val otherLocals = ArrayList<ArcadeTrack>()

        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.DISPLAY_NAME
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
                val dispCol = c.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)

                while (c.moveToNext() && (zaagRemixes.size + otherLocals.size) < 250) {
                    val id = c.getLong(idCol)
                    val title = c.getString(titleCol)?.takeIf { it.isNotBlank() }
                        ?: (if (dispCol >= 0) c.getString(dispCol)?.substringBeforeLast('.') else null)
                        ?: "Piste #$id"
                    val rawArtist = c.getString(artistCol).orEmpty()
                    val artist = if (rawArtist.isBlank() || rawArtist == "<unknown>") "Local" else rawArtist
                    val dur = c.getLong(durCol)
                    val rel = if (relCol >= 0) c.getString(relCol).orEmpty() else ""
                    val disp = if (dispCol >= 0) c.getString(dispCol).orEmpty() else ""
                    val isZaag = rel.contains("ZaagForge", ignoreCase = true) ||
                        disp.contains("ZaagForge", ignoreCase = true) ||
                        disp.contains("UOK-", ignoreCase = true) ||
                        disp.contains("KRACH-", ignoreCase = true) ||
                        disp.contains("ZAAG-", ignoreCase = true)

                    val bpmHint = extractBpmFromFileName(disp.ifBlank { title }) ?: if (isZaag) 200f else 138f
                    val uri = Uri.withAppendedPath(collection, id.toString())
                    val track = ArcadeTrack(
                        id = if (isZaag) "zaag_$id" else "local_$id",
                        title = title,
                        artist = if (isZaag) "REMIX ZAAGFORGE" else artist,
                        bpm = bpmHint,
                        durationMs = dur,
                        source = if (isZaag) TrackSource.LOCAL_ZAAGFORGE else TrackSource.LOCAL_FILE,
                        genreTag = if (isZaag) "REMIX ZAAGFORGE" else "MP3 LOCAL",
                        contentUri = uri.toString(),
                        isZaagForgeRemix = isZaag
                    )
                    if (isZaag) zaagRemixes.add(track) else otherLocals.add(track)
                }
            }
        }
        return zaagRemixes to otherLocals
    }

    internal fun extractBpmFromFileName(name: String): Float? {
        val m = Regex("(\\d{2,3})\\s*BPM", RegexOption.IGNORE_CASE).find(name) ?: return null
        val v = m.groupValues[1].toFloatOrNull() ?: return null
        return if (v in 70f..250f) v else null
    }

    fun searchDeezerTracks(query: String, limit: Int = 15): List<ArcadeTrack> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val enc = URLEncoder.encode(q, "UTF-8")
        val jsonStr = httpGet("https://api.deezer.com/search?q=$enc&limit=$limit") ?: return emptyList()
        val data = JSONObject(jsonStr).optJSONArray("data") ?: return emptyList()
        val out = ArrayList<ArcadeTrack>(data.length())
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val preview = item.optString("preview", "")
            if (preview.isBlank()) continue
            val id = item.optLong("id", i.toLong())
            val title = item.optString("title", "Titre")
            val artist = item.optJSONObject("artist")?.optString("name", "Artiste") ?: "Artiste"
            val cover = item.optJSONObject("album")?.optString("cover_medium", "") ?: ""
            val bpm = item.optDouble("bpm", 0.0).toFloat().let { if (it in 75f..235f) it else 140f }
            out.add(
                ArcadeTrack(
                    id = "dz_$id",
                    title = title,
                    artist = artist,
                    bpm = bpm,
                    durationMs = 30_000L,
                    source = TrackSource.DEEZER_PREVIEW,
                    genreTag = "DEEZER 30S FFT",
                    previewUrl = preview,
                    externalUrl = item.optString("link", ""),
                    coverUrl = cover,
                    streamingPlatform = StreamingPlatform.DEEZER
                )
            )
        }
        return out
    }

    fun downloadPreviewFile(context: Context, track: ArcadeTrack): File {
        val url = track.previewUrl ?: throw IllegalStateException("URL d'aperçu manquante.")
        val dir = File(context.cacheDir, "strikecam_previews").apply { mkdirs() }
        val file = File(dir, "${track.id}.mp3")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "StrikeCamArcade/1.0")
        }
        try {
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${conn.responseCode}")
            }
            conn.inputStream.use { inp ->
                FileOutputStream(file).use { out -> inp.copyTo(out) }
            }
        } finally {
            conn.disconnect()
        }
        return file
    }

    fun parseStreamingUrl(rawText: String): ParsedStreamUrl? {
        val m = Regex("https?://\\S+").find(rawText.trim())?.value ?: return null
        val clean = m.trimEnd('.', ',', ';', ')', ']', '"', '\'')
        val lower = clean.lowercase()
        return when {
            lower.contains("spotify.com") || lower.contains("spotify.link") -> {
                val id = Regex("/track/([A-Za-z0-9]+)").find(clean)?.groupValues?.getOrNull(1)
                ParsedStreamUrl(StreamingPlatform.SPOTIFY, clean, id)
            }
            lower.contains("deezer.com") || lower.contains("deezer.page.link") -> {
                val id = Regex("/track/([0-9]+)").find(clean)?.groupValues?.getOrNull(1)
                ParsedStreamUrl(StreamingPlatform.DEEZER, clean, id)
            }
            lower.contains("soundcloud.com") -> {
                ParsedStreamUrl(StreamingPlatform.SOUNDCLOUD, clean, null)
            }
            else -> ParsedStreamUrl(StreamingPlatform.UNKNOWN, clean, null)
        }
    }

    fun resolveStreamingLiveTrack(rawText: String): ArcadeTrack? {
        val parsed = parseStreamingUrl(rawText) ?: return null
        var title = ""
        var artist = ""
        var bpm = 140f
        var previewUrl: String? = null

        when (parsed.platform) {
            StreamingPlatform.DEEZER -> {
                val id = parsed.trackId
                if (!id.isNullOrBlank()) {
                    httpGet("https://api.deezer.com/track/$id")?.let { json ->
                        val obj = JSONObject(json)
                        title = obj.optString("title", "")
                        artist = obj.optJSONObject("artist")?.optString("name", "") ?: ""
                        val apiBpm = obj.optDouble("bpm", 0.0).toFloat()
                        if (apiBpm in 75f..235f) bpm = apiBpm
                        previewUrl = obj.optString("preview", "").ifBlank { null }
                    }
                }
            }
            StreamingPlatform.SPOTIFY -> {
                val enc = URLEncoder.encode(parsed.cleanUrl, "UTF-8")
                httpGet("https://open.spotify.com/oembed?url=$enc")?.let { json ->
                    val obj = JSONObject(json)
                    title = obj.optString("title", "")
                    artist = obj.optString("author_name", "Spotify")
                }
            }
            StreamingPlatform.SOUNDCLOUD -> {
                val enc = URLEncoder.encode(parsed.cleanUrl, "UTF-8")
                httpGet("https://soundcloud.com/oembed?format=json&url=$enc")?.let { json ->
                    val obj = JSONObject(json)
                    val raw = obj.optString("title", "")
                    title = raw.substringBefore(" by ").trim().ifBlank { raw }
                    artist = obj.optString("author_name", "SoundCloud")
                }
            }
            StreamingPlatform.UNKNOWN -> Unit
        }

        if (title.isBlank()) {
            title = "Live ${parsed.platform.label}"
            artist = parsed.platform.label
        } else if (parsed.platform != StreamingPlatform.DEEZER) {
            // Try enriching BPM + optional 30s preview via public Deezer lookup
            val match = searchDeezerTracks("$title $artist", limit = 1).firstOrNull()
            if (match != null) {
                if (match.bpm in 75f..235f) bpm = match.bpm
                previewUrl = match.previewUrl
            }
        }

        return ArcadeTrack(
            id = "live_${parsed.platform.name}_${System.currentTimeMillis()}",
            title = title,
            artist = artist,
            bpm = bpm,
            durationMs = 180_000L,
            source = TrackSource.STREAMING_LIVE_SYNC,
            genreTag = "LIVE ${parsed.platform.label.uppercase()}",
            previewUrl = previewUrl,
            externalUrl = parsed.cleanUrl,
            streamingPlatform = parsed.platform
        )
    }

    fun launchExternalStreamingApp(context: Context, platform: StreamingPlatform, url: String? = null) {
        val intent = if (!url.isNullOrBlank()) {
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                if (platform.packageName.isNotBlank()) setPackage(platform.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else if (platform.packageName.isNotBlank()) {
            context.packageManager.getLaunchIntentForPackage(platform.packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            null
        }
        runCatching {
            if (intent != null) context.startActivity(intent)
        }.onFailure {
            if (!url.isNullOrBlank()) {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }
    }

    private fun httpGet(urlStr: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 10_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "StrikeCamArcade/1.0")
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
