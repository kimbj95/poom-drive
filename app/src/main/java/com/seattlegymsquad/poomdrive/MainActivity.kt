package com.seattlegymsquad.poomdrive

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.car.Car
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.TypefaceSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.seattlegymsquad.poomdrive.care.Selection
import com.seattlegymsquad.poomdrive.voice.Answer
import com.seattlegymsquad.poomdrive.voice.PassengerType

/**
 * The parked-only screen, in the look of the Pleos vehicle-control app: square tiles, 1px dividers, an inverted
 * selection, Asta Sans, and day and night colors that follow the system. It shows who is on board, lets the driver
 * pick (several kinds at once), and the 포근 지수 history. The demo panel opens after holding the title for 3 seconds.
 * The app does not declare Distraction Optimization, so the system blocks this screen while driving. As a second
 * guard the screen also covers itself whenever UX restrictions are on or the gear is not P.
 */
class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var colors: Palette
    private lateinit var regular: Typeface
    private lateinit var bold: Typeface
    private lateinit var seatMap: SeatMapView
    private lateinit var ridersText: TextView
    private lateinit var howText: TextView
    private lateinit var factsText: TextView
    private val segments = linkedMapOf<String, TextView>()
    private lateinit var scoreText: TextView
    private lateinit var scoreSub: TextView
    private lateinit var trendChart: TrendView
    private lateinit var trendText: TextView
    private lateinit var sensitiveText: TextView
    private lateinit var sensitiveState: TextView
    private lateinit var demoPanel: LinearLayout
    private lateinit var demoText: TextView
    private lateinit var drivingCover: TextView

    private val refresh = object : Runnable {
        override fun run() {
            render()
            main.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        colors = if (night) NIGHT else DAY
        regular = Typeface.Builder(assets, FONT).setFontVariationSettings("'wght' 400").build()
        bold = Typeface.Builder(assets, FONT).setFontVariationSettings("'wght' 700").build()
        setContentView(buildScreen())
        requestPermissions(
            arrayOf(Car.PERMISSION_SPEED, Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.BLUETOOTH_CONNECT),
            REQUEST_PERMISSIONS,
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        PoomService.instance?.monitor?.registerSpeed()
    }

    override fun onResume() {
        super.onResume()
        // Also when the screen comes back to the front after the service was stopped (e.g. during Suspend).
        PoomService.start(this)
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
            setBackgroundColor(colors.bg)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(dp(48), dp(34), dp(48), dp(28))
        }
        header.addView(text("품 드라이브", 44f, bold = true).also(::holdForDemo))
        header.addView(text("정차 중(P단)에만 보이는 화면이에요", 24f, color = colors.sub).apply { setPadding(dp(28), 0, 0, dp(4)) })
        column.addView(header)
        column.addView(divider())

        val now = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(48), dp(28), dp(48), dp(28))
        }
        seatMap = SeatMapView(this)
        now.addView(seatMap, LinearLayout.LayoutParams(dp(220), dp(300)))
        val nowText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(56), 0, 0, 0)
        }
        nowText.addView(label("지금"))
        ridersText = text("", 56f, bold = true)
        howText = text("", 24f, color = colors.sub).apply { setPadding(0, dp(10), 0, 0) }
        factsText = text("", 28f, color = colors.sub).apply { setPadding(0, dp(26), 0, 0) }
        nowText.addView(ridersText)
        nowText.addView(howText)
        nowText.addView(factsText)
        now.addView(nowText)
        column.addView(now)
        column.addView(divider())

        val who = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(30), dp(48), dp(34))
        }
        who.addView(label("누구와 함께 가나요?"))
        // The container's color shows through 1px gaps as the cell borders.
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(colors.line)
            setPadding(1, 1, 1, 1)
        }
        for ((key, name) in listOf(AUTO to "자동", CHILD to "아이", PARENT to "부모님", PET to "반려견", SOLO to "혼자")) {
            val cell = text(name, 30f).apply {
                gravity = Gravity.CENTER
                setOnClickListener { onSegment(key) }
            }
            segments[key] = cell
            row.addView(cell, LinearLayout.LayoutParams(0, dp(86), 1f).apply { if (key != AUTO) marginStart = 1 })
        }
        who.addView(row)
        who.addView(text("여러 명을 함께 고를 수 있어요. 혼자를 고르면 나머지는 꺼져요.", 24f, color = colors.sub).apply { setPadding(0, dp(10), 0, 0) })
        column.addView(who)
        column.addView(divider())

        val tiles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tiles.addView(tile().apply {
            addView(label("포근 지수"))
            scoreText = text("-", 120f, bold = true).apply { includeFontPadding = false }
            scoreSub = text("", 24f, color = colors.sub).apply { setPadding(0, dp(10), 0, 0) }
            addView(scoreText)
            addView(scoreSub)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        tiles.addView(verticalDivider())
        tiles.addView(tile().apply {
            addView(label("최근 5번"))
            trendChart = TrendView(this@MainActivity)
            addView(trendChart, LinearLayout.LayoutParams(dp(340), dp(190)))
            trendText = text("", 24f, color = colors.sub).apply { setPadding(0, dp(10), 0, 0) }
            addView(trendText)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        tiles.addView(verticalDivider())
        tiles.addView(tile().apply {
            setBackgroundColor(colors.tile)
            addView(label("멀미 주의"))
            sensitiveText = text("", 56f, bold = true).apply { setPadding(0, dp(18), 0, 0) }
            sensitiveState = text("", 28f, bold = true).apply { setPadding(0, dp(14), 0, 0) }
            addView(sensitiveText)
            addView(sensitiveState)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        column.addView(tiles, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(colors.bg)
            addView(column)
        }
        demoPanel = buildDemoPanel()
        // Covers the whole screen while the car restricts driver distraction, so nothing on it needs a look.
        drivingCover = text("주행 중에는 화면 대신 목소리로 안내해요.\n정차하면(P단) 다시 보여요.", 30f, bold = true).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(colors.bg)
            visibility = View.GONE
            isClickable = true
        }
        return FrameLayout(this).apply {
            addView(scroll)
            addView(demoPanel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
            addView(drivingCover, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
    }

    /** Hidden panel for the demo video and tests, over the tiles. */
    private fun buildDemoPanel() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        setBackgroundColor(colors.tile)
        addView(divider())
        val body = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(24), dp(48), dp(28))
        }
        body.addView(label("시연 (영상·시험용)"))
        demoText = text("", 22f, color = colors.sub)
        body.addView(demoText)
        val rows = listOf(
            listOf("시간 압축" to { toggleTimeScale() }, "실내 31℃" to { toggleCabin() }, "탑승 확인" to { demo("trigger:boarding") }, "닫기" to { visibility = View.GONE }),
            listOf("휴식 제안" to { demo("trigger:rest") }, "환기 권유" to { demo("trigger:ventilation") }, "동화" to { toggleStory() }, "누가 탔나요 알림" to { demo("trigger:pickpassenger") }),
        )
        for (items in rows) {
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(colors.line)
                setPadding(1, 1, 1, 1)
            }
            items.forEachIndexed { i, (name, action) ->
                row.addView(text(name, 24f).apply {
                    gravity = Gravity.CENTER
                    setBackgroundColor(colors.bg)
                    setOnClickListener { action() }
                }, LinearLayout.LayoutParams(0, dp(72), 1f).apply { if (i > 0) marginStart = 1 })
            }
            body.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        }
        addView(body)
    }

    /** Holding the title for [DEMO_HOLD_MS] opens the demo panel, so it stays out of the way in normal use. */
    @SuppressLint("ClickableViewAccessibility")
    private fun holdForDemo(view: View) {
        val open = Runnable { demoPanel.visibility = View.VISIBLE }
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> main.postDelayed(open, DEMO_HOLD_MS)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> main.removeCallbacks(open)
            }
            true
        }
    }

    private fun tile() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(48), dp(34), dp(48), dp(34))
    }

    private fun label(value: String) = text(value, 26f, color = colors.sub).apply { setPadding(0, 0, 0, dp(10)) }

    private fun divider() = View(this).apply {
        setBackgroundColor(colors.line)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
    }

    private fun verticalDivider() = View(this).apply {
        setBackgroundColor(colors.line)
        layoutParams = LinearLayout.LayoutParams(1, LinearLayout.LayoutParams.MATCH_PARENT)
    }

    private fun text(value: String, sizeSp: Float, bold: Boolean = false, color: Int = colors.text) = TextView(this).apply {
        text = value
        textSize = sizeSp
        setTextColor(color)
        typeface = if (bold) this@MainActivity.bold else regular
        setLineSpacing(0f, 1.15f)
    }

    // ---------------------------------------------------------------- choice
    /** 자동 and 혼자 stand alone; 아이, 부모님 and 반려견 toggle and can be on together (none left on means 자동). */
    private fun onSegment(key: String) {
        val care = PoomService.instance?.care ?: return
        val store = PoomService.instance?.store ?: return
        when (key) {
            AUTO -> care.select(Selection.AUTO)
            SOLO -> care.select(Selection.SOLO)
            else -> {
                val kind = PassengerType.valueOf(key)
                val current = if (store.selection == Selection.MANUAL) store.manualKinds else emptySet()
                val next = if (kind in current) current - kind else current + kind
                if (next.isEmpty()) care.select(Selection.AUTO) else care.select(Selection.MANUAL, next)
            }
        }
        render()
    }

    // ---------------------------------------------------------------- state
    private fun render() {
        val service = PoomService.instance
        if (service == null) {
            ridersText.text = "시작하는 중"
            return
        }
        val state = service.state
        drivingCover.visibility = if (state.drivingRestricted || !state.parked()) View.VISIBLE else View.GONE
        val store = service.store
        val trip = store.trip
        val riders = service.care.riders()

        seatMap.update(state.occupied.filterValues { it }.keys, riders?.contains(PassengerType.PET) == true, colors)
        ridersText.text = service.care.ridersLabel(riders)
        howText.text = when (store.selection) {
            Selection.SOLO -> "화면에서 혼자로 골랐어요"
            Selection.MANUAL -> "화면에서 골랐어요"
            Selection.AUTO -> when {
                riders == null -> "누가 탔는지 아직 몰라요. 더 조심스럽게 살펴요"
                riders.isEmpty() -> "좌석 센서로 정했어요"
                else -> "좌석 센서와 대답으로 정했어요"
            }
        }
        val cabin = service.demo.fakeCabinTempC.takeUnless { it.isNaN() } ?: state.cabinTempC
        factsText.text = SpannableStringBuilder()
            .fact("연속 주행", duration(trip.continuousSec))
            .fact("내기순환", if (state.recirculationOn == true) duration(trip.recirculationSec) + "째" else "꺼짐")
            .fact("실내", temperature(cabin))
            .append(" · 설정 ${temperature(state.setTempC)}")

        segments.forEach { (key, cell) ->
            val on = when (key) {
                AUTO -> store.selection == Selection.AUTO
                SOLO -> store.selection == Selection.SOLO
                else -> store.selection == Selection.MANUAL && PassengerType.valueOf(key) in store.manualKinds
            }
            cell.setBackgroundColor(if (on) colors.inverseBg else colors.bg)
            cell.setTextColor(if (on) colors.inverseText else colors.text)
            cell.typeface = if (on) bold else regular
        }

        val trips = store.recentTrips
        val last = trips.lastOrNull()
        if (last == null) {
            scoreText.text = "-"
            scoreSub.text = "아직 기록이 없어요"
        } else {
            scoreText.text = last.score.toString()
            val events = listOf("급제동" to last.hardBrakes, "급가속" to last.hardAccels, "급회전" to last.sharpTurns)
                .filter { it.second > 0 }.joinToString(" · ") { "${it.first} ${it.second}회" }.ifEmpty { "흔들림 없음" }
            val sickness = when (last.sickness) {
                Answer.YES -> " · 멀미 괜찮음"
                Answer.NO -> " · 멀미 있었음"
                else -> ""
            }
            scoreSub.text = "지난 주행 · ${last.minutes}분 · $events\n${last.riders}$sickness"
        }
        trendChart.update(trips.map { it.score }, colors, regular)
        trendText.text = if (trips.size < 2) {
            "두 번 이상 달리면 비교해 드려요"
        } else {
            val change = trips.last().score - trips[trips.size - 2].score
            when {
                change >= TREND_STEP -> "지난번보다 포근해졌어요"
                change <= -TREND_STEP -> "지난번보다 흔들렸어요"
                else -> "지난번과 비슷해요"
            }
        }
        val sensitive = store.sensitiveKinds
        sensitiveText.text = if (sensitive.isEmpty()) "없음" else sensitive.sorted().joinToString(" · ") { it.noun }
        sensitiveState.text = if (sensitive.isEmpty()) "도착할 때 물어보고 기록해요" else "작은 흔들림도 알려요"
        sensitiveState.setTextColor(if (sensitive.isEmpty()) colors.sub else colors.accent)
        sensitiveState.typeface = if (sensitive.isEmpty()) regular else bold

        val scale = service.demo.timeScale
        val fake = service.demo.fakeCabinTempC
        demoText.text = "시간 압축 ${if (scale > 1) "켜짐 (1초 = ${scale}초)" else "꺼짐"} · " +
            "실내 온도 시연 ${if (fake.isNaN()) "꺼짐" else "${fake.toInt()}℃"} · 동화 ${if (service.voice.storyPlaying) "재생 중" else "꺼짐"}"
    }

    /** "연속 주행 1시간 2분" with the value in the text color and bold, then a gap before the next fact. */
    private fun SpannableStringBuilder.fact(name: String, value: String): SpannableStringBuilder {
        if (isNotEmpty()) append("     ")
        append("$name ")
        val start = length
        append(value)
        setSpan(ForegroundColorSpan(colors.text), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(TypefaceSpan(bold), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return this
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

    private fun duration(seconds: Long): String {
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        return if (h > 0) "${h}시간 ${m}분" else "${m}분"
    }

    private fun temperature(celsius: Float?) = celsius?.let { "%.0f℃".format(it) } ?: "-"

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    /** Colors sampled from the Pleos vehicle-control app in day and night mode. */
    class Palette(
        val bg: Int, val tile: Int, val line: Int, val text: Int, val sub: Int,
        val inverseBg: Int, val inverseText: Int, val accent: Int, val seatOn: Int, val seatOff: Int,
    )

    private companion object {
        const val REQUEST_PERMISSIONS = 1
        const val FONT = "fonts/AstaSans.ttf"
        const val DEMO_HOLD_MS = 3_000L

        /** 포근 지수 change from the previous trip that counts as smoother or bumpier. */
        const val TREND_STEP = 5

        const val AUTO = "AUTO"
        const val SOLO = "SOLO"
        val CHILD = PassengerType.CHILD.name
        val PARENT = PassengerType.PARENT.name
        val PET = PassengerType.PET.name

        val DAY = Palette(
            bg = Color.parseColor("#FFFFFF"), tile = Color.parseColor("#F0F0F0"), line = Color.parseColor("#A6A6A6"),
            text = Color.parseColor("#000000"), sub = Color.parseColor("#7C7C7C"), inverseBg = Color.parseColor("#000000"),
            inverseText = Color.parseColor("#FFFFFF"), accent = Color.parseColor("#1262F5"),
            seatOn = Color.parseColor("#000000"), seatOff = Color.parseColor("#C8C8C8"),
        )
        val NIGHT = Palette(
            bg = Color.parseColor("#151515"), tile = Color.parseColor("#222222"), line = Color.parseColor("#333333"),
            text = Color.parseColor("#DDDDDD"), sub = Color.parseColor("#9A9A9A"), inverseBg = Color.parseColor("#DDDDDD"),
            inverseText = Color.parseColor("#151515"), accent = Color.parseColor("#4D8BFF"),
            seatOn = Color.parseColor("#DDDDDD"), seatOff = Color.parseColor("#444444"),
        )
    }
}

/** Top view of the cabin: occupied seats filled, empty ones outlined, and a dot on a free rear seat for a pet. */
private class SeatMapView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var occupied: Set<String> = emptySet()
    private var pet = false
    private var colors: MainActivity.Palette? = null

    fun update(occupied: Set<String>, pet: Boolean, colors: MainActivity.Palette) {
        if (occupied == this.occupied && pet == this.pet && colors == this.colors) return
        this.occupied = occupied
        this.pet = pet
        this.colors = colors
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val c = colors ?: return
        val s = width / 220f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3 * s
        paint.color = c.line
        canvas.drawRoundRect(RectF(20 * s, 10 * s, 200 * s, 290 * s), 70 * s, 70 * s, paint)
        val seats = listOf("ROW_1_LEFT" to (42f to 70f), "ROW_1_RIGHT" to (120f to 70f), "ROW_2_LEFT" to (42f to 168f), "ROW_2_RIGHT" to (120f to 168f))
        for ((seat, pos) in seats) {
            val rect = RectF(pos.first * s, pos.second * s, (pos.first + 58) * s, (pos.second + 66) * s)
            // The driver's seat is always taken while the screen is in use.
            if (seat == "ROW_1_LEFT" || seat in occupied) {
                paint.style = Paint.Style.FILL
                paint.color = c.seatOn
            } else {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 4 * s
                paint.color = c.seatOff
            }
            canvas.drawRoundRect(rect, 14 * s, 14 * s, paint)
        }
        if (pet) {
            val free = listOf("ROW_2_RIGHT" to 149f, "ROW_2_LEFT" to 71f).firstOrNull { it.first !in occupied }?.second ?: 149f
            paint.style = Paint.Style.FILL
            paint.color = c.accent
            canvas.drawCircle(free * s, 201 * s, 14 * s, paint)
        }
        paint.style = Paint.Style.FILL
        paint.color = c.sub
        paint.textSize = 18 * s
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("운전석", 71 * s, 158 * s, paint)
        canvas.drawText("뒷좌석", 110 * s, 262 * s, paint)
    }
}

/** 포근 지수 of the last trips as bars from zero, the newest in the text color, with the value under each bar. */
private class TrendView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var scores: List<Int> = emptyList()
    private var colors: MainActivity.Palette? = null
    private var font: Typeface? = null

    fun update(scores: List<Int>, colors: MainActivity.Palette, font: Typeface) {
        if (scores == this.scores && colors == this.colors) return
        this.scores = scores
        this.colors = colors
        this.font = font
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val c = colors ?: return
        val s = width / 340f
        val base = 150 * s
        paint.color = c.line
        canvas.drawRect(0f, base, width.toFloat(), base + 1, paint)
        scores.forEachIndexed { i, score ->
            val x = (20 + i * 64) * s
            val h = maxOf(4f, score / 100f * 140 * s)
            paint.color = if (i == scores.lastIndex) c.text else c.seatOff
            canvas.drawRoundRect(RectF(x, base - h, x + 40 * s, base), 4 * s, 4 * s, paint)
            canvas.drawRect(x, base - minOf(h, 4 * s), x + 40 * s, base, paint)
            paint.color = c.sub
            paint.textSize = 22 * s
            paint.typeface = font
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText(score.toString(), x + 20 * s, 178 * s, paint)
        }
    }
}
