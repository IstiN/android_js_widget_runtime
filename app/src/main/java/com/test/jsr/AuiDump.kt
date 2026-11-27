package com.test.jsr

import org.json.JSONArray
import org.json.JSONObject
import android.view.View
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView

/**
 * AUI dump (план 1.3, issue #1): type из записи узла, props из применённой
 * записи, текстовые props (data, text, hint, value, label) читаются живьём
 * из View, colors/размеры уже нормализованы (JsWidgetTree). Форма совпадает
 * с normalizeTree обвязки обвязки пина: {type, props, children, events, nodeId}.
 */
object AuiDump {

    fun dump(node: JsNode, viewOf: (String) -> View?): JSONObject {
        val out = JSONObject()
        out.put("type", node.type)
        // Union-словарь (record + raw): delta-пути L0 assert raw имена
        // (button.text, container.color), seeds — алиасный data.
        val props = node.dumpProps()
        viewOf(node.nodeId)?.let { readLive(it, node, props) }
        out.put("props", props)
        out.put("children", JSONArray(node.children.map { dump(it, viewOf) }))
        out.put("events", JSONObject(node.events.toString()))
        out.put("nodeId", node.nodeId)
        return out
    }

    /** Живой текст и scroll-состояние (A3) перезаписывают только присутствующие ключи. */
    private fun readLive(view: View, node: JsNode, props: JSONObject) {
        if (putScrollState(view, node.type, props)) return
        readLiveText(view, props)
    }

    /**
     * A3 (kit 68e149d, handoff js_widget_runtime «Amendment A3»): узел
     * resolved-типа scroll несёт scrollOffset/scrollExtent/viewportExtent —
     * логические px по главной оси, >= 0, в КАЖДОМ дампе. Android:
     * computeVerticalScrollOffset / computeVerticalScrollRange / высота view,
     * делённые на density (действия драйвера тоже приходят в логических px).
     */
    private fun putScrollState(view: View, type: String, props: JSONObject): Boolean {
        val sv = scrollableOf(view, type) ?: return false
        val d = sv.resources.displayMetrics.density
        props.put("scrollOffset", toLogical(sv.scrollOffsetPx(), d))
        props.put("scrollExtent", toLogical(sv.scrollRangePx(), d))
        props.put("viewportExtent", toLogical(sv.height, d))
        return true
    }

    private fun scrollableOf(view: View, type: String): ScrollColumn? {
        if (type != "scroll") return null
        return view as? ScrollColumn
    }

    private fun toLogical(px: Int, density: Float): Double {
        if (density <= 0f) return 0.0
        return px.toDouble() / density
    }

    /** Живой текст перезаписывает только ключи, присутствующие в записи. */
    private fun readLiveText(view: View, props: JSONObject) {
        liveInput(view, props) || liveText(view, props)
    }

    private fun liveInput(view: View, props: JSONObject): Boolean {
        if (view !is EditText) return false
        live(props, "value", view.text.toString())
        live(props, "hint", hintText(view))
        return true
    }

    private fun hintText(view: EditText): String {
        val hint = view.hint ?: return ""
        return hint.toString()
    }

    private fun liveText(view: View, props: JSONObject): Boolean {
        if (view !is TextView) return false // Button тоже TextView
        live(props, "data", view.text.toString())
        return true
    }

    private fun live(props: JSONObject, key: String, value: String) {
        if (props.has(key)) props.put(key, value)
    }
}
