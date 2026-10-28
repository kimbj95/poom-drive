package com.seattlegymsquad.poomdrive.care

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.seattlegymsquad.poomdrive.PassengerPrompt
import com.seattlegymsquad.poomdrive.data.RestAreaRepository
import com.seattlegymsquad.poomdrive.data.StoryBook
import com.seattlegymsquad.poomdrive.log
import com.seattlegymsquad.poomdrive.navi.NaviAgent
import com.seattlegymsquad.poomdrive.navi.StopResult
import com.seattlegymsquad.poomdrive.vehicle.Gear
import com.seattlegymsquad.poomdrive.vehicle.VehicleListener
import com.seattlegymsquad.poomdrive.vehicle.VehicleState
import com.seattlegymsquad.poomdrive.voice.Answer
import com.seattlegymsquad.poomdrive.voice.Answers
import com.seattlegymsquad.poomdrive.voice.PassengerType
import com.seattlegymsquad.poomdrive.voice.VoiceAgent
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class DrivingEvent { BRAKE, ACCEL, TURN }

/**
 * The care rules. While parked it finds out who is on board and runs the boarding checks; while driving it
 * watches driving events, air, temperature and driving time and speaks; after parking it gives the trip score.
 * With several kinds of passenger on board every kind's care is on, at the most careful of their thresholds.
 * Everything runs on the main thread, driven by vehicle events and a one-second tick.
 */
class CareEngine(
    private val context: Context,
    private val state: VehicleState,
    private val voice: VoiceAgent,
    private val navi: NaviAgent,
    private val store: TripStore,
    private val demo: DemoSettings,
    private val restAreas: RestAreaRepository,
    private val stories: StoryBook,
    /** Shows a one-line status in the care notification, e.g. "아이 · 반려견 동승 · 흔들림 2번". */
    private val onStatus: (String) -> Unit = {},
) : VehicleListener {

    private val main = Handler(Looper.getMainLooper())
    private val trip get() = store.trip
    private val speedSamples = ArrayDeque<Pair<Long, Float>>()
    private var speedLatchUntil = 0L
    private var turnLatchUntil = 0L
    private var lastAlertAt = 0L
    private var askingPassenger = false
    private var tripEnd: Runnable? = null
    private var lastTickAt = 0L
    private var movingAtLastTick = false
    private var offSince = 0L
    private var lastStatus = ""
    private var selectedAt = 0L
    private var occupancyChangedAt = 0L
    private var movedThisTrip = false

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            main.postDelayed(this, TICK_MS)
        }
    }

    fun start() {
        // A new process: the last save tells since when the app was not running (car off, process killed).
        offSince = store.savedAt
        main.post(ticker)
        log("care engine started, riders=${ridersLabel()}")
    }

    fun stop() {
        main.removeCallbacks(ticker)
        store.save(force = true)
    }

    // ---------------------------------------------------------------- who is on board
    /**
     * The kinds the rules care for. Empty: the driver alone. Null: someone sits with the driver but nobody said
     * who, so every rule runs at its most careful value.
     */
    fun riders(): Set<PassengerType>? = when (store.selection) {
        Selection.SOLO -> emptySet()
        Selection.MANUAL -> store.manualKinds
        Selection.AUTO -> trip.passengers ?: if (state.hasPassenger()) null else emptySet()
    }

    /** "혼자", "동승자" (not known yet), "아이 동승", "아이 · 반려견 동승". */
    fun ridersLabel(riders: Set<PassengerType>? = riders()) = when {
        riders == null -> "동승자"
        riders.isEmpty() -> "혼자"
        else -> riders.sorted().joinToString(" · ") { it.noun } + " 동승"
    }

    /** Subject of a spoken alert: "아이가", "아이와 반려견이", and "뒷좌석이" for three kinds or when nobody said who. */
    private fun subject(riders: Set<PassengerType>?): String {
        val kinds = riders.orEmpty().sorted()
        return when {
            kinds.size == 1 -> kinds[0].subject
            kinds.size == 2 -> "${kinds[0].with} ${kinds[1].subject}"
            riders == null && !state.rearOccupied() -> "동승자가"
            else -> "뒷좌석이"
        }
    }

    /** "아이와", "아이, 반려견과", "아이, 부모님, 반려견과". */
    private fun withPhrase(kinds: Set<PassengerType>): String {
        val sorted = kinds.sorted()
        return (sorted.dropLast(1).map { it.noun } + sorted.last().with).joinToString(", ")
    }

    /** Earlier driving-event alerts when nobody said who rides along, or when anyone on board got carsick before. */
    private fun sensitive(riders: Set<PassengerType>?) = riders == null || riders.any { it in store.sensitiveKinds }

    private fun heatMargin(riders: Set<PassengerType>?) =
        if (riders == null || PassengerType.PET in riders) minOf(store.heatMarginC, CareConfig.PET_HEAT_MARGIN_C) else store.heatMarginC

    /** A choice on the parked screen. Before departure it runs the boarding checks again; while driving it only switches rules. */
    fun select(selection: Selection, kinds: Set<PassengerType> = emptySet()) {
        store.select(selection, if (selection == Selection.MANUAL) kinds else emptySet())
        selectedAt = SystemClock.elapsedRealtime()
        if (state.parked() && !trip.active) {
            trip.boardingDone = false
            trip.passengers = null
        }
        log("selection $selection ${store.manualKinds} -> riders ${ridersLabel()}")
    }

    // ---------------------------------------------------------------- tick
    private fun tick() {
        val step = demo.timeScale.toLong()
        countTimeAway()
        val moving = state.gear != Gear.PARK && state.gear != Gear.UNKNOWN
        movingAtLastTick = moving
        if (moving) {
            if (!trip.active) startTrip()
            trip.drivingSec += step
            trip.continuousSec += step
            trip.parkedSec = 0
        } else {
            addStop(step)
        }
        trip.recirculationSec = if (state.recirculationOn == true) trip.recirculationSec + step else 0
        if (moving) {
            checkRidersWhileDriving()
            checkVentilation()
            checkHeat()
            checkRest()
            checkPosture()
        } else if (state.parked()) {
            checkBoarding()
        }
        publishStatus()
        store.save()
    }

    /** The notification title is the only text that reads well in the notification center, so the status goes there. */
    private fun publishStatus() {
        val riders = ridersLabel()
        val last = store.lastTrip
        val status = when {
            trip.active -> "$riders · 흔들림 ${trip.hardBrakes + trip.hardAccels + trip.sharpTurns}번"
            last != null -> "$riders · 지난 주행 포근 지수 ${last.score}"
            else -> riders
        }
        if (status == lastStatus) return
        lastStatus = status
        onStatus(status)
    }

    /**
     * Counts the time the app could not see as a stop: the car was switched off, or the head unit slept (Suspend).
     * Within one process the gap shows in elapsedRealtime, which keeps counting in deep sleep; after a restart
     * only the time of the last save can show it. Without this, a driver who rests with the engine off would
     * be asked to rest again soon after setting off.
     */
    private fun countTimeAway() {
        val now = SystemClock.elapsedRealtime()
        val awayMs = when {
            lastTickAt > 0 -> if (movingAtLastTick) 0 else now - lastTickAt
            offSince > 0 -> System.currentTimeMillis() - offSince
            else -> 0
        }
        lastTickAt = now
        if (awayMs >= AWAY_MIN_MS) onBackFromAway(awayMs)
    }

    /** The car slept or was off for [awayMs]: prompts waiting from before are dropped, and the time counts as a stop. */
    private fun onBackFromAway(awayMs: Long) {
        val stopSec = awayMs / 1000 * demo.timeScale
        val dropped = voice.discardPending()
        askingPassenger = false
        log("app was not running for ${awayMs / 1000}s, counted as a ${stopSec / 60}-minute stop, dropped $dropped voice prompts")
        addStop(stopSec)
    }

    private fun addStop(sec: Long) {
        if (trip.continuousSec <= 0) return
        trip.parkedSec += sec
        if (trip.parkedSec < CareConfig.REST_RESET_SEC) return
        trip.continuousSec = 0
        trip.lastPostureSec = 0
        trip.lastRestAskSec = -1
        // A short trip that was never scored ends quietly after a long stop.
        if (trip.active) {
            trip.active = false
            trip.storyRequested = false
            forgetPassengers()
        }
        // A pet the seat sensors cannot see is not assumed to be on board after a long stop.
        if (!state.hasPassenger()) forgetPassengers()
    }

    /** The next passenger who gets in is a new boarding. */
    private fun forgetPassengers() {
        trip.passengers = null
        trip.boardingDone = false
        PassengerPrompt.dismiss(context)
    }

    private fun startTrip() {
        trip.active = true
        // Once the car moves, boarding is over; it is checked again only after everyone gets out.
        trip.boardingDone = true
        trip.drivingSec = 0
        trip.hardBrakes = 0
        trip.hardAccels = 0
        trip.sharpTurns = 0
        trip.lastVentilationSec = -1
        trip.lastHeatSec = -1
        trip.askedWhileDriving = false
        movedThisTrip = false
        // Whoever gets in during the trip is ignored unless the driver picks them; an empty seat at departure means alone.
        if (store.selection == Selection.AUTO && trip.passengers == null && !state.hasPassenger()) trip.passengers = emptySet()
        val riders = riders()
        log("trip start, riders=${ridersLabel(riders)}, continuous=${trip.continuousSec}s")
        if (riders == null) PassengerPrompt.show(context, emptySet())
        if (trip.storyRequested && riders?.contains(PassengerType.CHILD) == true) {
            main.postDelayed({ if (!state.parked()) startStory() }, STORY_DELAY_MS)
        }
        store.save(force = true)
    }

    /** Reads the story offered at boarding; the next drive gets the following one. */
    private fun startStory() {
        voice.startStory(stories.next.parts)
        stories.advance()
    }

    // ---------------------------------------------------------------- boarding (parked)
    /** Runs once per boarding: before departure, until all passengers have left again. */
    private fun checkBoarding() {
        if (trip.active || trip.boardingDone || askingPassenger || voice.busy) return
        // Several kinds can be tapped one after another on the parked screen, and a passenger who just sat down is
        // still getting settled; speak once both have settled.
        val now = SystemClock.elapsedRealtime()
        if (now - selectedAt < SELECTION_SETTLE_MS || now - occupancyChangedAt < SELECTION_SETTLE_MS) return
        when (store.selection) {
            Selection.AUTO -> if (state.hasPassenger() && trip.passengers == null) {
                askPassengers("동승자가 탔네요. 아이, 부모님, 반려견 중에 누구와 함께 가세요?")
            }
            Selection.MANUAL -> boardingFlow(store.manualKinds)
            Selection.SOLO -> trip.boardingDone = true
        }
    }

    private fun askPassengers(question: String) {
        askingPassenger = true
        voice.ask(question, { Answers.passengers(it) }) { result, _ ->
            askingPassenger = false
            @Suppress("UNCHECKED_CAST")
            onPassengersAnswered(result as Set<PassengerType>?)
        }
    }

    private fun onPassengersAnswered(kinds: Set<PassengerType>?) {
        // Picked on the notification while the question was still waiting: that choice stands.
        if (kinds == null && trip.passengers != null) return
        trip.boardingDone = true
        // An answer that comes after departure counts as the one question while driving.
        if (!state.parked()) trip.askedWhileDriving = true
        when {
            kinds == null && state.hasPassenger() -> {
                voice.say("잘 못 들었어요. 누가 탔는지 알 때까지 더 조심스럽게 살필게요.")
                PassengerPrompt.show(context, emptySet())
            }
            kinds == null -> voice.say("잘 못 들었어요. 정차 화면에서 고를 수 있어요.")
            else -> {
                PassengerPrompt.dismiss(context)
                addPassengers(kinds)
            }
        }
        store.save(force = true)
    }

    /** The driver picked one more kind on the "누가 탔나요?" notification, which also works while driving. */
    fun onPassengerPicked(type: PassengerType) {
        log("passenger picked from notification: $type")
        addPassengers(setOf(type))
        PassengerPrompt.show(context, trip.passengers.orEmpty())
        store.save(force = true)
    }

    private fun addPassengers(kinds: Set<PassengerType>) {
        trip.passengers = trip.passengers.orEmpty() + kinds
        trip.boardingDone = true
        log("passengers ${trip.passengers}, riders ${ridersLabel()}")
        when {
            kinds.isEmpty() -> voice.say("알겠어요.")
            state.parked() && !trip.active -> boardingFlow(kinds)
            else -> {
                // Too late for the car seat check while driving; the story is offered right away.
                voice.say("${withPhrase(kinds)} 함께 가는 걸로 살필게요.")
                if (PassengerType.CHILD in kinds && !voice.storyPlaying) offerStory()
            }
        }
    }

    /**
     * Before departure, within about 15 seconds: who is on board, the safety check (car seat, pet carrier) on the
     * first departure of the day only, and the story offer last.
     */
    private fun boardingFlow(kinds: Set<PassengerType>) {
        trip.boardingDone = true
        if (kinds.isEmpty()) return
        voice.say(
            when (kinds.singleOrNull()) {
                PassengerType.PARENT -> "부모님과 함께 가시네요. 급제동이나 급회전이 있으면 바로 알려 드릴게요."
                PassengerType.PET -> "반려견과 함께 가시네요. 실내 온도와 환기를 살피고, 두 시간마다 쉬어 가자고 알려 드릴게요."
                else -> "${withPhrase(kinds)} 함께 가시네요."
            }
        )
        val today = LocalDate.now().toEpochDay()
        val check = kinds.filter { it == PassengerType.CHILD || it == PassengerType.PET }.toSet() - store.safetyCheckedToday(today)
        val text = when {
            PassengerType.CHILD in check && PassengerType.PET in check -> "출발 전에 카시트와 반려견 안전장비를 확인해 주세요."
            PassengerType.CHILD in check && kinds.size == 1 ->
                "출발 전에 카시트를 확인해 주세요. 보조 좌석은 뒷좌석에만, 좌석 벨트 하나에 한 명만, 장착한 뒤 흔들어서 고정됐는지 봐 주세요."
            PassengerType.CHILD in check -> "출발 전에 카시트를 확인해 주세요."
            PassengerType.PET in check -> "출발 전에 반려견 이동장이나 안전장비가 흔들리지 않게 고정됐는지 확인해 주세요."
            else -> null
        }
        if (text != null) {
            voice.say(text)
            store.markSafetyChecked(today, check)
        }
        if (PassengerType.CHILD in kinds) offerStory()
        store.save(force = true)
    }

    private fun offerStory() {
        val now = trip.active
        voice.askYesNo("${if (now) "지금" else "출발하면"} 아이를 위해 「${stories.next.title}」 동화를 들려줄까요?") { answer ->
            trip.storyRequested = answer == Answer.YES
            if (trip.storyRequested) {
                // The answer can come after departure, when the start-of-trip story hook has already passed.
                if (trip.active) startStory() else voice.say("좋아요. 출발하면 읽어 줄게요.")
            }
            store.save(force = true)
        }
    }

    override fun onOccupancyChanged() {
        occupancyChangedAt = SystemClock.elapsedRealtime()
        // Who gets in or out during a trip is ignored unless the driver picks them in the app.
        if (!state.parked() || trip.active) return
        when {
            // Everyone got out: the next passenger who gets in is a new boarding.
            !state.hasPassenger() -> forgetPassengers()
            // Someone got in after a trip the driver made alone.
            trip.passengers?.isEmpty() == true -> forgetPassengers()
        }
    }

    override fun onDoorChanged(door: String, open: Boolean) {
        if (open || !door.startsWith("ROW_2")) return
        // A pet may not register on the seat sensor: a rear door that opened and closed with no seat taken is asked about.
        main.postDelayed({ checkRearDoorBoarding() }, DOOR_SETTLE_MS)
    }

    private fun checkRearDoorBoarding() {
        if (!state.parked() || trip.active || trip.boardingDone || askingPassenger || voice.busy) return
        if (store.selection != Selection.AUTO || trip.passengers != null || state.hasPassenger()) return
        askPassengers("뒷좌석에 누가 탔나요?")
    }

    /**
     * A driver who set off before saying who is on board is asked once while driving: at the first stop
     * (D, 0 km/h) or after three minutes. Until then, and if the answer is not understood, the rules stay careful.
     */
    private fun checkRidersWhileDriving() {
        if (trip.askedWhileDriving || askingPassenger || voice.busy || riders() != null) return
        val stopped = movedThisTrip && state.speedKmh < 1f
        if (!stopped && trip.drivingSec < CareConfig.ASK_WHILE_DRIVING_AFTER_SEC) return
        trip.askedWhileDriving = true
        askPassengers("뒷좌석에 누가 탔나요? 아이, 부모님, 반려견 중에 말씀해 주세요.")
    }

    // ---------------------------------------------------------------- driving events
    override fun onSpeed(kmh: Float, timeMs: Long) {
        if (kmh >= CareConfig.EVENT_MIN_KMH) movedThisTrip = true
        speedSamples.addLast(timeMs to kmh)
        while (speedSamples.size > 2 && timeMs - speedSamples.first().first > SPEED_WINDOW_MS * 2) speedSamples.removeFirst()
        if (timeMs < speedLatchUntil) return
        val reference = speedSamples.lastOrNull { timeMs - it.first >= SPEED_WINDOW_MS } ?: return
        val rate = (kmh - reference.second) / ((timeMs - reference.first) / 1000f)
        val sensitive = sensitive(riders())
        val brake = if (sensitive) CareConfig.SENSITIVE_BRAKE_KMH_PER_S else CareConfig.HARD_BRAKE_KMH_PER_S
        val accel = if (sensitive) CareConfig.SENSITIVE_ACCEL_KMH_PER_S else CareConfig.HARD_ACCEL_KMH_PER_S
        val event = when {
            rate <= -brake && reference.second >= CareConfig.EVENT_MIN_KMH -> DrivingEvent.BRAKE
            rate >= accel && kmh >= CareConfig.EVENT_MIN_KMH -> DrivingEvent.ACCEL
            else -> return
        }
        speedLatchUntil = timeMs + EVENT_LATCH_MS
        onDrivingEvent(event)
    }

    override fun onSteering(deg: Float, timeMs: Long) {
        val turn = if (sensitive(riders())) CareConfig.SENSITIVE_TURN_DEG else CareConfig.SHARP_TURN_DEG
        if (abs(deg) < turn || state.speedKmh < CareConfig.SHARP_TURN_MIN_KMH) return
        if (timeMs < turnLatchUntil) return
        turnLatchUntil = timeMs + EVENT_LATCH_MS
        onDrivingEvent(DrivingEvent.TURN)
    }

    private fun onDrivingEvent(event: DrivingEvent) {
        if (!trip.active) return
        when (event) {
            DrivingEvent.BRAKE -> trip.hardBrakes++
            DrivingEvent.ACCEL -> trip.hardAccels++
            DrivingEvent.TURN -> trip.sharpTurns++
        }
        log("driving event $event (brakes=${trip.hardBrakes}, accels=${trip.hardAccels}, turns=${trip.sharpTurns})")
        val riders = riders()
        val now = SystemClock.elapsedRealtime()
        if (riders?.isEmpty() == true || now - lastAlertAt < CareConfig.ALERT_COOLDOWN_MS) return
        lastAlertAt = now
        val who = subject(riders)
        val text = when (event) {
            DrivingEvent.BRAKE -> "$who 앞으로 쏠렸어요. 조금 일찍, 부드럽게 멈춰 보세요."
            DrivingEvent.ACCEL -> "$who 뒤로 쏠렸어요. 조금 천천히 속도를 올려 보세요."
            DrivingEvent.TURN -> "회전 전에 속도를 조금 줄이면 $who 편해요."
        }
        voice.say(text, urgent = true)
    }

    override fun onGearChanged(gear: Gear) {
        if (gear == Gear.PARK) {
            voice.stopStory()
            if (trip.active && trip.drivingSec >= CareConfig.MIN_TRIP_SEC) {
                val end = Runnable { if (state.parked()) endTrip() }
                tripEnd = end
                main.postDelayed(end, TRIP_END_DELAY_MS)
            }
        } else {
            tripEnd?.let { main.removeCallbacks(it) }
            tripEnd = null
        }
    }

    // ---------------------------------------------------------------- rules while driving
    private fun checkVentilation() {
        if (trip.recirculationSec < CareConfig.VENTILATION_AFTER_SEC) return
        if (trip.lastVentilationSec >= 0 && trip.drivingSec - trip.lastVentilationSec < CareConfig.VENTILATION_REPEAT_SEC) return
        trip.lastVentilationSec = trip.drivingSec
        val minutes = trip.recirculationSec / 60
        val text = if (riders()?.contains(PassengerType.PET) == true) {
            "내기순환이 ${minutes}분째예요. 반려견을 위해 1분이나 2분만 외기로 바꿔 보세요."
        } else {
            "내기순환이 ${minutes}분째예요. 1분이나 2분만 외기로 바꿔 보세요."
        }
        voice.say(text)
    }

    private fun checkHeat() {
        val riders = riders()
        if (riders?.isEmpty() == true) return
        val cabin = demo.fakeCabinTempC.takeUnless { it.isNaN() } ?: state.cabinTempC ?: return
        val target = state.setTempC ?: return
        val over = cabin - target
        if (over < heatMargin(riders)) return
        if (trip.lastHeatSec >= 0 && trip.drivingSec - trip.lastHeatSec < CareConfig.HEAT_REPEAT_SEC) return
        trip.lastHeatSec = trip.drivingSec
        voice.say("${subject(riders)} 더울 수 있어요. 실내가 설정보다 ${over.roundToInt()}도 높아요. 풍량을 한 단계 올려 보세요.")
    }

    private fun checkRest() {
        if (trip.continuousSec < CareConfig.REST_AFTER_SEC) return
        if (trip.lastRestAskSec >= 0 && trip.continuousSec - trip.lastRestAskSec < CareConfig.REST_REASK_SEC) return
        if (voice.busy && !voice.storyPlaying) return
        trip.lastRestAskSec = trip.continuousSec
        askRest()
    }

    /** Offers the nearest drowsy-driving rest area and adds it to the route when the driver says yes. */
    fun askRest() {
        val question = if (riders()?.contains(PassengerType.PET) == true) {
            "두 시간 넘게 달렸어요. 반려견도 쉬어 갈 겸, 가까운 졸음쉼터에 들를까요?"
        } else {
            "두 시간 넘게 달렸어요. 가까운 졸음쉼터에 들를까요?"
        }
        voice.askYesNo(question) { answer ->
            log("rest answer $answer")
            when (answer) {
                Answer.YES -> addNearestRestStop()
                Answer.NO -> voice.say("알겠어요. 조금 뒤에 다시 여쭤볼게요.")
                Answer.UNKNOWN -> voice.say("조금 뒤에 다시 여쭤볼게요.")
            }
        }
    }

    private fun addNearestRestStop() {
        navi.requestLocation { location ->
            if (location == null) {
                voice.say("지금 위치를 알 수 없어서 졸음쉼터를 찾지 못했어요.")
                return@requestLocation
            }
            val found = restAreas.nearest(location.first, location.second, needToilet = riders()?.isEmpty() != true)
            if (found == null) {
                voice.say("가까운 졸음쉼터를 찾지 못했어요.")
                return@requestLocation
            }
            val (area, km) = found
            val name = "${area.name} 졸음쉼터"
            log("nearest rest area $name ${"%.1f".format(km)}km (${area.route}, ${area.direction})")
            navi.addStop(name, area.lat, area.lng) { result ->
                log("add stop $result")
                val distance = km.roundToInt()
                voice.say(
                    when (result) {
                        StopResult.ADDED_AS_WAYPOINT -> "${name}를 경유지로 넣었어요. 약 ${distance}킬로미터 거리예요."
                        StopResult.ROUTED_AS_DESTINATION -> "${name}로 길안내를 시작할게요. 약 ${distance}킬로미터 거리예요."
                        StopResult.WAYPOINTS_FULL -> "경유지가 이미 두 곳이라 더 넣을 수 없어요. 정차한 뒤 내비에서 바꿔 주세요."
                        StopResult.NOT_CONNECTED -> "내비게이션과 연결되지 않아 경유지를 넣지 못했어요."
                    }
                )
            }
        }
    }

    private fun checkPosture() {
        if (riders()?.isEmpty() != true) return
        if (trip.continuousSec - trip.lastPostureSec < CareConfig.POSTURE_AFTER_SEC) return
        if (state.speedKmh >= 1f || state.gear != Gear.DRIVE || voice.busy) return
        trip.lastPostureSec = trip.continuousSec
        voice.say("신호 대기 중이에요. 어깨와 허리를 한 번 쭉 펴 보세요.")
    }

    // ---------------------------------------------------------------- end of trip
    /** 100 minus 5 per driving event, counted per [CareConfig.SCORE_WINDOW_SEC] of driving. */
    private fun comfortIndex(events: Int, drivingSec: Long): Int {
        val perWindow = events * CareConfig.SCORE_WINDOW_SEC.toDouble() / maxOf(drivingSec, CareConfig.SCORE_WINDOW_SEC)
        return (100 - 5 * perWindow).roundToInt().coerceIn(0, 100)
    }

    private fun endTrip() {
        val riders = riders()
        val score = comfortIndex(trip.hardBrakes + trip.hardAccels + trip.sharpTurns, trip.drivingSec)
        val summary = TripSummary(
            score, trip.hardBrakes, trip.hardAccels, trip.sharpTurns, trip.drivingSec / 60, ridersLabel(riders),
            sickness = null, endedAt = System.currentTimeMillis(),
        )
        store.addTrip(summary)
        PassengerPrompt.dismiss(context)
        log("trip end: $summary")
        when {
            // A run to the shop down the road: the record still counts, but a report and a question would only nag.
            trip.drivingSec < CareConfig.REPORT_MIN_SEC -> log("short trip, recorded quietly")
            riders?.isEmpty() == true -> {
                voice.say("운전 수고하셨어요. 오늘 포근 지수는 ${score}점이에요.")
                if (trip.continuousSec >= CareConfig.REST_AFTER_SEC) voice.say("내리기 전에 손목 당기기, 목과 어깨 풀기를 15초씩 해 보세요.")
            }
            else -> {
                voice.say("도착했어요. 오늘 포근 지수는 ${score}점이에요.")
                voice.askYesNo("뒷좌석 멀미는 괜찮았나요?") { answer ->
                    store.updateLastTrip(summary.copy(sickness = answer))
                    when (answer) {
                        Answer.YES -> voice.say("다행이에요. 기록해 둘게요.")
                        // Nobody can tell who got sick, so everyone on board is marked.
                        Answer.NO -> if (riders == null) {
                            voice.say("알려 주셔서 고마워요. 기록해 둘게요.")
                        } else {
                            store.addSensitive(riders)
                            voice.say("알려 주셔서 고마워요. 다음에 함께 탈 때는 작은 흔들림도 알려 드릴게요.")
                        }
                        Answer.UNKNOWN -> Unit
                    }
                }
            }
        }
        // Passengers usually stay on board between legs, so the boarding check is not repeated on arrival.
        trip.active = false
        trip.storyRequested = false
        store.save(force = true)
    }

    // ---------------------------------------------------------------- demo and tests
    /** Commands from the demo panel or adb: `timescale:60`, `cabin:31`, `answer:yes`, `mode:child+pet`, `pick:pet`, `trigger:rest`, ... */
    fun onDemoCommand(command: String) {
        log("demo command: $command")
        val name = command.substringBefore(':')
        val arg = command.substringAfter(':', "")
        when (name) {
            "timescale" -> demo.timeScale = arg.toIntOrNull() ?: 1
            "cabin" -> demo.fakeCabinTempC = arg.toFloatOrNull() ?: Float.NaN
            "answer" -> voice.injectedAnswer = arg.takeIf { it.isNotEmpty() && it != "off" }?.let(::answerAudio)
            "mode" -> when (arg) {
                "auto" -> select(Selection.AUTO)
                "solo" -> select(Selection.SOLO)
                else -> select(Selection.MANUAL, arg.split("+").map { PassengerType.valueOf(it.uppercase()) }.toSet())
            }
            "drive" -> arg.toLongOrNull()?.let { sec ->
                trip.continuousSec = sec
                // Reminders already given later than the new time would otherwise never come again.
                if (trip.lastPostureSec > sec) trip.lastPostureSec = 0
                if (trip.lastRestAskSec > sec) trip.lastRestAskSec = -1
            }
            // The emulator cannot Suspend, so this runs what a wake-up after `away:<seconds>` would run.
            "away" -> arg.toLongOrNull()?.let { onBackFromAway(it * 1000) }
            "route" -> arg.split(",").takeIf { it.size >= 3 }?.let { p ->
                navi.addStop(p[2], p[0].toDouble(), p[1].toDouble()) { log("demo route $it") }
            }
            "cancelroute" -> navi.cancelRoute()
            // What a tap on the "누가 탔나요?" notification does, for captures that cannot tap the notification center.
            "pick" -> onPassengerPicked(PassengerType.valueOf(arg.uppercase()))
            "sensitive" -> if (arg == "clear") store.clearSensitive()
            "story" -> arg.toIntOrNull()?.let(stories::select)
            // Says one line in the app's voice: the demo video records its lines again while the car is quiet.
            "say" -> voice.say(arg)
            // Lets a test repeat the once-a-day car seat and pet carrier check.
            "safety" -> if (arg == "clear") store.clearSafetyChecks()
            "trigger" -> when (arg) {
                "rest" -> askRest()
                "ventilation" -> {
                    trip.recirculationSec = maxOf(trip.recirculationSec, CareConfig.VENTILATION_AFTER_SEC)
                    trip.lastVentilationSec = -1
                    checkVentilation()
                }
                "heat" -> {
                    trip.lastHeatSec = -1
                    checkHeat()
                }
                "boarding" -> {
                    trip.boardingDone = false
                    trip.passengers = null
                    checkBoarding()
                }
                "story" -> startStory()
                "pickpassenger" -> PassengerPrompt.show(context, trip.passengers.orEmpty())
                "posture" -> {
                    trip.lastPostureSec = trip.continuousSec - CareConfig.POSTURE_AFTER_SEC
                    checkPosture()
                }
                "brake" -> onDrivingEvent(DrivingEvent.BRAKE)
                "end" -> endTrip()
            }
            "stopstory" -> voice.stopStory()
            "status" -> log(statusLine())
        }
        store.save(force = true)
    }

    private fun answerAudio(name: String): ByteArray? = runCatching {
        context.assets.open("demo/answer_$name.pcm").use { it.readBytes() }
    }.onFailure { log("no demo answer '$name'") }.getOrNull()

    fun statusLine() =
        "riders=${ridersLabel()} selection=${store.selection} manual=${store.manualKinds} passengers=${trip.passengers} " +
            "gear=${state.gear} speed=${"%.0f".format(state.speedKmh)} seats=${state.passengerSeats()} doors=${state.doorOpen} " +
            "trip=${trip.active} boarding=${trip.boardingDone} askedDriving=${trip.askedWhileDriving} " +
            "driving=${trip.drivingSec}s continuous=${trip.continuousSec}s recirculation=${trip.recirculationSec}s " +
            "cabin=${state.cabinTempC} set=${state.setTempC} fake=${demo.fakeCabinTempC} scale=${demo.timeScale} " +
            "events=${trip.hardBrakes}/${trip.hardAccels}/${trip.sharpTurns} navi=${navi.status} ux=${state.drivingRestricted} " +
            "sensitive=${store.sensitiveKinds} recent=${store.recentTrips.map { it.score }}"

    private companion object {
        const val TICK_MS = 1_000L

        /** Shorter gaps (a restarted process, a busy main thread) are not counted as a stop. */
        const val AWAY_MIN_MS = 30_000L
        const val SPEED_WINDOW_MS = 1_000L
        const val EVENT_LATCH_MS = 5_000L
        const val TRIP_END_DELAY_MS = 3_000L
        const val STORY_DELAY_MS = 5_000L

        /** Quiet time after the last tap on the parked screen, or after someone sat down, before the boarding talk starts. */
        const val SELECTION_SETTLE_MS = 3_000L

        /** Time for the seat sensor to report after a rear door closes. */
        const val DOOR_SETTLE_MS = 2_000L
    }
}
