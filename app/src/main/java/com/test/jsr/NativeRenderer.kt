package com.test.jsr

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

/**
 * Рендерер: применяет патч-операции reconciler'а к дереву View (issue #2,
 * plan 1.5 A2) — без полной пересборки. Каждый корневой View узла несёт
 * setTag(R.id.jsr_node_id, nodeId). Зарегистрированные типы seed'ов
 * (center, column, row, text, sizedBox, textButton, scroll, container,
 * card, textField) отображаются реально; всё остальное — fallback-карточка
 * с именем типа (общее правило волны C).
 *
 * Все функции этого файла исполняются только под Robolectric (JaCoCo их не
 * видит -> 0%), поэтому каждая держится на сложности <= 2.
 */
class NativeRenderer(
    private val context: Context,
    private val sink: EventSink,
    private val container: ViewGroup,
) {
    fun interface EventSink {
        fun onAction(actionId: String, payloadJson: String)
    }

    private val views = HashMap<String, View>()
    private val records = HashMap<String, JsNode>()
    private var rootTree: JsNode? = null

    /** Полный цикл render: первый вызов монтирует, дальше — патчи. */
    fun render(tree: JsNode): List<PatchOp> {
        val old = rootTree
        if (old == null) {
            mountFull(tree)
            return emptyList()
        }
        val result = Reconciler.diff(old, tree)
        applyAll(result.ops)
        remapAll(result.renames)
        rootTree = tree
        return result.ops
    }

    /** Сброс между загрузками виджетов (loadWidget очищает контейнер сам). */
    fun reset() {
        views.clear()
        records.clear()
        rootTree = null
    }

    // Доступ для dump/driver/runner (A2b) и тестов.
    internal fun viewOf(nodeId: String): View? = views[nodeId]
    internal fun allViews(): Collection<View> = views.values
    internal fun rootNode(): JsNode? = rootTree

    private fun mountFull(tree: JsNode) {
        val view = inflate(tree)
        container.addView(view, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        rootTree = tree
    }

    // ---------- применение операций ----------

    /** Удаления раньше замен/вставок: старые id обязаны быть живы в картах. */
    private val PRIORITY: Map<Class<out PatchOp>, Int> = mapOf(
        PatchOp.RemoveChild::class.java to 0,
        PatchOp.MoveChild::class.java to 1,
        PatchOp.ReplaceSubtree::class.java to 2,
        PatchOp.InsertChild::class.java to 3,
        PatchOp.UpdateProps::class.java to 4,
    )

    private fun applyAll(ops: List<PatchOp>) {
        ops.sortedBy { PRIORITY[it.javaClass] ?: 9 }.forEach { apply(it) }
    }

    private fun apply(op: PatchOp) {
        val handler = APPLY[op.javaClass] ?: return
        handler(op)
    }

    private val APPLY: Map<Class<out PatchOp>, (PatchOp) -> Unit> = mapOf(
        PatchOp.UpdateProps::class.java to { o -> applyUpdate(o as PatchOp.UpdateProps) },
        PatchOp.InsertChild::class.java to { o -> applyInsert(o as PatchOp.InsertChild) },
        PatchOp.RemoveChild::class.java to { o -> applyRemove(o as PatchOp.RemoveChild) },
        PatchOp.MoveChild::class.java to { o -> applyMove(o as PatchOp.MoveChild) },
        PatchOp.ReplaceSubtree::class.java to { o -> applyReplace(o as PatchOp.ReplaceSubtree) },
    )

    private fun applyUpdate(op: PatchOp.UpdateProps) {
        val view = views[op.nodeId] ?: return
        applyPropsDiff(view, records[op.nodeId], op.node)
    }

    private fun applyInsert(op: PatchOp.InsertChild) {
        parentView(op.parentId)?.let { parent ->
            val view = inflate(op.node)
            parent.addView(view, op.index.coerceAtMost(parent.childCount))
        }
    }


    private fun applyRemove(op: PatchOp.RemoveChild) {
        parentView(op.parentId)?.let { parent -> removeSubtree(parent, op.nodeId) }
    }

    private fun removeSubtree(parent: ViewGroup, nodeId: String) {
        val view = views[nodeId] ?: return
        records[nodeId]?.children?.forEach { removeSubtreeIds(it) }
        parent.removeView(view)
        views.remove(nodeId)
        records.remove(nodeId)
    }

    private fun removeSubtreeIds(node: JsNode) {
        node.children.forEach { removeSubtreeIds(it) }
        views.remove(node.nodeId)
        records.remove(node.nodeId)
    }

    private fun applyMove(op: PatchOp.MoveChild) {
        parentView(op.parentId)?.let { parent ->
            val view = views[op.nodeId] ?: return
            parent.removeView(view)
            parent.addView(view, op.toIndex.coerceAtMost(parent.childCount))
        }
    }

    private fun applyReplace(op: PatchOp.ReplaceSubtree) {
        replaceIn(views[op.nodeId] ?: return, op)
    }

    private fun replaceIn(old: View, op: PatchOp.ReplaceSubtree) {
        val parent = old.parent as? ViewGroup ?: return
        val index = parent.indexOfChild(old)
        removeSubtree(parent, op.nodeId)
        parent.addView(inflate(op.node), index.coerceAtMost(parent.childCount))
    }

    /** Один проход после всех ops: сначала изъять старые слоты, потом записать новые. */
    private fun remapAll(renames: List<Pair<JsNode, JsNode>>) {
        val moving = extractedViews(renames)
        renames.forEach { (old, new) ->
            val view = moving[old.nodeId] ?: return@forEach
            views[new.nodeId] = view
            records[new.nodeId] = new
            view.setTag(R.id.jsr_node_id, new.nodeId)
        }
    }

    private fun extractedViews(renames: List<Pair<JsNode, JsNode>>): Map<String, View> {
        val moving = HashMap<String, View>()
        renames.forEach { (old, _) ->
            views.remove(old.nodeId)?.let { moving[old.nodeId] = it }
            records.remove(old.nodeId)
        }
        return moving
    }

    private fun parentView(nodeId: String): ViewGroup? = views[nodeId] as? ViewGroup

    // ---------- обновление свойств без потери состояния (issue #2 п.3) ----------

    private val PROPDIFF: Map<Class<out View>, (View, JsNode, JsNode?) -> Unit> = mapOf(
        Button::class.java to { v, n, _ -> updateTextContent(v as Button, n) },
        EditText::class.java to { v, n, old -> updateInputContent(v as EditText, old, n) },
        TextView::class.java to { v, n, _ -> updateTextContent(v as TextView, n) },
    )

    private fun applyPropsDiff(view: View, old: JsNode?, new: JsNode) {
        val handler = PROPDIFF[view.javaClass] ?: return
        handler(view, new, old)
    }

    /** EditText хранит текст, пока prop initialValue не изменился. */
    private fun updateInputContent(view: EditText, old: JsNode?, new: JsNode) {
        if (initialValueChanged(old, new)) view.setText(new.props.optString("initialValue"))
    }

    private fun initialValueChanged(old: JsNode?, new: JsNode): Boolean {
        old ?: return true
        return differs(old.props, new.props, "initialValue")
    }

    private fun differs(a: JSONObject, b: JSONObject, key: String): Boolean =
        b.has(key) && b.optString(key) != a.optString(key)

    private fun updateTextContent(view: TextView, node: JsNode) {
        view.text = textOf(node)
    }

    // ---------- создание views (типы seed'ов; остальное — fallback) ----------

    private fun inflate(node: JsNode): View {
        val view = createView(node.type)
        view.setTag(R.id.jsr_node_id, node.nodeId)
        views[node.nodeId] = view
        records[node.nodeId] = node
        config[node.type]?.invoke(view, node)
        attachChildren(view, node)
        return view
    }

    private fun createView(type: String): View =
        FACTORIES[type]?.invoke(context) ?: fallbackCard(type)

    private fun attachChildren(view: View, node: JsNode) {
        node.children.forEachIndexed { i, child ->
            val childView = inflate(child)
            attach(view, childView)
            applyChildMargin(child, childView)
            applySpacing(view, node, childView, i)
        }
    }

    private val FACTORIES: Map<String, (Context) -> View> = mapOf(
        "center" to { c -> FrameLayout(c) },
        "column" to { c -> LinearLayout(c).apply { orientation = LinearLayout.VERTICAL } },
        "row" to { c -> LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL } },
        "text" to { c -> TextView(c) },
        "sizedBox" to { c -> View(c) },
        "textButton" to { c -> Button(c).apply { isAllCaps = false } },
        "button" to { c -> Button(c).apply { isAllCaps = false } },
        "textField" to { c -> EditText(c) },
        "scroll" to { c -> scrollOf(c) },
        "container" to { c -> LinearLayout(c).apply { orientation = LinearLayout.VERTICAL } },
        "card" to { c -> cardOf(c) },
    )

    /** Fallback-карточка референса: имя типа + тег nodeId. */
    private fun fallbackCard(type: String): View {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#334155"))
                cornerRadius = dp(8f).toFloat()
            }
            setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
        }
        val tv = TextView(context)
        tv.text = "[$type]"
        tv.setTextColor(Color.WHITE)
        box.addView(tv)
        Log.i(TAG, "fallback card for '$type'")
        return box
    }

    private fun scrollOf(c: Context): View {
        val sv = ScrollView(c)
        val inner = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        sv.addView(inner, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return sv
    }

    private fun cardOf(c: Context): View {
        val ll = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        ll.background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(12f).toFloat()
        }
        ll.elevation = dp(2f).toFloat()
        return ll
    }

    // ---------- прикрепление детей и отступы ----------

    private val ATTACH: Map<Class<out View>, (View, View) -> Unit> = mapOf(
        ScrollView::class.java to { v, child -> scrollChild(v as ScrollView, child) },
        FrameLayout::class.java to { v, child -> centerChild(v as FrameLayout, child) },
        ViewGroup::class.java to { v, child -> (v as ViewGroup).addView(child) },
    )

    private fun attach(view: View, child: View) {
        handlerOf(view)?.invoke(view, child)
    }

    private fun handlerOf(view: View): ((View, View) -> Unit)? {
        ATTACH[view.javaClass]?.let { return it }
        return ATTACH[ViewGroup::class.java]
    }

    private fun scrollChild(sv: ScrollView, child: View) {
        (sv.getChildAt(0) as ViewGroup).addView(child)
    }

    private fun centerChild(fl: FrameLayout, child: View) {
        fl.addView(child, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    private fun applySpacing(view: View, node: JsNode, child: View, index: Int) {
        val add = spacingPx(node) * index.coerceAtMost(1)
        if (add == 0) return
        setSpacingSide(child, add, isVertical(view))
    }

    private fun setSpacingSide(child: View, add: Int, vertical: Boolean) {
        val lp = child.layoutParams as? LinearLayout.LayoutParams ?: return
        lp.topMargin += add * sideFactor(vertical)
        lp.leftMargin += add * sideFactor(!vertical)
        child.layoutParams = lp
    }

    private fun sideFactor(v: Boolean): Int = if (v) 1 else 0

    private fun isVertical(view: View): Boolean =
        (view as? LinearLayout)?.orientation != LinearLayout.HORIZONTAL

    private fun applyChildMargin(child: JsNode, childView: View) {
        val lp = childView.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val margin = pxOf(child.props, "margin")
        lp.setMargins(margin, margin, margin, margin)
        childView.layoutParams = lp
    }

    // ---------- конфигурация по типу ----------

    private val config: Map<String, (View, JsNode) -> Unit> = mapOf(
        "text" to { v, n -> configureText(v as TextView, n) },
        "textButton" to { v, n -> configureButton(v as Button, n) },
        "button" to { v, n -> configureButton(v as Button, n) },
        "textField" to { v, n -> configureInput(v as EditText, n) },
        "sizedBox" to { v, n -> configureSizedBox(v, n) },
        "container" to { v, n -> configureContainer(v, n) },
        "card" to { v, n -> configureCard(v, n) },
        "scroll" to { v, _ -> (v as ScrollView).isVerticalScrollBarEnabled = true },
    )

    private fun configureText(view: TextView, node: JsNode) {
        view.text = textOf(node)
        applyFontSize(view, node)
        applyTextColor(view, node)
        applyFontWeight(view, node)
    }

    private fun applyFontSize(view: TextView, node: JsNode) {
        val style = node.props.optDouble("fontSize", -1.0)
        if (style > 0) view.textSize = dp(style.toFloat()).toFloat()
    }

    private fun applyTextColor(view: TextView, node: JsNode) {
        val color = node.props.optString("color")
        if (color.isNotEmpty()) view.setTextColor(parseColor(color))
    }

    private fun applyFontWeight(view: TextView, node: JsNode) {
        val bold = node.props.optString("fontWeight") in setOf("bold", "w600", "w700", "w800")
        view.setTypeface(null, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun configureButton(view: Button, node: JsNode) {
        view.text = textOf(node)
        val action = firstEvent(node, "onTap", "onPressed", "onPress")
        if (action != null) view.setOnClickListener {
            sink.onAction(action, payloadOf(node))
        }
    }

    private fun configureInput(view: EditText, node: JsNode) {
        view.hint = node.props.optString("hint")
        view.setText(node.props.optString("initialValue"))
    }

    private fun configureSizedBox(view: View, node: JsNode) {
        view.layoutParams = ViewGroup.LayoutParams(pxOf(node.props, "width"), pxOf(node.props, "height"))
    }

    private fun configureContainer(view: View, node: JsNode) {
        val color = decorationColor(node)
        if (color.isNotEmpty()) {
            view.background = GradientDrawable().apply { setColor(parseColor(color)) }
            applyRadius(view.background, node)
        }
        applyPadding(view, node)
    }

    private fun applyRadius(background: android.graphics.drawable.Drawable?, node: JsNode) {
        roundOf(background)?.let { it.cornerRadius = radiusPx(node) }
    }

    private fun roundOf(background: android.graphics.drawable.Drawable?): GradientDrawable? =
        background as? GradientDrawable

    private fun radiusPx(node: JsNode): Float {
        val r = node.props.optDouble("borderRadius", -1.0)
        if (r < 0) return -1f
        return dp(r.toFloat()).toFloat()
    }

    private fun applyPadding(view: View, node: JsNode) {
        val pad = pxOf(node.props, "padding")
        if (pad > 0) view.setPadding(pad, pad, pad, pad)
    }

    private fun configureCard(view: View, node: JsNode) {
        val color = decorationColor(node)
        if (color.isNotEmpty()) {
            val gd = view.background as? GradientDrawable
            gd?.setColor(parseColor(color))
        }
        applyPadding(view, node)
    }

    // ---------- чтение нормализованных значений ----------

    private fun textOf(node: JsNode): String {
        val props = node.props
        return props.optString("data", props.optString("text", props.optString("label", "")))
    }

    private fun firstEvent(node: JsNode, vararg keys: String): String? =
        keys.map { node.events.optString(it) }.firstOrNull { it.isNotEmpty() }

    /** Тап-события несут payload- prop узла (по умолчанию {}). */
    private fun payloadOf(node: JsNode): String {
        val payload = node.props.optJSONObject("payload")
        return payload?.toString() ?: "{}"
    }

    private fun decorationColor(node: JsNode): String =
        node.props.optJSONObject("decoration")?.optString("color") ?: ""

    private fun spacingPx(node: JsNode): Int = pxOf(node.props, "spacing")

    private fun pxOf(props: JSONObject, key: String): Int {
        val v = props.optDouble(key, -1.0)
        if (v < 0) return 0
        return dp(v.toFloat())
    }

    private fun parseColor(s: String): Int = try {
        Color.parseColor(s)
    } catch (e: IllegalArgumentException) {
        Color.TRANSPARENT
    }

    private fun dp(d: Float): Int = (d * 1.5f).toInt()

    companion object {
        private const val TAG = "NativeRenderer"
    }
}
