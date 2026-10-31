package de.exi.fieldguide

import android.content.Context
import de.exi.fieldguide.core.LatLon
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Atomic local JSON save with separate backup. Offline, no login, no cloud. */
class FieldStore(ctx: Context) {
    private val directory = ctx.filesDir
    private val db = File(directory, "fieldguide.json")
    private val tmp = File(directory, "fieldguide.json.tmp")
    private val backup = File(directory, "fieldguide.json.bak")
    private var protectBackup = false

    fun load(): FarmData {
        for (file in listOf(db, backup)) if (file.exists()) {
            try {
                if (file == backup) protectBackup = true
                return decode(JSONObject(file.readText(Charsets.UTF_8)))
            } catch (_: Exception) { /* try backup */ }
        }
        // Never silently replace corrupted original data with a new empty database.
        if (db.exists() || backup.exists()) throw IllegalStateException("FieldGuide-Datenbank unlesbar. Dateien zuerst sichern.")
        return FarmData()
    }

    @Synchronized fun save(data: FarmData) {
        val content = encode(data).toString()
        tmp.outputStream().use { output ->
            output.write(content.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        // Preserve the preceding successful file as an emergency recovery copy.
        if (db.exists() && !protectBackup) db.copyTo(backup, overwrite = true)
        if (!tmp.renameTo(db)) {
            tmp.copyTo(db, overwrite = true)
            if (!tmp.delete()) tmp.deleteOnExit()
        }
        protectBackup = false
    }

    private fun p(point: LatLon) = JSONArray().put(point.lat).put(point.lon)
    private fun pp(json: JSONArray) = LatLon(json.getDouble(0), json.getDouble(1))
    private fun encode(data: FarmData) = JSONObject().apply {
        put("schema", 1)
        put("selectedFieldId", data.selectedFieldId ?: JSONObject.NULL)
        put("selectedToolId", data.selectedToolId ?: JSONObject.NULL)
        put("tools", JSONArray().apply { data.tools.forEach { tool ->
            put(JSONObject().apply {
                put("id", tool.id); put("name", tool.name)
                put("width", tool.widthMeters); put("overlap", tool.overlapMeters)
                put("forward", tool.receiverForwardMeters); put("left", tool.receiverLeftMeters)
            })
        } })
        put("fields", JSONArray().apply { data.fields.forEach { field ->
            put(JSONObject().apply {
                put("id", field.id); put("name", field.name)
                put("boundary", JSONArray().apply { field.boundary.forEach { put(p(it)) } })
                put("a", field.a?.let(::p) ?: JSONObject.NULL)
                put("b", field.b?.let(::p) ?: JSONObject.NULL)
                put("duration", field.recordedWorkMs)
                put("distance", field.recordedDistanceMeters)
                put("track", JSONArray().apply { field.track.forEach { track ->
                    put(JSONArray().put(track.point.lat).put(track.point.lon).put(track.timestampMs)
                        .put(track.connectsPrevious).put(track.widthMeters))
                } })
            })
        } })
    }
    private fun decode(json: JSONObject): FarmData {
        require(json.optInt("schema") == 1) { "Unsupported schema" }
        val data = FarmData(mutableListOf(), mutableListOf())
        data.selectedFieldId = json.optString("selectedFieldId").takeUnless { it == "null" || it.isEmpty() }
        data.selectedToolId = json.optString("selectedToolId").takeUnless { it == "null" || it.isEmpty() }
        val tools = json.getJSONArray("tools")
        for (i in 0 until tools.length()) {
            val t = tools.getJSONObject(i)
            data.tools.add(ToolProfile(t.getString("id"), t.getString("name"), t.getDouble("width"),
                t.getDouble("overlap"), t.optDouble("forward", 0.0), t.optDouble("left", 0.0)))
        }
        if (data.tools.isEmpty()) data.tools.add(ToolProfile(name = "Standardgerät"))
        val fields = json.getJSONArray("fields")
        for (i in 0 until fields.length()) {
            val f = fields.getJSONObject(i)
            val field = FarmField(f.getString("id"), f.getString("name"))
            val boundary = f.getJSONArray("boundary")
            for (j in 0 until boundary.length()) field.boundary.add(pp(boundary.getJSONArray(j)))
            if (!f.isNull("a")) field.a = pp(f.getJSONArray("a"))
            if (!f.isNull("b")) field.b = pp(f.getJSONArray("b"))
            field.recordedWorkMs = f.optLong("duration")
            field.recordedDistanceMeters = f.optDouble("distance")
            val track = f.getJSONArray("track")
            for (j in 0 until track.length()) {
                val t = track.getJSONArray(j)
                field.track.add(TrackSample(LatLon(t.getDouble(0), t.getDouble(1)), t.getLong(2),
                    t.getBoolean(3), t.getDouble(4)))
            }
            data.fields.add(field)
        }
        return data
    }
}
