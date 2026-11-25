package com.example.signaldetector

import kotlin.math.pow

class SignalTracker(val targetId: String) {

    data class Sample(val timeMs: Long, val rssi: Int, val azimuthDeg: Float?)

    private val samples = ArrayList<Sample>()
    private val sectorMax = FloatArray(12) { Float.NaN }
    private val sectorCount = IntArray(12)

    fun addSample(rssi: Int, azimuthDeg: Float?, nowMs: Long = System.currentTimeMillis()) {
        samples.add(Sample(nowMs, rssi, azimuthDeg))
        val cutoff = nowMs - 60_000L
        while (samples.isNotEmpty() && samples.first().timeMs < cutoff) {
            samples.removeAt(0)
        }
        if (azimuthDeg != null) {
            val norm = ((azimuthDeg % 360f) + 360f) % 360f
            val sector = ((norm / 30f).toInt()) % 12
            if (sectorMax[sector].isNaN() || rssi > sectorMax[sector]) {
                sectorMax[sector] = rssi.toFloat()
            }
            sectorCount[sector]++
        }
    }

    fun resetDirection() {
        for (i in 0 until 12) {
            sectorMax[i] = Float.NaN
            sectorCount[i] = 0
        }
    }

    fun recentSamples(): List<Sample> = samples.toList()

    fun latestRssi(): Int? = samples.lastOrNull()?.rssi

    fun lastSampleAgeSec(nowMs: Long = System.currentTimeMillis()): Long? {
        val last = samples.lastOrNull() ?: return null
        return (nowMs - last.timeMs) / 1000L
    }

    fun estimateDistanceMeters(rssi: Int): Double {
        val txPower = -59.0
        val n = 2.4
        return 10.0.pow((txPower - rssi) / (10.0 * n))
    }

    fun distanceLabel(rssi: Int): String = when {
        rssi >= -52 -> "매우 가까움 (약 1m 이내)"
        rssi >= -65 -> "가까움 (약 1~3m)"
        rssi >= -78 -> "중간 거리 (약 3~8m)"
        else -> "멀거나 약함 (8m 이상)"
    }

    fun slopeDbPerSec(): Float {
        if (samples.size < 3) return 0f
        val recent = samples.takeLast(8)
        val t0 = recent.first().timeMs
        var sumX = 0.0
        var sumY = 0.0
        var sumXY = 0.0
        var sumXX = 0.0
        val n = recent.size.toDouble()
        for (s in recent) {
            val x = (s.timeMs - t0) / 1000.0
            val y = s.rssi.toDouble()
            sumX += x
            sumY += y
            sumXY += x * y
            sumXX += x * x
        }
        val denom = n * sumXX - sumX * sumX
        if (denom < 0.001) return 0f
        return ((n * sumXY - sumX * sumY) / denom).toFloat()
    }

    fun trend(nowMs: Long = System.currentTimeMillis()): Trend {
        val age = lastSampleAgeSec(nowMs) ?: return Trend.CALCULATING
        if (age > 12) return Trend.LOST
        if (samples.size < 3) return Trend.CALCULATING
        val slope = slopeDbPerSec()
        return when {
            slope > 0.45f -> Trend.APPROACHING
            slope < -0.45f -> Trend.RECEDING
            else -> Trend.STABLE
        }
    }

    fun direction(): DirectionResult {
        val covered = sectorMax.count { !it.isNaN() }
        if (covered < 3) {
            return DirectionResult(null, "낮음", covered, sectorMax.copyOf())
        }
        var maxVal = Float.NEGATIVE_INFINITY
        var minVal = Float.POSITIVE_INFINITY
        var bestIdx = 0
        for (i in 0 until 12) {
            val v = sectorMax[i]
            if (!v.isNaN()) {
                if (v > maxVal) {
                    maxVal = v
                    bestIdx = i
                }
                if (v < minVal) minVal = v
            }
        }
        val spread = maxVal - minVal
        val conf = when {
            covered >= 8 && spread >= 8f -> "높음"
            covered >= 5 && spread >= 4f -> "보통"
            else -> "낮음"
        }
        return DirectionResult(bestIdx * 30f + 15f, conf, covered, sectorMax.copyOf())
    }
}
