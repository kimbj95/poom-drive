package com.seattlegymsquad.poomdrive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.seattlegymsquad.poomdrive.voice.PassengerType

/**
 * Lets the driver pick who is on board while driving, without opening the app: the app's own screen is blocked
 * while driving, but the notification center is system UI. Only the passenger choice is offered, nothing else.
 * It waits in the notification center without popping up (voice asks first); each tap adds one kind, and the
 * buttons left are the kinds not picked yet.
 */
object PassengerPrompt {
    private const val CHANNEL = "passenger_pick"
    private const val NOTIFICATION_ID = 3
    const val ACTION = "com.seattlegymsquad.poomdrive.PICK_PASSENGER"
    const val EXTRA_TYPE = "type"

    fun show(context: Context, picked: Set<PassengerType>) {
        val left = PassengerType.entries - picked
        if (left.isEmpty()) {
            dismiss(context)
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.passenger_channel), NotificationManager.IMPORTANCE_LOW)
        )
        val title = if (picked.isEmpty()) {
            context.getString(R.string.passenger_title)
        } else {
            context.getString(R.string.passenger_title_more, picked.sorted().joinToString(" · ") { it.noun })
        }
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.passenger_text))
            .setOnlyAlertOnce(true)
            // Its own group, so the system does not fold it under the care notification, where it would take an extra tap.
            .setGroup(CHANNEL)
        for (type in left) {
            val pick = PendingIntent.getBroadcast(
                context, type.ordinal,
                Intent(context, PassengerPickReceiver::class.java).setAction(ACTION).putExtra(EXTRA_TYPE, type.name),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(Notification.Action.Builder(null, type.noun, pick).build())
        }
        manager.notify(NOTIFICATION_ID, builder.build())
        log("passenger prompt shown: $title")
    }

    fun dismiss(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }
}

class PassengerPickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val type = intent.getStringExtra(PassengerPrompt.EXTRA_TYPE)?.let { PassengerType.valueOf(it) } ?: return
        PoomService.instance?.care?.onPassengerPicked(type)
    }
}
