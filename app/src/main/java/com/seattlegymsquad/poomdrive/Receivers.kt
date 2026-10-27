package com.seattlegymsquad.poomdrive

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the care service after a full boot. A car resumed from Suspend keeps the process, so no event is needed. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            log("boot completed, starting service")
            PoomService.start(context)
        }
    }
}

/**
 * Covers a car that came back from Suspend without our process: the driver's phone reconnecting over Bluetooth
 * wakes the app even when it is not running (an exempt broadcast), and [StartPrompt] asks to start again.
 */
class PhoneConnectReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == BluetoothDevice.ACTION_ACL_CONNECTED) StartPrompt.onPhoneConnected(context)
    }
}

/**
 * Demo and test commands from adb (the receiver requires the DUMP permission, which only the shell holds):
 * `adb shell am broadcast -a com.seattlegymsquad.poomdrive.DEMO -n com.seattlegymsquad.poomdrive/.DemoReceiver --es cmd trigger:rest`
 */
class DemoReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val command = intent.getStringExtra("cmd") ?: return
        when (command) {
            // adb cannot send the protected ACL_CONNECTED broadcast, and the emulator has no Bluetooth.
            "bt:connected" -> StartPrompt.onPhoneConnected(context)
            // Stands in for the system stopping the app during Suspend.
            "service:stop" -> {
                log("demo command: $command")
                context.stopService(Intent(context, PoomService::class.java))
            }
            else -> {
                val service = PoomService.instance
                if (service == null) {
                    log("demo command ignored, service not running: $command")
                    return
                }
                service.care.onDemoCommand(command)
            }
        }
    }
}
