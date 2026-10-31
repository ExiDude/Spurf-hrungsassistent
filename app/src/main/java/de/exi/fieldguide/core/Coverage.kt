package de.exi.fieldguide.core

import java.util.BitSet
import kotlin.math.*

/** Area estimate: grid-cell union of worked swaths, clipped to field polygon.
 *  Results are estimates with effective resolution. Never present as a surveyed area. */
class CoverageGrid(boundary: List<LatLon>, private val toolWidth: Double, maxCells: Int = 250_000) {
    init { require(boundary.size >= 3 && toolWidth > 0.0 && maxCells >= 100) }
    val frame = LocalFrame(boundary[0])
    private val poly = boundary.map(frame::meters)
    private val minX = poly.minOf { it.x }
    private val minY = poly.minOf { it.y }
    private val maxX = poly.maxOf { it.x }
    private val maxY = poly.maxOf { it.y }
    private val width = (maxX - minX).coerceAtLeast(0.1)
    private val height = (maxY - minY).coerceAtLeast(0.1)
    val cellMeters = max(0.5, sqrt(width * height / maxCells.toDouble()))
    private val nx = max(1, ceil(width / cellMeters).toInt())
    private val ny = max(1, ceil(height / cellMeters).toInt())
    private val fieldBits = BitSet(nx * ny)
    private val worked = BitSet(nx * ny)
    init {
        for (y in 0 until ny) for (x in 0 until nx) {
            if (FieldGeometry.inside(center(x, y), poly)) fieldBits.set(y * nx + x)
        }
    }
    val sampledFieldMeters2: Double get() = fieldBits.cardinality().toDouble() * cellMeters * cellMeters
    val workedMeters2: Double get() = worked.cardinality().toDouble() * cellMeters * cellMeters
    val progress: Double get() = if (fieldBits.isEmpty) 0.0 else worked.cardinality().toDouble() / fieldBits.cardinality()

    private fun center(x: Int, y: Int) = Vec2(minX + (x + 0.5) * cellMeters, minY + (y + 0.5) * cellMeters)
    fun markSegment(from: LatLon, to: LatLon, widthMeters: Double = toolWidth) {
        require(widthMeters > 0.0)
        val a = frame.meters(from); val b = frame.meters(to)
        val d = b - a
        // Ignore implausible teleports. Caller also applies time and GNSS quality filters.
        if (d.length() > 80.0) return
        val radius = widthMeters / 2.0
        val lowX = (floor((min(a.x, b.x) - radius - minX) / cellMeters).toInt()).coerceIn(0, nx - 1)
        val hiX = (ceil((max(a.x, b.x) + radius - minX) / cellMeters).toInt()).coerceIn(0, nx - 1)
        val lowY = (floor((min(a.y, b.y) - radius - minY) / cellMeters).toInt()).coerceIn(0, ny - 1)
        val hiY = (ceil((max(a.y, b.y) + radius - minY) / cellMeters).toInt()).coerceIn(0, ny - 1)
        val length2 = d.dot(d)
        for (y in lowY..hiY) for (x in lowX..hiX) {
            val idx = y * nx + x
            if (!fieldBits[idx]) continue
            val p = center(x, y)
            val t = if (length2 < 1e-6) 0.0 else ((p - a).dot(d) / length2).coerceIn(0.0, 1.0)
            if ((p - (a + d * t)).length() <= radius) worked.set(idx)
        }
    }
    fun coveredCenters(limit: Int = 14000): List<Vec2> {
        val result = ArrayList<Vec2>()
        val stride = max(1, worked.cardinality() / limit)
        var k = 0
        var i = worked.nextSetBit(0)
        while (i >= 0) {
            if (k++ % stride == 0) result.add(center(i % nx, i / nx))
            i = worked.nextSetBit(i + 1)
        }
        return result
    }
}
