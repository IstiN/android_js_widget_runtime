package com.test.jsr

import android.content.Context
import android.graphics.Color
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
 * setTag(R.id.jsr_node_id, nodeId). Создание и настройка View живут в
 * реестре мапперов NodeMapperRegistry (по категории, plan 6.1 A3); типы
 * вне реестра получают fallback-карточку с именем типа (общее правило волны C).
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
            Log.i(TAG, "mount ${tree.type} (${tree.children.size} children)")
            return emptyList()
        }
        val result = Reconciler.diff(old, tree)
        applyAll(result.ops)
        remapAll(result.renames)
        rootTree = tree
        Log.i(TAG, "patch ops=${result.ops.size} renames=${result.renames.size}")
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
        updateView(view, op)
        // records держит применённый узел: следующий diff сравнивается с ним.
        records[op.nodeId] = op.node
    }

    private fun updateView(view: View, op: PatchOp.UpdateProps) {
        if (view !is EditText) {
            // Patch-путь пере-применяет mount-конфигурацию mapper'а (реестр
            // идемпотентен) — review NativeRenderer.kt:185: без этого патч
            // молча терял все не-текстовые props каждого типа.
            NodeMapperRegistry.configure[op.node.type]?.invoke(view, op.node, sink)
            return
        }
        // Печатный текст держится, пока не изменился initialValue
        // (issue #2 п.3); полный mapper конфигурации затёр бы ввод.
        updateInputContent(view, records[op.nodeId], op.node)
    }

    private fun applyInsert(op: PatchOp.InsertChild) {
        patchContainer(op.parentId)?.let { parent ->
            val view = inflate(op.node)
            parent.addView(view, op.index.coerceAtMost(parent.childCount))
        }
    }


    private fun applyRemove(op: PatchOp.RemoveChild) {
        patchContainer(op.parentId)?.let { parent -> removeSubtree(parent, op.nodeId) }
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
        patchContainer(op.parentId)?.let { parent ->
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

    /**
     * Контейнер, куда патч-операции кладут детей parent'а: mount и patch
     * обязаны соглашаться (review NativeRenderer.kt:174). У scroll/listView
     * дети живут во внутреннем LinearLayout (scrollChild), не в самом
     * ScrollView — патч в сам ScrollView дал бы второй прямой ребёнок
     * (IllegalStateException) или views-призраки. Платформенный инвариант:
     * у ScrollView ровно один прямой ребёнок.
     */
    private fun patchContainer(nodeId: String): ViewGroup? {
        val view = views[nodeId] as? ScrollView ?: return parentView(nodeId)
        return view.getChildAt(0) as? ViewGroup
    }

    // ---------- обновление свойств без потери состояния (issue #2 п.3) ----------

    /** EditText хранит текст, пока prop initialValue не изменился. */
    private fun updateInputContent(view: EditText, old: JsNode?, new: JsNode) {
        view.hint = new.props.optString("hint")
        if (initialValueChanged(old, new)) setTextSilently(view, new.props.optString("initialValue"))
    }

    private fun initialValueChanged(old: JsNode?, new: JsNode): Boolean {
        old ?: return true
        return differs(old.props, new.props, "initialValue")
    }

    private fun differs(a: JSONObject, b: JSONObject, key: String): Boolean =
        b.has(key) && b.optString(key) != a.optString(key)

    // ---------- создание views (типы seed'ов; остальное — fallback) ----------

    private fun inflate(node: JsNode): View {
        val view = createView(node.type)
        view.setTag(R.id.jsr_node_id, node.nodeId)
        views[node.nodeId] = view
        records[node.nodeId] = node
        NodeMapperRegistry.configure[node.type]?.invoke(view, node, sink)
        attachChildren(view, node)
        return view
    }

    /** Registry lookup (plan 6.1 A3): every inventory type resolves; the rest falls back. */
    private fun createView(type: String): View =
        NodeMapperRegistry.factories[type]?.invoke(context) ?: fallbackCard(type)

    private fun attachChildren(view: View, node: JsNode) {
        node.children.forEachIndexed { i, child ->
            val childView = inflate(child)
            attach(view, childView)
            applyChildMargin(child, childView)
            applySpacing(view, node, childView, i)
        }
    }

    /** Fallback-карточка референса: имя типа + тег nodeId. */
    private fun fallbackCard(type: String): View {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#334155"))
                cornerRadius = NodeProps.dp(8f).toFloat()
            }
            setPadding(NodeProps.dp(12f), NodeProps.dp(12f), NodeProps.dp(12f), NodeProps.dp(12f))
        }
        val tv = TextView(context)
        tv.text = "[$type]"
        tv.setTextColor(Color.WHITE)
        box.addView(tv)
        Log.i(TAG, "fallback card for '$type'")
        return box
    }

    // ---------- прикрепление детей и отступы ----------

    private val ATTACH: Map<Class<out View>, (View, View) -> Unit> = mapOf(
        ScrollView::class.java to { v, child -> scrollChild(v as ScrollView, child) },
        FrameLayout::class.java to { v, child -> centerChild(v as FrameLayout, child) },
        // Локальная сборка (GT-P7510): обёртки контракта (padding/gestureDetector/
        // expanded/opacity/sizedBox) передают ограничения ребёнку — MATCH/MATCH,
        // а не wrap. Иначе кнопки калькулятора сжимались в узкие таблетки.
        FillLayout::class.java to { v, child -> (v as FrameLayout).addView(child, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)) },
        ViewGroup::class.java to { v, child ->
            // Дети списка — TIGHT ширина (Flutter-семантика вертикального
            // списка: cross-axis constraints tight).
            if (v.tag == "jsr-list-column") {
                (v as LinearLayout).addView(child, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            // expanded внутри LinearLayout — weight 1 (flex, дефолт 1).
            } else if (v is LinearLayout && child.tag == "jsr-expanded") {
                val horizontal = v.orientation == LinearLayout.HORIZONTAL
                v.addView(child, if (horizontal)
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                else
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            // textAlign:'center' — ширину по родителю, гравитация центрует.
            } else if (v is LinearLayout && child.tag == "jsr-text-center") {
                val vertical = v.orientation == LinearLayout.VERTICAL
                v.addView(child, if (vertical)
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                else
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
            } else if (v is ViewGroup) {
                // TextView-ноды (badge/chip) детей по контракту не принимают —
                // молча пропускаем вместо ClassCastException (регресс m3).
                (v as ViewGroup).addView(child)
            } else {
                android.util.Log.w("NativeRenderer",
                    "skip child attach: parent ${v.javaClass.simpleName} is not a ViewGroup")
            }
        },
    )

    private fun attach(view: View, child: View) {
        handlerOf(view)?.invoke(view, child)
    }

    private fun handlerOf(view: View): ((View, View) -> Unit)? {
        attachEntry(view)?.let { return it }
        return ATTACH[ViewGroup::class.java]
    }

    /**
     * Точное совпадение класса, КРОМЕ ScrollView: центр FrameLayout не
     * применяется к его подклассам (stack/padding — FillLayout без
     * центрирования), а ScrollColumn (scroll и listView) обязан
     * прикреплять детей во внутреннюю колонку, как plain ScrollView.
     */
    private fun attachEntry(view: View): ((View, View) -> Unit)? {
        if (view is ScrollView) return ATTACH[ScrollView::class.java]
        return ATTACH[view.javaClass]
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
        val add = NodeProps.pxOf(node.props, "spacing") * index.coerceAtMost(1)
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
        val m = NodeProps.boxInset(child.props, "margin")
        lp.setMargins(m[0], m[1], m[2], m[3])
        childView.layoutParams = lp
    }

    companion object {
        private const val TAG = "NativeRenderer"
    }
}
