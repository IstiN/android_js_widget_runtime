package com.test.jsr

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ScrollView

/**
 * InteractionDriver (план 1.4 §3.3, issue #1): действия из expected.json
 * поверх реального дерева View. Тап — по nodeId или видимому тексту;
 * input/sett submit отправляют документированные события с {value};
 * scroll — pointer scroll (ACTION_SCROLL, amendment A2); swipe — touch drag;
 * back — host-event глобал референса __jsrHostEvent('back', {}).
 */
class InteractionDriver(
    private val views: (String) -> View?,
    private val allViews: () -> Collection<View>,
    private val root: () -> JsNode?,
    private val onAction: (actionId: String, payloadJson: String) -> Unit,
    private val onHostEvent: (target: String, payloadJson: String) -> Unit,
) {
    /** Тап: события onTap ?? onPress ?? onPressed, payload = node.payload. */
    fun tap(selector: String) {
        val node = if (selector.matches(Regex("n\\d+"))) findByNodeId(selector) else findByText(selector)
        node?.let { dispatchTap(it) }
    }

    private fun dispatchTap(node: JsNode) {
        val action = firstEvent(node, "onTap", "onPress", "onPressed") ?: return
        onAction(action, payloadOf(node))
    }

    /** input: onChange ?? onChanged с {"value": "<текст>"}, текст живёт в EditText. */
    fun input(nodeId: String, value: String) {
        val (node, field) = inputTarget(nodeId) ?: return
        field.setText(value)
        dispatchInput(node, "onChange", "onChanged", valuePayload(value))
    }

    /** submit: клавиатурный done на узле; onSubmit ?? onSubmitted. */
    fun submit(nodeId: String) {
        val (node, field) = inputTarget(nodeId) ?: return
        dispatchInput(node, "onSubmit", "onSubmitted", valuePayload(field.text.toString()))
    }

    private fun inputTarget(nodeId: String): Pair<JsNode, EditText>? {
        val node = findByNodeId(nodeId) ?: return null
        return editTextOf(nodeId)?.let { node to it }
    }

    private fun editTextOf(nodeId: String): EditText? = views(nodeId) as? EditText

    private fun dispatchInput(node: JsNode, first: String, second: String, payload: String) {
        firstEvent(node, first, second)?.let { onAction(it, payload) }
    }

    /** scroll: pointer scroll (ACTION_SCROLL) в первый ScrollView. */
    fun scroll(dy: Int) {
        scrollTarget()?.let { target ->
            val coords = MotionEvent.PointerCoords()
            coords.x = 0f
            coords.y = 0f
            coords.setAxisValue(MotionEvent.AXIS_VSCROLL, -dy.toFloat())
            val props = MotionEvent.PointerProperties()
            props.id = 0
            props.toolType = MotionEvent.TOOL_TYPE_MOUSE
            val event = MotionEvent.obtain(
                SystemClock.uptimeMillis(), SystemClock.uptimeMillis(),
                MotionEvent.ACTION_SCROLL, 1, arrayOf(props), arrayOf(coords),
                0, 0, 0f, 0f, 0, 0,
                android.view.InputDevice.SOURCE_CLASS_POINTER, 0)
            target.dispatchGenericMotionEvent(event)
            event.recycle()
        }
    }

    /** swipe: touch drag (DOWN -> MOVE -> UP) на цели прокрутки. */
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Int) {
        val target = scrollTarget() ?: return
        val start = SystemClock.uptimeMillis()
        target.dispatchTouchEvent(motionEvent(MotionEvent.ACTION_DOWN, start, x1.toFloat(), y1.toFloat()))
        val steps = 10
        (1 until steps).forEach { i ->
            val t = i.toFloat() / steps
            val x = x1 + (x2 - x1) * t
            val y = y1 + (y2 - y1) * t
            target.dispatchTouchEvent(motionEvent(MotionEvent.ACTION_MOVE, start + (ms * t).toLong(), x, y))
        }
        target.dispatchTouchEvent(motionEvent(MotionEvent.ACTION_UP, start + ms, x2.toFloat(), y2.toFloat()))
    }

    /** Действие wait: реальное время на всех платформах (имя waitMs: Object.wait). */
    fun waitMs(ms: Long) {
        SystemClock.sleep(ms)
    }

    /** back: host-event глобал референса (docs/conformance PROTOCOL §3.3). */
    fun back() {
        onHostEvent("back", "{}")
    }

    // ---------- поиск ----------

    private fun findByNodeId(nodeId: String): JsNode? {
        val tree = root() ?: return null
        return findNodeById(tree, nodeId)
    }

    private fun findNodeById(node: JsNode, nodeId: String): JsNode? {
        if (node.nodeId == nodeId) return node
        return node.children.firstNotNullOfOrNull { findNodeById(it, nodeId) }
    }

    private fun findByText(selector: String): JsNode? {
        val tree = root() ?: return null
        return findNodeByText(tree, selector)
    }

    private fun findNodeByText(node: JsNode, text: String): JsNode? {
        if (visibleText(node) == text) return node
        return node.children.firstNotNullOfOrNull { findNodeByText(it, text) }
    }

    /** Видимый текст: первый из data/text/label/value/hint/title в записи. */
    private fun visibleText(node: JsNode): String {
        val key = listOf("data", "text", "label", "value", "hint", "title")
            .firstOrNull { node.props.has(it) }
        return key?.let { node.props.optString(it) } ?: ""
    }

    private fun firstEvent(node: JsNode, vararg keys: String): String? =
        keys.map { node.events.optString(it) }.firstOrNull { it.isNotEmpty() }

    /** Тап-события несут payload-prop узла (по умолчанию {}). */
    private fun payloadOf(node: JsNode): String {
        val payload = node.props.optJSONObject("payload")
        return payload?.toString() ?: "{}"
    }

    private fun valuePayload(value: String): String =
        "{\"value\":" + JsStrings.quote(value) + "}"

    // ---------- цели жестов ----------

    private fun scrollTarget(): View? {
        allViews().filterIsInstance<ScrollView>().firstOrNull()?.let { return it }
        return allViews().firstOrNull { it is ViewGroup }
    }

    private fun motionEvent(action: Int, time: Long, x: Float, y: Float): MotionEvent =
        MotionEvent.obtain(time, time, action, x, y, 0)
}
