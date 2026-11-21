package com.astropro.xiaomi14u.storage

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.astropro.xiaomi14u.camera.AstroSessionItem
import com.astropro.xiaomi14u.camera.CalibrationKind
import com.astropro.xiaomi14u.camera.CalibrationMasterInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Persistent local storage for lossless .astro32 stacking sessions, calibration masters (.cal32),
 * and MediaStore export for DNG, 16-bit TIFF, 32-bit FITS, EXIF-tagged JPEG, and HAL JSON reports.
 */
class AstroSessionStore(private val context: Context) {

    private val sessionsDir = File(context.filesDir, "sessions").apply { mkdirs() }
    private val calibrationDir = File(context.filesDir, "calibration").apply { mkdirs() }
    private val exportCacheDir = File(context.filesDir, "exports").apply { mkdirs() }

    private val sessionsIndexFile = File(sessionsDir, "index.json")
    private val calibrationIndexFile = File(calibrationDir, "index.json")

    fun nowIsoUtc(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }

    fun nowFileStamp(): String {
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        return sdf.format(Date())
    }

    fun newAstro32File(prefix: String): File = File(sessionsDir, "${prefix}_${nowFileStamp()}.astro32")
    fun newThumbFile(prefix: String): File = File(sessionsDir, "${prefix}_${nowFileStamp()}_thumb.jpg")
    fun newCalibrationFile(kind: CalibrationKind, lensLabel: String, iso: Int): File {
        val cleanLens = lensLabel.replace(Regex("[^A-Za-z0-9]"), "")
        return File(calibrationDir, "${kind.name}_${cleanLens}_ISO${iso}_${nowFileStamp()}.cal32")
    }
    fun newExportTempFile(name: String): File = File(exportCacheDir, name)

    fun saveThumbnail(bitmap: Bitmap, file: File) {
        runCatching {
            FileOutputStream(file).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 88, fos)
            }
        }
    }

    fun listSessions(): List<AstroSessionItem> {
        if (!sessionsIndexFile.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(sessionsIndexFile.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val path = o.getString("astro32Path")
                if (!File(path).exists()) return@mapNotNull null
                AstroSessionItem(
                    id = o.getString("id"),
                    astro32Path = path,
                    thumbPath = o.optString("thumbPath", ""),
                    title = o.getString("title"),
                    modeName = o.getString("modeName"),
                    lensName = o.getString("lensName"),
                    exposureSec = o.optDouble("exposureSec", 1.0).toFloat(),
                    iso = o.optInt("iso", 1600),
                    stackCount = o.optInt("stackCount", 1),
                    dateObs = o.optString("dateObs", "")
                )
            }
        }.getOrDefault(emptyList())
    }

    fun addSession(item: AstroSessionItem): List<AstroSessionItem> {
        val current = listSessions().toMutableList()
        current.add(0, item)
        // Keep last 40 sessions neatly indexed
        val trimmed = current.take(40)
        writeSessionsIndex(trimmed)
        return trimmed
    }

    fun deleteSession(id: String): List<AstroSessionItem> {
        val current = listSessions()
        val target = current.firstOrNull { it.id == id }
        if (target != null) {
            runCatching { File(target.astro32Path).delete() }
            runCatching { File(target.thumbPath).delete() }
        }
        val remaining = current.filterNot { it.id == id }
        writeSessionsIndex(remaining)
        return remaining
    }

    private fun writeSessionsIndex(items: List<AstroSessionItem>) {
        val arr = JSONArray()
        for (it in items) {
            arr.put(
                JSONObject().apply {
                    put("id", it.id)
                    put("astro32Path", it.astro32Path)
                    put("thumbPath", it.thumbPath)
                    put("title", it.title)
                    put("modeName", it.modeName)
                    put("lensName", it.lensName)
                    put("exposureSec", it.exposureSec.toDouble())
                    put("iso", it.iso)
                    put("stackCount", it.stackCount)
                    put("dateObs", it.dateObs)
                }
            )
        }
        runCatching { sessionsIndexFile.writeText(arr.toString(2)) }
    }

    // ---------------- Calibration Masters ----------------

    fun listCalibrationMasters(): List<CalibrationMasterInfo> {
        if (!calibrationIndexFile.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(calibrationIndexFile.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val path = o.getString("filePath")
                if (!File(path).exists()) return@mapNotNull null
                CalibrationMasterInfo(
                    id = o.getString("id"),
                    kind = CalibrationKind.valueOf(o.getString("kind")),
                    filePath = path,
                    lensLabel = o.getString("lensLabel"),
                    focalMm = o.optDouble("focalMm", 23.0).toFloat(),
                    iso = o.optInt("iso", 1600),
                    exposureSec = o.optDouble("exposureSec", 1.0).toFloat(),
                    frameCount = o.optInt("frameCount", 10),
                    isRawBayer = o.optBoolean("isRawBayer", true),
                    createdAt = o.optString("createdAt", "")
                )
            }
        }.getOrDefault(emptyList())
    }

    fun addCalibrationMaster(info: CalibrationMasterInfo): List<CalibrationMasterInfo> {
        val list = listCalibrationMasters().toMutableList()
        list.add(0, info)
        writeCalibrationIndex(list)
        return list
    }

    fun deleteCalibrationMaster(id: String): List<CalibrationMasterInfo> {
        val list = listCalibrationMasters()
        list.firstOrNull { it.id == id }?.let { runCatching { File(it.filePath).delete() } }
        val remaining = list.filterNot { it.id == id }
        writeCalibrationIndex(remaining)
        return remaining
    }

    private fun writeCalibrationIndex(items: List<CalibrationMasterInfo>) {
        val arr = JSONArray()
        for (it in items) {
            arr.put(
                JSONObject().apply {
                    put("id", it.id)
                    put("kind", it.kind.name)
                    put("filePath", it.filePath)
                    put("lensLabel", it.lensLabel)
                    put("focalMm", it.focalMm.toDouble())
                    put("iso", it.iso)
                    put("exposureSec", it.exposureSec.toDouble())
                    put("frameCount", it.frameCount)
                    put("isRawBayer", it.isRawBayer)
                    put("createdAt", it.createdAt)
                }
            )
        }
        runCatching { calibrationIndexFile.writeText(arr.toString(2)) }
    }

    // ---------------- MediaStore Public Exports ----------------

    fun saveJpegWithExifToGallery(
        bitmap: Bitmap,
        fileName: String,
        focalMm: Float,
        apertureF: Float,
        exposureSec: Float,
        iso: Int
    ): String? {
        return try {
            val tmp = File(exportCacheDir, fileName)
            FileOutputStream(tmp).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 97, fos)
            }
            runCatching {
                val exif = ExifInterface(tmp.absolutePath)
                exif.setAttribute(ExifInterface.TAG_MAKE, "Xiaomi")
                exif.setAttribute(ExifInterface.TAG_MODEL, "Xiaomi 14 Ultra")
                exif.setAttribute(ExifInterface.TAG_SOFTWARE, "AstroPro 14U")
                exif.setAttribute(ExifInterface.TAG_F_NUMBER, String.format(Locale.US, "%.2f", apertureF))
                exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, String.format(Locale.US, "%.5f", exposureSec))
                exif.setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, iso.toString())
                exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "${(focalMm * 100).toInt()}/100")
                exif.saveAttributes()
            }
            copyFileToDcimMediaStore(tmp, fileName, "image/jpeg")
        } catch (_: Throwable) {
            null
        }
    }

    fun copyFileToDcimMediaStore(sourceFile: File, displayName: String, mimeType: String): String? {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/AstroPro14U")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return sourceFile.absolutePath
            resolver.openOutputStream(uri)?.use { os ->
                FileInputStream(sourceFile).use { fis -> fis.copyTo(os) }
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            "DCIM/AstroPro14U/$displayName"
        } catch (_: Throwable) {
            null
        }
    }

    fun copyFileToPicturesMediaStore(sourceFile: File, displayName: String, mimeType: String): String? {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/AstroPro14U")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return sourceFile.absolutePath
            resolver.openOutputStream(uri)?.use { os ->
                FileInputStream(sourceFile).use { fis -> fis.copyTo(os) }
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            "Pictures/AstroPro14U/$displayName"
        } catch (_: Throwable) {
            null
        }
    }

    fun copyFileToDownloadsMediaStore(sourceFile: File, displayName: String, mimeType: String): String? {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/AstroPro14U")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return sourceFile.absolutePath
            resolver.openOutputStream(uri)?.use { os ->
                FileInputStream(sourceFile).use { fis -> fis.copyTo(os) }
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            "Downloads/AstroPro14U/$displayName"
        } catch (_: Throwable) {
            null
        }
    }

    fun shareFile(file: File, mimeType: String, chooserTitle: String) {
        runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(intent, chooserTitle).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        }
    }
}
