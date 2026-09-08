package it.kartcoach.gps

/**
 * Riferimenti incorporati per Morcone ricavati dall'analisi della telemetria di Damiano
 * e dal video del pilota professionista sullo stesso kart.
 *
 * Non è un giro maestro importato: sono soltanto due punti di tecnica verificati
 * (inizio apertura volante) usati come cue preventivi.
 */
object MorconeTechnique {
    private const val TRACK_ID = "morcone"

    val builtInMarkers: List<CoachMarker> = listOf(
        CoachMarker(
            id = 8_105_005L,
            type = CueType.STRAIGHTEN,
            latitude = 41.36632241,
            longitude = 14.70400319,
            baseRadiusM = 4.5,
            leadSeconds = 0.22,
            holdMillis = 850L,
            note = "C5 · VOLANTE · RAGGIO GRANDE · 71–72 km/h",
            expectedBearingDeg = 164.0
        ),
        CoachMarker(
            id = 8_105_006L,
            type = CueType.STRAIGHTEN,
            latitude = 41.36569170,
            longitude = 14.70354607,
            baseRadiusM = 4.5,
            leadSeconds = 0.32,
            holdMillis = 1_050L,
            note = "C6 · VOLANTE · APRI SUBITO · USA L'USCITA",
            expectedBearingDeg = 305.0
        )
    )

    fun markersFor(track: TrackConfig): List<CoachMarker> =
        if (track.profileId == TRACK_ID) builtInMarkers else emptyList()
}
