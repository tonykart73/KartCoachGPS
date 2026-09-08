package it.kartcoach.gps

data class GpsPoint(
    val latitude: Double,
    val longitude: Double,
    val speedMps: Float = 0f,
    val bearingDeg: Float = 0f,
    val accuracyM: Float = Float.NaN,
    val elapsedRealtimeNanos: Long = 0L,
    val wallTimeMillis: Long = 0L
)

data class SensorSnapshot(
    val ax: Float = 0f,
    val ay: Float = 0f,
    val az: Float = 0f,
    val gx: Float = 0f,
    val gy: Float = 0f,
    val gz: Float = 0f
)

data class TelemetrySample(
    val point: GpsPoint,
    val sensors: SensorSnapshot,
    val sessionElapsedMs: Long
)

data class LapData(
    val number: Int,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val samples: List<TelemetrySample>
) {
    val durationMs: Long get() = endedAtMs - startedAtMs
}

enum class CueType(val label: String) {
    BRAKE("FRENA"),
    TURN("INSERISCI"),
    STRAIGHTEN("RADDRIZZA"),
    THROTTLE("GAS"),
    FULL_THROTTLE("TUTTO GAS")
}

data class CoachMarker(
    val id: Long,
    val type: CueType,
    val latitude: Double,
    val longitude: Double,
    val baseRadiusM: Double = 5.0,
    val leadSeconds: Double = 0.45,
    val holdMillis: Long = 900L,
    val note: String = "",
    val expectedBearingDeg: Double? = null
)

data class TrackProfile(
    val id: String,
    val name: String,
    val centerLat: Double,
    val centerLon: Double,
    val detectionRadiusM: Double,
    val nominalLengthM: Double
)

data class TrackConfig(
    val profileId: String = "morcone",
    val name: String = "Morcone",
    val startFinish: GpsPoint? = null,
    val startFinishRadiusM: Double = 10.0,
    val markers: List<CoachMarker> = emptyList()
)

data class AnalysisFinding(
    val rank: Int,
    val deltaMs: Long,
    val distanceFromLapStartM: Double,
    val title: String,
    val advice: String,
    val cueType: CueType,
    val latitude: Double,
    val longitude: Double,
    val expectedBearingDeg: Double?,
    val evidence: String
)

data class SessionAnalysis(
    val bestLapMs: Long,
    val idealLapMs: Long,
    val potentialGainMs: Long,
    val findings: List<AnalysisFinding>
)

enum class LearningMode {
    WAITING_FOR_TRACK,
    LEARNING_TRACK,
    LEARNING_DRIVER,
    COACH_READY
}

data class LiveCoachState(
    val location: GpsPoint? = null,
    val sensors: SensorSnapshot = SensorSnapshot(),
    val gpsAvailable: Boolean = false,
    val activeCue: CoachMarker? = null,
    val distanceToCueM: Double? = null,
    val lapCount: Int = 0,
    val currentLapMillis: Long = 0L,
    val lastLapMillis: Long? = null,
    val bestLapMillis: Long? = null,
    val targetLapMillis: Long? = null,
    val lastLapTargetDeltaMs: Long? = null,
    val bestTargetDeltaMs: Long? = null,
    val recording: Boolean = false,
    val autoRecording: Boolean = true,
    val track: TrackConfig = TrackConfig(),
    val recognizedTrack: TrackProfile? = null,
    val pendingTrackConfirmation: TrackProfile? = null,
    val learningMode: LearningMode = LearningMode.WAITING_FOR_TRACK,
    val analysis: SessionAnalysis? = null,
    val sessionLapTimes: List<Long> = emptyList(),
    val statusMessage: String = "In attesa del GPS",
    val lastSavedFile: String? = null
)
