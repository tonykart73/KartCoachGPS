package it.kartcoach.gps

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class TrackStore(context: Context) {
    private val prefs = context.getSharedPreferences("kartcoach_track", Context.MODE_PRIVATE)

    fun load(): TrackConfig {
        val raw = prefs.getString("track_json", null) ?: return TrackConfig()
        return runCatching { fromJson(JSONObject(raw)) }.getOrElse { TrackConfig() }
    }

    fun save(track: TrackConfig) {
        prefs.edit().putString("track_json", toJson(track).toString()).apply()
    }

    fun exportJson(track: TrackConfig): String = toJson(track).toString(2)

    private fun toJson(track: TrackConfig): JSONObject = JSONObject().apply {
        put("profileId", track.profileId)
        put("name", track.name)
        put("startFinishRadiusM", track.startFinishRadiusM)
        track.startFinish?.let {
            put("startFinish", JSONObject().apply {
                put("latitude", it.latitude)
                put("longitude", it.longitude)
                put("bearingDeg", it.bearingDeg.toDouble())
            })
        } ?: put("startFinish", JSONObject.NULL)
        put("markers", JSONArray().apply {
            track.markers.forEach { marker ->
                put(JSONObject().apply {
                    put("id", marker.id)
                    put("type", marker.type.name)
                    put("latitude", marker.latitude)
                    put("longitude", marker.longitude)
                    put("baseRadiusM", marker.baseRadiusM)
                    put("leadSeconds", marker.leadSeconds)
                    put("holdMillis", marker.holdMillis)
                    put("note", marker.note)
                    marker.expectedBearingDeg?.let { put("expectedBearingDeg", it) }
                })
            }
        })
    }

    private fun fromJson(obj: JSONObject): TrackConfig {
        val sfObj = obj.optJSONObject("startFinish")
        val sf = sfObj?.let {
            GpsPoint(
                latitude = it.getDouble("latitude"),
                longitude = it.getDouble("longitude"),
                bearingDeg = it.optDouble("bearingDeg", 0.0).toFloat()
            )
        }
        val markersArray = obj.optJSONArray("markers") ?: JSONArray()
        val markers = buildList {
            for (i in 0 until markersArray.length()) {
                val m = markersArray.getJSONObject(i)
                add(
                    CoachMarker(
                        id = m.optLong("id", System.nanoTime()),
                        type = runCatching { CueType.valueOf(m.getString("type")) }.getOrDefault(CueType.STRAIGHTEN),
                        latitude = m.getDouble("latitude"),
                        longitude = m.getDouble("longitude"),
                        baseRadiusM = m.optDouble("baseRadiusM", 5.0),
                        leadSeconds = m.optDouble("leadSeconds", 0.45),
                        holdMillis = m.optLong("holdMillis", 900L),
                        note = m.optString("note", ""),
                        expectedBearingDeg = if (m.has("expectedBearingDeg")) m.optDouble("expectedBearingDeg") else null
                    )
                )
            }
        }
        return TrackConfig(
            profileId = obj.optString("profileId", "morcone"),
            name = obj.optString("name", "Morcone"),
            startFinish = sf,
            startFinishRadiusM = obj.optDouble("startFinishRadiusM", 10.0),
            markers = markers
        )
    }
}
