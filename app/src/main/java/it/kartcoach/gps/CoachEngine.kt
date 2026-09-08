package it.kartcoach.gps

class CoachEngine {
    private var activeUntilMs = 0L
    private var activeMarkerId: Long? = null
    private val lastTriggeredAt = mutableMapOf<Long, Long>()

    data class Result(val marker: CoachMarker?, val distanceM: Double?)

    fun reset() {
        activeUntilMs = 0L
        activeMarkerId = null
        lastTriggeredAt.clear()
    }

    fun update(nowMs: Long, location: GpsPoint, track: TrackConfig): Result {
        if (activeMarkerId != null && nowMs < activeUntilMs) {
            val marker = track.markers.firstOrNull { it.id == activeMarkerId }
            return Result(marker, marker?.let { Geo.distanceMeters(location, it.latitude, it.longitude) })
        }
        activeMarkerId = null

        val candidates = track.markers.map { marker ->
            marker to Geo.distanceMeters(location, marker.latitude, marker.longitude)
        }.sortedBy { it.second }

        for ((marker, distance) in candidates) {
            val expected = marker.expectedBearingDeg
            val bearingOk = expected == null || location.speedMps < 3f ||
                Geo.angularDifferenceDeg(location.bearingDeg.toDouble(), expected) <= 65.0
            if (!bearingOk) continue

            val dynamicLeadM = location.speedMps.toDouble() * marker.leadSeconds
            val triggerDistance = maxOf(marker.baseRadiusM, dynamicLeadM)
            val recentlyTriggered = nowMs - (lastTriggeredAt[marker.id] ?: 0L) < 7_000L
            if (distance <= triggerDistance && !recentlyTriggered) {
                activeMarkerId = marker.id
                activeUntilMs = nowMs + marker.holdMillis
                lastTriggeredAt[marker.id] = nowMs
                return Result(marker, distance)
            }
        }
        return Result(null, candidates.firstOrNull()?.second)
    }
}
