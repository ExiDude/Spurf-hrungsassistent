import de.exi.fieldguide.core.*
import kotlin.math.*

fun approx(actual: Double, expected: Double, error: Double, what: String) {
    check(abs(actual - expected) <= error) { "$what: $actual should be around $expected (+/- $error)" }
}
fun main() {
    val center = LatLon(51.0, 10.0)
    val f = LocalFrame(center)
    val gp = f.geo(Vec2(45.0, 100.0))
    approx(f.meters(gp).x, 45.0, 0.001, "east projection")
    approx(f.meters(gp).y, 100.0, 0.001, "north projection")
    val a = f.geo(Vec2(0.0, 0.0)); val b = f.geo(Vec2(0.0, 100.0))
    val g = GuidanceEngine(a, b, 4.0)
    val same = g.solve(f.geo(Vec2(-4.6, 50.0)), 0.0)
    check(same.lane == 1) { "west is left of north line" }
    approx(same.lateralErrorMeters, 0.6, 0.01, "cross track")
    check(same.steer == Steer.RIGHT)
    check(g.solve(f.geo(Vec2(-4.6, 50.0)), 180.0).steer == Steer.LEFT)
    check(g.solve(f.geo(Vec2(0.0, 30.0)), 0.0).steer == Steer.STRAIGHT)
    // Receiver mounted 1 m left of implement center while travelling north.
    approx(g.solve(f.geo(Vec2(-1.0, 25.0)), 0.0, receiverLeftMeters = 1.0).lateralErrorMeters,
        0.0, 0.01, "antenna offset compensation")
    check(g.solve(f.geo(Vec2(-1.0, 25.0)), null).steer == Steer.UNKNOWN)
    val rect = listOf(f.geo(Vec2(0.0, 0.0)), f.geo(Vec2(100.0, 0.0)),
        f.geo(Vec2(100.0, 100.0)), f.geo(Vec2(0.0, 100.0)))
    approx(FieldGeometry.areaSquareMeters(rect), 10000.0, 0.01, "1ha area")
    approx(FieldGeometry.perimeterMeters(rect), 400.0, 0.01, "perimeter")
    val c = CoverageGrid(rect, 4.0)
    approx(c.sampledFieldMeters2, 10000.0, 200.0, "raster field")
    c.markSegment(f.geo(Vec2(15.0, 10.0)), f.geo(Vec2(15.0, 50.0)))
    val first = c.workedMeters2
    check(first > 150.0 && first < 190.0) { "swath clipped raster: $first" }
    c.markSegment(f.geo(Vec2(15.0, 10.0)), f.geo(Vec2(15.0, 50.0)))
    approx(c.workedMeters2, first, 0.0, "overlaps not double-counted")
    c.markSegment(f.geo(Vec2(30.0, 20.0)), f.geo(Vec2(30.0, 40.0)))
    check(c.workedMeters2 > first)
    check(c.progress in 0.0..1.0)
    val beforeOutside = c.workedMeters2
    c.markSegment(f.geo(Vec2(-50.0, -50.0)), f.geo(Vec2(-40.0, -40.0)))
    approx(c.workedMeters2, beforeOutside, 0.0, "out-of-field work clipped")
    try { GuidanceEngine(a, a, 4.0); error("should reject same A/B") } catch (_: IllegalArgumentException) { }
    println("PASS: WGS84 projection, A/B lanes, direction, antenna offset, area/perimeter, coverage overlap & clipping, invalid A/B")
}
