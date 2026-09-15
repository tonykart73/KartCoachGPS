package it.kartcoach.gps

import kotlin.math.abs

/**
 * Coach di ingresso curva basato sull'IMU.
 *
 * Obiettivo: misurare il tempo tra l'inizio del carico/decelerazione e l'inizio
 * della rotazione del kart. Non usa la posizione laterale GPS e non impone un
 * ritardo fisso: il riferimento viene appreso dai 2-3 giri migliori del pilota.
 *
 * Il telefono deve essere fissato rigidamente al kart. Usiamo magnitudine di
 * accelerazione lineare e magnitudine del giroscopio, quindi l'algoritmo non
 * dipende dall'orientamento portrait/landscape del telefono.
 */
class EntryTimingCoach {

    private data class CornerEvent(
        val distanceM: Double,
        val loadStartMs: Long,
        val turnStartMs: Long,
        val delayMs: Long,
        val entrySpeedKmh: Double,
        var minSpeedKmh: Double,
        var exitSpeedKmh: Double
    )

    private data class LapProfile(
        val lapNumber: Int,
        val lapTimeMs: Long,
        val events: List<CornerEvent>
    )

    private data class Reference(
        val index: Int,
        val distanceM: Double,
        val delayMs: Long
    )

    private data class Candidate(
        val id: Long,
        val loadStartMs: Long,
        val distanceM: Double,
        val entrySpeedKmh: Double,
        val reference: Reference?
    )

    private data class ActiveTurn(
        val event: CornerEvent,
        val startedAtMs: Long
    )

    private val lapProfiles = mutableListOf<LapProfile>()
    private var references: List<Reference> = emptyList()
    private var currentEvents = mutableListOf<CornerEvent>()

    private var filteredAccel = 0.0
    private var filteredGyro = 0.0
    private var loadHighSinceMs: Long? = null
    private var gyroHighSinceMs: Long? = null
    private var candidate: Candidate? = null
    private var activeTurn: ActiveTurn? = null
    private var lastAcceptedDistanceM = -1_000.0
    private var serial = 0L
    private var lastSpeedKmh = 0.0

    fun resetSession() {
        lapProfiles.clear()
        references = emptyList()
        currentEvents.clear()
        filteredAccel = 0.0
        filteredGyro = 0.0
        loadHighSinceMs = null
        gyroHighSinceMs = null
        candidate = null
        activeTurn = null
        lastAcceptedDistanceM = -1_000.0
        serial = 0L
        lastSpeedKmh = 0.0
    }

    fun newLap() {
        currentEvents = mutableListOf()
        loadHighSinceMs = null
        gyroHighSinceMs = null
        candidate = null
        activeTurn = null
        lastAcceptedDistanceM = -1_000.0
    }

    fun onSensor(
        snapshot: SensorSnapshot,
        speedMps: Double,
        lapDistanceM: Double,
        speedAccelMps2: Double,
        nowMs: Long
    ): CoachMarker? {
        val accel = snapshot.linearAccelMps2.toDouble().coerceIn(0.0, 25.0)
        val gyro = snapshot.gyroMagnitudeRadS.toDouble().coerceIn(0.0, 8.0)
        filteredAccel = if (filteredAccel == 0.0) accel else filteredAccel * 0.82 + accel * 0.18
        filteredGyro = if (filteredGyro == 0.0) gyro else filteredGyro * 0.78 + gyro * 0.22
        lastSpeedKmh = speedMps * 3.6

        updateActiveTurn(nowMs, lastSpeedKmh)

        val c = candidate
        if (c != null) {
            if (nowMs - c.loadStartMs > MAX_LOAD_TO_TURN_MS) {
                candidate = null
                gyroHighSinceMs = null
            } else if (filteredGyro >= TURN_ONSET_RAD_S) {
                if (gyroHighSinceMs == null) gyroHighSinceMs = nowMs
                val highSince = gyroHighSinceMs ?: nowMs
                if (nowMs - highSince >= TURN_CONFIRM_MS) {
                    val turnStart = highSince
                    val delay = turnStart - c.loadStartMs
                    candidate = null
                    gyroHighSinceMs = null
                    if (delay in MIN_VALID_DELAY_MS..MAX_VALID_DELAY_MS) {
                        val event = CornerEvent(
                            distanceM = c.distanceM,
                            loadStartMs = c.loadStartMs,
                            turnStartMs = turnStart,
                            delayMs = delay,
                            entrySpeedKmh = c.entrySpeedKmh,
                            minSpeedKmh = lastSpeedKmh,
                            exitSpeedKmh = lastSpeedKmh
                        )
                        activeTurn = ActiveTurn(event, turnStart)
                        lastAcceptedDistanceM = c.distanceM
                    }
                }
            } else {
                gyroHighSinceMs = null
            }
            return currentCue(nowMs)
        }

        // Non iniziare un secondo evento mentre stiamo ancora chiudendo il precedente.
        if (activeTurn != null) return null
        if (speedMps < MIN_SPEED_MPS) {
            loadHighSinceMs = null
            return null
        }
        if (lapDistanceM - lastAcceptedDistanceM < MIN_CORNER_SPACING_M) return null

        // "Carico" e' volutamente piu' corretto di "freno": senza sensore pedale non
        // pretendiamo di sapere il momento esatto in cui il pilota tocca il freno.
        val decelConfirmed = speedAccelMps2 <= GPS_DECEL_CONFIRM_MPS2 || filteredAccel >= HARD_LOAD_MPS2
        val loadCondition = filteredAccel >= LOAD_ONSET_MPS2 &&
            filteredGyro <= PRE_TURN_GYRO_MAX_RAD_S && decelConfirmed

        if (loadCondition) {
            if (loadHighSinceMs == null) loadHighSinceMs = nowMs
            val since = loadHighSinceMs ?: nowMs
            if (nowMs - since >= LOAD_CONFIRM_MS) {
                val ref = nearestReference(lapDistanceM)
                serial += 1L
                candidate = Candidate(
                    id = serial,
                    loadStartMs = since,
                    distanceM = lapDistanceM,
                    entrySpeedKmh = lastSpeedKmh,
                    reference = ref
                )
                loadHighSinceMs = null
                return currentCue(nowMs)
            }
        } else {
            loadHighSinceMs = null
        }
        return null
    }

    fun currentCue(nowMs: Long): CoachMarker? {
        val c = candidate ?: return null
        val ref = c.reference ?: return null // primi giri: misura soltanto, non disturba il pilota
        val elapsed = nowMs - c.loadStartMs
        val turnWindowStart = (ref.delayMs - 70L).coerceAtLeast(0L)

        return when {
            elapsed < turnWindowStart -> dynamicMarker(
                id = -9_100_000L - c.id,
                type = CueType.WAIT,
                note = "LASCIA ASSESTARE · ${ref.delayMs} ms riferimento"
            )
            elapsed <= ref.delayMs + 220L -> dynamicMarker(
                id = -9_200_000L - c.id,
                type = CueType.TURN,
                note = "ORA · UNA SOLA ROTAZIONE"
            )
            else -> null
        }
    }

    /**
     * Va chiamato quando il giro e' stato validato dal cronometro.
     * Il consiglio del giro appena concluso viene confrontato con i riferimenti
     * gia' appresi, poi il giro entra nel database per il giro successivo.
     */
    fun completeLap(lapNumber: Int, lapTimeMs: Long): List<EntryTimingInsight> {
        forceFinalizeActiveTurn()
        val priorRefs = references
        val profile = LapProfile(lapNumber, lapTimeMs, currentEvents.toList())

        val insights = if (priorRefs.isEmpty()) emptyList() else currentEvents.mapNotNull { event ->
            val ref = priorRefs.minByOrNull { abs(it.distanceM - event.distanceM) } ?: return@mapNotNull null
            if (abs(ref.distanceM - event.distanceM) > MATCH_WINDOW_M) return@mapNotNull null
            val delta = event.delayMs - ref.delayMs
            if (abs(delta) < INSIGHT_THRESHOLD_MS) return@mapNotNull null
            val advice = if (delta < 0) {
                "Sterzi circa ${abs(delta)} ms troppo presto: lascia finire il trasferimento di carico prima della rotazione."
            } else {
                "Inizi la rotazione circa ${delta} ms piu' tardi dei tuoi ingressi migliori."
            }
            EntryTimingInsight(
                cornerIndex = ref.index,
                distanceM = event.distanceM,
                measuredDelayMs = event.delayMs,
                referenceDelayMs = ref.delayMs,
                deltaMs = delta,
                advice = advice
            )
        }.sortedByDescending { abs(it.deltaMs) }.take(4)

        lapProfiles += profile
        recomputeReferences()
        currentEvents = mutableListOf()
        candidate = null
        activeTurn = null
        return insights
    }

    fun hasReference(): Boolean = references.isNotEmpty()

    private fun updateActiveTurn(nowMs: Long, speedKmh: Double) {
        val active = activeTurn ?: return
        active.event.minSpeedKmh = minOf(active.event.minSpeedKmh, speedKmh)
        active.event.exitSpeedKmh = speedKmh
        if (nowMs - active.startedAtMs >= EXIT_SAMPLE_MS) {
            currentEvents += active.event
            activeTurn = null
        }
    }

    private fun forceFinalizeActiveTurn() {
        val active = activeTurn ?: return
        active.event.exitSpeedKmh = lastSpeedKmh
        currentEvents += active.event
        activeTurn = null
    }

    private fun recomputeReferences() {
        val fastest = lapProfiles
            .filter { it.events.size >= 2 }
            .sortedBy { it.lapTimeMs }
            .take(3)
        if (fastest.size < 2) {
            references = emptyList()
            return
        }

        val anchor = fastest.first().events.sortedBy { it.distanceM }
        references = anchor.mapIndexedNotNull { index, anchorEvent ->
            val matches = fastest.mapNotNull { profile ->
                profile.events.minByOrNull { abs(it.distanceM - anchorEvent.distanceM) }
                    ?.takeIf { abs(it.distanceM - anchorEvent.distanceM) <= MATCH_WINDOW_M }
            }
            if (matches.size < 2) return@mapIndexedNotNull null
            val delays = matches.map { it.delayMs }.sorted()
            val medianDelay = delays[delays.size / 2]
            val meanDistance = matches.map { it.distanceM }.average()
            Reference(index = index + 1, distanceM = meanDistance, delayMs = medianDelay)
        }
    }

    private fun nearestReference(distanceM: Double): Reference? {
        val ref = references.minByOrNull { abs(it.distanceM - distanceM) } ?: return null
        return ref.takeIf { abs(it.distanceM - distanceM) <= MATCH_WINDOW_M }
    }

    private fun dynamicMarker(id: Long, type: CueType, note: String) = CoachMarker(
        id = id,
        type = type,
        latitude = 0.0,
        longitude = 0.0,
        baseRadiusM = 0.0,
        leadSeconds = 0.0,
        holdMillis = 250L,
        note = note,
        expectedBearingDeg = null,
        lapDistanceM = null
    )

    private companion object {
        const val MIN_SPEED_MPS = 7.0
        const val LOAD_ONSET_MPS2 = 2.2
        const val HARD_LOAD_MPS2 = 3.8
        const val GPS_DECEL_CONFIRM_MPS2 = -0.35
        const val PRE_TURN_GYRO_MAX_RAD_S = 0.30
        const val TURN_ONSET_RAD_S = 0.42
        const val LOAD_CONFIRM_MS = 70L
        const val TURN_CONFIRM_MS = 60L
        const val MIN_VALID_DELAY_MS = 80L
        const val MAX_VALID_DELAY_MS = 1_100L
        const val MAX_LOAD_TO_TURN_MS = 1_350L
        const val EXIT_SAMPLE_MS = 1_050L
        const val MIN_CORNER_SPACING_M = 28.0
        const val MATCH_WINDOW_M = 45.0
        const val INSIGHT_THRESHOLD_MS = 80L
    }
}
