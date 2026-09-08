package it.kartcoach.gps

import kotlin.math.*

object Geo {
    private const val EARTH_RADIUS_M = 6_371_000.0

    fun distanceMeters(a: GpsPoint, bLat: Double, bLon: Double): Double {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(bLat)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(bLon - a.longitude)
        val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    fun initialBearingDeg(a: GpsPoint, bLat: Double, bLon: Double): Double {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(bLat)
        val dLon = Math.toRadians(bLon - a.longitude)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    fun angularDifferenceDeg(a: Double, b: Double): Double {
        val d = abs(a - b) % 360.0
        return if (d > 180.0) 360.0 - d else d
    }
}
