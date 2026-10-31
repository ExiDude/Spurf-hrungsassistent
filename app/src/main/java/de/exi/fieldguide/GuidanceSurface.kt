package de.exi.fieldguide

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import de.exi.fieldguide.core.*
import kotlin.math.*

/** 2D field/map view and perspective guidance, rendered with Android Canvas (no online tiles). */
class GuidanceSurface(context: Context) : View(context) {
    var field: FarmField? = null
    var current: LatLon? = null
    var engine: GuidanceEngine? = null
    var solution: Guidance? = null
    var coverage: CoverageGrid? = null
    var planMode: Boolean = false
    var recordingBoundary: Boolean = false
    var bearing: Double? = null
    var toolWidth = 3.0
    var goodFix = false
    var onTapMap: ((LatLon) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillGreen = Color.rgb(75, 216, 91)
    private val deepGreen = Color.rgb(29, 105, 55)
    private val lineGreen = Color.rgb(119, 250, 119)
    private val dimWhite = Color.rgb(192, 211, 197)
    private val bg = Color.rgb(19, 36, 32)
    private var zoomFactor = 1.0
    private var panX = 0f
    private var panY = 0f
    private var startX = 0f
    private var startY = 0f
    private var moved = false
    private var hadMultiTouch = false
    private var planFrame: LocalFrame? = null
    private var planAnchor = Vec2(0.0, 0.0)
    private var planScale = 1.0
    private val pinch = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoomFactor = (zoomFactor * detector.scaleFactor).coerceIn(0.3, 16.0)
            invalidate(); return true
        }
    })
    private fun rect(canvas: Canvas, color: Int, l: Float, t: Float, r: Float, b: Float) {
        paint.color = color; paint.style = Paint.Style.FILL
        canvas.drawRect(l, t, r, b, paint)
    }
    private fun line(canvas: Canvas, color: Int, width: Float, x: Float, y: Float, x2: Float, y2: Float) {
        paint.color = color; paint.strokeWidth = width; paint.style = Paint.Style.STROKE
        canvas.drawLine(x, y, x2, y2, paint); paint.style = Paint.Style.FILL
    }
    private fun caption(canvas: Canvas, label: String, x: Float, y: Float, size: Float, color: Int = Color.WHITE) {
        paint.color = color; paint.textSize = size; paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        paint.style = Paint.Style.FILL
        canvas.drawText(label, x, y, paint)
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        canvas.drawColor(bg)
        if (planMode) drawPlan(canvas) else drawPerspective(canvas)
        if (!goodFix) {
            rect(canvas, Color.argb(195, 103, 38, 22), 0f, 0f, width.toFloat(), dp(28f))
            caption(canvas, "GNSS NICHT ZUVERLÄSSIG – KEINE SPURFREIGABE", dp(8f), dp(19f), dp(12f))
        }
    }
    private fun dp(value: Float) = value * resources.displayMetrics.density

    private fun drawPerspective(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val horizon = h * 0.22f
        paint.shader = LinearGradient(0f, 0f, 0f, horizon, Color.rgb(95, 136, 158),
            Color.rgb(183, 202, 179), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, horizon, paint); paint.shader = null
        rect(canvas, Color.rgb(68, 104, 52), 0f, horizon - dp(7f), w, horizon + dp(12f))
        paint.shader = LinearGradient(0f, horizon, 0f, h,
            Color.rgb(118, 83, 57), Color.rgb(82, 61, 46), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, horizon, w, h, paint); paint.shader = null
        // Gentle furrows make 3D guidance legible without an internet map.
        for (i in -18..18) {
            line(canvas, Color.argb(90, 32, 29, 24), dp(1.3f), w / 2 + i * dp(1.5f), horizon,
                w / 2 + i * dp(38f), h)
        }
        val fix = current
        val g = engine
        val worldFrame = LocalFrame(fix ?: field?.boundary?.firstOrNull() ?: LatLon(0.0, 0.0))
        val bearingDeg = bearing ?: if (g != null) {
            (Math.toDegrees(atan2(g.dir.x, g.dir.y)) + 360.0) % 360.0
        } else 0.0
        val rad = Math.toRadians(bearingDeg)
        val forward = Vec2(sin(rad), cos(rad))
        val right = Vec2(cos(rad), -sin(rad))
        fun projection(point: LatLon): Pair<Float, Float>? {
            val xy = worldFrame.meters(point)
            val z = xy.dot(forward)
            if (z < -2.0 || z > 550.0) return null
            val side = xy.dot(right)
            val depth = max(0.0, z)
            val ratio = (depth / (depth + 23.0)).toFloat()
            val y = h - (h - horizon) * ratio
            val scale = (dp(28f) * 23.0 / (depth + 23.0)).toFloat()
            return Pair(w / 2f + side.toFloat() * scale, y)
        }
        if (fix != null && g != null) {
            val lane = solution?.lane ?: 0
            val base = g.frame.meters(fix)
            val t = (base - g.aXY).dot(g.dir)
            for (n in lane - 14..lane + 14) {
                val path = Path()
                var started = false
                // Approximate lane surface via strokes; extends beyond A/B endpoints.
                for (s in -250..350 step 8) {
                    val laneXY = g.aXY + g.dir * (t + s) + g.left * (n * g.spacingMeters)
                    val view = projection(g.frame.geo(laneXY))
                    if (view == null) { started = false; continue }
                    if (!started) { path.moveTo(view.first, view.second); started = true }
                    else path.lineTo(view.first, view.second)
                }
                paint.color = if (n == lane) Color.argb(225, 105, 244, 104) else Color.argb(145, 77, 199, 78)
                paint.strokeWidth = dp(if (n == lane) 4.4f else 2.4f)
                paint.style = Paint.Style.STROKE
                canvas.drawPath(path, paint)
                paint.style = Paint.Style.FILL
            }
            // Real traveled path, separate from guidance reference.
            field?.track?.takeLast(450)?.let { track ->
                for (i in 1 until track.size) if (track[i].connectsPrevious) {
                    val a = projection(track[i-1].point); val b = projection(track[i].point)
                    if (a != null && b != null) line(canvas, Color.rgb(84, 190, 219), dp(2.5f), a.first, a.second, b.first, b.second)
                }
            }
        } else {
            caption(canvas, "Feld anlegen · A und B setzen", dp(18f), h * 0.48f, dp(16f), Color.WHITE)
        }
        // Visual implement footprint at the bottom center.
        paint.color = Color.argb(135, 168, 246, 169)
        val footprint = Path().apply {
            moveTo(w * 0.35f, h); lineTo(w * 0.41f, h * 0.80f)
            lineTo(w * 0.59f, h * 0.80f); lineTo(w * 0.65f, h); close()
        }
        canvas.drawPath(footprint, paint)
        line(canvas, Color.WHITE, dp(2f), w / 2, h * 0.80f, w / 2, h * 0.96f)
        paint.color = Color.WHITE
        canvas.drawCircle(w / 2, h * 0.87f, dp(6f), paint)
        caption(canvas, "3D-SPURFÜHRUNG · visuelle Anzeige", dp(10f), dp(20f), dp(12f), Color.WHITE)
        solution?.let {
            val label = when (it.steer) {
                Steer.LEFT -> "← LINKS"; Steer.RIGHT -> "RECHTS →"
                Steer.STRAIGHT -> "SPUR OK"; else -> "RICHTUNG UNBEKANNT"
            }
            val text = "${"%.2f".format(java.util.Locale.GERMANY, abs(it.lateralErrorMeters))} m  $label"
            paint.textSize = dp(21f); paint.typeface = Typeface.DEFAULT_BOLD
            rect(canvas, Color.argb(185, 8, 29, 24), dp(7f), dp(31f), w - dp(7f), dp(77f))
            caption(canvas, text, dp(17f), dp(61f), dp(21f), if (goodFix) fillGreen else Color.LTGRAY)
        }
    }
    private fun drawPlan(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val f = field
        val source = f?.boundary?.firstOrNull() ?: current
        if (source == null) {
            caption(canvas, "Warte auf GNSS-Position ...", dp(16f), h / 2, dp(18f))
            return
        }
        val frame = LocalFrame(source)
        planFrame = frame
        val xy = f?.boundary?.map(frame::meters).orEmpty()
        val curr = current?.let(frame::meters)
        val anchor = if (xy.isNotEmpty()) Vec2((xy.minOf { it.x } + xy.maxOf { it.x }) / 2.0,
            (xy.minOf { it.y } + xy.maxOf { it.y }) / 2.0) else curr ?: Vec2(0.0, 0.0)
        planAnchor = anchor
        val extent = if (xy.size >= 2) max(20.0, max(xy.maxOf { it.x } - xy.minOf { it.x },
            xy.maxOf { it.y } - xy.minOf { it.y })) else 110.0
        planScale = (min(w, h) * 0.75 / extent * zoomFactor).coerceIn(0.05, 100.0)
        fun pt(p: Vec2) = PointF((w / 2 + (p.x - anchor.x) * planScale + panX).toFloat(),
            (h / 2 - (p.y - anchor.y) * planScale + panY).toFloat())
        rect(canvas, Color.rgb(20, 43, 37), 0f, 0f, w, h)
        val spacingPx = 25.0 * planScale
        if (spacingPx > dp(8f)) {
            for (i in -25..25) {
                val x = w / 2 + i * spacingPx.toFloat() + panX
                val y = h / 2 + i * spacingPx.toFloat() + panY
                line(canvas, Color.rgb(35, 65, 55), dp(0.8f), x, 0f, x, h)
                line(canvas, Color.rgb(35, 65, 55), dp(0.8f), 0f, y, w, y)
            }
        }
        if (xy.isNotEmpty()) {
            val path = Path()
            xy.forEachIndexed { i, p -> val screen = pt(p)
                if (i == 0) path.moveTo(screen.x, screen.y) else path.lineTo(screen.x, screen.y)
            }
            if (xy.size >= 3) path.close()
            paint.color = Color.argb(90, 61, 107, 61)
            paint.style = Paint.Style.FILL; canvas.drawPath(path, paint)
            paint.color = if (recordingBoundary) Color.YELLOW else fillGreen
            paint.strokeWidth = dp(2.5f); paint.style = Paint.Style.STROKE
            canvas.drawPath(path, paint); paint.style = Paint.Style.FILL
            if (recordingBoundary) xy.forEach { p ->
                val v = pt(p); paint.color = Color.YELLOW; canvas.drawCircle(v.x, v.y, dp(3.5f), paint)
            }
        }
        val cover = coverage
        if (cover != null && cover.workedMeters2 > 0.0) {
            paint.color = Color.argb(130, 90, 217, 103)
            val rad = (cover.cellMeters * planScale / 2.0).toFloat().coerceAtLeast(0.8f)
            cover.coveredCenters(4500).forEach { p ->
                val point = pt(frame.meters(cover.frame.geo(p)))
                canvas.drawRect(point.x - rad, point.y - rad, point.x + rad, point.y + rad, paint)
            }
        }
        val guidance = engine
        if (guidance != null) {
            val n0 = solution?.lane ?: 0
            for (index in n0 - 35..n0 + 35) {
                val (a, b) = guidance.laneLine(index)
                val p = pt(frame.meters(guidance.frame.geo(a - guidance.dir * 10000.0)))
                val q = pt(frame.meters(guidance.frame.geo(b + guidance.dir * 10000.0)))
                line(canvas, if (index == n0) lineGreen else Color.argb(115, 90, 214, 92),
                    dp(if (index == n0) 2.6f else 1.0f), p.x, p.y, q.x, q.y)
            }
            val a = pt(frame.meters(guidance.a)); val b = pt(frame.meters(guidance.b))
            paint.color = Color.WHITE; canvas.drawCircle(a.x, a.y, dp(6f), paint)
            paint.color = Color.YELLOW; canvas.drawCircle(b.x, b.y, dp(6f), paint)
            caption(canvas, "A", a.x + dp(7f), a.y, dp(14f))
            caption(canvas, "B", b.x + dp(7f), b.y, dp(14f))
        }
        f?.track?.let { track ->
            for (i in 1 until track.size) if (track[i].connectsPrevious) {
                val a = pt(frame.meters(track[i-1].point)); val b = pt(frame.meters(track[i].point))
                line(canvas, Color.rgb(100, 201, 247), dp(2f), a.x, a.y, b.x, b.y)
            }
        }
        if (curr != null) {
            val p = pt(curr)
            paint.color = Color.WHITE; canvas.drawCircle(p.x, p.y, dp(8f), paint)
            paint.color = if (goodFix) deepGreen else Color.RED
            canvas.drawCircle(p.x, p.y, dp(5f), paint)
            bearing?.let { deg ->
                val rad = Math.toRadians(deg)
                line(canvas, Color.WHITE, dp(2f), p.x, p.y,
                    p.x + (sin(rad) * dp(23f)).toFloat(), p.y - (cos(rad) * dp(23f)).toFloat())
            }
        }
        caption(canvas, if (recordingBoundary) "FELDGRENZE AUFZEICHNEN · tippen oder abfahren" else
            "FELDKARTE · 2D · grüner Bereich = bearbeitet", dp(9f), dp(20f), dp(12f))
        caption(canvas, "N ↑", w - dp(47f), dp(21f), dp(12f))
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!planMode) return true
        pinch.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { startX = event.x; startY = event.y; moved = false; hadMultiTouch = false; return true }
            MotionEvent.ACTION_POINTER_DOWN -> { hadMultiTouch = true; moved = true }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1 && !pinch.isInProgress) {
                    val dx = event.x - startX; val dy = event.y - startY
                    if (abs(dx) + abs(dy) > dp(4f)) moved = true
                    if (moved) { panX += dx; panY += dy; invalidate() }
                    startX = event.x; startY = event.y
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!moved && !hadMultiTouch && recordingBoundary && planFrame != null && !pinch.isInProgress) {
                    val v = Vec2(planAnchor.x + (event.x - width / 2.0 - panX) / planScale,
                        planAnchor.y - (event.y - height / 2.0 - panY) / planScale)
                    onTapMap?.invoke(planFrame!!.geo(v))
                }
                performClick()
            }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
