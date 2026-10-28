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
        StartPrompt.dismiss(this)
        state = VehicleState()
        store = TripStore(this)
        demo = DemoSettings(this)
        voice = VoiceAgent(this)
        navi = NaviAgent(this)
        care = CareEngine(this, state, voice, navi, store, demo, RestAreaRepository(this), StoryBook(this), ::updateStatus)
        monitor = VehicleMonitor(this, state, care)
        voice.start()
        navi.start()
        monitor.start()
        care.start()
        instance = this
        log("service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Started again after the car slept: say so once, as nothing on screen shows it while driving.
        when (intent?.getStringExtra(StartPrompt.EXTRA_STARTED_BY)) {
            StartPrompt.BY_PHONE -> voice.say(getString(R.string.started_by_phone))
            StartPrompt.BY_PROMPT -> voice.say(getString(R.string.started_by_prompt))
        }
        return START_STICKY
    }

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
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.app_name)), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    /**
     * Puts the care status in the notification title ("품 드라이브 · 아이 동승 · 흔들림 2번"). The Pleos notification
     * center draws this app's body text very faintly, so the title carries what matters. No pop-up: it is seen
     * only when the notification center is opened.
     */
    private fun updateStatus(status: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification("${getString(R.string.app_name)} · $status"))
    }

    private fun buildNotification(title: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle(title)
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setGroup(CHANNEL)
            .build()
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
