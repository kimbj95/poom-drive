package com.seattlegymsquad.poomdrive.care

import android.content.Context
import android.content.SharedPreferences
import com.seattlegymsquad.poomdrive.voice.Answer
import com.seattlegymsquad.poomdrive.voice.PassengerType

/**
 * Who rides along, as chosen on the parked screen. AUTO decides from the seat sensors and the spoken answer,
 * MANUAL uses [TripStore.manualKinds] (several can be on), SOLO turns every passenger rule off.
 */
enum class Selection { AUTO, MANUAL, SOLO }

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

    /** Who the driver said is on board in AUTO; empty means nobody, null means not known yet. */
    var passengers: Set<PassengerType>? = null
    var boardingDone = false
    var storyRequested = false

    /** The one question while driving when nobody said who is on board. */
    var askedWhileDriving = false
    var lastRestAskSec = -1L
    var lastVentilationSec = -1L
    var lastHeatSec = -1L
    var lastPostureSec = 0L
}

/** [riders] is the label of who rode, e.g. "아이 · 반려견 동승" or "혼자". */
data class TripSummary(
    val score: Int,
    val hardBrakes: Int,
    val hardAccels: Int,
    val sharpTurns: Int,
    val minutes: Long,
    val riders: String,
    val sickness: Answer?,
    val endedAt: Long,
)

/**
 * Keeps the trip in SharedPreferences, so a restarted process (or a car resumed from Suspend after the
 * process was killed) continues the same trip instead of starting from zero.
 */
class TripStore(context: Context) {
    private val prefs = context.getSharedPreferences("trip", Context.MODE_PRIVATE)

    /** Wall-clock time of the last save. After a restart it tells how long the app was not running. */
    var savedAt: Long = prefs.getLong("savedAt", 0)
        private set

    var selection: Selection = prefs.getString("selection", null)?.let { Selection.valueOf(it) }
        ?: when (prefs.getString("userMode", null)) {
            // Saved by the one-mode version of the app.
            null, "AUTO" -> Selection.AUTO
            "SOLO" -> Selection.SOLO
            else -> Selection.MANUAL
        }
        private set

    var manualKinds: Set<PassengerType> = prefs.getKinds("manualKinds")
        ?: setOfNotNull(prefs.getString("userMode", null)?.let(::kindOf))
        private set

    fun select(selection: Selection, kinds: Set<PassengerType>) {
        this.selection = selection
        manualKinds = kinds
        prefs.edit().putString("selection", selection.name).putKinds("manualKinds", kinds).apply()
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
        passengers = prefs.getKinds("passengers")
        boardingDone = prefs.getBoolean("boardingDone", false)
        storyRequested = prefs.getBoolean("storyRequested", false)
        askedWhileDriving = prefs.getBoolean("askedWhileDriving", false)
        lastRestAskSec = prefs.getLong("lastRestAskSec", -1)
        lastVentilationSec = prefs.getLong("lastVentilationSec", -1)
        lastHeatSec = prefs.getLong("lastHeatSec", -1)
        lastPostureSec = prefs.getLong("lastPostureSec", 0)
    }

    /** The last few trips, oldest first, for the 포근 지수 trend on the parked screen. */
    var recentTrips: List<TripSummary> =
        prefs.getString("recentTrips", null)?.split("\n")?.mapNotNull(::decode)
            ?: listOfNotNull(prefs.getString("lastTrip", null)?.let(::decode))
        private set

    val lastTrip: TripSummary? get() = recentTrips.lastOrNull()

    fun addTrip(summary: TripSummary) = saveTrips((recentTrips + summary).takeLast(RECENT_TRIPS))

    /** Replaces the newest trip, e.g. once the motion-sickness answer is known. */
    fun updateLastTrip(summary: TripSummary) = saveTrips(recentTrips.dropLast(1) + summary)

    private fun saveTrips(trips: List<TripSummary>) {
        recentTrips = trips
        prefs.edit().putString("recentTrips", trips.joinToString("\n", transform = ::encode)).apply()
    }

    /** Passengers who got carsick: alerts come earlier whenever they ride again. */
    var sensitiveKinds: Set<PassengerType> = prefs.getKinds("sensitiveKinds")
        ?: prefs.getStringSet("sensitiveModes", emptySet())!!.mapNotNull(::kindOf).toSet()
        private set

    fun addSensitive(kinds: Set<PassengerType>) {
        sensitiveKinds = sensitiveKinds + kinds
        prefs.edit().putKinds("sensitiveKinds", sensitiveKinds).apply()
    }

    fun clearSensitive() {
        sensitiveKinds = emptySet()
        prefs.edit().putKinds("sensitiveKinds", emptySet()).apply()
    }

    /** Kinds whose safety check (car seat, pet carrier) was already given today, so it is said once a day. */
    fun safetyCheckedToday(today: Long): Set<PassengerType> =
        if (prefs.getLong("safetyDay", -1) == today) prefs.getKinds("safetyKinds").orEmpty() else emptySet()

    fun markSafetyChecked(today: Long, kinds: Set<PassengerType>) {
        prefs.edit().putLong("safetyDay", today).putKinds("safetyKinds", safetyCheckedToday(today) + kinds).apply()
    }

    fun clearSafetyChecks() {
        prefs.edit().remove("safetyDay").remove("safetyKinds").apply()
    }

    fun save(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - savedAt < SAVE_INTERVAL_MS) return
        savedAt = now
        prefs.edit()
            .putLong("savedAt", now)
            .putBoolean("active", trip.active)
            .putLong("drivingSec", trip.drivingSec)
            .putLong("continuousSec", trip.continuousSec)
            .putLong("parkedSec", trip.parkedSec)
            .putLong("recirculationSec", trip.recirculationSec)
            .putInt("hardBrakes", trip.hardBrakes)
            .putInt("hardAccels", trip.hardAccels)
            .putInt("sharpTurns", trip.sharpTurns)
            .putKinds("passengers", trip.passengers)
            .putBoolean("boardingDone", trip.boardingDone)
            .putBoolean("storyRequested", trip.storyRequested)
            .putBoolean("askedWhileDriving", trip.askedWhileDriving)
            .putLong("lastRestAskSec", trip.lastRestAskSec)
            .putLong("lastVentilationSec", trip.lastVentilationSec)
            .putLong("lastHeatSec", trip.lastHeatSec)
            .putLong("lastPostureSec", trip.lastPostureSec)
            .apply()
    }

    private fun encode(s: TripSummary) =
        listOf(s.score, s.hardBrakes, s.hardAccels, s.sharpTurns, s.minutes, s.riders, s.sickness?.name ?: "", s.endedAt)
            .joinToString("|")

    private fun decode(text: String): TripSummary? = runCatching {
        val p = text.split("|")
        TripSummary(
            score = p[0].toInt(), hardBrakes = p[1].toInt(), hardAccels = p[2].toInt(), sharpTurns = p[3].toInt(),
            minutes = p[4].toLong(), riders = OLD_MODE_LABELS[p[5]] ?: p[5],
            sickness = p[6].takeIf { it.isNotEmpty() }?.let { Answer.valueOf(it) }, endedAt = p[7].toLong(),
        )
    }.getOrNull()

    private companion object {
        const val SAVE_INTERVAL_MS = 5_000L
        const val RECENT_TRIPS = 5

        /** Trips saved by the one-mode version kept the mode name. */
        val OLD_MODE_LABELS = mapOf(
            "SOLO" to "혼자", "PASSENGER" to "동승자", "CHILD" to "아이 동승", "PARENT" to "부모님 동승", "PET" to "반려견 동승",
        )

        fun kindOf(name: String) = PassengerType.entries.find { it.name == name }

        /** A set of kinds as "CHILD,PET"; "" is the empty set and a missing key is null. */
        fun SharedPreferences.getKinds(key: String): Set<PassengerType>? =
            getString(key, null)?.split(",")?.mapNotNull(::kindOf)?.toSet()

        fun SharedPreferences.Editor.putKinds(key: String, kinds: Set<PassengerType>?): SharedPreferences.Editor =
            putString(key, kinds?.joinToString(",") { it.name })
    }
}
