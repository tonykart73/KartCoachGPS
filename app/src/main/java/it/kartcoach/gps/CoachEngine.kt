package it.kartcoach.gps

class CoachEngine {
    private var activeUntilMs = 0L
    private var activeMarker: CoachMarker? = null
    private val lastTriggeredAt = mutableMapOf<Long, Long>()
    private val cornerPhaseCoach = CornerPhaseCoach()

    data class Result(val marker: CoachMarker?, val distanceM: Double?)

    fun reset() {
        activeUntilMs = 0L
        activeMarker = null
        lastTriggeredAt.clear()
        cornerPhaseCoach.reset()
    }

    fun update(nowMs: Long, location: GpsPoint, track: TrackConfig): Result {
        val active = activeMarker
        if (active != null && nowMs < activeUntilMs) {
            val distance = if (active.baseRadiusM <= 0.0) 0.0
            else Geo.distanceMeters(location, active.latitude, active.longitude)
            return Result(active, distance)
        }
        activeMarker = null

        val builtIn = MorconeTechnique.markersFor(track)
        val candidates = (builtIn + track.markers)
            .distinctBy { it.id }
            .map { marker -> marker to Geo.distanceMeters(location, marker.latitude, marker.longitude) }
            .sortedBy { it.second }

        for ((marker, distance) in candidates) {
            val expected = marker.expectedBearingDeg
            val bearingOk = expected == null || location.speedMps < 3f ||
                Geo.angularDifferenceDeg(location.bearingDeg.toDouble(), expected) <= 65.0
            if (!bearingOk) continue

            val dynamicLeadM = location.speedMps.toDouble() * marker.leadSeconds
            val triggerDistance = maxOf(marker.baseRadiusM, dynamicLeadM)
            val recentlyTriggered = nowMs - (lastTriggeredAt[marker.id] ?: 0L) < 7_000L
            if (distance <= triggerDistance && !recentlyTriggered) {
                activate(marker, nowMs)
                lastTriggeredAt[marker.id] = nowMs
                return Result(marker, distance)
            }
        }

        // Vicino ai due punti Morcone calibrati non sommare un secondo cue dinamico.
        val nearBuiltIn = builtIn.any {
            Geo.distanceMeters(location, it.latitude, it.longitude) <= 24.0
        }
        val dynamicMarker = cornerPhaseCoach.update(
            nowMs = nowMs,
            point = location,
            enabled = track.profileId == "morcone" && !nearBuiltIn
        )
        if (dynamicMarker != null) {
            activate(dynamicMarker, nowMs)
            return Result(dynamicMarker, 0.0)
        }

        return Result(null, candidates.firstOrNull()?.second)
    }

    private fun activate(marker: CoachMarker, nowMs: Long) {
        activeMarker = marker
        activeUntilMs = nowMs + marker.holdMillis
    }
}
