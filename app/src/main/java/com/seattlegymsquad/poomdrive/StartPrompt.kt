package com.seattlegymsquad.poomdrive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * The "start again?" notification. A broadcast in the background may not start a foreground service, but a tap
 * on the app's notification may, so the phone connection only asks and the driver's tap starts the service.
 */
object StartPrompt {
    private const val CHANNEL = "start"
    private const val NOTIFICATION_ID = 2

    fun onPhoneConnected(context: Context) {
        if (PoomService.instance != null) {
            log("phone connected, care service already running")
            return
        }
        log("phone connected while the care service is stopped, asking to start")
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.start_channel), NotificationManager.IMPORTANCE_HIGH)
        )
        val start = PendingIntent.getForegroundService(
            context, 0, Intent(context, PoomService::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(context.getString(R.string.start_title))
            .setContentText(context.getString(R.string.start_text))
            .setContentIntent(start)
            .addAction(Notification.Action.Builder(null, context.getString(R.string.start_action), start).build())
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    /** Called when the service starts by any route, so a stale prompt does not stay on screen. */
    fun dismiss(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }
}
