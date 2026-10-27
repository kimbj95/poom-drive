package com.seattlegymsquad.poomdrive

import android.Manifest
import android.app.Activity
import android.car.Car
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.seattlegymsquad.poomdrive.care.CareMode
import com.seattlegymsquad.poomdrive.care.UserMode
import com.seattlegymsquad.poomdrive.voice.Answer

/**
 * The parked-only screen: who is on board, the care mode, the last trip score and the demo panel.
 * The app does not declare Distraction Optimization, so the system blocks this screen while driving
 * ("주행 중에는 사용할 수 없습니다" on the emulator). As a second guard the screen also covers itself whenever
 * UX restrictions are on or the gear is not P. The care service keeps working by voice.
 */
class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var nowText: TextView
    private lateinit var scoreText: TextView
    private lateinit var lastTripText: TextView
    private lateinit var demoText: TextView
    private lateinit var drivingCover: TextView
    private val modeButtons = linkedMapOf<UserMode, Button>()

    private val refresh = object : Runnable {
        override fun run() {
            render()
            main.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildScreen())
        requestPermissions(arrayOf(Car.PERMISSION_SPEED, Manifest.permission.POST_NOTIFICATIONS), REQUEST_PERMISSIONS)
        PoomService.start(this)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        PoomService.instance?.monitor?.registerSpeed()
    }

    override fun onResume() {
        super.onResume()
        main.post(refresh)
    }

    override fun onPause() {
        main.removeCallbacks(refresh)
        super.onPause()
    }

    // ---------------------------------------------------------------- layout
    private fun buildScreen(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), dp(24), dp(32), dp(32))
        }
        column.addView(text("품 드라이브", 34f, bold = true))
        column.addView(text("정차 중에만 보이는 화면이에요. 주행 중에는 목소리로 안내해요.", 18f, color = MUTED))

        column.addView(card("지금").also { card ->
            nowText = text("", 22f)
            card.addView(nowText)
        })

        column.addView(card("누구와 함께 가나요?").also { card ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for ((mode, label) in listOf(
                UserMode.AUTO to "자동", UserMode.CHILD to "아이", UserMode.PARENT to "부모님",
                UserMode.PET to "반려견", UserMode.SOLO to "혼자",
            )) {
                val button = button(label) { PoomService.instance?.care?.setUserMode(mode) }
                modeButtons[mode] = button
                row.addView(button, LinearLayout.LayoutParams(0, dp(72), 1f).apply { marginEnd = dp(12) })
            }
            card.addView(row)
            card.addView(text("자동은 좌석 센서로 동승자를 알아채고, 누가 탔는지 목소리로 물어봐요.", 16f, color = MUTED))
        })

        column.addView(card("지난 주행").also { card ->
            scoreText = text("", 40f, bold = true, color = ACCENT)
            lastTripText = text("", 20f)
            card.addView(scoreText)
            card.addView(lastTripText)
        })

        column.addView(card("시연 (영상·시험용)").also { card ->
            demoText = text("", 18f, color = MUTED)
            card.addView(demoText)
            val rows = listOf(
                listOf("시간 압축" to { toggleTimeScale() }, "실내 31℃" to { toggleCabin() }, "탑승 확인" to { demo("trigger:boarding") }),
                listOf("휴식 제안" to { demo("trigger:rest") }, "환기 권유" to { demo("trigger:ventilation") }, "동화" to { toggleStory() }),
            )
            for (items in rows) {
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, 0) }
                for ((label, action) in items) {
                    row.addView(button(label) { action() }, LinearLayout.LayoutParams(0, dp(64), 1f).apply { marginEnd = dp(12) })
                }
                card.addView(row)
            }
        })

        val scroll = ScrollView(this).apply {
            setBackgroundColor(BACKGROUND)
            addView(column)
        }
        // Covers the whole screen while the car restricts driver distraction, so nothing on it needs a look.
        drivingCover = text("주행 중에는 화면 대신 목소리로 안내해요.\n정차하면(P단) 다시 보여요.", 30f, bold = true).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(BACKGROUND)
            visibility = View.GONE
            isClickable = true
        }
        return FrameLayout(this).apply {
            addView(scroll)
            addView(drivingCover, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
    }

    private fun card(title: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(20), dp(24), dp(20))
        background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(SURFACE)
        }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(20) }
        addView(text(title, 20f, bold = true, color = MUTED))
    }

    private fun text(value: String, sizeSp: Float, bold: Boolean = false, color: Int = Color.WHITE) = TextView(this).apply {
        text = value
        textSize = sizeSp
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(0f, 1.25f)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 20f
        isAllCaps = false
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(BUTTON)
        }
        setOnClickListener { onClick() }
    }

    // ---------------------------------------------------------------- state
    private fun render() {
        val service = PoomService.instance
        if (service == null) {
            nowText.text = "케어 서비스를 시작하는 중이에요."
            return
        }
        val state = service.state
        drivingCover.visibility = if (state.drivingRestricted || !state.parked()) View.VISIBLE else View.GONE
        val trip = service.store.trip
        val userMode = service.store.userMode
        val cabin = service.demo.fakeCabinTempC.takeUnless { it.isNaN() } ?: state.cabinTempC
        nowText.text = buildString {
            appendLine("탑승: ${state.passengerSeats().joinToString(", ") { seatName(it) }.ifEmpty { "운전자만" }}")
            appendLine("모드: ${modeName(service.care.mode())}${if (userMode == UserMode.AUTO) " (자동)" else ""}")
            appendLine("연속 주행: ${duration(trip.continuousSec)}")
            appendLine("내기순환: ${if (state.recirculationOn == true) duration(trip.recirculationSec) + "째" else "꺼짐"}")
            append("실내 ${temperature(cabin)} · 설정 ${temperature(state.setTempC)}")
        }
        modeButtons.forEach { (mode, button) ->
            (button.background as GradientDrawable).setColor(if (mode == userMode) ACCENT_DARK else BUTTON)
        }
        val last = service.store.lastTrip
        if (last == null) {
            scoreText.text = "-"
            lastTripText.text = "아직 기록이 없어요."
        } else {
            scoreText.text = "흔들림 점수 ${last.score}점"
            lastTripText.text = buildString {
                append("${modeName(last.mode)} · 주행 ${last.minutes}분 · 급제동 ${last.hardBrakes} · 급가속 ${last.hardAccels} · 급회전 ${last.sharpTurns}")
                when (last.sickness) {
                    Answer.YES -> append(" · 멀미 괜찮음")
                    Answer.NO -> append(" · 멀미 있음")
                    else -> Unit
                }
            }
        }
        val scale = service.demo.timeScale
        val fake = service.demo.fakeCabinTempC
        demoText.text = "시간 압축 ${if (scale > 1) "켜짐 (1초 = ${scale}초)" else "꺼짐"} · " +
            "실내 온도 시연 ${if (fake.isNaN()) "꺼짐" else "${fake.toInt()}℃"} · 동화 ${if (service.voice.storyPlaying) "재생 중" else "꺼짐"}"
    }

    private fun demo(command: String) {
        PoomService.instance?.care?.onDemoCommand(command)
    }

    private fun toggleTimeScale() {
        val service = PoomService.instance ?: return
        demo(if (service.demo.timeScale > 1) "timescale:1" else "timescale:60")
    }

    private fun toggleCabin() {
        val service = PoomService.instance ?: return
        demo(if (service.demo.fakeCabinTempC.isNaN()) "cabin:31" else "cabin:off")
    }

    private fun toggleStory() {
        val service = PoomService.instance ?: return
        demo(if (service.voice.storyPlaying) "stopstory" else "trigger:story")
    }

    private fun seatName(seat: String) = when (seat) {
        "ROW_1_RIGHT" -> "조수석"
        "ROW_2_LEFT" -> "뒷좌석 왼쪽"
        "ROW_2_CENTER" -> "뒷좌석 가운데"
        "ROW_2_RIGHT" -> "뒷좌석 오른쪽"
        else -> seat
    }

    private fun modeName(mode: CareMode) = when (mode) {
        CareMode.SOLO -> "혼자"
        CareMode.PASSENGER -> "동승자"
        CareMode.CHILD -> "아이 동승"
        CareMode.PARENT -> "부모님 동승"
        CareMode.PET -> "반려견 동승"
    }

    private fun duration(seconds: Long): String {
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        return if (h > 0) "${h}시간 ${m}분" else "${m}분"
    }

    private fun temperature(celsius: Float?) = celsius?.let { "%.1f℃".format(it) } ?: "-"

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val REQUEST_PERMISSIONS = 1
        val BACKGROUND = Color.parseColor("#0F1720")
        val SURFACE = Color.parseColor("#1B2633")
        val BUTTON = Color.parseColor("#2A3A4C")
        val ACCENT = Color.parseColor("#F2B94B")
        val ACCENT_DARK = Color.parseColor("#B07A1A")
        val MUTED = Color.parseColor("#A8B3C0")
    }
}
