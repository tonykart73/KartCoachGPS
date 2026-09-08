package it.kartcoach.gps

object TrackProfiles {
    // Morcone: il centro è volutamente un geofence ampio sulla Contrada Canepino.
    // La linea start/finish viene appresa dal telefono durante la prima sessione.
    val MORCONE = TrackProfile(
        id = "morcone",
        name = "Pista di Morcone",
        centerLat = 41.37044,
        centerLon = 14.68734,
        detectionRadiusM = 3_000.0,
        nominalLengthM = 800.0
    )

    val all = listOf(MORCONE)

    fun nearest(point: GpsPoint): TrackProfile? = all
        .map { it to Geo.distanceMeters(point, it.centerLat, it.centerLon) }
        .filter { (profile, distance) -> distance <= profile.detectionRadiusM }
        .minByOrNull { it.second }
        ?.first
}
