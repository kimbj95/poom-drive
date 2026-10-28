package com.seattlegymsquad.poomdrive.vehicle

import java.util.concurrent.ConcurrentHashMap

enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }

/** Latest vehicle state. SDK callbacks write it; the care engine reads it on the main thread. */
class VehicleState {
    /** Seat area (e.g. "ROW_2_LEFT") -> occupied, already corrected for the SDK inversion. */
    val occupied = ConcurrentHashMap<String, Boolean>()
    val doorOpen = ConcurrentHashMap<String, Boolean>()

    @Volatile var speedKmh = 0f
    @Volatile var gear = Gear.UNKNOWN
    @Volatile var brakePressed = false
    @Volatile var steeringDeg = 0f
    @Volatile var recirculationOn: Boolean? = null
    @Volatile var fanSpeed: Int? = null
    @Volatile var cabinTempC: Float? = null
    @Volatile var setTempC: Float? = null
    @Volatile var outsideTempC: Float? = null

    /** True while the system restricts screens for driving (non-DO apps are blocked). */
    @Volatile var drivingRestricted = false

    fun parked() = gear == Gear.PARK

    /** Occupied seats other than the driver's. */
    fun passengerSeats(): List<String> = occupied.filter { it.value && it.key != DRIVER_SEAT }.keys.sorted()

    fun hasPassenger() = passengerSeats().isNotEmpty()

    fun rearOccupied() = occupied.any { it.value && it.key.startsWith("ROW_2") }

    companion object {
        /** Left-hand drive: the driver sits in the first-row left seat. */
        const val DRIVER_SEAT = "ROW_1_LEFT"
    }
}

/** Events the care engine reacts to immediately, all delivered on the main thread. */
interface VehicleListener {
    fun onOccupancyChanged() {}
    fun onDoorChanged(door: String, open: Boolean) {}
    fun onGearChanged(gear: Gear) {}
    fun onSpeed(kmh: Float, timeMs: Long) {}
    fun onSteering(deg: Float, timeMs: Long) {}
}
