package com.example.signaldetector

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class CompassView(context: Context) : View(context) {
    var currentAzimuthDeg: Float = 0f
    var targetBearingDeg: Float? = null
    var sectorValues: FloatArray = FloatArray(12) { Float.NaN }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.parseColor("#CFD8DC")
    }
    private val sectorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#D32F2F")
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#263238")
        textSize = 36f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(cx, cy) - 36f
        if (radius <= 20f) return

        val oval = RectF(cx - radius, cy - radius, cx + radius, cy + radius)

        for (i in 0 until 12) {
            val v = sectorValues.getOrElse(i) { Float.NaN }
            sectorPaint.color = if (v.isNaN()) {
                Color.parseColor("#ECEFF1")
            } else {
                val ratio = ((v + 95f) / 50f).coerceIn(0f, 1f)
                Color.rgb((60 + 195 * ratio).toInt(), (180 * (1f - ratio)).toInt(), 70)
            }
            val startAngle = i * 30f - 90f - currentAzimuthDeg
            canvas.drawArc(oval, startAngle, 28f, true, sectorPaint)
        }

        canvas.drawCircle(cx, cy, radius, ringPaint)
        canvas.drawCircle(cx, cy, radius * 0.45f, Paint().apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        })

        canvas.drawText("정면 ▲", cx, 32f, textPaint)

        targetBearingDeg?.let { target ->
            val relRad = Math.toRadians((target - currentAzimuthDeg - 90f).toDouble())
            val tipX = cx + (radius * 0.82f * cos(relRad)).toFloat()
            val tipY = cy + (radius * 0.82f * sin(relRad)).toFloat()
            canvas.drawLine(cx, cy, tipX, tipY, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#D32F2F")
                strokeWidth = 12f
                strokeCap = Paint.Cap.ROUND
            })
            canvas.drawCircle(tipX, tipY, 18f, arrowPaint)
        }
    }
}

class GraphView(context: Context) : View(context) {
    var samples: List<SignalTracker.Sample> = emptyList()

    private val bgPaint = Paint().apply { color = Color.parseColor("#F5F7FA") }
    private val gridPaint = Paint().apply {
        color = Color.parseColor("#CFD8DC")
        strokeWidth = 2f
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1565C0")
        strokeWidth = 6f
        style = Paint.Style.STROKE
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D32F2F")
        style = Paint.Style.FILL
    }
    private val txtPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#546E7A")
        textSize = 28f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        for (db in listOf(-40, -60, -80, -100)) {
            val y = mapDbToY(db, h)
            canvas.drawLine(70f, y, w - 20f, y, gridPaint)
            canvas.drawText("$db", 10f, y + 10f, txtPaint)
        }

        if (samples.size < 2) return
        val now = System.currentTimeMillis()
        val path = Path()
        var started = false
        for (s in samples) {
            val ageSec = ((now - s.timeMs) / 1000f).coerceIn(0f, 60f)
            val x = 70f + (w - 90f) * (1f - ageSec / 60f)
            val y = mapDbToY(s.rssi, h)
            if (!started) {
                path.moveTo(x, y)
                started = true
            } else {
                path.lineTo(x, y)
            }
            canvas.drawCircle(x, y, 7f, dotPaint)
        }
        canvas.drawPath(path, linePaint)
    }

    private fun mapDbToY(rssi: Int, h: Float): Float {
        val clamped = rssi.coerceIn(-105, -30)
        val ratio = (clamped - (-105f)) / 75f
        return h - 24f - ratio * (h - 48f)
    }
}
