package com.seattlegymsquad.poomdrive

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
 * Demo and test commands from adb (the receiver requires the DUMP permission, which only the shell holds):
 * `adb shell am broadcast -a com.seattlegymsquad.poomdrive.DEMO -n com.seattlegymsquad.poomdrive/.DemoReceiver --es cmd trigger:rest`
 */
class DemoReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val command = intent.getStringExtra("cmd") ?: return
        val service = PoomService.instance
        if (service == null) {
            log("demo command ignored, service not running: $command")
            return
        }
        service.care.onDemoCommand(command)
    }
}
