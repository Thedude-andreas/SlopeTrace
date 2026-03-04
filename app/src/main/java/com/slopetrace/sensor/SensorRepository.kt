package com.slopetrace.sensor

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

data class SensorSnapshot(
    val timestampMs: Long,
    val latitude: Double,
    val longitude: Double,
    val speedMps: Double,
    val pressureHpa: Float,
    val accelerationMagnitude: Float
)

class SensorRepository(
    context: Context,
    private val scope: CoroutineScope
) {
    private val appContext = context.applicationContext
    private val fused = LocationServices.getFusedLocationProviderClient(appContext)
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val pressureFlow = MutableStateFlow(1013.25f)
    private val accelerationFlow = MutableStateFlow(0f)

    private val pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
    private val accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private var sensorsRegistered = false

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_PRESSURE -> pressureFlow.update { event.values[0] }
                Sensor.TYPE_LINEAR_ACCELERATION -> {
                    val x = event.values[0]
                    val y = event.values[1]
                    val z = event.values[2]
                    accelerationFlow.update { kotlin.math.sqrt(x * x + y * y + z * z) }
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    fun shutdown() {
        if (!sensorsRegistered) return
        sensorManager.unregisterListener(sensorListener)
        sensorsRegistered = false
    }

    @SuppressLint("MissingPermission")
    fun trackingFlow(): Flow<SensorSnapshot> {
        registerSensorsIfNeeded()
        val locationFlow = callbackFlow {
            var currentIntervalMs = 1000L

            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    for (location in result.locations) {
                        val nextInterval = when {
                            location.speed > 5f -> 200L
                            location.speed < 1f -> 2000L
                            else -> 1000L
                        }

                        if (nextInterval != currentIntervalMs) {
                            currentIntervalMs = nextInterval
                            requestUpdates(currentIntervalMs, this)
                        }

                        trySend(location)
                    }
                }
            }

            fun start(intervalMs: Long) {
                currentIntervalMs = intervalMs
                requestUpdates(intervalMs, callback)
            }

            start(1000L)

            awaitClose {
                fused.removeLocationUpdates(callback)
            }
        }

        return combine(locationFlow, pressureFlow, accelerationFlow) { location, pressure, acc ->
            SensorSnapshot(
                timestampMs = location.time.takeIf { it > 0 } ?: Clock.System.now().toEpochMilliseconds(),
                latitude = location.latitude,
                longitude = location.longitude,
                speedMps = location.speed.toDouble().coerceAtLeast(0.0),
                pressureHpa = pressure,
                accelerationMagnitude = acc
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestUpdates(intervalMs: Long, callback: LocationCallback) {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs)
            .setWaitForAccurateLocation(false)
            .build()
        scope.launch(Dispatchers.Main.immediate) {
            fused.removeLocationUpdates(callback)
            fused.requestLocationUpdates(request, callback, appContext.mainLooper)
        }
    }

    private fun registerSensorsIfNeeded() {
        if (sensorsRegistered) return
        pressureSensor?.also {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        accelSensor?.also {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        sensorsRegistered = true
    }
}
