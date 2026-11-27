package com.test.jsr

import org.json.JSONArray
import org.json.JSONObject

/**
 * Нормализованный узел AUI (план 1.3): type без алиасов, плоские props
 * (colors #RRGGBB(AA) верхним регистром), events из строковых on*-props,
 * children унифицированы (child/children/items), nodeId n<i> в depth-first
 * pre-order. key — подсказка идентичности для reconciler'а: не рендерится
 * и не попадает в dump.
 */
class JsNode(
    val type: String,
    val props: JSONObject,
    val events: JSONObject,
    val children: List<JsNode>,
    val nodeId: String,
    val key: String? = null,
    /** Raw (pre-alias) props of the payload; the dump union (plan 1.3). */
    val rawProps: JSONObject? = null,
) {
    /**
     * Dump props: the applied (normalized) record unioned with the raw
     * payload props. The union keeps both vocabularies: seeds expect the
     * aliased `data`, while L0 delta paths assert raw names (`button.text`,
     * `container.color`); compare only checks expected keys, so the extra
     * vocabulary is harmless.
     */
    fun dumpProps(): JSONObject {
        val out = JSONObject(props.toString())
        rawProps?.keys()?.forEach { k -> if (!out.has(k)) out.put(k, rawProps.get(k)) }
        return out
    }

    /** Форма dump: {type, props, children, events, nodeId}. */
    fun toJson(): JSONObject {
        val out = JSONObject()
        out.put("type", type)
        out.put("props", dumpProps())
        out.put("children", JSONArray(children.map { it.toJson() }))
        out.put("events", JSONObject(events.toString()))
        out.put("nodeId", nodeId)
        return out
    }
}

/**
 * Порт нормализатора контракта (harness/normalize.mjs + aliases.mjs пина
 * js_widget_runtime): правила 1..7 плана 1.3 + field-алиасы референса
 * (UiViewTreeNormalizer._aliasFields), идемпотентно: алиас применяется,
 * только если целевой prop отсутствует.
 */
object JsWidgetTree {

    fun normalize(payload: JSONObject): JsNode {
        val root = if (!payload.has("type") && payload.has("root") &&
            payload.optJSONObject("root") != null
        ) payload.getJSONObject("root") else payload
        val tree = buildNode(root)
        return assignIds(tree, counter = intArrayOf(0))
    }

    private fun assignIds(node: JsNode, counter: IntArray): JsNode {
        val id = "n" + counter[0]++
        val children = node.children.map { assignIds(it, counter) }
        return JsNode(node.type, node.props, node.events, children, id, node.key, node.rawProps)
    }

    // ---------- правило 1..3: рабочая карта, алиасы, разбор ----------

    private fun buildNode(node: JSONObject): JsNode {
        val working = workingOf(node)
        // Снимок ДО полевых алиасов (они мутируют working): raw-словарь дампа.
        val rawProps = rawPropsOf(working, node)
        val type = applyFieldAliases(resolveTypeAlias(working.optString("type")), working)
        val (props, events) = splitProps(node, working)
        val children = rawChildren(working).map { toChild(it) } // правило 3
        val key = (working.opt("key") as? String)
        return JsNode(type, props, events, children, "", key, rawProps)
    }

    /** Raw-props словарь узла: ключи payload, значения по правилу 6 (colors/sizes). */
    private fun rawPropsOf(working: JSONObject, node: JSONObject): JSONObject {
        val raw = JSONObject()
        working.keys().forEach { k ->
            if (k in STRUCTURAL || k == "events") return@forEach
            if (k.matches(Regex("on[A-Z].*")) && working.opt(k) is String) return@forEach
            raw.put(k, normalizePropValue(k, working.get(k)))
        }
        node.optJSONObject("style")?.let { style -> // правило 1: ключи узла старше
            style.keys().forEach { k -> if (!raw.has(k)) raw.put(k, normalizePropValue(k, style.get(k))) }
        }
        return raw
    }

    /** props объединяются с верхним уровнем узла (ключи узла старше). */
    private fun workingOf(node: JSONObject): JSONObject {
        val working = JSONObject()
        node.optJSONObject("props")?.let { p ->
            p.keys().forEach { k -> working.put(k, p.get(k)) }
        }
        node.keys().forEach { k ->
            if (k != "props" && !working.has(k)) working.put(k, node.get(k))
        }
        return working
    }

    private fun splitProps(node: JSONObject, working: JSONObject): Pair<JSONObject, JSONObject> {
        val props = JSONObject()
        val events = JSONObject()
        working.optJSONObject("events")?.let { e ->
            e.keys().forEach { k -> events.put(k, e.get(k)) }
        }
        working.keys().forEach { k -> splitKey(k, working, props, events) }
        node.optJSONObject("style")?.let { style -> // правило 1: ключи узла старше
            style.keys().forEach { k -> mergeStyleKey(k, style, props, events) }
        }
        return props to events
    }

    /** Правило 2: строковые on* уходят в events. */
    private fun splitKey(k: String, working: JSONObject, props: JSONObject, events: JSONObject) {
        if (k in STRUCTURAL || k == "events") return
        if (k.matches(Regex("on[A-Z].*")) && working.opt(k) is String) {
            events.put(k, working.getString(k))
            return
        }
        props.put(k, normalizePropValue(k, working.get(k)))
    }

    private fun mergeStyleKey(k: String, style: JSONObject, props: JSONObject, events: JSONObject) {
        if (!props.has(k) && !events.has(k)) props.put(k, normalizePropValue(k, style.get(k)))
    }

    private fun toChild(child: Any): JsNode = when (child) {
        is JSONObject -> buildNode(child)
        is String -> buildNode(JSONObject().put("type", "text").put("data", child))
        else -> throw IllegalArgumentException("node child is not an object: $child")
    }

    /** children-массив | child | items; отсутствие -> пустой список. */
    private fun rawChildren(working: JSONObject): List<Any> {
        working.optJSONArray("children")?.let { a ->
            return (0 until a.length()).map { a.get(it) }
        }
        working.opt("child")?.let { c ->
            if (c != null) return listOf(c)
        }
        working.optJSONArray("items")?.let { a ->
            return (0 until a.length()).map { a.get(it) }
        }
        return emptyList()
    }

    // ---------- алиасы полей референса ----------

    private val BOX_TYPES = setOf("container", "card", "animatedContainer")
    private val BUTTON_TYPES = setOf("button", "textButton", "outlinedButton")
    private val INPUT_TYPES = setOf("textField", "input", "textArea")
    private val IMAGE_TYPES = setOf("image", "networkImage", "img")
    private val VALUE_TYPES = setOf("switch", "checkbox", "slider")

    /** Возвращает (возможно переименованный) тип узла. */
    private fun applyFieldAliases(type: String, out: JSONObject): String {
        aliasTextContent(out, type)
        var result = type
        if (type in BOX_TYPES) aliasBoxDecoration(out, type)
        if (type in BUTTON_TYPES) aliasButtonLabel(out)
        if (type == "svg") aliasTarget(out, "markup", "data")
        return aliasFormFamily(type, out, result)
    }

    /** input/image/value/dropdown семейства: переименование полей и типа. */
    private fun aliasFormFamily(type: String, out: JSONObject, current: String): String {
        var result = current
        if (type in INPUT_TYPES) {
            result = if (type == "textArea") "textArea" else "textField"
            aliasTarget(out, "placeholder", "hint")
            aliasTarget(out, "value", "initialValue")
            aliasOnChanged(out)
        }
        if (type in IMAGE_TYPES) {
            result = "image"
            aliasTarget(out, "uri", "url")
            aliasTarget(out, "source", "url")
        }
        if (type in VALUE_TYPES) aliasOnChanged(out)
        if (type == "dropdown" || type == "select") {
            result = "dropdown"
            aliasOnChanged(out)
        }
        return result
    }

    private fun aliasTarget(out: JSONObject, from: String, to: String) {
        if (out.has(from) && out.get(from) != null && !out.has(to)) {
            out.put(to, out.get(from))
            out.remove(from)
        }
    }

    /** content -> data; label -> data у text (reference сохраняет label-prop). */
    private fun aliasTextContent(out: JSONObject, type: String) {
        val content = out.opt("content")
        if (content != null && !out.has("data")) {
            out.put("data", content)
            out.remove("content")
        }
        val label = out.opt("label")
        if (label != null && !out.has("data") && type == "text") {
            out.put("data", label) // label остаётся в props
        }
    }

    private fun aliasBoxDecoration(out: JSONObject, type: String) {
        val bg = out.opt("backgroundColor")
        if (bg != null && !out.has("decoration")) {
            out.put("decoration", JSONObject().put("color", bg))
            out.remove("backgroundColor")
        }
        val color = out.opt("color")
        if (color != null && !out.has("decoration") && type == "container") {
            out.put("decoration", JSONObject().put("color", color))
            out.remove("color")
        }
    }

    private fun aliasButtonLabel(out: JSONObject) {
        aliasTarget(out, "title", "data")
        aliasTarget(out, "text", "data")
    }

    /** onChanged -> onChange (value-типы и dropdown); цель старше источника. */
    private fun aliasOnChanged(out: JSONObject) {
        if (out.has("onChanged") && !out.has("onChange")) {
            out.put("onChange", out.get("onChanged"))
            out.remove("onChanged")
        }
    }

    // ---------- типы ----------

    /** Таблица алиасов референса — ВЕРБАТИМ из harness/aliases.mjs пина. */
    private val TYPE_ALIASES = mapOf(
        "Text" to "text",
        "text" to "text",
        "Label" to "text",
        "label" to "text",
        "p" to "text",
        "span" to "text",
        "Button" to "button",
        "button" to "button",
        "ElevatedButton" to "button",
        "elevatedButton" to "button",
        "TextButton" to "textButton",
        "OutlinedButton" to "outlinedButton",
        "IconButton" to "iconButton",
        "Rect" to "rect",
        "Circle" to "circle",
        "Line" to "line",
        "Polygon" to "polygon",
        "View" to "column",
        "view" to "column",
        "div" to "column",
        "box" to "container",
        "Box" to "container",
        "Fragment" to "column",
        "ScrollView" to "scroll",
        "scrollView" to "scroll",
        "scroll" to "scroll",
        "SingleChildScrollView" to "scroll",
        "singleChildScrollView" to "scroll",
        "ListView" to "listView",
        "GridView" to "gridView",
        "Image" to "image",
        "networkImage" to "image",
        "NetworkImage" to "image",
        "img" to "image",
        "Svg" to "svg",
        "SVG" to "svg",
        "Card" to "card",
        "Row" to "row",
        "Column" to "column",
        "Stack" to "stack",
        "Center" to "center",
        "Padding" to "padding",
        "SizedBox" to "sizedBox",
        "Container" to "container",
        "ListTile" to "listTile",
        "listItem" to "listTile",
        "ListItem" to "listTile",
        "ProgressBar" to "linearProgressIndicator",
        "progress" to "linearProgressIndicator",
        "linearProgress" to "linearProgressIndicator",
        "ActivityIndicator" to "circularProgressIndicator",
        "spinner" to "circularProgressIndicator",
        "TextInput" to "textField",
        "textInput" to "textField",
        "input" to "textField",
        "TextArea" to "textArea",
        "textarea" to "textArea",
        "Switch" to "switch",
        "Checkbox" to "checkbox",
        "Slider" to "slider",
        "Markdown" to "markdown",
        "md" to "markdown",
        "Avatar" to "circleAvatar",
        "CircleAvatar" to "circleAvatar",
        "avatar" to "circleAvatar",
        "Chip" to "chip",
        "Tag" to "chip",
        "tag" to "chip",
        "Badge" to "badge",
        "Dropdown" to "dropdown",
        "Select" to "dropdown",
        "select" to "dropdown",
        "InkWell" to "inkWell",
        "GestureDetector" to "gestureDetector",
        "SafeArea" to "safeArea",
        "Divider" to "divider",
        "Spacer" to "spacer",
        "Icon" to "icon",
        "Scene3d" to "scene3d",
        "scene3d" to "scene3d",
        "scene3D" to "scene3d",
        "3d" to "scene3d",
        "model3d" to "scene3d",
        "model" to "scene3d",
        "glb" to "scene3d",
    )

    private fun resolveTypeAlias(raw: String): String {
        val key = raw.trim()
        return TYPE_ALIASES[key] ?: TYPE_ALIASES[key.lowercase()] ?: key
    }

    // ---------- правило 6: нормализация значений ----------

    private val COLOR_KEY = Regex("color", RegexOption.IGNORE_CASE)

    private fun normalizePropValue(key: String, value: Any?): Any? = when {
        COLOR_KEY.containsMatchIn(key) -> normalizeColor(value)
        value is JSONArray -> JSONArray((0 until value.length()).map { i ->
            val v = value.get(i)
            if (v is JSONObject) normalizePropValue("*", v) else v
        })
        value is JSONObject -> {
            val out = JSONObject()
            value.keys().forEach { k -> out.put(k, normalizePropValue(k, value.get(k))) }
            out
        }
        else -> value
    }

    /** #RRGGBB / #RRGGBBAA верхним регистром; rgb()/rgba() -> hex. */
    fun normalizeColor(value: Any?): Any? {
        if (value !is String) return value
        val trimmed = value.trim()
        return normalizedHex(trimmed) ?: rgbToHex(trimmed) ?: value
    }

    private fun normalizedHex(trimmed: String): String? {
        val hex = Regex("^#([0-9a-fA-F]{3,8})$").find(trimmed) ?: return null
        val h = hex.groupValues[1]
        if (h.length == 3 || h.length == 4) {
            return "#" + h.map { c -> "$c$c" }.joinToString("").uppercase()
        }
        if (h.length == 6 || h.length == 8) return "#" + h.uppercase()
        return null // 5/7 цифр: не hex и не rgb() — значение как есть
    }

    private fun rgbToHex(trimmed: String): String? {
        val rgb = Regex("^rgba?\\(\\s*([^)]+?)\\s*\\)$", RegexOption.IGNORE_CASE).find(trimmed)
            ?: return null
        val nums = rgb.groupValues[1].split(Regex("[\\s,+/]+")).mapNotNull { it.toDoubleOrNull() }
        if (nums.size != 3 && nums.size != 4) return null
        val alpha = if (nums.size == 4) (if (nums[3] > 1) nums[3] / 255 else nums[3]) else null
        fun byte(n: Double): String = n.coerceIn(0.0, 255.0).toInt().toString(16).padStart(2, '0')
            .uppercase()
        val out = StringBuilder("#")
        nums.take(3).forEach { out.append(byte(it)) }
        if (alpha != null) out.append(byte(Math.round(alpha * 255).toDouble()))
        return out.toString()
    }

    private val STRUCTURAL = setOf(
        "type", "child", "children", "items", "key", "style", "props", "events", "nodeId", "__iid",
    )
}
