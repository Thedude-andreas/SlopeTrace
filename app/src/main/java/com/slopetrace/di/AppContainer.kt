package com.slopetrace.di

import android.content.Context
import com.slopetrace.data.local.AppDatabase
import com.slopetrace.data.remote.RealtimeRepository
import com.slopetrace.domain.classification.ClassificationEngine
import com.slopetrace.domain.coords.CoordinateConverter
import com.slopetrace.domain.stats.StatsEngine
import com.slopetrace.sensor.SensorRepository
import com.slopetrace.tracking.ActiveSessionStore
import com.slopetrace.tracking.TrackingRepository
import com.slopetrace.ui.login.AuthPreferencesStore
import com.slopetrace.ui.session.SessionViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val db = AppDatabase.get(appContext)
    private val dao = db.trackingDao()

    val activeSessionStore = ActiveSessionStore(appContext)
    private val authPreferencesStore = AuthPreferencesStore(appContext)
    private val sensorRepository = SensorRepository(appContext, appScope)
    private val classificationEngine = ClassificationEngine()
    private val coordinateConverter = CoordinateConverter()
    val trackingRepository = TrackingRepository(
        appContext = appContext,
        sensorRepository = sensorRepository,
        dao = dao,
        classifier = classificationEngine,
        converter = coordinateConverter,
        scope = appScope
    )
    val realtimeRepository = RealtimeRepository(dao, appScope)
    private val statsEngine = StatsEngine()

    fun createSessionViewModel(): SessionViewModel {
        return SessionViewModel(
            trackingRepository = trackingRepository,
            realtimeRepository = realtimeRepository,
            activeSessionStore = activeSessionStore,
            authPreferencesStore = authPreferencesStore,
            statsEngine = statsEngine
        )
    }
}
