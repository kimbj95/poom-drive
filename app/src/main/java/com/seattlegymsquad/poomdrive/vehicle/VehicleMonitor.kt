package com.seattlegymsquad.poomdrive.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.HvacFanSpeedListener
import ai.pleos.playground.vehicle.listener.HvacRecirculationStateListener
import ai.pleos.playground.vehicle.listener.HvacTemperatureValueListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.seattlegymsquad.poomdrive.log

/**
 * Reads every signal the care rules need: Pleos Vehicle SDK for seats, doors, climate, brake and steering,
 * and the AAOS Car API for gear, speed and outside temperature (as Pleos recommends for standard data).
 */
class VehicleMonitor(
    private val context: Context,
    private val state: VehicleState,
    private val listener: VehicleListener,
) {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var vehicle: Vehicle
    private var car: Car? = null
    private var properties: CarPropertyManager? = null
    private var uxManager: CarUxRestrictionsManager? = null
    private var speedRegistered = false

    fun start() {
        startPleos()
        startCarApi()
    }

    fun stop() {
        properties?.unregisterCallback(propertyCallback)
        uxManager?.unregisterListener()
        car?.disconnect()
        if (::vehicle.isInitialized) vehicle.release()
    }

    // ---------------------------------------------------------------- Pleos Vehicle SDK
    private fun startPleos() {
        vehicle = Vehicle(context)
        vehicle.initialize()
        val seat = vehicle.getSeat()
        val hvac = vehicle.getHVAC()
        val door = vehicle.getDoor()

        seat.checkSeatOccupancyCapabilities(
            onComplete = { _, areas -> areas.forEach { seat.registerSeatOccupancy(it, occupancyListener(it.values.joinToString(","))) } },
            onFailed = { log("seat occupancy unavailable: $it") }
        )
        hvac.checkHvacRecirculationCapabilities(
            onComplete = { _, areas -> areas.firstOrNull()?.let { hvac.registerHvacRecirculationState(it, recirculationListener) } },
            onFailed = { log("recirculation unavailable: $it") }
        )
        hvac.checkHvacFanSpeedCapabilities(
            onComplete = { _, bounds -> bounds.keys.firstOrNull()?.let { hvac.registerHvacFanSpeed(it, fanListener) } },
            onFailed = { log("fan speed unavailable: $it") }
        )
        hvac.checkHvacTemperatureCurrentCapabilities(
            onComplete = { _, areas -> areas.firstOrNull()?.let { hvac.registerHvacTemperatureCurrent(it, cabinTemperatureListener) } },
            onFailed = { log("cabin temperature unavailable: $it") }
        )
        hvac.checkHvacTemperatureCapabilities(
            onComplete = { _, bounds -> bounds.keys.firstOrNull()?.let { hvac.registerHvacTemperature(it, setTemperatureListener) } },
            onFailed = { log("set temperature unavailable: $it") }
        )
        door.checkDoorPositionCapabilities(
            onComplete = { _, bounds -> bounds.keys.forEach { door.registerDoorPosition(it, doorListener(it.values.joinToString(","))) } },
            onFailed = { log("door position unavailable: $it") }
        )
        vehicle.getBrake().registerBrakePedalStatus(object : BrakePedalStatusListener {
            override fun onBrakePedalStatusUpdated(status: Boolean?) {
                state.brakePressed = status == true
            }

            override fun onFailed(e: Exception) = log("brake pedal unavailable: $e")
        })
        vehicle.getSteeringWheel().registerSteeringWheelAngle(object : SteeringWheelAngleListener {
            override fun onSteeringWheelAngleUpdated(angle: Float?) {
                val deg = angle ?: return
                state.steeringDeg = deg
                val now = SystemClock.elapsedRealtime()
                main.post { listener.onSteering(deg, now) }
            }

            override fun onFailed(e: Exception) = log("steering angle unavailable: $e")
        })
    }

    private fun occupancyListener(seat: String) = object : SeatOccupancyListener {
        override fun onSeatOccupancyUpdated(occupancy: Boolean?) {
            // Vehicle SDK 2.3.2 reports `raw value == 1` as true, but in VehicleSeatOccupancyState 1 is VACANT
            // and 2 is OCCUPIED. Verified on the Pleos emulator (research/evidence/2026-10-26-signalcheck.md).
            val occupied = if (OCCUPANCY_SDK_INVERTED) occupancy == false else occupancy == true
            if (state.occupied.put(seat, occupied) != occupied) {
                log("occupancy $seat=$occupied")
                main.post { listener.onOccupancyChanged() }
            }
        }

        override fun onFailed(e: Exception) = log("occupancy $seat failed: $e")
    }

    private val recirculationListener = object : HvacRecirculationStateListener {
        override fun onHvacAutoRecirculationStateUpdated(state: Boolean?) {
            this@VehicleMonitor.state.recirculationOn = state
            log("recirculation on=$state")
        }

        override fun onFailed(e: Exception) = log("recirculation failed: $e")
    }

    private val fanListener = object : HvacFanSpeedListener {
        override fun onHvacFanSpeedUpdated(speed: Int?) {
            state.fanSpeed = speed
        }

        override fun onFailed(e: Exception) = log("fan speed failed: $e")
    }

    private val cabinTemperatureListener = object : HvacTemperatureValueListener {
        override fun onHvacTemperatureUpdated(celsius: Float?, fahrenheit: Float?) {
            state.cabinTempC = celsius
        }

        override fun onFailed(e: Exception) = log("cabin temperature failed: $e")
    }

    private val setTemperatureListener = object : HvacTemperatureValueListener {
        override fun onHvacTemperatureUpdated(celsius: Float?, fahrenheit: Float?) {
            state.setTempC = celsius
        }

        override fun onFailed(e: Exception) = log("set temperature failed: $e")
    }

    private fun doorListener(door: String) = object : DoorPositionListener {
        override fun onPositionUpdated(pos: Int?) {
            val open = (pos ?: 0) > 0
            // The first report on registration is the current state, not a change.
            val before = state.doorOpen.put(door, open)
            if (before != null && before != open) {
                log("door $door open=$open")
                main.post { listener.onDoorChanged(door, open) }
            }
        }

        override fun onFailed(e: Exception) = log("door $door failed: $e")
    }

    // ---------------------------------------------------------------- AAOS Car API
    private fun startCarApi() {
        val connected = Car.createCar(context)
        car = connected
        val props = connected.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
        properties = props
        register(props, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)
        register(props, VehiclePropertyIds.ENV_OUTSIDE_TEMPERATURE, CarPropertyManager.SENSOR_RATE_NORMAL)
        registerSpeed()

        val ux = connected.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
        uxManager = ux
        state.drivingRestricted = ux.currentCarUxRestrictions?.isRequiresDistractionOptimization == true
        ux.registerListener { restrictions ->
            state.drivingRestricted = restrictions.isRequiresDistractionOptimization
            log("ux restricted=${state.drivingRestricted}")
        }
    }

    /** Speed needs the runtime CAR_SPEED permission; MainActivity calls this again after it is granted. */
    fun registerSpeed() {
        if (speedRegistered) return
        val props = properties ?: return
        if (context.checkSelfPermission(Car.PERMISSION_SPEED) != PackageManager.PERMISSION_GRANTED) {
            log("CAR_SPEED not granted yet")
            return
        }
        speedRegistered = register(props, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL)
    }

    private fun register(props: CarPropertyManager, id: Int, rate: Float): Boolean = try {
        props.registerCallback(propertyCallback, id, rate).also { log("register ${VehiclePropertyIds.toString(id)} ok=$it") }
    } catch (e: Exception) {
        log("register ${VehiclePropertyIds.toString(id)} failed: $e")
        false
    }

    private val propertyCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val now = SystemClock.elapsedRealtime()
            when (value.propertyId) {
                VehiclePropertyIds.PERF_VEHICLE_SPEED -> {
                    val kmh = (value.value as Float) * 3.6f
                    state.speedKmh = kmh
                    main.post { listener.onSpeed(kmh, now) }
                }
                VehiclePropertyIds.GEAR_SELECTION -> {
                    val gear = gearOf(value.value as Int)
                    if (gear != state.gear) {
                        state.gear = gear
                        log("gear=$gear")
                        main.post { listener.onGearChanged(gear) }
                    }
                }
                VehiclePropertyIds.ENV_OUTSIDE_TEMPERATURE -> state.outsideTempC = value.value as Float
            }
        }

        override fun onErrorEvent(propertyId: Int, areaId: Int) =
            log("property error ${VehiclePropertyIds.toString(propertyId)} area=$areaId")
    }

    private fun gearOf(value: Int) = when (value) {
        GEAR_PARK -> Gear.PARK
        GEAR_REVERSE -> Gear.REVERSE
        GEAR_NEUTRAL -> Gear.NEUTRAL
        GEAR_DRIVE -> Gear.DRIVE
        else -> Gear.UNKNOWN
    }

    private companion object {
        /** Set to false if a later Vehicle SDK reports occupancy the standard way. */
        const val OCCUPANCY_SDK_INVERTED = true

        // VehicleGear values used by GEAR_SELECTION.
        const val GEAR_NEUTRAL = 1
        const val GEAR_REVERSE = 2
        const val GEAR_PARK = 4
        const val GEAR_DRIVE = 8
    }
}
