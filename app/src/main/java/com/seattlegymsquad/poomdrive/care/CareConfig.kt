package com.seattlegymsquad.poomdrive.care

/** Timing rules and thresholds. Times are in seconds of (possibly compressed) driving time. */
object CareConfig {
    /** Rest at least every two hours on long drives (국민건강보험·국민체육진흥공단 안내). */
    const val REST_AFTER_SEC = 2 * 3600L

    /** A stop of 15 minutes or more counts as a rest (한국도로공사 휴식포인트 기준). */
    const val REST_RESET_SEC = 15 * 60L

    /** Ask again this long after the driver declined (prototype default). */
    const val REST_REASK_SEC = 30 * 60L

    /** Switch to outside air for 1-2 minutes every 1-2 hours of recirculation (한국교통안전공단 자동차연구원 권고). */
    const val VENTILATION_AFTER_SEC = 3600L
    const val VENTILATION_REPEAT_SEC = 3600L

    /** After one hour of continuous driving, suggest stretching at the next stop at a signal. */
    const val POSTURE_AFTER_SEC = 3600L

    const val HEAT_REPEAT_SEC = 15 * 60L

    /** Dogs sweat only on the paw pads and nose (서울시 폭염 안전 안내), so the heat reminder comes 1℃ earlier (prototype default). */
    const val PET_HEAT_MARGIN_C = 2f

    /** A driver who set off before saying who is on board is asked at the first stop, or after this much driving. */
    const val ASK_WHILE_DRIVING_AFTER_SEC = 3 * 60L

    /** Trips shorter than this are not scored. */
    const val MIN_TRIP_SEC = 60L

    /** Trips shorter than this are recorded quietly: no 포근 지수 announcement and no motion-sickness question. */
    const val REPORT_MIN_SEC = 20 * 60L

    /** 포근 지수 counts driving events per this much driving, so a long trip is not marked down for its length. */
    const val SCORE_WINDOW_SEC = 30 * 60L

    // Risky driving events follow the event types of 국토교통부·한국교통안전공단 (급가속, 급감속, 급회전).
    // Their published thresholds are per commercial vehicle type, so these are prototype defaults to tune on a car.
    const val HARD_BRAKE_KMH_PER_S = 8f
    const val HARD_ACCEL_KMH_PER_S = 10f
    const val EVENT_MIN_KMH = 10f
    const val SHARP_TURN_DEG = 90f
    const val SHARP_TURN_MIN_KMH = 30f

    // For a passenger who got carsick, alerts come earlier: thresholds about a quarter lower (prototype defaults).
    const val SENSITIVE_BRAKE_KMH_PER_S = 6f
    const val SENSITIVE_ACCEL_KMH_PER_S = 7.5f
    const val SENSITIVE_TURN_DEG = 70f

    /** Real time between two driving-event alerts, so the voice does not nag. */
    const val ALERT_COOLDOWN_MS = 2 * 60 * 1000L
}
