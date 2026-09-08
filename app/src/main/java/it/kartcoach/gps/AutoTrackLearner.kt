package it.kartcoach.gps

class AutoTrackLearner(private val nominalLengthM: Double) {
    private var anchor: GpsPoint? = null
    private var distanceSinceAnchor = 0.0
    private var anchorTimeMs = 0L
    private var previous: GpsPoint? = null

    fun reset() {
        anchor = null
        distanceSinceAnchor = 0.0
        anchorTimeMs = 0L
        previous = null
    }

    fun update(point: GpsPoint): GpsPoint? {
        val prev = previous
        if (prev != null) distanceSinceAnchor += Geo.distanceMeters(prev, point.latitude, point.longitude)
        previous = point

        if (anchor == null) {
            if (point.speedMps >= 8.0f && (!point.accuracyM.isFinite() || point.accuracyM <= 12f)) {
                anchor = point
                anchorTimeMs = point.wallTimeMillis
                distanceSinceAnchor = 0.0
            }
            return null
        }

        val a = anchor ?: return null
        val enoughDistance = distanceSinceAnchor >= nominalLengthM * 0.70
        val enoughTime = point.wallTimeMillis - anchorTimeMs >= 18_000L
        val close = Geo.distanceMeters(point, a.latitude, a.longitude) <= 14.0
        val directionMatches = Geo.angularDifferenceDeg(point.bearingDeg.toDouble(), a.bearingDeg.toDouble()) <= 50.0

        return if (enoughDistance && enoughTime && close && directionMatches && point.speedMps >= 6f) {
            a.copy(speedMps = 0f, bearingDeg = a.bearingDeg)
        } else null
    }
}
