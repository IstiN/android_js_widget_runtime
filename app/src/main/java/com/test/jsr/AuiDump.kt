package com.test.jsr

import org.json.JSONArray
import org.json.JSONObject
import android.view.View
import android.widget.EditText
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
        val props = JSONObject(node.props.toString())
        viewOf(node.nodeId)?.let { readLiveText(it, props) }
        out.put("props", props)
        out.put("children", JSONArray(node.children.map { dump(it, viewOf) }))
        out.put("events", JSONObject(node.events.toString()))
        out.put("nodeId", node.nodeId)
        return out
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
