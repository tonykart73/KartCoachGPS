package it.kartcoach.gps

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper

class LocationTracker(
    context: Context,
    private val onLocation: (GpsPoint) -> Unit,
    private val onProviderState: (Boolean) -> Unit
) : LocationListener {
    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @SuppressLint("MissingPermission")
    fun start() {
        onProviderState(manager.isProviderEnabled(LocationManager.GPS_PROVIDER))
        manager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            50L,
            0f,
            this,
            Looper.getMainLooper()
        )
    }

    fun stop() = manager.removeUpdates(this)

    override fun onLocationChanged(location: Location) {
        onLocation(
            GpsPoint(
                latitude = location.latitude,
                longitude = location.longitude,
                speedMps = if (location.hasSpeed()) location.speed else 0f,
                bearingDeg = if (location.hasBearing()) location.bearing else 0f,
                accuracyM = if (location.hasAccuracy()) location.accuracy else Float.NaN,
                elapsedRealtimeNanos = location.elapsedRealtimeNanos,
                wallTimeMillis = location.time
            )
        )
    }

    override fun onProviderEnabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER) onProviderState(true)
    }

    override fun onProviderDisabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER) onProviderState(false)
    }

    @Deprecated("Deprecated in Android")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
}
