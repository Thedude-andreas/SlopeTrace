package com.slopetrace.di

import android.content.Context
import com.slopetrace.data.local.AppDatabase
import com.slopetrace.data.remote.RealtimeRepository
import com.slopetrace.domain.classification.ClassificationEngine
import com.slopetrace.domain.coords.CoordinateConverter
import com.slopetrace.domain.stats.StatsEngine
import com.slopetrace.sensor.SensorRepository
import com.slopetrace.tracking.TrackingRepository
import com.slopetrace.ui.session.SessionViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AppContainer(context: Context) {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val db = AppDatabase.get(context)
    private val dao = db.trackingDao()

    private val sensorRepository = SensorRepository(context, appScope)
    private val classificationEngine = ClassificationEngine()
    private val coordinateConverter = CoordinateConverter()
    private val trackingRepository = TrackingRepository(
        sensorRepository = sensorRepository,
        dao = dao,
        classifier = classificationEngine,
        converter = coordinateConverter,
        scope = appScope
    )
    private val realtimeRepository = RealtimeRepository(dao, appScope)
    private val statsEngine = StatsEngine()

    fun createSessionViewModel(): SessionViewModel {
        return SessionViewModel(
            trackingRepository = trackingRepository,
            realtimeRepository = realtimeRepository,
            statsEngine = statsEngine
        )
    }
}
