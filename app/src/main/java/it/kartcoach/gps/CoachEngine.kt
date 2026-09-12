package it.kartcoach.gps

/**
 * Live coach v0.5.
 *
 * La posizione GPS dello smartphone (tipicamente +/- 3-6 m) NON viene usata per
 * decidere la traiettoria o il punto preciso della curva. I marker appresi vengono
 * richiamati tramite distanza longitudinale dal passaggio S/F, ottenuta integrando
 * la velocita' GPS. Le coordinate restano solo come fallback per marker manuali e
 * vengono usate esclusivamente con fix molto preciso.
 */
class CoachEngine {
    private var activeUntilMs = 0L
    private var activeMarker: CoachMarker? = null
    private val lastTriggeredAt = mutableMapOf<Long, Long>()

    data class Result(val marker: CoachMarker?, val distanceM: Double?)

    fun reset() {
        activeUntilMs = 0L
        activeMarker = null
        lastTriggeredAt.clear()
    }

    fun newLap() {
        activeUntilMs = 0L
        activeMarker = null
    }

    fun update(
        nowMs: Long,
        location: GpsPoint,
        track: TrackConfig,
        lapDistanceM: Double? = null
    ): Result {
        val active = activeMarker
        if (active != null && nowMs < activeUntilMs) {
            val d = active.lapDistanceM?.let { target ->
                lapDistanceM?.let { live -> (target - live).coerceAtLeast(0.0) }
            }
            return Result(active, d)
        }
        activeMarker = null

        // 1) Marker appresi: trigger per distanza lungo il giro, non per lat/lon.
        if (lapDistanceM != null) {
            val distanceMarkers = track.markers
                .filter { it.lapDistanceM != null }
                .sortedBy { it.lapDistanceM }

            for (marker in distanceMarkers) {
                val target = marker.lapDistanceM ?: continue
                val remaining = target - lapDistanceM
                val leadM = maxOf(1.5, location.speedMps.toDouble() * marker.leadSeconds)
                val recentlyTriggered = nowMs - (lastTriggeredAt[marker.id] ?: 0L) < 8_000L

                // Piccola tolleranza negativa per aggiornamenti GPS a bassa frequenza.
                if (remaining in -1.5..leadM && !recentlyTriggered) {
                    activate(marker, nowMs)
                    lastTriggeredAt[marker.id] = nowMs
                    return Result(marker, remaining.coerceAtLeast(0.0))
                }
            }

            val next = distanceMarkers
                .mapNotNull { m -> m.lapDistanceM?.let { it - lapDistanceM } }
                .filter { it >= 0.0 }
                .minOrNull()
            if (next != null) return Result(null, next)
        }

        // 2) Fallback marker manuali a coordinate: SOLO con precisione <= 2.5 m.
        // Con +/-4 m non vengono lanciati falsi segnali.
        val preciseFix = location.accuracyM.isFinite() && location.accuracyM <= 2.5f
        if (preciseFix) {
            val coordinateMarkers = track.markers
                .filter { it.lapDistanceM == null }
                .map { marker -> marker to Geo.distanceMeters(location, marker.latitude, marker.longitude) }
                .sortedBy { it.second }

            for ((marker, distance) in coordinateMarkers) {
                val expected = marker.expectedBearingDeg
                val bearingOk = expected == null || location.speedMps < 3f ||
                    Geo.angularDifferenceDeg(location.bearingDeg.toDouble(), expected) <= 50.0
                if (!bearingOk) continue

                val leadM = maxOf(marker.baseRadiusM, location.speedMps.toDouble() * marker.leadSeconds)
                val recentlyTriggered = nowMs - (lastTriggeredAt[marker.id] ?: 0L) < 8_000L
                if (distance <= leadM && !recentlyTriggered) {
                    activate(marker, nowMs)
                    lastTriggeredAt[marker.id] = nowMs
                    return Result(marker, distance)
                }
            }
        }

        return Result(null, null)
    }

    private fun activate(marker: CoachMarker, nowMs: Long) {
        activeMarker = marker
        activeUntilMs = nowMs + marker.holdMillis
    }
}
