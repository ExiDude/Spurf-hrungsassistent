package de.exi.fieldguide.core

import kotlin.math.*

/** WGS84 points in degrees. Local meters are valid for ordinary farm-scale fields. */
data class LatLon(val lat: Double, val lon: Double) {
    init { require(lat in -90.0..90.0 && lon in -180.0..180.0 && lat.isFinite() && lon.isFinite()) }
}
data class Vec2(val x: Double, val y: Double) {
    operator fun plus(other: Vec2) = Vec2(x + other.x, y + other.y)
    operator fun minus(other: Vec2) = Vec2(x - other.x, y - other.y)
    operator fun times(s: Double) = Vec2(x * s, y * s)
    fun dot(v: Vec2) = x * v.x + y * v.y
    fun length() = hypot(x, y)
}

/** Local east/north tangent-plane approximation for small fields (typically a few km).
 *  Not a substitute for a national geodetic projection on large regions. */
class LocalFrame(val origin: LatLon) {
    private val rad = Math.PI / 180.0
    private val earthRadius = 6378137.0
    private val e2 = 6.69437999014e-3
    private val sinLat = sin(origin.lat * rad)
    private val factor = 1 - e2 * sinLat * sinLat
    private val eastRadius = earthRadius / sqrt(factor) * cos(origin.lat * rad).coerceAtLeast(1e-6)
    private val northRadius = earthRadius * (1 - e2) / factor.pow(1.5)
    fun meters(p: LatLon): Vec2 {
        val dLon = ((p.lon - origin.lon + 540.0) % 360.0) - 180.0
        return Vec2(eastRadius * dLon * rad, northRadius * (p.lat - origin.lat) * rad)
    }
    fun geo(v: Vec2) = LatLon(origin.lat + v.y / northRadius / rad,
        ((origin.lon + v.x / (eastRadius * rad) + 540.0) % 360.0) - 180.0)
}

object FieldGeometry {
    fun areaSquareMeters(poly: List<LatLon>): Double {
        if (poly.size < 3) return 0.0
        val frame = LocalFrame(poly[0])
        val xy = poly.map(frame::meters)
        var cross = 0.0
        for (i in xy.indices) {
            val a = xy[i]; val b = xy[(i + 1) % xy.size]
            cross += a.x * b.y - b.x * a.y
        }
        return abs(cross) * 0.5
    }
    fun perimeterMeters(poly: List<LatLon>): Double {
        if (poly.size < 2) return 0.0
        val frame = LocalFrame(poly[0])
        val xy = poly.map(frame::meters)
        return xy.indices.sumOf { (xy[(it + 1) % xy.size] - xy[it]).length() }
    }
    fun inside(p: Vec2, polygon: List<Vec2>): Boolean {
        if (polygon.size < 3) return false
        var inside = false
        var j = polygon.lastIndex
        for (i in polygon.indices) {
            val a = polygon[i]; val b = polygon[j]
            if ((a.y > p.y) != (b.y > p.y) && p.x <
                (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) inside = !inside
            j = i
        }
        return inside
    }
}

data class Guidance(val lane: Int, val lateralErrorMeters: Double, val steer: Steer,
                    val spacingMeters: Double, val alongMeters: Double)
enum class Steer { LEFT, RIGHT, STRAIGHT, UNKNOWN }

/** Infinite parallel A/B lines. Lane zero is the original A -> B reference. */
class GuidanceEngine(val a: LatLon, val b: LatLon, val spacingMeters: Double) {
    init { require(spacingMeters in 0.2..100.0) }
    val frame = LocalFrame(a)
    val aXY = frame.meters(a)
    val bXY = frame.meters(b)
    val dir: Vec2
    val left: Vec2
    init {
        val delta = bXY - aXY
        require(delta.length() >= 3.0) { "A und B müssen mindestens 3 m auseinanderliegen" }
        dir = delta * (1.0 / delta.length())
        left = Vec2(-dir.y, dir.x)
    }
    fun laneLine(index: Int): Pair<Vec2, Vec2> {
        val offset = left * (index * spacingMeters)
        return Pair(aXY + offset, bXY + offset)
    }
    /** receiverOffset: +forward/+left in vehicle frame, corrected to the implement center. */
    fun solve(point: LatLon, travelBearingDeg: Double?,
              receiverForwardMeters: Double = 0.0, receiverLeftMeters: Double = 0.0): Guidance {
        val raw = frame.meters(point)
        val bearing = travelBearingDeg?.takeIf { it.isFinite() }
        val adjusted = if (bearing != null) {
            val rad = Math.toRadians(bearing)
            val forward = Vec2(sin(rad), cos(rad))
            val leftSide = Vec2(-forward.y, forward.x)
            raw - forward * receiverForwardMeters - leftSide * receiverLeftMeters
        } else raw
        val rel = adjusted - aXY
        val across = rel.dot(left)
        val lane = (across / spacingMeters).roundToInt()
        val signedError = across - lane * spacingMeters
        val forward = if (bearing == null) null else {
            val rad = Math.toRadians(bearing)
            Vec2(sin(rad), cos(rad)).dot(dir) >= 0.0
        }
        val steer = when {
            abs(signedError) < 0.08 -> Steer.STRAIGHT
            forward == null -> Steer.UNKNOWN
            signedError > 0.0 -> if (forward) Steer.RIGHT else Steer.LEFT
            else -> if (forward) Steer.LEFT else Steer.RIGHT
        }
        return Guidance(lane, signedError, steer, spacingMeters, rel.dot(dir))
    }
}
