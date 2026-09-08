package it.kartcoach.gps

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

class SensorTracker(
    context: Context,
    private val onSnapshot: (SensorSnapshot) -> Unit
) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private var current = SensorSnapshot()

    fun start() {
        accelerometer?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gyroscope?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() = manager.unregisterListener(this)

    override fun onSensorChanged(event: SensorEvent) {
        current = when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> current.copy(
                ax = event.values.getOrElse(0) { 0f },
                ay = event.values.getOrElse(1) { 0f },
                az = event.values.getOrElse(2) { 0f }
            )
            Sensor.TYPE_GYROSCOPE -> current.copy(
                gx = event.values.getOrElse(0) { 0f },
                gy = event.values.getOrElse(1) { 0f },
                gz = event.values.getOrElse(2) { 0f }
            )
            else -> current
        }
        onSnapshot(current)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
