package de.exi.fieldguide

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.WindowInsets
import android.widget.*
import android.text.InputType
import de.exi.fieldguide.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.*

/** First field-usable MVP: manual visual assistance only. Never controls steering. */
class MainActivity : Activity() {
    private lateinit var repository: FieldStore
    private lateinit var data: FarmData
    private lateinit var gps: LocationSource
    private lateinit var surface: GuidanceSurface
    private lateinit var topLine: TextView
    private lateinit var gpsLine: TextView
    private lateinit var detailLine: TextView
    private lateinit var startButton: Button
    private lateinit var boundaryButton: Button
    private lateinit var viewButton: Button

    private var lastFix: LocationSource.Fix? = null
    private var providerMessage = "Suche GNSS"
    private var collectingBoundary = false
    private var working = false
    private var workStartMs = 0L
    private var lastTracked: TrackSample? = null
    private var coverage: CoverageGrid? = null
    private var planMode = false
    private var exportType = "geojson"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() { refreshUi(); mainHandler.postDelayed(this, 1000L) }
    }
    private val bg = Color.rgb(14, 27, 26)
    private val foreground = Color.rgb(236, 243, 237)
    private val accent = Color.rgb(94, 214, 96)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = FieldStore(this)
        data = repository.load()
        gps = LocationSource(this, ::onFix) { providerMessage = it; refreshUi() }
        rebuildCoverage()
        buildUi()
        mainHandler.post(ticker)
        requestGps()
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        buildUi()
        refreshUi()
    }
    override fun onStart() {
        super.onStart()
        if (::gps.isInitialized && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) gps.start()
    }
    override fun onResume() {
        super.onResume()
        if (::gps.isInitialized && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) gps.start()
    }
    override fun onStop() {
        if (working) pauseWork("Aufzeichnung pausiert: App im Hintergrund")
        else if (collectingBoundary) persist()
        gps.stop()
        super.onStop()
    }
    override fun onDestroy() {
        mainHandler.removeCallbacks(ticker)
        gps.stop()
        super.onDestroy()
    }
    private fun dp(n: Int) = (resources.displayMetrics.density * n + 0.5f).toInt()
    private fun fmt(n: Double, d: Int = 1) = "%.${d}f".format(Locale.GERMANY, n)
    private fun message(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    private fun persist() {
        try { repository.save(data) }
        catch (e: Exception) { message("Speichern fehlgeschlagen: ${e.localizedMessage}") }
    }
    private fun acceptable(): Boolean = lastFix?.acceptable() == true
    private fun currentPoint() = lastFix?.let { LatLon(it.lat, it.lon) }
    private fun tool() = data.selectedTool()
    private fun field() = data.selectedField()

    private fun requestGps() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION), 42)
        } else gps.start()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 42) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) gps.start()
            else message("Für die Spurführung muss präziser Standort erlaubt sein")
        }
    }
    private fun implementPosition(f: LocationSource.Fix): LatLon? {
        val t = tool()
        if (t.receiverForwardMeters == 0.0 && t.receiverLeftMeters == 0.0) return LatLon(f.lat, f.lon)
        val bearing = f.bearing?.toDouble() ?: return null
        val frame = LocalFrame(LatLon(f.lat, f.lon))
        val r = Math.toRadians(bearing)
        val forward = Vec2(sin(r), cos(r))
        val left = Vec2(-forward.y, forward.x)
        return frame.geo(Vec2(0.0, 0.0) - forward * t.receiverForwardMeters - left * t.receiverLeftMeters)
    }
    private fun onFix(f: LocationSource.Fix) {
        lastFix = f
        val field = field()
        if (f.acceptable() && field != null) {
            val p = LatLon(f.lat, f.lon)
            if (collectingBoundary) {
                val last = field.boundary.lastOrNull()
                if (last == null || (LocalFrame(last).meters(p)).length() >= 3.0) {
                    field.boundary.add(p)
                    if (field.boundary.size % 4 == 0) persist()
                }
            }
            if (working && !collectingBoundary && f.speedMps >= 0.35f) {
                val actual = implementPosition(f)
                if (actual == null) {
                    lastTracked = null
                    providerMessage = "Fahrtrichtung für Geräteversatz unbestimmt"
                } else appendTrack(field, actual, f.fixTimeMs)
            } else lastTracked = null
        } else lastTracked = null
        refreshUi()
    }
    private fun appendTrack(field: FarmField, p: LatLon, timeMs: Long) {
        val prev = lastTracked
        val dist = if (prev != null) LocalFrame(prev.point).meters(p).length() else 0.0
        if (prev != null && dist < 1.5 && timeMs - prev.timestampMs < 4500) return
        val contiguous = prev != null && timeMs > prev.timestampMs && timeMs - prev.timestampMs <= 12000 && dist <= 25.0
        val sample = TrackSample(p, timeMs, contiguous, tool().widthMeters)
        field.track.add(sample)
        if (contiguous && prev != null) {
            field.recordedDistanceMeters += dist
            coverage?.markSegment(prev.point, p, tool().widthMeters)
        }
        lastTracked = sample
        if (field.track.size % 5 == 0) persist()
    }
    private fun rebuildCoverage() {
        coverage = field()?.let { f ->
            if (f.boundary.size < 3) null else {
                try {
                    CoverageGrid(f.boundary, tool().widthMeters).also { grid ->
                        for (i in 1 until f.track.size) {
                            val sample = f.track[i]
                            if (sample.connectsPrevious)
                                grid.markSegment(f.track[i-1].point, sample.point, sample.widthMeters)
                        }
                    }
                } catch (_: Exception) { null }
            }
        }
    }
    private fun guidanceEngine(): GuidanceEngine? {
        val f = field() ?: return null
        val a = f.a ?: return null; val b = f.b ?: return null
        return try { GuidanceEngine(a, b, tool().spacing) } catch (_: Exception) { null }
    }
    @Suppress("DEPRECATION")
    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(6), dp(5), dp(6), dp(5))
            // Prevent controls from being hidden behind Android 15/16 edge-to-edge system bars.
            setOnApplyWindowInsetsListener { view, insets ->
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars())
                    view.setPadding(dp(6) + bars.left, dp(5) + bars.top, dp(6) + bars.right, dp(5) + bars.bottom)
                } else {
                    view.setPadding(dp(6) + insets.systemWindowInsetLeft, dp(5) + insets.systemWindowInsetTop,
                        dp(6) + insets.systemWindowInsetRight, dp(5) + insets.systemWindowInsetBottom)
                }
                insets
            }
        }
        topLine = TextView(this).apply {
            textSize = 15f; setTextColor(this@MainActivity.foreground); setPadding(dp(6), dp(4), 0, dp(2))
        }
        gpsLine = TextView(this).apply {
            textSize = 12f; setTextColor(accent); setPadding(dp(6), dp(2), 0, dp(5))
        }
        root.addView(topLine)
        root.addView(gpsLine)
        surface = GuidanceSurface(this).apply {
            onTapMap = { point ->
                if (collectingBoundary) {
                    field()?.boundary?.add(point)
                    persist(); rebuildCoverage(); refreshUi()
                }
            }
        }
        root.addView(surface, LinearLayout.LayoutParams(-1, 0, 1f))
        detailLine = TextView(this).apply {
            textSize = 13f; setTextColor(this@MainActivity.foreground); gravity = Gravity.CENTER
            setPadding(dp(6), dp(7), dp(6), dp(7))
        }
        root.addView(detailLine)
        val firstRow = linearScrollRow(root)
        val secondRow = linearScrollRow(root)
        button(firstRow, "Feld", ::showFields)
        boundaryButton = button(firstRow, "Grenze", ::boundaryAction)
        button(firstRow, "A setzen", { setAB(true) })
        button(firstRow, "B setzen", { setAB(false) })
        button(secondRow, "Gerät", ::showTools)
        startButton = button(secondRow, "Start", ::toggleWork)
        viewButton = button(secondRow, "Karte", { planMode = !planMode; refreshUi() })
        button(secondRow, "Export", ::showExport)
        button(secondRow, "Info", ::showInfo)
        setContentView(root)
        refreshUi()
    }
    private fun linearScrollRow(root: LinearLayout): LinearLayout {
        val scroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        scroller.addView(line)
        root.addView(scroller, LinearLayout.LayoutParams(-1, dp(58)))
        return line
    }
    private fun button(row: LinearLayout, label: String, action: () -> Unit): Button {
        val b = Button(this).apply {
            text = label; textSize = 12f
            setAllCaps(false)
            minWidth = dp(85)
            setOnClickListener { action() }
            setTextColor(Color.WHITE)
            setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(32, 70, 53)))
        }
        row.addView(b, LinearLayout.LayoutParams(-2, dp(54)).apply { setMargins(dp(2), dp(1), dp(2), dp(1)) })
        return b
    }
    private fun refreshUi() {
        if (!::surface.isInitialized) return
        val f = field(); val fix = lastFix; val ok = acceptable()
        val engine = guidanceEngine()
        val computed = if (engine != null && fix != null && ok)
            engine.solve(LatLon(fix.lat, fix.lon), fix.bearing?.toDouble(),
                tool().receiverForwardMeters, tool().receiverLeftMeters) else null
        // A numerical lateral error alone is not evidence that GNSS is precise enough
        // to advise a steering correction. Keep this stricter than track recording.
        val steeringPrecise = fix != null && ok && engine != null &&
            fix.accuracyMeters <= min(1.0, engine.spacingMeters / 3.0)
        val solution = if (computed != null && !steeringPrecise)
            computed.copy(steer = Steer.UNKNOWN) else computed
        val speed = if (ok && fix != null) fmt(fix.speedMps * 3.6) else "--"
        val deviation = if (solution == null) "--" else fmt(abs(solution.lateralErrorMeters), 2) + " m"
        val direction = when (solution?.steer) {
            Steer.LEFT -> "← LINKS"; Steer.RIGHT -> "RECHTS →"
            Steer.STRAIGHT -> "SPUR OK"; else -> "RICHTUNG --"
        }
        topLine.text = "${f?.name ?: "Kein Feld"}   |   ${speed} km/h   |   ${deviation}   ${direction}"
        gpsLine.text = when {
            fix == null -> "GNSS: $providerMessage"
            fix.mocked -> "GNSS: Simulierter Standort – Aufzeichnung gesperrt"
            !ok -> "GNSS UNGENAU / VERALTET · ±${fmt(fix.accuracyMeters.toDouble())} m · Aufzeichnung gesperrt"
            !steeringPrecise && engine != null -> "GNSS ±${fmt(fix.accuracyMeters.toDouble())} m · Lenkkorrektur unzuverlässig"
            else -> "GNSS ±${fmt(fix.accuracyMeters.toDouble())} m · ${fmt(fix.lat, 6)}°, ${fmt(fix.lon, 6)}°"
        }
        gpsLine.setTextColor(if (ok && (engine == null || steeringPrecise)) accent else Color.rgb(255, 164, 97))
        val area = if (f != null) FieldGeometry.areaSquareMeters(f.boundary) / 10000.0 else 0.0
        val progress = (coverage?.progress ?: 0.0) * 100.0
        val trackedTime = if (f == null) 0L else f.recordedWorkMs + if (working) SystemClock.elapsedRealtime() - workStartMs else 0L
        detailLine.text = "${fmt(area, 2)} ha   ·   ${fmt(progress, 1)} % bearbeitet (ca.)   ·   ${fmt(tool().widthMeters, 2)} m   ·   ${trackedTime / 60000} min"
        boundaryButton.text = if (collectingBoundary) "Grenze fertig" else "Grenze"
        startButton.text = if (working) "Pause" else "Start"
        viewButton.text = if (planMode) "3D-Spuren" else "Karte"
        surface.field = f
        surface.current = currentPoint()
        surface.solution = solution
        surface.engine = engine
        surface.coverage = coverage
        surface.planMode = planMode
        surface.recordingBoundary = collectingBoundary
        surface.bearing = fix?.bearing?.toDouble()
        surface.toolWidth = tool().widthMeters
        surface.goodFix = ok && (engine == null || steeringPrecise)
        surface.invalidate()
        if (working) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private fun withGoodPoint(onPoint: (LatLon) -> Unit) {
        val fix = lastFix
        if (fix == null || !fix.acceptable()) { message("GNSS-Fix nicht ausreichend. Unter freiem Himmel warten."); return }
        val pos = implementPosition(fix)
        if (pos == null) { message("GNSS-Fahrtrichtung für eingestellten Geräteversatz erforderlich"); return }
        onPoint(pos)
    }
    private fun createField() {
        val input = EditText(this).apply { setSingleLine(true); setText("Feld ${data.fields.size + 1}"); selectAll() }
        AlertDialog.Builder(this).setTitle("Neues Feld").setView(input)
            .setPositiveButton("Erstellen") { _, _ ->
                val name = input.text.toString().trim().take(70)
                if (name.isEmpty()) return@setPositiveButton
                if (working) pauseWork("Arbeit pausiert")
                val f = FarmField(name = name)
                data.fields.add(f); data.selectedFieldId = f.id
                collectingBoundary = true; planMode = true; lastTracked = null
                coverage = null; persist(); refreshUi()
                message("Feldgrenze abfahren oder auf Karte Punkte setzen. Danach 'Grenze fertig'.")
            }.setNegativeButton("Abbrechen", null).show()
    }
    private fun showFields() {
        val options = listOf("+ Neues Feld", *data.fields.map { it.name }.toTypedArray(), "Aktuelles Feld löschen")
        AlertDialog.Builder(this).setTitle("Felder").setItems(options.toTypedArray()) { _, index ->
            when {
                index == 0 -> createField()
                index == options.lastIndex -> confirmDelete()
                else -> {
                    if (working) pauseWork("Arbeit pausiert")
                    data.selectedFieldId = data.fields[index - 1].id
                    collectingBoundary = false; lastTracked = null
                    rebuildCoverage(); persist(); refreshUi()
                }
            }
        }.show()
    }
    private fun confirmDelete() {
        val f = field() ?: return
        AlertDialog.Builder(this).setTitle("Feld wirklich löschen?")
            .setMessage("${f.name}: Feldgrenze, A/B-Linie und alle aufgezeichneten Fahrwege werden entfernt.")
            .setPositiveButton("Löschen") { _, _ ->
                if (working) pauseWork("Arbeit pausiert")
                data.fields.removeAll { it.id == f.id }; data.selectedFieldId = data.fields.firstOrNull()?.id
                collectingBoundary = false; rebuildCoverage(); persist(); refreshUi()
            }.setNegativeButton("Abbrechen", null).show()
    }
    private fun boundaryAction() {
        val f = field() ?: run { createField(); return }
        if (collectingBoundary) {
            if (f.boundary.size < 3 || FieldGeometry.areaSquareMeters(f.boundary) < 15.0) {
                message("Mindestens 3 Punkte und eine gültige Feldfläche nötig"); return
            }
            collectingBoundary = false
            rebuildCoverage(); persist(); refreshUi()
            message("Feld gespeichert: ${fmt(FieldGeometry.areaSquareMeters(f.boundary) / 10000, 3)} ha")
        } else {
            AlertDialog.Builder(this).setTitle("Feldgrenze bearbeiten")
                .setMessage("Feld neu vermessen? Alte Grenze, A/B-Linie und Fahrwege dieses Felds werden zurückgesetzt.")
                .setPositiveButton("Neu erfassen") { _, _ ->
                    if (working) pauseWork("Arbeit pausiert")
                    f.boundary.clear(); f.a = null; f.b = null; f.track.clear()
                    f.recordedWorkMs = 0L; f.recordedDistanceMeters = 0.0
                    collectingBoundary = true; planMode = true
                    coverage = null; persist(); refreshUi()
                }.setNegativeButton("Abbrechen", null).show()
        }
    }
    private fun setAB(setA: Boolean) {
        val f = field() ?: run { message("Erst ein Feld anlegen"); return }
        if (collectingBoundary) { message("Erst Feldgrenze abschließen"); return }
        withGoodPoint { p ->
            if (setA) {
                f.a = p; f.b = null
                message("A gesetzt – mindestens 3 m geradeaus fahren, dann B setzen")
            } else {
                val a = f.a
                if (a == null) { message("Zuerst A setzen"); return@withGoodPoint }
                if (LocalFrame(a).meters(p).length() < 3.0) { message("B muss mindestens 3 m von A entfernt sein"); return@withGoodPoint }
                f.b = p
                message("A-B-Führung aktiviert: ${fmt(tool().spacing, 2)} m Spurabstand")
            }
            persist(); refreshUi()
        }
    }
    private fun toggleWork() {
        if (working) { pauseWork("Arbeit pausiert"); return }
        val f = field()
        if (f == null || f.boundary.size < 3 || collectingBoundary) { message("Erst Feldgrenze erfassen und abschließen"); return }
        if (guidanceEngine() == null) { message("Erst A- und B-Punkt setzen"); return }
        if (!acceptable()) { message("GNSS nicht genau genug für die Aufzeichnung"); return }
        if (tool().receiverForwardMeters != 0.0 || tool().receiverLeftMeters != 0.0) {
            if (lastFix?.bearing == null) { message("Geräteversatz benötigt verlässliche Bewegungsrichtung"); return }
        }
        working = true; lastTracked = null; workStartMs = SystemClock.elapsedRealtime()
        refreshUi()
    }
    private fun pauseWork(text: String) {
        if (!working) return
        field()?.recordedWorkMs = (field()?.recordedWorkMs ?: 0L) +
            (SystemClock.elapsedRealtime() - workStartMs).coerceAtLeast(0L)
        working = false; lastTracked = null; persist(); refreshUi(); message(text)
    }
    private fun showTools() {
        val choices = listOf("+ Neues Gerät", *data.tools.map { "${it.name} – ${fmt(it.widthMeters, 2)} m" }.toTypedArray())
        AlertDialog.Builder(this).setTitle("Arbeitsgeräte").setItems(choices.toTypedArray()) { _, i ->
            if (i == 0) editTool(null) else editTool(data.tools[i - 1])
        }.show()
    }
    private fun editTool(selected: ToolProfile?) {
        val t = selected ?: ToolProfile(name = "Neues Gerät")
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        fun entry(label: String, value: String, decimal: Boolean = true): EditText {
            layout.addView(TextView(this).apply { text = label })
            return EditText(this).apply {
                setSingleLine(true); setText(value)
                inputType = if (decimal) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or
                    InputType.TYPE_NUMBER_FLAG_SIGNED else InputType.TYPE_CLASS_TEXT
                layout.addView(this)
            }
        }
        val name = entry("Name", t.name, false)
        val width = entry("Arbeitsbreite (m)", t.widthMeters.toString())
        val overlap = entry("Überlappung (m)", t.overlapMeters.toString())
        val forward = entry("GNSS-Antenne vor Gerätemitte (+m)", t.receiverForwardMeters.toString())
        val left = entry("GNSS-Antenne links der Gerätemitte (+m)", t.receiverLeftMeters.toString())
        val scroller = ScrollView(this).apply { addView(layout) }
        AlertDialog.Builder(this).setTitle("Arbeitsgerät einstellen").setView(scroller)
            .setPositiveButton("Speichern") { _, _ ->
                val w = width.text.toString().replace(',', '.').toDoubleOrNull()
                val o = overlap.text.toString().replace(',', '.').toDoubleOrNull()
                val fw = forward.text.toString().replace(',', '.').toDoubleOrNull()
                val lf = left.text.toString().replace(',', '.').toDoubleOrNull()
                if (w == null || o == null || fw == null || lf == null || w !in 0.3..100.0 ||
                    o !in 0.0..(w - 0.2) || abs(fw) > 30 || abs(lf) > 30) {
                    message("Ungültige Gerätewerte. Arbeitsbreite > Überlappung nötig.")
                    return@setPositiveButton
                }
                if (working) pauseWork("Gerätewechsel: Arbeit pausiert")
                t.name = name.text.toString().trim().take(60).ifEmpty { "Gerät" }
                t.widthMeters = w; t.overlapMeters = o
                t.receiverForwardMeters = fw; t.receiverLeftMeters = lf
                if (selected == null) data.tools.add(t)
                data.selectedToolId = t.id
                rebuildCoverage(); persist(); refreshUi()
            }.setNegativeButton("Abbrechen", null).show()
    }
    private fun showExport() {
        if (field() == null) { message("Kein Feld vorhanden"); return }
        val formats = arrayOf("GeoJSON – Feld, A/B und Fahrweg", "GPX – Fahrweg", "CSV – Fahrweg")
        AlertDialog.Builder(this).setTitle("Exportieren").setItems(formats) { _, which ->
            exportType = when (which) { 0 -> "geojson"; 1 -> "gpx"; else -> "csv" }
            val mime = when (exportType) { "geojson" -> "application/geo+json"; "gpx" -> "application/gpx+xml"; else -> "text/csv" }
            val fileName = "FieldGuide_${field()?.name?.replace(Regex("[^A-Za-z0-9_-]"), "_")}.${exportType}"
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = mime
                putExtra(Intent.EXTRA_TITLE, fileName)
            }
            startActivityForResult(intent, 113)
        }.show()
    }
    @Deprecated("Android document save callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, result: Intent?) {
        super.onActivityResult(requestCode, resultCode, result)
        if (requestCode == 113 && resultCode == RESULT_OK) {
            val uri = result?.data ?: return
            try {
                val content = exportContent(field() ?: return)
                contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                    ?: error("Konnte Datei nicht öffnen")
                message("Export gespeichert")
            } catch (e: Exception) { message("Export fehlgeschlagen: ${e.localizedMessage}") }
        }
    }
    private fun exportContent(f: FarmField): String {
        fun coords(p: LatLon) = JSONArray().put(p.lon).put(p.lat)
        when (exportType) {
            "csv" -> return buildString {
                appendLine("timestamp_iso;latitude;longitude;segment_connected;working_width_m")
                f.track.forEach {
                    appendLine("${java.time.Instant.ofEpochMilli(it.timestampMs)};${it.point.lat};${it.point.lon};${it.connectsPrevious};${it.widthMeters}")
                }
            }
            "gpx" -> return buildString {
                append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"FieldGuide GPS\" xmlns=\"http://www.topografix.com/GPX/1/1\"><trk><name>Fahrweg</name><trkseg>")
                f.track.forEachIndexed { i, p ->
                    if (i > 0 && !p.connectsPrevious) append("</trkseg><trkseg>")
                    append("<trkpt lat=\"${p.point.lat}\" lon=\"${p.point.lon}\"><time>${java.time.Instant.ofEpochMilli(p.timestampMs)}</time></trkpt>")
                }
                append("</trkseg></trk></gpx>")
            }
            else -> {
                val features = JSONArray()
                fun feature(name: String, geometry: JSONObject) {
                    features.put(JSONObject().put("type", "Feature")
                        .put("properties", JSONObject().put("name", name)).put("geometry", geometry))
                }
                if (f.boundary.size >= 3) {
                    val ring = JSONArray()
                    f.boundary.forEach { ring.put(coords(it)) }; ring.put(coords(f.boundary[0]))
                    feature("Feldgrenze", JSONObject().put("type", "Polygon").put("coordinates", JSONArray().put(ring)))
                }
                if (f.a != null && f.b != null) {
                    feature("A-B-Linie", JSONObject().put("type", "LineString")
                        .put("coordinates", JSONArray().put(coords(f.a!!)).put(coords(f.b!!))))
                }
                val lines = JSONArray(); var segment = JSONArray()
                f.track.forEachIndexed { i, s ->
                    if (i > 0 && !s.connectsPrevious) {
                        if (segment.length() >= 2) lines.put(segment)
                        segment = JSONArray()
                    }
                    segment.put(coords(s.point))
                }
                if (segment.length() >= 2) lines.put(segment)
                if (lines.length() > 0) feature("Fahrspuren", JSONObject().put("type", "MultiLineString").put("coordinates", lines))
                return JSONObject().put("type", "FeatureCollection").put("features", features).toString(2)
            }
        }
    }
    private fun showInfo() {
        AlertDialog.Builder(this).setTitle("FieldGuide GPS · MVP 0.1")
            .setMessage("Visuelle Spurführung – KEINE Autolenkung.\n\n" +
                "Die angezeigte ±-Zahl ist die vom Android-GNSS gemeldete Positionsgenauigkeit, kein Präzisionsnachweis. " +
                "Mit Smartphone-GPS sind Zentimeter-Spuren nicht zuverlässig möglich.\n\n" +
                "Feld anlegen → Grenze abfahren oder Karte antippen → Grenze fertig → A/B setzen → Start. " +
                "Für genaue Feldarbeit einen geeigneten RTK-Empfänger verwenden (in dieser Version noch NICHT angebunden).\n\n" +
                "GNSS wird nur im Vordergrund erfasst; Daten bleiben lokal. Bei schlechter Position werden Arbeitspunkte verworfen. " +
                "Aufmerksamkeit und Lenkverantwortung bleiben beim Fahrer.")
            .setPositiveButton("OK", null).show()
    }
}
