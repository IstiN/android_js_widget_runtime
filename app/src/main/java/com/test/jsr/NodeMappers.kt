package com.test.jsr

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import org.json.JSONObject

/**
 * Per-category mappers (plan 6.1 A3, issue #4): every registered node type of
 * the inventory resolves through the registry map — no type `when` chain.
 * A type is either mapped to a real view, or a host-contract container
 * (webView, three, scene3d, voxel, map, video, audio family), or falls back
 * to the reference fallback card. Status vocabulary for docs/node-support.json:
 * `yes` | `partial` with reason `host contract` | `partial` with reason `fallback`.
 *
 * View-side lambdas run only under Robolectric (invisible to JaCoCo), so each
 * stays at complexity <= 2. Prop math lives in NodeProps (JVM-tested).
 */
internal interface NodeMapper {
    /** node type -> fresh root View. */
    val factories: Map<String, (Context) -> View>

    /** node type -> mount-time prop application; runs before children attach. */
    val configure: Map<String, (View, JsNode, NativeRenderer.EventSink) -> Unit>
}

/** FrameLayout without the centered child attach of plain FrameLayout. */
internal class FillLayout(context: Context) : FrameLayout(context)

/** Container for a host surface (WebView, GL texture, video player...). */
class HostContractView(context: Context) : FrameLayout(context) {
    /** The host application fills this slot with its platform surface. */
    val slot: FrameLayout = FrameLayout(context)

    init {
        addView(
            slot,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }
}

internal object LayoutMapper : NodeMapper {
    override val factories = mapOf(
        "center" to { c: Context -> FrameLayout(c) },
        "column" to { c: Context -> LinearLayout(c).apply { orientation = LinearLayout.VERTICAL } },
        "row" to { c: Context -> LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL } },
        "stack" to { c: Context -> FillLayout(c) },
        "padding" to { c: Context -> FillLayout(c) },
        "sizedBox" to { c: Context -> FillLayout(c) },
        "spacer" to { c: Context -> View(c) },
        "absoluteFill" to { c: Context -> FillLayout(c) },
        "fill" to { c: Context -> FillLayout(c) },
        "container" to { c: Context -> LinearLayout(c).apply { orientation = LinearLayout.VERTICAL } },
        "card" to { c: Context -> LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = roundDrawable("#FFFFFF", dpF(12f))
            // setElevation — API 21; ниже карта без тени (issue #6).
            if (Build.VERSION.SDK_INT >= 21) elevation = dpF(2f)
        } },
    )

    override val configure = mapOf(
        "sizedBox" to { v: View, n: JsNode, _: NativeRenderer.EventSink ->
            v.layoutParams = ViewGroup.LayoutParams(NodeProps.pxOf(n.props, "width"), NodeProps.pxOf(n.props, "height"))
        },
        "padding" to { v: View, n: JsNode, _: NativeRenderer.EventSink ->
            val pad = NodeProps.pxOf(n.props, "padding")
            if (pad > 0) v.setPadding(pad, pad, pad, pad)
        },
        "absoluteFill" to { v: View, n: JsNode, _: NativeRenderer.EventSink -> fillBackground(v, n.props) },
        "fill" to { v: View, n: JsNode, _: NativeRenderer.EventSink -> fillBackground(v, n.props) },
        "container" to { v: View, n: JsNode, _: NativeRenderer.EventSink ->
            val color = NodeProps.decorationColor(n.props)
            if (color.isNotEmpty()) {
                v.background = roundDrawable(color, NodeProps.radiusPx(n.props))
            }
            val pad = NodeProps.pxOf(n.props, "padding")
            if (pad > 0) v.setPadding(pad, pad, pad, pad)
        },
        "card" to { v: View, n: JsNode, _: NativeRenderer.EventSink ->
            val color = NodeProps.decorationColor(n.props).ifEmpty { n.props.optString("color") }
            if (color.isNotEmpty()) roundOf(v.background)?.setColor(parseColor(color))
            val pad = NodeProps.pxOf(n.props, "padding")
            if (pad > 0) v.setPadding(pad, pad, pad, pad)
        },
    )
}

internal object TextMapper : NodeMapper {
    override val factories = mapOf(
        "text" to { c: Context -> TextView(c) },
        "textField" to { c: Context -> EditText(c) },
        "textArea" to { c: Context -> EditText(c) },
        "badge" to { c: Context -> labeledBox(c) },
        "chip" to { c: Context -> TextView(c).apply {
            background = roundDrawable("#E2E8F0", dpF(999f))
            setPadding(dpI(12f), dpI(4f), dpI(12f), dpI(4f))
        } },
        "divider" to { c: Context -> View(c) },
        "circularProgressIndicator" to { c: Context -> ProgressBar(c) },
        "linearProgressIndicator" to { c: Context -> ProgressBar(c) },
    )

    override val configure = mapOf(
        "text" to { v: View, n: JsNode, _: NativeRenderer.EventSink ->
            val tv = v as TextView
            tv.text = NodeProps.transformText(n.props)
            val style = n.props.optDouble("fontSize", -1.0)
            if (style > 0) tv.textSize = NodeProps.dp(style.toFloat()).toFloat()
            val color = n.props.optString("color")
            if (color.isNotEmpty()) tv.setTextColor(parseColor(color))
            val bold = n.props.optString("fontWeight") in BOLD_WEIGHT_SET
            tv.setTypeface(null, if (bold) Typeface.BOLD else Typeface.NORMAL)
        },
        "textField" to { v: View, n: JsNode, sink: NativeRenderer.EventSink ->
            val input = v as EditText
            input.hint = n.props.optString("hint")
            setTextSilently(input, n.props.optString("initialValue"))
            bindTextEvents(input, n, sink)
        },
        "textArea" to { v: View, n: JsNode, sink: NativeRenderer.EventSink ->
            val input = v as EditText
            input.hint = n.props.optString("hint")
            setTextSilently(input, n.props.optString("initialValue"))
            input.setSingleLine(false)
            input.minLines = 3
            bindTextEvents(input, n, sink)
        },
        "badge" to { v: View, n: JsNode, _: NativeRenderer.EventSink ->
            labelAt(v as ViewGroup, 0).text = NodeProps.textOf(n.props)
            val bg = n.props.optString("backgroundColor")
            if (bg.isNotEmpty()) labelAt(v as ViewGroup, 0).setBackgroundColor(parseColor(bg))
        },
        "chip" to { v: View, n: JsNode, sink: NativeRenderer.EventSink ->
            (v as TextView).text = NodeProps.textOf(n.props)
            tapEvents(v, n, sink, "onTap")
        },
        "divider" to { v: View, n: JsNode, _: NativeRenderer.EventSink ->
            val thickness = NodeProps.pxOf(n.props, "thickness").coerceAtLeast(1)
            val lp = v.layoutParams ?: ViewGroup.LayoutParams(-2, thickness)
            lp.height = thickness
            v.layoutParams = lp
            val color = n.props.optString("color")
            v.setBackgroundColor(if (color.isEmpty()) parseColor("#E2E8F0") else parseColor(color))
        },
        "circularProgressIndicator" to { v: View, _: JsNode, _: NativeRenderer.EventSink ->
            (v as ProgressBar).isIndeterminate = true
        },
        "linearProgressIndicator" to { v: View, n: JsNode, _: NativeRenderer.EventSink ->
            val p = v as ProgressBar
            p.isIndeterminate = false
            p.max = 100
            p.progress = NodeProps.unitProgress(n.props)
        },
    )
}

internal object InputMapper : NodeMapper {
    override val factories = mapOf(
        "button" to { c: Context -> button(c) },
        "textButton" to { c: Context -> button(c) },
        "outlinedButton" to { c: Context -> button(c) },
        "fab" to { c: Context -> button(c).apply {
            background = roundDrawable("#6366F1", dpF(28f))
            setTextColor(Color.WHITE)
        } },
        "checkbox" to { c: Context -> CheckBox(c) },
        "switch" to { c: Context -> Switch(c) },
        "slider" to { c: Context -> SeekBar(c) },
        "dropdown" to { c: Context -> Spinner(c) },
        "radio" to { c: Context -> RadioButton(c) },
        "gestureDetector" to { c: Context -> FillLayout(c).apply { isClickable = true } },
        "inkWell" to { c: Context -> FillLayout(c).apply { isClickable = true } },
    )

    override val configure = mapOf(
        "button" to { v: View, n: JsNode, sink: NativeRenderer.EventSink ->
            (v as Button).text = NodeProps.textOf(n.props)
            tapEvents(v, n, sink, "onTap", "onPressed", "onPress")
        },
        "textButton" to { v: View, n: JsNode, sink: NativeRenderer.EventSink ->
            (v as Button).text = NodeProps.textOf(n.props)
            tapEvents(v, n, sink, "onTap", "onPressed", "onPress")
        },
        "outlinedButton" to { v: View, n: JsNode, sink: NativeRenderer.EventSink ->
            (v as Button).text = NodeProps.textOf(n.props)
            tapEvents(v, n, sink, "onTap", "onPressed", "onPress")
        },
        "fab" to { v: View, n: JsNode, sink: NativeRenderer.EventSink ->
            (v as Button).text = NodeProps.textOf(n.props)
            tapEvents(v, n, sink, "onTap", "onPressed", "onPress")
        },
        "checkbox" to { v: View, n: JsNode, sink: NativeRenderer.EventSink ->
            val cb = v as CheckBox
            cb.emitSuppressed = true
            cb.isChecked = n.props.optBoolean("value", false)
            cb.emitSuppressed = false
            cb.text = NodeProps.textOf(n.props)
            val action = changeAction(n)
            if (action != null) cb.setOnCheckedChangeListener { _, checked ->
                if (!cb.emitSuppressed) sink.onAction(action, NodeProps.valuePayload(checked))
            }
        },
        "switch" to { v: View, n: JsNode, sink: NativeRenderer.EventSink ->
            val sw = v as Switch
            sw.emitSuppressed = true
            sw.isChecked = n.props.optBoolean("value", false)
            sw.emitSuppressed = false
            sw.text = NodeProps.textOf(n.props)
            val action = changeAction(n)
            if (action != null) sw.setOnCheckedChangeListener { _, checked ->
                if (!sw.emitSuppressed) sink.onAction(action, NodeProps.valuePayload(checked))
            }
        },
        "slider" to { v: View, n: JsNode, sink: NativeRenderer.EventSink ->
            val bar = v as SeekBar
            bar.max = 100
            bar.emitSuppressed = true
            bar.progress = NodeProps.sliderProgress(n.props)
            bar.emitSuppressed = false
            val action = changeAction(n)
            if (action != null) bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seek: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) emitSlider(seek, progress, action, sink)
                }

                override fun onStartTrackingTouch(seek: SeekBar) = Unit

                override fun onStopTrackingTouch(seek: SeekBar) = Unit
            })
        },
        "dropdown" to { v: View, n: JsNode, sink: NativeRenderer.EventSink ->
            val items = NodeProps.itemsOf(n.props)
            if (items.isNotEmpty()) {
                val spinner = v as Spinner
                spinner.adapter = ArrayAdapter(
                    v.context, android.R.layout.simple_spinner_dropdown_item, items)
                spinner.setSelection(NodeProps.indexOfItem(items, n.props.optString("value")))
                bindDropdownEvents(spinner, items, n, sink)
            }
        },
        "radio" to { v: View, n: JsNode, sink: NativeRenderer.EventSink ->
            val rb = v as RadioButton
            rb.text = NodeProps.textOf(n.props)
            rb.emitSuppressed = true
            rb.isChecked = NodeProps.radioChecked(n.props)
            rb.emitSuppressed = false
            val action = changeAction(n)
            if (action != null) rb.setOnCheckedChangeListener { _, _ ->
                if (rb.emitSuppressed) return@setOnCheckedChangeListener
                // Референс шлёт value узла (не groupValue): {'value': value}.
                sink.onAction(action, NodeProps.valuePayload(n.props.optString("value")))
            }
        },
        "gestureDetector" to { v: View, n: JsNode, sink: NativeRenderer.EventSink ->
            tapEvents(v, n, sink, "onTap", "onPressed", "onPress")
        },
        "inkWell" to { v: View, n: JsNode, sink: NativeRenderer.EventSink ->
            tapEvents(v, n, sink, "onTap", "onPressed", "onPress")
        },
    )
}

internal object ListMapper : NodeMapper {
    override val factories = mapOf(
        "scroll" to { c: Context -> ScrollColumn(c) },
        "listView" to { c: Context -> ScrollColumn(c) },
        "listTile" to { c: Context -> LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(c))
            addView(TextView(c))
        } },
    )

    override val configure = mapOf(
        "listTile" to { v: View, n: JsNode, _: NativeRenderer.EventSink ->
            val g = v as ViewGroup
            labelAt(g, 0).text = NodeProps.tileTitle(n.props)
            labelAt(g, 1).text = NodeProps.tileSubtitle(n.props)
        },
    )
}

internal object MaterialMapper : NodeMapper {
    override val factories = mapOf(
        "appBar" to { c: Context -> bar(c) },
        "banner" to { c: Context -> LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; addView(TextView(c)) } },
        "popupMenu" to { c: Context -> TextView(c) },
        "tabBar" to { c: Context -> TextView(c) },
    )

    override val configure = mapOf(
        "appBar" to { v: View, n: JsNode, _: NativeRenderer.EventSink ->
            labelAt(v as ViewGroup, 0).text = n.props.optString("title")
            val color = n.props.optString("color")
            if (color.isNotEmpty()) v.setBackgroundColor(parseColor(color))
        },
        "banner" to { v: View, n: JsNode, _: NativeRenderer.EventSink ->
            labelAt(v as ViewGroup, 0).text = n.props.optString("message")
            val color = n.props.optString("backgroundColor")
            if (color.isNotEmpty()) v.setBackgroundColor(parseColor(color))
        },
        // Reference parity (flutter_js_widget_runtime#15, fixture l0_popup_menu):
        // the reference drops documented items and injects the default message;
        // the observed dump shape is kept until the reference is fixed.
        "popupMenu" to { v: View, n: JsNode, _: NativeRenderer.EventSink ->
            (v as TextView).text = NodeProps.textOf(n.props).ifEmpty { DEFAULT_POPUP_MESSAGE }
            if (n.props.optString("message").isEmpty()) n.props.put("message", DEFAULT_POPUP_MESSAGE)
        },
        // Reference parity (fixture l0_tab_bar): the dump live-reads the first
        // tab label as a dump-only data prop (tabBar has no scalar documented prop).
        "tabBar" to { v: View, n: JsNode, _: NativeRenderer.EventSink ->
            val first = NodeProps.firstTab(n.props)
            (v as TextView).text = first
            if (n.props.optString("data").isEmpty()) n.props.put("data", first)
        },
    )

    private const val DEFAULT_POPUP_MESSAGE = "Show menu"
}

internal object EffectsMapper : NodeMapper {
    override val factories = mapOf(
        "opacity" to { c: Context -> FillLayout(c) },
    )

    override val configure = mapOf(
        "opacity" to { v: View, n: JsNode, _: NativeRenderer.EventSink ->
            v.alpha = NodeProps.clampedAlpha(n.props)
        },
    )
}

internal object DrawMapper : NodeMapper {
    override val factories = mapOf(
        "rect" to { c: Context -> View(c) },
        "circle" to { c: Context -> View(c) },
    )

    override val configure = mapOf(
        "rect" to { v: View, n: JsNode, _: NativeRenderer.EventSink ->
            val radius = NodeProps.pxOf(n.props, "radius").toFloat()
            v.background = roundDrawable(fillOr(n.props, "#6366F1"), radius)
            sizeOf(v, n.props, "width", "height")
        },
        "circle" to { v: View, n: JsNode, _: NativeRenderer.EventSink ->
            val size = NodeProps.pxOf(n.props, "size").coerceAtLeast(1)
            v.background = roundDrawable(fillOr(n.props, "#6366F1"), size / 2f)
            v.layoutParams = ViewGroup.LayoutParams(size, size)
        },
    )
}

/** Host-contract types: container + slot; the host fills the slot (plan 6.1 A3). */
internal object HostContractMapper : NodeMapper {
    val types = setOf("webView", "three", "scene3d", "voxel", "map", "video", "audio", "audio_player")

    override val factories = mapOf(
        "webView" to { c: Context -> HostContractView(c) },
        "three" to { c: Context -> HostContractView(c) },
        "scene3d" to { c: Context -> HostContractView(c) },
        "voxel" to { c: Context -> HostContractView(c) },
        "map" to { c: Context -> HostContractView(c) },
        "video" to { c: Context -> HostContractView(c) },
        "audio" to { c: Context -> HostContractView(c) },
        "audio_player" to { c: Context -> HostContractView(c) },
    )

    override val configure: Map<String, (View, JsNode, NativeRenderer.EventSink) -> Unit> = emptyMap()
}

/** Registry: every node type of the inventory resolves here (no renderer `when`). */
internal object NodeMapperRegistry {
    val mappers: List<NodeMapper> = listOf(
        LayoutMapper, TextMapper, InputMapper, ListMapper, MaterialMapper,
        EffectsMapper, DrawMapper, HostContractMapper,
    )

    /** Merged factory map; the registry test asserts the per-type keys are disjoint. */
    val factories: Map<String, (Context) -> View> =
        mappers.flatMap { it.factories.toList() }.toMap()

    val configure: Map<String, (View, JsNode, NativeRenderer.EventSink) -> Unit> =
        mappers.flatMap { it.configure.toList() }.toMap()

    /** Mapped views that only reproduce the reference's observed dump shape. */
    private val REFERENCE_PARITY = setOf("popupMenu", "tabBar")

    /** Support row of a node type: yes | partial with reason (plan 6.1 A3). */
    fun statusOf(type: String): Pair<String, String?> = when {
        // Host-contract types have factories too; the contract wins the row.
        type in HostContractMapper.types -> "partial" to "host contract"
        type in REFERENCE_PARITY -> "partial" to "reference parity"
        factories.containsKey(type) -> "yes" to null
        else -> "partial" to "fallback"
    }
}

// ---------- view-side helpers (Robolectric-only; complexity <= 2) ----------

private val BOLD_WEIGHT_SET = setOf("bold", "w600", "w700", "w800")

/** Tappable wrapper: first present event fires sink.onAction(action, payload). */
private fun tapEvents(
    view: View,
    node: JsNode,
    sink: NativeRenderer.EventSink,
    vararg keys: String,
) {
    val action = NodeProps.firstEvent(node.events, *keys) ?: return
    view.setOnClickListener { sink.onAction(action, NodeProps.payloadOf(node)) }
}

// ---------- input family: реальные слушатели пользователя (review NodeMappers.kt:210) ----------

/**
 * Глушение событий слушателей при программной настройке view: patch-путь
 * пере-применяет mapper-конфигурацию, драйвер делает setText + собственный
 * dispatch — ни тот, ни другой не должны эмитить onChange (референс не шлёт
 * onChanged от rebuild/controller.text).
 */
internal var View.emitSuppressed: Boolean
    get() = getTag(R.id.jsr_emit_silent) == true
    set(value) = setTag(R.id.jsr_emit_silent, value)

/** setText без события (TextField.onChanged референса не срабатывает от rebuild). */
internal fun setTextSilently(input: EditText, text: String) {
    input.emitSuppressed = true
    input.setText(text)
    input.emitSuppressed = false
}

/** Action id семейства изменений (референс _changeHandler): onChange ?? onChanged ?? onTap. */
private fun changeAction(n: JsNode): String? =
    NodeProps.firstEvent(n.events, "onChange", "onChanged", "onTap")

/** Action id клавиатурного done текстовых узлов. */
private fun submitAction(n: JsNode): String? =
    NodeProps.firstEvent(n.events, "onSubmit", "onSubmitted")

/** Программный drag ответа не шлёт (сравни референс: Slider.onChanged только от пользователя). */
private fun emitSlider(seek: SeekBar, progress: Int, action: String, sink: NativeRenderer.EventSink) {
    if (seek.emitSuppressed) return
    sink.onAction(action, NodeProps.valuePayload(progress / 100.0))
}

/** TextWatcher -> onChange(value); IME done -> onSubmit(value); события из node.events. */
private fun bindTextEvents(input: EditText, node: JsNode, sink: NativeRenderer.EventSink) {
    if (!hasTextEvents(node)) return
    bindTextWatcher(input, node, sink)
    bindSubmit(input, node, sink)
}

private fun hasTextEvents(node: JsNode): Boolean =
    changeAction(node) != null || submitAction(node) != null

private fun bindTextWatcher(input: EditText, node: JsNode, sink: NativeRenderer.EventSink) {
    val change = changeAction(node) ?: return
    input.addTextChangedListener(object : TextWatcher {
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            emitTextChange(input, s, change, sink)
        }

        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

        override fun afterTextChanged(s: Editable?) = Unit
    })
}

private fun bindSubmit(input: EditText, node: JsNode, sink: NativeRenderer.EventSink) {
    val submit = submitAction(node) ?: return
    input.setOnEditorActionListener { _, actionId, event ->
        textSubmitted(input, submit, actionId, event, sink)
    }
}

private fun emitTextChange(input: EditText, s: CharSequence?, change: String, sink: NativeRenderer.EventSink) {
    if (input.emitSuppressed) return
    sink.onAction(change, NodeProps.valuePayload(textOrEmpty(s)))
}

private fun textOrEmpty(s: CharSequence?): String {
    s ?: return ""
    return s.toString()
}

private fun textSubmitted(
    input: EditText,
    submit: String,
    actionId: Int,
    event: KeyEvent?,
    sink: NativeRenderer.EventSink,
): Boolean {
    if (!isDoneAction(actionId, event)) return false
    sink.onAction(submit, NodeProps.valuePayload(input.text.toString()))
    return true
}

private fun isDoneAction(actionId: Int, event: KeyEvent?): Boolean =
    actionId == EditorInfo.IME_ACTION_DONE || event?.keyCode == KeyEvent.KEYCODE_ENTER

/** Выбор пункта Spinner -> onChange(item); начальный колбэк после setSelection глушится (position == last). */
private fun bindDropdownEvents(
    spinner: Spinner,
    items: List<String>,
    node: JsNode,
    sink: NativeRenderer.EventSink,
) {
    val action = changeAction(node) ?: return
    spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        private var last = spinner.selectedItemPosition

        override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
            if (position == last) return
            last = position
            sink.onAction(action, NodeProps.valuePayload(items[position]))
        }

        override fun onNothingSelected(parent: AdapterView<*>) = Unit
    }
}

/** TextView child at index (factories put labels before node children attach). */
private fun labelAt(group: ViewGroup, index: Int): TextView = group.getChildAt(index) as TextView

private fun button(c: Context): Button = Button(c).apply { isAllCaps = false }

/**
 * Vertical scrolling column (scroll and listView). Публичное чтение
 * защищённых scroll-метрик — AUI dump (A3) читает ровно
 * computeVerticalScrollOffset / computeVerticalScrollRange.
 */
class ScrollColumn(context: Context) : ScrollView(context) {
    init {
        addView(
            LinearLayout(context).apply { orientation = LinearLayout.VERTICAL },
            ViewGroup.LayoutParams(-1, -2),
        )
    }

    fun scrollOffsetPx(): Int = computeVerticalScrollOffset()

    fun scrollRangePx(): Int = computeVerticalScrollRange()
}

private fun bar(c: Context): LinearLayout = LinearLayout(c).apply {
    orientation = LinearLayout.HORIZONTAL
    setPadding(dpI(16f), dpI(8f), dpI(16f), dpI(8f))
    addView(TextView(c))
}

/** Badge box: a small label overlay at the end; node children attach after it. */
private fun labeledBox(c: Context): FillLayout = FillLayout(c).apply {
    val tv = TextView(c)
    tv.textSize = 10f
    addView(tv, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.BOTTOM))
}

private fun fillBackground(v: View, props: JSONObject) {
    val color = props.optString("color")
    if (color.isNotEmpty()) v.setBackgroundColor(parseColor(color))
}

private fun sizeOf(v: View, props: JSONObject, wKey: String, hKey: String) {
    val w = NodeProps.pxOf(props, wKey)
    val h = NodeProps.pxOf(props, hKey)
    if (NodeProps.hasSize(w, h)) v.layoutParams = ViewGroup.LayoutParams(w.coerceAtLeast(1), h.coerceAtLeast(1))
}

private fun roundOf(background: android.graphics.drawable.Drawable?): GradientDrawable? =
    background as? GradientDrawable

private fun roundDrawable(hex: String, radiusPx: Float): GradientDrawable =
    GradientDrawable().apply {
        setColor(parseColor(hex))
        cornerRadius = radiusPx.coerceAtLeast(0f)
    }

private fun fillOr(props: JSONObject, defaultHex: String): String {
    val fill = props.optString("fill")
    if (fill.isEmpty()) return defaultHex
    return fill
}

private fun parseColor(s: String): Int = try {
    Color.parseColor(s)
} catch (e: IllegalArgumentException) {
    Color.TRANSPARENT
}

private fun dpI(d: Float): Int = (d * 1.5f).toInt()

private fun dpF(d: Float): Float = d * 1.5f
