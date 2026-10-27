package com.seattlegymsquad.poomdrive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.seattlegymsquad.poomdrive.care.CareEngine
import com.seattlegymsquad.poomdrive.care.DemoSettings
import com.seattlegymsquad.poomdrive.care.TripStore
import com.seattlegymsquad.poomdrive.data.RestAreaRepository
import com.seattlegymsquad.poomdrive.data.StoryBook
import com.seattlegymsquad.poomdrive.navi.NaviAgent
import com.seattlegymsquad.poomdrive.vehicle.VehicleMonitor
import com.seattlegymsquad.poomdrive.vehicle.VehicleState
import com.seattlegymsquad.poomdrive.voice.VoiceAgent

/**
 * Foreground service that keeps the care rules running while the app is off screen. Without it the system
 * freezes a background app after about 23 seconds (measured on the Pleos emulator).
 */
class PoomService : Service() {
    lateinit var state: VehicleState
        private set
    lateinit var store: TripStore
        private set
    lateinit var demo: DemoSettings
        private set
    lateinit var voice: VoiceAgent
        private set
    lateinit var navi: NaviAgent
        private set
    lateinit var monitor: VehicleMonitor
        private set
    lateinit var care: CareEngine
        private set

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        state = VehicleState()
        store = TripStore(this)
        demo = DemoSettings(this)
        voice = VoiceAgent(this)
        navi = NaviAgent(this)
        care = CareEngine(this, state, voice, navi, store, demo, RestAreaRepository(this), StoryBook(this))
        monitor = VehicleMonitor(this, state, care)
        voice.start()
        navi.start()
        monitor.start()
        care.start()
        instance = this
        log("service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        care.stop()
        monitor.stop()
        navi.release()
        voice.release()
        log("service destroyed")
        super.onDestroy()
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    companion object {
        private const val CHANNEL = "care"
        private const val NOTIFICATION_ID = 1

        @Volatile
        var instance: PoomService? = null
            private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, PoomService::class.java))
        }
    }
}
