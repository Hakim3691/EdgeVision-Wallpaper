package com.edgevision.wallpaper.inference

import org.json.JSONArray

object DetectionParser {

    fun parse(json: String): InferenceResult {
        val root = org.json.JSONObject(json)
        val error = root.optString("error", "")
        if (error.isNotBlank()) {
            throw IllegalStateException(error)
        }

        val detectionsJson = root.optJSONArray("detections") ?: JSONArray()
        val detections = ArrayList<Detection>(detectionsJson.length())
        for (index in 0 until detectionsJson.length()) {
            val item = detectionsJson.optJSONObject(index) ?: continue
            val kind = HazardKind.fromWire(item.optString("hazard")) ?: continue
            val boxValues = item.optJSONArray("box") ?: continue
            if (boxValues.length() < 4) continue

            detections += Detection(
                label = item.optString("label", "obstacle"),
                hazard = kind,
                confidence = item.optDouble("score", 0.0).toFloat(),
                box = Box.fromNormalized(
                    left = boxValues.optDouble(0, 0.0).toFloat(),
                    top = boxValues.optDouble(1, 0.0).toFloat(),
                    right = boxValues.optDouble(2, 0.0).toFloat(),
                    bottom = boxValues.optDouble(3, 0.0).toFloat(),
                ),
            )
        }

        return InferenceResult(
            detections = detections,
            backend = root.optString("backend", "unknown"),
            latencyMs = root.optDouble("latency_ms", 0.0).toLong(),
            inputSize = root.optInt("input_size", 0),
        )
    }
}
