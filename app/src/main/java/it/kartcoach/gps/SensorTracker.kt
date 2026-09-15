package it.kartcoach.gps

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.sqrt

class SensorTracker(
    context: Context,
    private val onSnapshot: (SensorSnapshot) -> Unit
) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val linearAcceleration = manager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val gyroscope = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private var current = SensorSnapshot()

    fun start() {
        accelerometer?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        linearAcceleration?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gyroscope?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() = manager.unregisterListener(this)

    override fun onSensorChanged(event: SensorEvent) {
        val now = System.currentTimeMillis()
        current = when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val ax = event.values.getOrElse(0) { 0f }
                val ay = event.values.getOrElse(1) { 0f }
                val az = event.values.getOrElse(2) { 0f }
                // Fallback se TYPE_LINEAR_ACCELERATION non e' disponibile.
                val fallbackLinear = abs(sqrt(ax * ax + ay * ay + az * az) - SensorManager.GRAVITY_EARTH)
                current.copy(
                    ax = ax,
                    ay = ay,
                    az = az,
                    linearAccelMps2 = if (linearAcceleration == null) fallbackLinear else current.linearAccelMps2,
                    wallTimeMillis = now
                )
            }

            Sensor.TYPE_LINEAR_ACCELERATION -> {
                val lx = event.values.getOrElse(0) { 0f }
                val ly = event.values.getOrElse(1) { 0f }
                val lz = event.values.getOrElse(2) { 0f }
                current.copy(
                    linearAccelMps2 = sqrt(lx * lx + ly * ly + lz * lz),
                    wallTimeMillis = now
                )
            }

            Sensor.TYPE_GYROSCOPE -> {
                val gx = event.values.getOrElse(0) { 0f }
                val gy = event.values.getOrElse(1) { 0f }
                val gz = event.values.getOrElse(2) { 0f }
                current.copy(
                    gx = gx,
                    gy = gy,
                    gz = gz,
                    gyroMagnitudeRadS = sqrt(gx * gx + gy * gy + gz * gz),
                    wallTimeMillis = now
                )
            }

            else -> current.copy(wallTimeMillis = now)
        }
        onSnapshot(current)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
