package com.seattlegymsquad.poomdrive.care

import android.content.Context

/**
 * Settings for the demo video and emulator tests, for what the Virtual Console cannot produce:
 * compressed time (1 real second counts as [timeScale] seconds) and a fake cabin temperature.
 */
class DemoSettings(context: Context) {
    private val prefs = context.getSharedPreferences("demo", Context.MODE_PRIVATE)

    var timeScale: Int = prefs.getInt("timeScale", 1)
        set(value) {
            field = value.coerceIn(1, 3600)
            prefs.edit().putInt("timeScale", field).apply()
        }

    /** Replaces the cabin temperature sensor while set; NaN means use the real sensor. */
    var fakeCabinTempC: Float = prefs.getFloat("fakeCabinTempC", Float.NaN)
        set(value) {
            field = value
            prefs.edit().putFloat("fakeCabinTempC", value).apply()
        }

    val enabled: Boolean get() = timeScale > 1 || !fakeCabinTempC.isNaN()
}
