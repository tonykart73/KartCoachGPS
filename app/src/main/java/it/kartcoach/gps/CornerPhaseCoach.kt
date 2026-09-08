package it.kartcoach.gps

/**
 * Coach dinamico senza giro di riferimento esterno.
 *
 * Dal video del professionista emerge una regola ripetuta: una sola rotazione,
 * poi apertura del volante subito dopo il picco di rotazione, senza aspettare
 * che il kart continui a perdere velocità. Questa classe riconosce quel momento
 * usando variazione di heading GPS e calo di velocità.
 */
class CornerPhaseCoach {
    private var previous: GpsPoint? = null
    private var filteredTurnRateDegS = 0.0

    private var inCorner = false
    private var cornerStartedAtMs = 0L
    private var entrySpeedMps = 0.0
    private var minSpeedMps = Double.MAX_VALUE
    private var minSpeedAtMs = 0L
    private var peakTurnRateDegS = 0.0
    private var peakAtMs = 0L
    private var cueIssuedForCorner = false
    private var belowExitRateSinceMs: Long? = null

    private var lastCueAtMs = Long.MIN_VALUE / 2
    private var serial = 0L

    fun reset() {
        previous = null
        filteredTurnRateDegS = 0.0
        clearCorner()
        lastCueAtMs = Long.MIN_VALUE / 2
        serial = 0L
    }

    fun update(nowMs: Long, point: GpsPoint, enabled: Boolean): CoachMarker? {
        val prev = previous
        previous = point

        if (!enabled) {
            clearCorner()
            return null
        }
        if (prev == null) return null

        val currentTime = if (point.wallTimeMillis > 0L) point.wallTimeMillis else nowMs
        val previousTime = if (prev.wallTimeMillis > 0L) prev.wallTimeMillis else currentTime - 100L
        val dt = (currentTime - previousTime) / 1000.0
        if (dt !in 0.03..1.20) return null

        if (point.speedMps < 6.0f || (point.accuracyM.isFinite() && point.accuracyM > 15f)) {
            filteredTurnRateDegS *= 0.55
            clearCorner()
            return null
        }

        val rawTurnRate = Geo.angularDifferenceDeg(
            point.bearingDeg.toDouble(),
            prev.bearingDeg.toDouble()
        ) / dt
        filteredTurnRateDegS = if (filteredTurnRateDegS == 0.0) rawTurnRate
        else filteredTurnRateDegS * 0.65 + rawTurnRate * 0.35

        if (!inCorner) {
            if (filteredTurnRateDegS >= ENTRY_RATE_DEG_S && point.speedMps >= 7.0f) {
                inCorner = true
                cornerStartedAtMs = currentTime
                entrySpeedMps = point.speedMps.toDouble()
                minSpeedMps = point.speedMps.toDouble()
                minSpeedAtMs = currentTime
                peakTurnRateDegS = filteredTurnRateDegS
                peakAtMs = currentTime
                cueIssuedForCorner = false
                belowExitRateSinceMs = null
            }
            return null
        }

        if (point.speedMps.toDouble() < minSpeedMps) {
            minSpeedMps = point.speedMps.toDouble()
            minSpeedAtMs = currentTime
        }
        if (filteredTurnRateDegS >= peakTurnRateDegS) {
            peakTurnRateDegS = filteredTurnRateDegS
            peakAtMs = currentTime
        }

        val cornerAgeMs = currentTime - cornerStartedAtMs
        val sincePeakMs = currentTime - peakAtMs
        val speedDropKmh = (entrySpeedMps - minSpeedMps).coerceAtLeast(0.0) * 3.6

        val meaningfulCorner = peakTurnRateDegS >= 32.0 &&
            (speedDropKmh >= 2.0 || minSpeedMps * 3.6 <= 82.0)
        val peakHasJustPassed = sincePeakMs >= 80L &&
            filteredTurnRateDegS <= peakTurnRateDegS * 0.92
        val steeringHeldTooLong = sincePeakMs >= 280L &&
            filteredTurnRateDegS >= peakTurnRateDegS * 0.70
        val cueCooldownOk = nowMs - lastCueAtMs >= 1_400L

        if (!cueIssuedForCorner && cueCooldownOk && meaningfulCorner && cornerAgeMs >= 180L &&
            (peakHasJustPassed || steeringHeldTooLong)
        ) {
            cueIssuedForCorner = true
            lastCueAtMs = nowMs
            serial += 1L

            val stillLosingSpeedAfterPeak = minSpeedAtMs >= peakAtMs &&
                currentTime - minSpeedAtMs <= 180L
            val note = when {
                steeringHeldTooLong -> "VOLANTE · NON TENERE STERZO"
                stillLosingSpeedAfterPeak -> "VOLANTE · NON ASPETTARE LA MINIMA"
                else -> "VOLANTE · USA L'USCITA"
            }

            return CoachMarker(
                id = -8_200_000L - serial,
                type = CueType.STRAIGHTEN,
                latitude = point.latitude,
                longitude = point.longitude,
                baseRadiusM = 0.0,
                leadSeconds = 0.0,
                holdMillis = 850L,
                note = note,
                expectedBearingDeg = point.bearingDeg.toDouble()
            )
        }

        if (filteredTurnRateDegS < EXIT_RATE_DEG_S) {
            if (belowExitRateSinceMs == null) belowExitRateSinceMs = currentTime
            if (currentTime - (belowExitRateSinceMs ?: currentTime) >= 180L) clearCorner()
        } else {
            belowExitRateSinceMs = null
        }

        if (cornerAgeMs > 4_000L) clearCorner()
        return null
    }

    private fun clearCorner() {
        inCorner = false
        cornerStartedAtMs = 0L
        entrySpeedMps = 0.0
        minSpeedMps = Double.MAX_VALUE
        minSpeedAtMs = 0L
        peakTurnRateDegS = 0.0
        peakAtMs = 0L
        cueIssuedForCorner = false
        belowExitRateSinceMs = null
    }

    private companion object {
        const val ENTRY_RATE_DEG_S = 13.0
        const val EXIT_RATE_DEG_S = 7.0
    }
}
