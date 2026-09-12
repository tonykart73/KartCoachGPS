package it.kartcoach.gps

import kotlin.math.max
import kotlin.math.min

class SessionAnalyzer(private val segments: Int = 24) {

    data class SegmentStats(
        val lap: LapData,
        val index: Int,
        val startIndex: Int,
        val endIndex: Int,
        val startFrac: Double,
        val endFrac: Double,
        val timeMs: Long,
        val entryKmh: Double,
        val minKmh: Double,
        val exitKmh: Double,
        val brakeFrac: Double?,
        val accelFrac: Double?,
        val peakTurnRateDegS: Double,
        val distanceStartM: Double,
        val minSpeedDistanceM: Double,
        val totalDistanceM: Double,
        val markerSample: TelemetrySample
    )

    fun analyze(laps: List<LapData>): SessionAnalysis? {
        val valid = laps.filter { it.durationMs in 20_000L..180_000L && it.samples.size >= 20 }
        if (valid.size < 2) return null

        val bestLap = valid.minBy { it.durationMs }
        val byLap = valid.associateWith { buildSegments(it) }
        if (byLap.values.any { it.size < segments }) return null

        val idealSegments = (0 until segments).map { segmentIndex ->
            byLap.values.map { it[segmentIndex] }.minBy { it.timeMs }
        }
        val idealMs = idealSegments.sumOf { it.timeMs }
        val bestSegments = byLap.getValue(bestLap)

        val rawFindings = bestSegments.mapIndexedNotNull { i, own ->
            val ideal = idealSegments[i]
            val delta = own.timeMs - ideal.timeMs
            if (delta < 35L || ideal.lap.number == bestLap.number) null
            else buildFinding(own, ideal, delta)
        }

        val findings = rawFindings
            .sortedByDescending { it.deltaMs }
            .take(4)
            .mapIndexed { index, finding -> finding.copy(rank = index + 1) }

        return SessionAnalysis(
            bestLapMs = bestLap.durationMs,
            idealLapMs = idealMs,
            potentialGainMs = max(0L, bestLap.durationMs - idealMs),
            findings = findings
        )
    }

    private fun buildSegments(lap: LapData): List<SegmentStats> {
        val samples = lap.samples
        val cumulative = DoubleArray(samples.size)
        for (i in 1 until samples.size) {
            cumulative[i] = cumulative[i - 1] +
                Geo.distanceMeters(samples[i - 1].point, samples[i].point.latitude, samples[i].point.longitude)
        }
        val total = cumulative.last().coerceAtLeast(1.0)

        return (0 until segments).map { seg ->
            val startFrac = seg.toDouble() / segments
            val endFrac = (seg + 1).toDouble() / segments
            val startDist = total * startFrac
            val endDist = total * endFrac
            var si = cumulative.indexOfFirst { it >= startDist }.let { if (it < 0) samples.lastIndex else it }
            var ei = cumulative.indexOfFirst { it >= endDist }.let { if (it < 0) samples.lastIndex else it }
            if (ei <= si) ei = min(samples.lastIndex, si + 1)
            if (si >= ei) si = max(0, ei - 1)

            val window = samples.subList(si, ei + 1)
            val speeds = window.map { it.point.speedMps.toDouble() * 3.6 }
            val entry = speeds.first()
            val minSpeed = speeds.minOrNull() ?: entry
            val exit = speeds.last()
            val timeMs = (window.last().point.wallTimeMillis - window.first().point.wallTimeMillis).coerceAtLeast(1L)
            val dynamics = dynamics(window, cumulative, si, total)
            val minIdxLocal = minSpeedIndex(speeds).coerceIn(0, window.lastIndex)
            val marker = window[minIdxLocal]
            val minIdxGlobal = (si + minIdxLocal).coerceAtMost(cumulative.lastIndex)

            SegmentStats(
                lap = lap,
                index = seg,
                startIndex = si,
                endIndex = ei,
                startFrac = startFrac,
                endFrac = endFrac,
                timeMs = timeMs,
                entryKmh = entry,
                minKmh = minSpeed,
                exitKmh = exit,
                brakeFrac = dynamics.first,
                accelFrac = dynamics.second,
                peakTurnRateDegS = peakTurnRate(window),
                distanceStartM = startDist,
                minSpeedDistanceM = cumulative[minIdxGlobal],
                totalDistanceM = total,
                markerSample = marker
            )
        }
    }

    private fun dynamics(
        window: List<TelemetrySample>,
        cumulative: DoubleArray,
        globalStart: Int,
        totalDistance: Double
    ): Pair<Double?, Double?> {
        if (window.size < 3) return null to null
        var brake: Double? = null
        var accel: Double? = null
        var minIndex = 0
        var minSpeed = Double.MAX_VALUE

        for (i in window.indices) {
            val speed = window[i].point.speedMps.toDouble()
            if (speed < minSpeed) {
                minSpeed = speed
                minIndex = i
            }
        }

        for (i in 1 until window.size) {
            val dt = (window[i].point.wallTimeMillis - window[i - 1].point.wallTimeMillis) / 1000.0
            if (dt <= 0.015) continue
            val acc = (window[i].point.speedMps - window[i - 1].point.speedMps) / dt
            val frac = cumulative[(globalStart + i).coerceAtMost(cumulative.lastIndex)] / totalDistance
            if (i <= minIndex && brake == null && acc < -1.4) brake = frac
            if (i >= minIndex && accel == null && acc > 0.9) accel = frac
        }
        return brake to accel
    }

    private fun peakTurnRate(window: List<TelemetrySample>): Double {
        var peak = 0.0
        for (i in 1 until window.size) {
            val dt = (window[i].point.wallTimeMillis - window[i - 1].point.wallTimeMillis) / 1000.0
            if (dt <= 0.015) continue
            val d = Geo.angularDifferenceDeg(
                window[i].point.bearingDeg.toDouble(),
                window[i - 1].point.bearingDeg.toDouble()
            )
            peak = max(peak, d / dt)
        }
        return peak
    }

    private fun minSpeedIndex(speeds: List<Double>): Int {
        var idx = 0
        var value = Double.MAX_VALUE
        speeds.forEachIndexed { i, v ->
            if (v < value) {
                value = v
                idx = i
            }
        }
        return idx
    }

    private fun buildFinding(own: SegmentStats, ideal: SegmentStats, delta: Long): AnalysisFinding {
        val entryDiff = own.entryKmh - ideal.entryKmh
        val minDiff = own.minKmh - ideal.minKmh
        val exitDiff = own.exitKmh - ideal.exitKmh
        val brakeEarlier = when {
            own.brakeFrac == null || ideal.brakeFrac == null -> false
            else -> own.brakeFrac < ideal.brakeFrac - 0.006
        }
        val accelLater = when {
            own.accelFrac == null || ideal.accelFrac == null -> false
            else -> own.accelFrac > ideal.accelFrac + 0.006
        }
        val rotatesTooHard = own.peakTurnRateDegS > ideal.peakTurnRateDegS * 1.15

        val (title, advice, cue) = when {
            brakeEarlier && entryDiff < -2.0 -> Triple(
                "Freni troppo presto",
                "Porta la frenata più avanti e falla più corta.",
                CueType.BRAKE
            )
            entryDiff > 2.5 && exitDiff < -3.0 -> Triple(
                "Ingresso troppo aggressivo",
                "Sacrifica leggermente l'ingresso e prepara prima l'uscita.",
                CueType.TURN
            )
            accelLater -> Triple(
                "Riapertura gas tardiva",
                "Finita la rotazione, libera il volante e riapri prima.",
                CueType.THROTTLE
            )
            rotatesTooHard -> Triple(
                "Rotazione troppo brusca",
                "Una sola rotazione progressiva: evita il colpo di sterzo.",
                CueType.TURN
            )
            minDiff < -3.0 || exitDiff < -3.0 -> Triple(
                "Volante tenuto troppo",
                "Come nel video del professionista: dopo il picco di rotazione APRI, non aspettare la minima e usa l'uscita.",
                CueType.STRAIGHTEN
            )
            else -> Triple(
                "Transizione da pulire",
                "Riduci il tempo di transizione e torna prima in accelerazione.",
                CueType.THROTTLE
            )
        }

        // Il marker e' collocato sul momento dell'azione del giro migliore interno.
        // CoachEngine lo anticipa poi con leadSeconds in funzione della velocita'.
        val cueDistance = when (cue) {
            CueType.BRAKE -> ideal.brakeFrac?.times(ideal.totalDistanceM) ?: ideal.distanceStartM
            CueType.THROTTLE, CueType.FULL_THROTTLE ->
                ideal.accelFrac?.times(ideal.totalDistanceM) ?: ideal.minSpeedDistanceM
            CueType.TURN -> ideal.minSpeedDistanceM
            CueType.STRAIGHTEN -> ideal.minSpeedDistanceM
        }.coerceIn(0.0, ideal.totalDistanceM)

        val evidence = buildString {
            append("Δ +%.3f s".format(delta / 1000.0))
            append(" · ingresso %.1f/%.1f".format(own.entryKmh, ideal.entryKmh))
            append(" · minima %.1f/%.1f".format(own.minKmh, ideal.minKmh))
            append(" · uscita %.1f/%.1f km/h".format(own.exitKmh, ideal.exitKmh))
        }

        val marker = ideal.markerSample.point
        return AnalysisFinding(
            rank = 0,
            deltaMs = delta,
            distanceFromLapStartM = cueDistance,
            title = title,
            advice = advice,
            cueType = cue,
            latitude = marker.latitude,
            longitude = marker.longitude,
            expectedBearingDeg = marker.bearingDeg.toDouble(),
            evidence = evidence
        )
    }
}
