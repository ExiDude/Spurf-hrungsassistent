package de.exi.fieldguide

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.SystemClock

/** Device GNSS only; intentionally never generates simulated fixes. */
class LocationSource(context: Context, private val onFix: (Fix) -> Unit,
                     private val onState: (String) -> Unit) : LocationListener {
    data class Fix(
        val lat: Double, val lon: Double, val accuracyMeters: Float,
        val speedMps: Float, val bearing: Float?, val fixTimeMs: Long,
        val elapsedRealtimeNs: Long, val mocked: Boolean
    ) {
        fun ageSeconds() = (SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNs) / 1e9
        fun acceptable(): Boolean = !mocked && accuracyMeters.isFinite() &&
            accuracyMeters in 0f..12f && ageSeconds() in 0.0..8.0
    }
    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val appContext = context.applicationContext
    private var running = false
    fun start() {
        if (running) return
        if (appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            onState("Präzisen Standort erlauben")
            return
        }
        try {
            if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                onState("GPS in Android aktivieren"); return
            }
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0.5f, this)
            running = true
            onState("Warte auf Satellitensignal")
        } catch (_: SecurityException) { onState("Standortberechtigung fehlt") }
        catch (e: Exception) { onState("GNSS: ${e.localizedMessage ?: "Fehler"}") }
    }
    fun stop() {
        manager.removeUpdates(this)
        running = false
    }
    @Suppress("DEPRECATION")
    override fun onLocationChanged(location: Location) {
        if (location.provider != LocationManager.GPS_PROVIDER) return
        val mock = if (android.os.Build.VERSION.SDK_INT >= 31) location.isMock else location.isFromMockProvider
        onFix(Fix(location.latitude, location.longitude,
            if (location.hasAccuracy()) location.accuracy else Float.POSITIVE_INFINITY,
            if (location.hasSpeed()) location.speed else 0f,
            if (location.hasBearing() && location.speed >= 0.75f) location.bearing else null,
            location.time, location.elapsedRealtimeNanos, mock))
    }
    @Deprecated("Android callback") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    override fun onProviderDisabled(provider: String) { onState("GNSS ausgeschaltet") }
    override fun onProviderEnabled(provider: String) { onState("GNSS eingeschaltet") }
}
