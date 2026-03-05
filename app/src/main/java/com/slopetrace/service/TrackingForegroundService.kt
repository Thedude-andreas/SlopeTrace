package com.slopetrace.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.slopetrace.SlopeTraceApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class TrackingForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SlopeTrace tracking")
            .setContentText("Collecting ski session data")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val appContainer = (application as SlopeTraceApp).appContainer

        if (intent?.action == ACTION_STOP) {
            appContainer.activeSessionStore.clear()
            appContainer.trackingRepository.stopTracking()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val sessionId = intent?.getStringExtra(EXTRA_SESSION_ID)
        val userId = intent?.getStringExtra(EXTRA_USER_ID)

        if (!sessionId.isNullOrBlank() && !userId.isNullOrBlank()) {
            appContainer.activeSessionStore.save(
                com.slopetrace.tracking.ActiveSessionState(sessionId = sessionId, userId = userId)
            )
            ensureTracking(sessionId = sessionId, userId = userId)
        } else {
            appContainer.activeSessionStore.load()?.let { state ->
                ensureTracking(sessionId = state.sessionId, userId = state.userId)
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureTracking(sessionId: String, userId: String) {
        val appContainer = (application as SlopeTraceApp).appContainer
        serviceScope.launch {
            appContainer.trackingRepository.startTracking(sessionId = sessionId, userId = userId)
            // Tracking job runs inside repository/app scope. Service ensures lifecycle and restart.
        }
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Tracking",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "tracking_channel"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START = "com.slopetrace.action.START"
        private const val ACTION_STOP = "com.slopetrace.action.STOP"
        private const val EXTRA_SESSION_ID = "extra_session_id"
        private const val EXTRA_USER_ID = "extra_user_id"

        fun start(context: Context, sessionId: String?, userId: String) {
            val intent = Intent(context, TrackingForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_USER_ID, userId)
                sessionId?.let { putExtra(EXTRA_SESSION_ID, it) }
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, TrackingForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
