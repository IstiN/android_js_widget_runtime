package com.test.jsr

import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ScrollView

/**
 * InteractionDriver (plan 1.4 §3.3, issue #1): actions from expected.json
 * over the real View tree. Tap — by nodeId or visible text; input/submit
 * send the documented events with {value}; scroll — pointer scroll
 * (ACTION_SCROLL, amendment A2); swipe — touch drag; back — the reference
 * host-event global __jsrHostEvent('back', {}).
 *
 * No silent no-ops: every action that cannot be applied returns the reason
 * (also written to logcat); the runner records it into result.json.error and
 * a fixture with an action error is a failure. Callbacks return the engine
 * error (or null) so eval failures surface the same way.
 */
class InteractionDriver(
    private val views: (String) -> View?,
    private val allViews: () -> Collection<View>,
    private val root: () -> JsNode?,
    private val onAction: (actionId: String, payloadJson: String) -> String?,
    private val onHostEvent: (target: String, payloadJson: String) -> String?,
) {
    /** Tap events: onTap ?? onPress ?? onPressed, payload = node.payload. */
    fun tap(selector: String): String? {
        val node = findTapNode(selector) ?: return missLog(tapMissReason(selector))
        return dispatchTap(node, selector)
    }

    private fun dispatchTap(node: JsNode, selector: String): String? {
        val action = firstEvent(node, "onTap", "onPress", "onPressed")
            ?: return missLog("tap: no tap event on node ${node.nodeId} ($selector)")
        return onAction(action, payloadOf(node))
    }

    /** input: onChange ?? onChanged with {"value": "<text>"}, text lives in the EditText. */
    fun input(nodeId: String, value: String): String? {
        val (node, field) = inputTarget(nodeId) ?: return missLog(inputMissReason("input", nodeId))
        field.setText(value)
        return dispatchInput(node, "onChange", "onChanged", valuePayload(value))
    }

    /** submit: keyboard done on the node; onSubmit ?? onSubmitted. */
    fun submit(nodeId: String): String? {
        val (node, field) = inputTarget(nodeId) ?: return missLog(inputMissReason("submit", nodeId))
        return dispatchInput(node, "onSubmit", "onSubmitted", valuePayload(field.text.toString()))
    }

    private fun inputTarget(nodeId: String): Pair<JsNode, EditText>? {
        val node = findByNodeId(nodeId) ?: return null
        return inputField(nodeId)?.let { node to it }
    }

    private fun inputField(nodeId: String): EditText? = views(nodeId) as? EditText

    /** Distinct miss reasons: no node vs the node's view is not an EditText. */
    private fun inputMissReason(kind: String, nodeId: String): String =
        if (findByNodeId(nodeId) == null) "$kind: no view with node id $nodeId"
        else "$kind: view is not an EditText"

    private fun dispatchInput(node: JsNode, first: String, second: String, payload: String): String? {
        val action = firstEvent(node, first, second)
            ?: return missLog("input: no change event on node ${node.nodeId}")
        return onAction(action, payload)
    }

    /** scroll: pointer scroll (ACTION_SCROLL) into the first ScrollView. */
    fun scroll(dy: Int): String? {
        val target = scrollTarget() ?: return missLog("scroll: no scroll target")
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
        return null
    }

    /** swipe: touch drag (DOWN -> MOVE -> UP) on the scroll target. */
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Int): String? {
        val target = scrollTarget() ?: return missLog("swipe: no scroll target")
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
        return null
    }

    /** Action wait: real time on every platform (name waitMs: Object.wait). */
    fun waitMs(ms: Long): String? {
        SystemClock.sleep(ms)
        return null
    }

    /** back: host-event global of the reference (docs/conformance PROTOCOL §3.3). */
    fun back(): String? = onHostEvent("back", "{}")

    // ---------- search ----------

    private fun findTapNode(selector: String): JsNode? =
        if (selector.matches(Regex("n\\d+"))) findByNodeId(selector) else findByText(selector)

    /** Distinct tap miss reasons: node id vs visible text. */
    private fun tapMissReason(selector: String): String =
        if (selector.matches(Regex("n\\d+"))) "tap: no view with node id $selector"
        else "tap: no view with text \"$selector\""

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

    /** Visible text: first of data/text/label/value/hint/title in the record. */
    private fun visibleText(node: JsNode): String {
        val key = listOf("data", "text", "label", "value", "hint", "title")
            .firstOrNull { node.props.has(it) }
        return key?.let { node.props.optString(it) } ?: ""
    }

    private fun firstEvent(node: JsNode, vararg keys: String): String? =
        keys.map { node.events.optString(it) }.firstOrNull { it.isNotEmpty() }

    /** Tap events carry the node's payload prop ({} by default). */
    private fun payloadOf(node: JsNode): String {
        val payload = node.props.optJSONObject("payload")
        return payload?.toString() ?: "{}"
    }

    private fun valuePayload(value: String): String =
        "{\"value\":" + JsStrings.quote(value) + "}"

    /** Every unapplied action goes to logcat (evidence artifact) and the caller. */
    private fun missLog(reason: String): String {
        Log.w(TAG, reason)
        return reason
    }

    // ---------- gesture targets ----------

    private fun scrollTarget(): View? {
        allViews().filterIsInstance<ScrollView>().firstOrNull()?.let { return it }
        return allViews().firstOrNull { it is ViewGroup }
    }

    private fun motionEvent(action: Int, time: Long, x: Float, y: Float): MotionEvent =
        MotionEvent.obtain(time, time, action, x, y, 0)

    companion object {
        private const val TAG = "InteractionDriver"
    }
}
