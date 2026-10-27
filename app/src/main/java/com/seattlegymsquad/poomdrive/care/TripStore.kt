package com.seattlegymsquad.poomdrive.care

import android.content.Context
import com.seattlegymsquad.poomdrive.voice.Answer
import com.seattlegymsquad.poomdrive.voice.PassengerType

/** Mode chosen on the parked screen. AUTO decides from the seat sensors and the spoken answer. */
enum class UserMode { AUTO, CHILD, PARENT, PET, SOLO }

/** The mode the rules run in. PASSENGER means someone sits with the driver but the type is not known yet. */
enum class CareMode { SOLO, PASSENGER, CHILD, PARENT, PET }

/** Counters of the current trip. Times are in (possibly time-compressed) seconds. */
class Trip {
    var active = false
    var drivingSec = 0L
    var continuousSec = 0L
    var parkedSec = 0L
    var recirculationSec = 0L
    var hardBrakes = 0
    var hardAccels = 0
    var sharpTurns = 0
    var passengerType: PassengerType? = null
    var boardingDone = false
    var storyRequested = false
    var lastRestAskSec = -1L
    var lastVentilationSec = -1L
    var lastHeatSec = -1L
    var lastPostureSec = 0L
}

data class TripSummary(
    val score: Int,
    val hardBrakes: Int,
    val hardAccels: Int,
    val sharpTurns: Int,
    val minutes: Long,
    val mode: CareMode,
    val sickness: Answer?,
    val endedAt: Long,
)

/**
 * Keeps the trip in SharedPreferences, so a restarted process (or a car resumed from Suspend after the
 * process was killed) continues the same trip instead of starting from zero.
 */
class TripStore(context: Context) {
    private val prefs = context.getSharedPreferences("trip", Context.MODE_PRIVATE)
    private var lastSave = 0L

    var userMode: UserMode = UserMode.valueOf(prefs.getString("userMode", UserMode.AUTO.name)!!)
        set(value) {
            field = value
            prefs.edit().putString("userMode", value.name).apply()
        }

    /** Extra degrees above the set temperature before the heat reminder speaks (the driver can change it). */
    var heatMarginC: Float = prefs.getFloat("heatMarginC", 3f)
        set(value) {
            field = value
            prefs.edit().putFloat("heatMarginC", value).apply()
        }

    val trip = Trip().apply {
        active = prefs.getBoolean("active", false)
        drivingSec = prefs.getLong("drivingSec", 0)
        continuousSec = prefs.getLong("continuousSec", 0)
        parkedSec = prefs.getLong("parkedSec", 0)
        recirculationSec = prefs.getLong("recirculationSec", 0)
        hardBrakes = prefs.getInt("hardBrakes", 0)
        hardAccels = prefs.getInt("hardAccels", 0)
        sharpTurns = prefs.getInt("sharpTurns", 0)
        passengerType = prefs.getString("passengerType", null)?.let { PassengerType.valueOf(it) }
        boardingDone = prefs.getBoolean("boardingDone", false)
        storyRequested = prefs.getBoolean("storyRequested", false)
        lastRestAskSec = prefs.getLong("lastRestAskSec", -1)
        lastVentilationSec = prefs.getLong("lastVentilationSec", -1)
        lastHeatSec = prefs.getLong("lastHeatSec", -1)
        lastPostureSec = prefs.getLong("lastPostureSec", 0)
    }

    var lastTrip: TripSummary? = prefs.getString("lastTrip", null)?.let(::decode)
        set(value) {
            field = value
            prefs.edit().putString("lastTrip", value?.let(::encode)).apply()
        }

    fun save(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastSave < SAVE_INTERVAL_MS) return
        lastSave = now
        prefs.edit()
            .putBoolean("active", trip.active)
            .putLong("drivingSec", trip.drivingSec)
            .putLong("continuousSec", trip.continuousSec)
            .putLong("parkedSec", trip.parkedSec)
            .putLong("recirculationSec", trip.recirculationSec)
            .putInt("hardBrakes", trip.hardBrakes)
            .putInt("hardAccels", trip.hardAccels)
            .putInt("sharpTurns", trip.sharpTurns)
            .putString("passengerType", trip.passengerType?.name)
            .putBoolean("boardingDone", trip.boardingDone)
            .putBoolean("storyRequested", trip.storyRequested)
            .putLong("lastRestAskSec", trip.lastRestAskSec)
            .putLong("lastVentilationSec", trip.lastVentilationSec)
            .putLong("lastHeatSec", trip.lastHeatSec)
            .putLong("lastPostureSec", trip.lastPostureSec)
            .apply()
    }

    private fun encode(s: TripSummary) =
        listOf(s.score, s.hardBrakes, s.hardAccels, s.sharpTurns, s.minutes, s.mode.name, s.sickness?.name ?: "", s.endedAt)
            .joinToString("|")

    private fun decode(text: String): TripSummary? = runCatching {
        val p = text.split("|")
        TripSummary(
            score = p[0].toInt(), hardBrakes = p[1].toInt(), hardAccels = p[2].toInt(), sharpTurns = p[3].toInt(),
            minutes = p[4].toLong(), mode = CareMode.valueOf(p[5]),
            sickness = p[6].takeIf { it.isNotEmpty() }?.let { Answer.valueOf(it) }, endedAt = p[7].toLong(),
        )
    }.getOrNull()

    private companion object {
        const val SAVE_INTERVAL_MS = 5_000L
    }
}
