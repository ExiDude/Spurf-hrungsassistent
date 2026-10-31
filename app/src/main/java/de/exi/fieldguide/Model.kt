package de.exi.fieldguide

import de.exi.fieldguide.core.LatLon
import java.util.UUID

/** Plain data classes: persistence is intentionally independent of Android UI. */
data class ToolProfile(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "Arbeitsgerät",
    var widthMeters: Double = 3.0,
    var overlapMeters: Double = 0.1,
    var receiverForwardMeters: Double = 0.0,
    var receiverLeftMeters: Double = 0.0
) {
    val spacing: Double get() = widthMeters - overlapMeters
}

data class TrackSample(
    val point: LatLon,
    val timestampMs: Long,
    /** True only if the previous sample forms one uninterrupted, quality-checked work segment. */
    val connectsPrevious: Boolean,
    val widthMeters: Double
)

data class FarmField(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    val boundary: MutableList<LatLon> = mutableListOf(),
    var a: LatLon? = null,
    var b: LatLon? = null,
    val track: MutableList<TrackSample> = mutableListOf(),
    var recordedWorkMs: Long = 0L,
    var recordedDistanceMeters: Double = 0.0
)

data class FarmData(
    val fields: MutableList<FarmField> = mutableListOf(),
    val tools: MutableList<ToolProfile> = mutableListOf(ToolProfile(name = "Standardgerät")),
    var selectedFieldId: String? = null,
    var selectedToolId: String? = null
) {
    fun selectedField() = fields.firstOrNull { it.id == selectedFieldId }
    fun selectedTool() = tools.firstOrNull { it.id == selectedToolId } ?: tools.first()
}
