package it.kartcoach.gps

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SessionRecorder(private val context: Context) {
    private val rows = mutableListOf<String>()
    private var startedAt = 0L
    private var trackName = "unknown"
    private var targetLapMs: Long? = null

    fun start(trackName: String, targetLapMs: Long?) {
        rows.clear()
        startedAt = System.currentTimeMillis()
        this.trackName = trackName
        this.targetLapMs = targetLapMs
        rows += "time_ms,session_ms,lap,target_lap_ms,lat,lon,speed_kmh,bearing_deg,accuracy_m,ax,ay,az,gx,gy,gz,linear_accel_mps2,gyro_mag_rads,sensor_time_ms"
    }

    fun add(point: GpsPoint, sensors: SensorSnapshot, lapNumber: Int) {
        if (startedAt == 0L) return
        rows += listOf(
            point.wallTimeMillis,
            point.wallTimeMillis - startedAt,
            lapNumber,
            targetLapMs ?: "",
            point.latitude,
            point.longitude,
            point.speedMps * 3.6f,
            point.bearingDeg,
            point.accuracyM,
            sensors.ax, sensors.ay, sensors.az,
            sensors.gx, sensors.gy, sensors.gz,
            sensors.linearAccelMps2,
            sensors.gyroMagnitudeRadS,
            sensors.wallTimeMillis
        ).joinToString(",")
    }

    fun stopAndSave(): String? {
        if (startedAt == 0L) return null
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ITALY).format(Date(startedAt))
        val safeTrack = trackName.replace(Regex("[^A-Za-z0-9_-]+"), "_")
        val filename = "KartCoach_${safeTrack}_$stamp.csv"
        val saved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveToDownloads(filename) else saveToAppExternal(filename)
        startedAt = 0L
        targetLapMs = null
        rows.clear()
        return if (saved) filename else null
    }

    private fun saveToDownloads(filename: String): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, filename)
            put(MediaStore.Downloads.MIME_TYPE, "text/csv")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/KartCoach")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer -> rows.forEach { writer.appendLine(it) } } ?: return false
        values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
        context.contentResolver.update(uri, values, null, null)
        return true
    }

    private fun saveToAppExternal(filename: String): Boolean {
        val dir = File(context.getExternalFilesDir(null), "KartCoach")
        if (!dir.exists() && !dir.mkdirs()) return false
        File(dir, filename).bufferedWriter().use { writer -> rows.forEach { writer.appendLine(it) } }
        return true
    }
}
