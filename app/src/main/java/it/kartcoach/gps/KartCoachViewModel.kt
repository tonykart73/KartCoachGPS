package it.kartcoach.gps

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class KartCoachViewModel(application: Application) : AndroidViewModel(application) {
    private val store = TrackStore(application)
    private val recorder = SessionRecorder(application)
    private val engine = CoachEngine()
    private val analyzer = SessionAnalyzer()
    private val entryTimingCoach = EntryTimingCoach()

    private val _state = MutableStateFlow(LiveCoachState(track = store.load()))
    val state: StateFlow<LiveCoachState> = _state.asStateFlow()

    private var locationTracker: LocationTracker? = null
    private var sensorTracker: SensorTracker? = null
    private var timerJob: Job? = null

    private var learner: AutoTrackLearner? = null
    private var sessionStartedAtMs: Long = 0L
    private var lapStartedAt: Long? = null
    private var currentLapSamples = mutableListOf<TelemetrySample>()
    private val completedLaps = mutableListOf<LapData>()
    private var outsideStartZone = true
    private var stationarySinceMs: Long? = null
    private val rejectedProfiles = mutableSetOf<String>()

    // Posizione longitudinale nel giro ricavata integrando la velocita'.
    // Evita di usare il +/- metri della posizione GPS per lanciare i cue.
    private var lapDistanceM = 0.0
    private var previousDistancePoint: GpsPoint? = null
    private var latestSpeedAccelMps2 = 0.0

    fun startSensorsAndGps() {
        if (locationTracker != null) return
        locationTracker = LocationTracker(
            context = getApplication(),
            onLocation = ::onLocation,
            onProviderState = { available -> _state.value = _state.value.copy(gpsAvailable = available) }
        ).also { it.start() }
        sensorTracker = SensorTracker(getApplication(), ::onSensors).also { it.start() }
        timerJob = viewModelScope.launch {
            while (true) {
                delay(50)
                lapStartedAt?.let { start ->
                    _state.value = _state.value.copy(currentLapMillis = System.currentTimeMillis() - start)
                }
            }
        }
    }

    fun stopSensorsAndGps() {
        locationTracker?.stop(); locationTracker = null
        sensorTracker?.stop(); sensorTracker = null
        timerJob?.cancel(); timerJob = null
    }

    private fun onSensors(snapshot: SensorSnapshot) {
        _state.value = _state.value.copy(sensors = snapshot)

        if (!_state.value.recording || lapStartedAt == null) return
        val location = _state.value.location ?: return
        val now = if (snapshot.wallTimeMillis > 0L) snapshot.wallTimeMillis else System.currentTimeMillis()
        val distanceEstimate = estimatedLapDistance(now, location)
        val cue = entryTimingCoach.onSensor(
            snapshot = snapshot,
            speedMps = location.speedMps.toDouble(),
            lapDistanceM = distanceEstimate,
            speedAccelMps2 = latestSpeedAccelMps2,
            nowMs = now
        ) ?: entryTimingCoach.currentCue(now)

        if (cue != null) {
            // Il coach d'ingresso ha priorita': e' sincronizzato con l'evento reale
            // di carico/rotazione, non con una coordinata GPS.
            _state.value = _state.value.copy(activeCue = cue, distanceToCueM = null)
        } else if ((_state.value.activeCue?.id ?: 0L) < -9_000_000L) {
            _state.value = _state.value.copy(activeCue = null, distanceToCueM = null)
        }
    }

    private fun onLocation(point: GpsPoint) {
        var next = _state.value.copy(location = point, gpsAvailable = true)
        _state.value = next

        resolveTrack(point)
        next = _state.value
        val profile = next.recognizedTrack ?: return

        if (next.autoRecording && !next.recording && next.targetLapMillis != null && point.speedMps >= 4.5f && gpsGood(point)) {
            startSession(profile)
        } else if (next.autoRecording && !next.recording && next.targetLapMillis == null && point.speedMps >= 4.5f) {
            _state.value = _state.value.copy(statusMessage = "Imposta il TARGET giro prima di partire.")
        }

        if (_state.value.recording) {
            updateLapDistance(point)

            val sample = TelemetrySample(
                point = point,
                sensors = _state.value.sensors,
                sessionElapsedMs = (point.wallTimeMillis - sessionStartedAtMs).coerceAtLeast(0L)
            )
            recorder.add(point, _state.value.sensors, completedLaps.size + 1)

            val sf = _state.value.track.startFinish
            if (sf == null) learnStartFinish(point) else updateLap(sample)
            detectReturnToPits(point)

            val now = System.currentTimeMillis()
            val entryCue = entryTimingCoach.currentCue(now)
            val coached = engine.update(
                nowMs = now,
                location = point,
                track = _state.value.track,
                lapDistanceM = if (lapStartedAt != null) lapDistanceM else null
            )
            val chosen = entryCue ?: coached.marker
            _state.value = _state.value.copy(
                activeCue = chosen,
                distanceToCueM = if (entryCue != null) null else coached.distanceM
            )
        } else {
            previousDistancePoint = point
            latestSpeedAccelMps2 = 0.0
            _state.value = _state.value.copy(activeCue = null, distanceToCueM = null)
        }
    }

    private fun updateLapDistance(point: GpsPoint) {
        val prev = previousDistancePoint
        previousDistancePoint = point
        if (prev == null) return

        val dt = (point.wallTimeMillis - prev.wallTimeMillis) / 1000.0
        if (dt !in 0.02..2.0) return
        latestSpeedAccelMps2 = ((point.speedMps - prev.speedMps) / dt).toDouble().coerceIn(-15.0, 15.0)
        if (lapStartedAt == null) return

        val avgSpeed = (prev.speedMps.toDouble() + point.speedMps.toDouble()) / 2.0
        if (avgSpeed in 0.0..70.0) lapDistanceM += avgSpeed * dt
    }

    private fun estimatedLapDistance(nowMs: Long, location: GpsPoint): Double {
        val ageS = ((nowMs - location.wallTimeMillis).coerceAtLeast(0L) / 1000.0).coerceAtMost(0.65)
        return lapDistanceM + location.speedMps.toDouble().coerceAtLeast(0.0) * ageS
    }

    private fun resolveTrack(point: GpsPoint) {
        if (_state.value.recognizedTrack != null || _state.value.pendingTrackConfirmation != null) return

        val storedSf = _state.value.track.startFinish
        if (storedSf != null && Geo.distanceMeters(point, storedSf.latitude, storedSf.longitude) <= 2_000.0) {
            val storedProfile = TrackProfiles.all.firstOrNull { it.id == _state.value.track.profileId } ?: TrackProfiles.MORCONE
            acceptTrack(storedProfile, ask = false)
            return
        }

        val candidate = TrackProfiles.nearest(point) ?: run {
            _state.value = _state.value.copy(
                learningMode = LearningMode.WAITING_FOR_TRACK,
                statusMessage = "Pista non riconosciuta: puoi scegliere Morcone manualmente"
            )
            return
        }
        if (candidate.id !in rejectedProfiles) {
            _state.value = _state.value.copy(
                pendingTrackConfirmation = candidate,
                statusMessage = "Pista rilevata: conferma ${candidate.name}"
            )
        }
    }

    fun confirmPendingTrack(yes: Boolean) {
        val candidate = _state.value.pendingTrackConfirmation ?: return
        if (yes) acceptTrack(candidate, ask = false)
        else {
            rejectedProfiles += candidate.id
            _state.value = _state.value.copy(
                pendingTrackConfirmation = null,
                statusMessage = "Rilevamento annullato. Seleziona la pista manualmente."
            )
        }
    }

    fun selectMorcone() {
        rejectedProfiles.remove(TrackProfiles.MORCONE.id)
        acceptTrack(TrackProfiles.MORCONE, ask = false)
    }

    private fun acceptTrack(profile: TrackProfile, ask: Boolean) {
        val current = _state.value.track
        val keepCalibration = current.profileId == profile.id
        val track = if (keepCalibration) current.copy(name = profile.name)
        else TrackConfig(profileId = profile.id, name = profile.name)
        store.save(track)
        learner = if (track.startFinish == null) AutoTrackLearner(profile.nominalLengthM) else null
        _state.value = _state.value.copy(
            track = track,
            recognizedTrack = profile,
            pendingTrackConfirmation = null,
            learningMode = if (track.startFinish == null) LearningMode.LEARNING_TRACK else LearningMode.LEARNING_DRIVER,
            statusMessage = if (track.startFinish == null)
                "Pista confermata. Parti: imparo automaticamente il giro e la linea virtuale."
            else "Pista riconosciuta. GPS per giro/velocita'; IMU per carico e rotazione."
        )
    }

    private fun startSession(profile: TrackProfile) {
        if (_state.value.targetLapMillis == null) {
            _state.value = _state.value.copy(statusMessage = "Imposta il TARGET giro prima di avviare la sessione.")
            return
        }
        completedLaps.clear()
        currentLapSamples.clear()
        lapStartedAt = null
        outsideStartZone = true
        stationarySinceMs = null
        lapDistanceM = 0.0
        previousDistancePoint = null
        latestSpeedAccelMps2 = 0.0
        sessionStartedAtMs = System.currentTimeMillis()
        recorder.start(profile.name, _state.value.targetLapMillis)
        engine.reset()
        entryTimingCoach.resetSession()
        _state.value = _state.value.copy(
            recording = true,
            lapCount = 0,
            currentLapMillis = 0L,
            lastLapMillis = null,
            bestLapMillis = null,
            lastLapTargetDeltaMs = null,
            bestTargetDeltaMs = null,
            sessionLapTimes = emptyList(),
            analysis = null,
            entryTimingInsights = emptyList(),
            lastSavedFile = null,
            learningMode = if (_state.value.track.startFinish == null) LearningMode.LEARNING_TRACK else LearningMode.LEARNING_DRIVER,
            statusMessage = if (_state.value.track.startFinish == null)
                "APPRENDIMENTO PISTA: guida normalmente, riconosco il primo giro chiuso."
            else "IMU IN APPRENDIMENTO: 2 giri senza disturbi, poi confronto carico → rotazione."
        )
    }

    private fun learnStartFinish(point: GpsPoint) {
        val profile = _state.value.recognizedTrack ?: return
        if (learner == null) learner = AutoTrackLearner(profile.nominalLengthM)
        val learned = learner?.update(point) ?: return
        val track = _state.value.track.copy(startFinish = learned, startFinishRadiusM = 10.0)
        store.save(track)
        outsideStartZone = false
        lapStartedAt = point.wallTimeMillis
        currentLapSamples.clear()
        lapDistanceM = 0.0
        previousDistancePoint = point
        engine.newLap()
        entryTimingCoach.newLap()
        _state.value = _state.value.copy(
            track = track,
            currentLapMillis = 0L,
            learningMode = LearningMode.LEARNING_DRIVER,
            statusMessage = "GIRO RICONOSCIUTO: completa 2 giri validi per imparare il timing d'ingresso."
        )
    }

    private fun updateLap(sample: TelemetrySample) {
        val sf = _state.value.track.startFinish ?: return
        currentLapSamples += sample

        val point = sample.point
        val distance = Geo.distanceMeters(point, sf.latitude, sf.longitude)
        val radius = _state.value.track.startFinishRadiusM
        if (distance > radius * 2.8) outsideStartZone = true

        val directionOk = sf.bearingDeg == 0f || point.speedMps < 3f ||
            Geo.angularDifferenceDeg(point.bearingDeg.toDouble(), sf.bearingDeg.toDouble()) <= 65.0

        if (distance <= radius && outsideStartZone && point.speedMps > 5f && directionOk) {
            outsideStartZone = false
            val now = point.wallTimeMillis
            val previousStart = lapStartedAt
            if (previousStart != null) {
                val lapTime = now - previousStart
                if (lapTime in 20_000L..180_000L && currentLapSamples.size >= 20) {
                    val lap = LapData(
                        number = completedLaps.size + 1,
                        startedAtMs = previousStart,
                        endedAtMs = now,
                        samples = currentLapSamples.toList()
                    )
                    completedLaps += lap
                    onLapCompleted(lap)
                }
            }
            lapStartedAt = now
            currentLapSamples = mutableListOf(sample)
            lapDistanceM = 0.0
            previousDistancePoint = point
            engine.newLap()
            entryTimingCoach.newLap()
        } else if (lapStartedAt == null && point.speedMps > 5f) {
            _state.value = _state.value.copy(statusMessage = "In attesa del passaggio sulla linea virtuale…")
        }
    }

    private fun onLapCompleted(lap: LapData) {
        val times = completedLaps.map { it.durationMs }
        val best = times.minOrNull()
        val target = _state.value.targetLapMillis
        val lastDelta = target?.let { lap.durationMs - it }
        val bestDelta = if (target != null && best != null) best - target else null
        val analysis = analyzer.analyze(completedLaps)
        val entryInsights = entryTimingCoach.completeLap(lap.number, lap.durationMs)

        var track = _state.value.track
        var mode = LearningMode.LEARNING_DRIVER
        var message = buildString {
            append("Giro ${lap.number}: ${formatMillis(lap.durationMs)}")
            if (lastDelta != null) append(" · target ${formatSignedMillis(lastDelta)}")
            if (!entryTimingCoach.hasReference()) append(" · IMU sta imparando")
        }

        if (analysis != null) {
            val markers = analysis.findings.mapIndexed { i, f ->
                CoachMarker(
                    id = 9_000_000L + i,
                    type = f.cueType,
                    latitude = f.latitude,
                    longitude = f.longitude,
                    baseRadiusM = 2.0,
                    leadSeconds = when (f.cueType) {
                        CueType.BRAKE -> 0.18
                        CueType.WAIT -> 0.05
                        CueType.TURN -> 0.12
                        CueType.STRAIGHTEN -> 0.22
                        CueType.THROTTLE -> 0.10
                        CueType.FULL_THROTTLE -> 0.05
                    },
                    holdMillis = 650L,
                    note = f.title,
                    expectedBearingDeg = f.expectedBearingDeg,
                    lapDistanceM = f.distanceFromLapStartM
                )
            }
            track = track.copy(markers = markers)
            store.save(track)
            mode = LearningMode.COACH_READY
            val targetTail = bestDelta?.let { " · BEST vs TARGET ${formatSignedMillis(it)}" } ?: ""
            message = when {
                entryInsights.any { it.deltaMs < -80L } ->
                    "INGRESSO: rilevata sterzata anticipata. Nel prossimo giro usa ASPETTA → INSERISCI.$targetTail"
                analysis.findings.isEmpty() ->
                    "Coach pronto: giri coerenti. IMU attiva sul timing d'ingresso.$targetTail"
                else -> "COACH ATTIVO: ${analysis.findings.size} punti + timing IMU carico→rotazione.$targetTail"
            }
        }

        _state.value = _state.value.copy(
            track = track,
            lapCount = completedLaps.size,
            lastLapMillis = lap.durationMs,
            bestLapMillis = best,
            lastLapTargetDeltaMs = lastDelta,
            bestTargetDeltaMs = bestDelta,
            currentLapMillis = 0L,
            sessionLapTimes = times,
            analysis = analysis,
            entryTimingInsights = entryInsights,
            learningMode = mode,
            statusMessage = message
        )
    }

    private fun detectReturnToPits(point: GpsPoint) {
        if (completedLaps.isEmpty()) return
        val now = System.currentTimeMillis()
        if (point.speedMps < 1.2f) {
            if (stationarySinceMs == null) stationarySinceMs = now
            if (now - (stationarySinceMs ?: now) >= 15_000L) {
                finishSession(auto = true)
                stationarySinceMs = null
            }
        } else {
            stationarySinceMs = null
        }
    }

    private fun gpsGood(point: GpsPoint): Boolean = !point.accuracyM.isFinite() || point.accuracyM <= 8f

    fun finishSession(auto: Boolean = false): String? {
        if (!_state.value.recording) return null
        val analysis = analyzer.analyze(completedLaps) ?: _state.value.analysis
        val file = recorder.stopAndSave()
        _state.value = _state.value.copy(
            recording = false,
            activeCue = null,
            distanceToCueM = null,
            analysis = analysis,
            learningMode = if (analysis != null) LearningMode.COACH_READY else LearningMode.LEARNING_DRIVER,
            statusMessage = if (analysis != null) {
                val td = _state.value.bestTargetDeltaMs?.let { " · BEST vs TARGET ${formatSignedMillis(it)}" } ?: ""
                "${if (auto) "BOX RILEVATO" else "SESSIONE CHIUSA"}: analisi pronta. Potenziale ${formatMillis(analysis.potentialGainMs)}.$td"
            } else "Sessione salvata. Servono almeno 2 giri validi per il confronto automatico.",
            lastSavedFile = file
        )
        return file
    }

    fun toggleRecording(): String? {
        return if (_state.value.recording) finishSession(auto = false)
        else {
            if (_state.value.targetLapMillis == null) {
                _state.value = _state.value.copy(statusMessage = "Imposta il TARGET giro prima di partire.")
                return null
            }
            val profile = _state.value.recognizedTrack ?: TrackProfiles.MORCONE.also { acceptTrack(it, ask = false) }
            startSession(profile)
            null
        }
    }

    fun setTargetFromText(raw: String): Boolean {
        if (_state.value.recording) return false
        val target = parseTargetMillis(raw) ?: return false
        if (target !in 20_000L..180_000L) return false
        _state.value = _state.value.copy(
            targetLapMillis = target,
            lastLapTargetDeltaMs = null,
            bestTargetDeltaMs = null,
            statusMessage = "TARGET impostato: ${formatMillis(target)}. Quando parti, la registrazione si avvierà automaticamente."
        )
        return true
    }

    fun clearTarget() {
        if (_state.value.recording) return
        _state.value = _state.value.copy(
            targetLapMillis = null,
            lastLapTargetDeltaMs = null,
            bestTargetDeltaMs = null,
            statusMessage = "Imposta il TARGET giro prima di partire."
        )
    }

    fun setStartFinishHere() {
        val loc = _state.value.location ?: return
        val track = _state.value.track.copy(startFinish = loc.copy(speedMps = 0f))
        store.save(track)
        learner?.reset(); learner = null
        lapStartedAt = null
        outsideStartZone = false
        lapDistanceM = 0.0
        previousDistancePoint = loc
        latestSpeedAccelMps2 = 0.0
        engine.reset()
        entryTimingCoach.resetSession()
        _state.value = _state.value.copy(
            track = track,
            lapCount = 0,
            lastLapMillis = null,
            bestLapMillis = null,
            entryTimingInsights = emptyList(),
            learningMode = LearningMode.LEARNING_DRIVER,
            statusMessage = "Linea start/finish calibrata manualmente. Completa 2 giri per il coach IMU."
        )
    }

    fun addMarker(type: CueType) {
        val loc = _state.value.location ?: return
        val marker = CoachMarker(
            id = System.currentTimeMillis(),
            type = type,
            latitude = loc.latitude,
            longitude = loc.longitude,
            baseRadiusM = 4.0,
            leadSeconds = when (type) {
                CueType.BRAKE -> 0.18
                CueType.WAIT -> 0.05
                CueType.TURN -> 0.12
                CueType.STRAIGHTEN -> 0.22
                CueType.THROTTLE -> 0.10
                CueType.FULL_THROTTLE -> 0.05
            },
            expectedBearingDeg = loc.bearingDeg.toDouble(),
            lapDistanceM = if (lapStartedAt != null) lapDistanceM else null
        )
        val track = _state.value.track.copy(markers = _state.value.track.markers + marker)
        store.save(track)
        _state.value = _state.value.copy(track = track)
    }

    fun removeLastMarker() {
        val current = _state.value.track
        if (current.markers.isEmpty()) return
        val track = current.copy(markers = current.markers.dropLast(1))
        store.save(track)
        _state.value = _state.value.copy(track = track)
    }

    fun clearMarkers() {
        val track = _state.value.track.copy(markers = emptyList())
        store.save(track)
        _state.value = _state.value.copy(track = track)
    }

    override fun onCleared() {
        if (_state.value.recording) recorder.stopAndSave()
        stopSensorsAndGps()
        super.onCleared()
    }

    private fun parseTargetMillis(raw: String): Long? {
        val clean = raw.trim().replace(',', '.')
        if (clean.isBlank()) return null
        val seconds = if (clean.contains(':')) {
            val parts = clean.split(':')
            if (parts.size != 2) return null
            val minutes = parts[0].toIntOrNull() ?: return null
            val sec = parts[1].toDoubleOrNull() ?: return null
            minutes * 60.0 + sec
        } else clean.toDoubleOrNull() ?: return null
        return (seconds * 1000.0).toLong()
    }

    private fun formatSignedMillis(ms: Long): String {
        val sign = if (ms > 0) "+" else if (ms < 0) "−" else "±"
        return "$sign${"%.3f".format(kotlin.math.abs(ms) / 1000.0)} s"
    }

    private fun formatMillis(ms: Long): String {
        val seconds = ms / 1000.0
        return if (seconds >= 60) "%d:%06.3f".format((seconds / 60).toInt(), seconds % 60) else "%.3f s".format(seconds)
    }
}
