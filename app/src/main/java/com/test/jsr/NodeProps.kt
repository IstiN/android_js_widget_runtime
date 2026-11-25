package com.test.jsr

import org.json.JSONObject

/**
 * Pure node-prop helpers shared by the mapper registry (plan 6.1 A3).
 * No android.* imports: plain JVM unit tests cover them (JaCoCo-visible).
 */
internal object NodeProps {

    /** Text-bearing order of the reference: data, then text, then label. */
    fun textOf(props: JSONObject): String =
        props.optString("data", props.optString("text", props.optString("label", "")))

    /** textTransform of the reference: the renderer transforms before building the Text. */
    fun transformText(props: JSONObject): String {
        val text = textOf(props)
        return when (props.optString("textTransform")) {
            "uppercase" -> text.uppercase()
            "lowercase" -> text.lowercase()
            "capitalize" -> text.replaceFirstChar { it.uppercase() }
            else -> text
        }
    }

    /** First label of a tabBar tabs prop (reference: live-read first tab). */
    fun firstTab(props: JSONObject): String {
        val tabs = props.optJSONArray("tabs") ?: return ""
        return tabs.optString(0)
    }

    /** First present event key. */
    fun firstEvent(events: JSONObject, vararg keys: String): String? =
        keys.map { events.optString(it) }.firstOrNull { it.isNotEmpty() }

    /** Tap events carry the payload prop of the node (default {}). */
    fun payloadOf(node: JsNode): String {
        val payload = node.props.optJSONObject("payload")
        return payload?.toString() ?: "{}"
    }

    /** Logical px to px with the renderer density factor (1.5 on the test device). */
    fun dp(d: Float): Int = (d * 1.5f).toInt()

    /** Numeric prop in px; absent/negative -> 0. */
    fun pxOf(props: JSONObject, key: String): Int {
        val v = props.optDouble(key, -1.0)
        if (v < 0) return 0
        return dp(v.toFloat())
    }

    /** At least one of the two px sizes is present. */
    fun hasSize(w: Int, h: Int): Boolean = w > 0 || h > 0

    /** radius prop in px; absent/negative -> -1 (no radius). */
    fun radiusPx(props: JSONObject): Float {
        val r = props.optDouble("borderRadius", -1.0)
        if (r < 0) return -1f
        return dp(r.toFloat()).toFloat()
    }

    /** decoration.color of container/card nodes. */
    fun decorationColor(props: JSONObject): String =
        props.optJSONObject("decoration")?.optString("color") ?: ""

    /** opacity prop clamped to [0,1]; absent -> 1.0 (no-op). */
    fun clampedAlpha(props: JSONObject): Float {
        val v = props.optDouble("opacity", 1.0)
        return v.coerceIn(0.0, 1.0).toFloat()
    }

    /** slider value mapped onto a 0..100 SeekBar scale. */
    fun sliderProgress(props: JSONObject): Int {
        val min = props.optDouble("min", 0.0)
        val max = props.optDouble("max", 100.0)
        val value = props.optDouble("value", min)
        if (max <= min) return 0
        val pct = ((value - min) / (max - min) * 100.0).coerceIn(0.0, 100.0)
        return pct.toInt()
    }

    /** unit-interval progress prop (linearProgressIndicator) mapped onto 0..100. */
    fun unitProgress(props: JSONObject): Int {
        val v = props.optDouble("value", 0.0).coerceIn(0.0, 1.0)
        return (v * 100.0).toInt()
    }

    /** Index of value in items; unknown values keep position 0. */
    fun indexOfItem(items: List<String>, value: String): Int {
        val i = items.indexOf(value)
        return if (i < 0) 0 else i
    }

    /** radio checked = value == groupValue (both strings; empty groupValue never matches). */
    fun radioChecked(props: JSONObject): Boolean {
        val value = props.optString("value")
        val group = props.optString("groupValue")
        return value.isNotEmpty() && value == group
    }

    /** dropdown items from items|options (array of strings or of {label|text|data}). */
    fun itemsOf(props: JSONObject): List<String> {
        val array = props.optJSONArray("items") ?: props.optJSONArray("options") ?: return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until array.length()) {
            val item = array.opt(i)
            when (item) {
                is String -> out.add(item)
                is JSONObject -> out.add(textOf(item))
            }
        }
        return out
    }

    /** listTile lines: title (or data/label) and subtitle. */
    fun tileTitle(props: JSONObject): String {
        val title = props.optString("title")
        if (title.isNotEmpty()) return title
        return textOf(props)
    }

    fun tileSubtitle(props: JSONObject): String = props.optString("subtitle")
}
