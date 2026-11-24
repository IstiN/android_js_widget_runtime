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
 * POC-рендерер: JSON-дерево jsr -> нативные Android Views.
 *
 * Поддержанные ноды (подмножество контракта flutter_js_widget_runtime):
 *   center, column, row, text, textButton, button, textField, sizedBox,
 *   padding, container, card, scroll, listView, divider, spacer
 *
 * POC-подход: каждый jsr.render пересобирает корень. Нативный рендер
 * кеширует display lists айтемов, поэтому скролл даже на Tegra 2 плавный.
 */
object NativeRenderer {

    interface EventSink {
        fun onAction(actionId: String, payloadJson: String = "{}")
    }

    fun build(tree: JSONObject, context: Context, sink: EventSink): View =
        inflate(tree, context, sink)

    // ---------- служебное ----------

    private fun parseColor(s: String?, def: Int): Int {
        if (s.isNullOrEmpty()) return def
        return try {
            Color.parseColor(s)
        } catch (e: IllegalArgumentException) {
            def
        }
    }

    private fun dp(d: Double): Int = (d * 1.5f).toInt() // ~hdpi; для POC достаточно

    private val CONTAINER_TYPES =
        setOf("padding", "container", "card", "column", "row", "center")

    private fun applyExplicitSize(v: View, node: JSONObject) {
        val w = if (node.has("width")) dp(node.getDouble("width")) else null
        val h = if (node.has("height")) dp(node.getDouble("height")) else null
        if (w != null || h != null) {
            val lp = v.layoutParams ?: ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
            if (w != null) lp.width = w
            if (h != null) lp.height = h
            v.layoutParams = lp
        }
    }

    // ---------- inflate ----------

    fun inflate(node: JSONObject, ctx: Context, sink: EventSink): View {
        val type = node.optString("type")
        val childNode: JSONObject? = node.optJSONObject("child")

        val view: View = when (type) {
            "center" -> {
                val fl = FrameLayout(ctx)
                if (childNode != null) {
                    fl.addView(inflate(childNode, ctx, sink), FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER))
                }
                containerCommon(fl, node)
            }

            "column", "row" -> {
                val ll = LinearLayout(ctx)
                ll.orientation = if (type == "column") LinearLayout.VERTICAL
                else LinearLayout.HORIZONTAL
                when (node.optString("mainAxisAlignment")) {
                    "center" -> ll.gravity = Gravity.CENTER
                    "end", "flexEnd" ->
                        ll.gravity = if (type == "column") Gravity.BOTTOM else Gravity.END
                }
                fillLinear(ll, node, ctx, sink)
                containerCommon(ll, node)
            }

            "text" -> {
                val tv = TextView(ctx)
                tv.text = node.optString("data", node.optString("text", ""))
                val style = node.optJSONObject("style")
                val fs = style?.optDouble("fontSize", 14.0)
                    ?: node.optDouble("fontSize", 14.0)
                tv.textSize = fs.toFloat()
                val color = style?.optString("color") ?: node.optString("color")
                if (!color.isNullOrEmpty()) tv.setTextColor(parseColor(color, Color.BLACK))
                val fw = style?.optString("fontWeight") ?: node.optString("fontWeight")
                tv.setTypeface(null, when (fw) {
                    "bold", "w600", "w700", "w800" -> Typeface.BOLD
                    else -> Typeface.NORMAL
                })
                when (node.optString("textAlign")) {
                    "center" -> tv.gravity = Gravity.CENTER
                    "right", "end" -> tv.gravity = Gravity.END
                }
                tv
            }

            "textButton", "button" -> {
                val b = Button(ctx)
                b.text = node.optString("text", node.optString("data", "Button"))
                b.isAllCaps = false
                val action = node.optString("onTap", node.optString("onPressed", ""))
                if (action.isNotEmpty()) {
                    b.setOnClickListener { sink.onAction(action) }
                }
                b
            }

            "textField" -> {
                val et = EditText(ctx)
                et.hint = node.optString("hint")
                et.setText(node.optString("value", ""))
                et
            }

            "sizedBox" -> View(ctx)

            "padding" -> {
                val fl = FrameLayout(ctx)
                val pad = node.optDouble("all", 0.0)
                fl.setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
                if (childNode != null) {
                    fl.addView(inflate(childNode, ctx, sink), FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT))
                }
                containerCommon(fl, node)
            }

            "container" -> {
                val fl = FrameLayout(ctx)
                if (node.has("children")) {
                    // container с children — вертикальный стак (POC-семантика)
                    val ll = LinearLayout(ctx)
                    ll.orientation = LinearLayout.VERTICAL
                    fillLinear(ll, node, ctx, sink)
                    fl.addView(ll, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER))
                } else if (childNode != null) {
                    fl.addView(inflate(childNode, ctx, sink), FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER))
                }
                containerCommon(fl, node)
            }

            "card" -> {
                val ll = LinearLayout(ctx)
                ll.orientation = LinearLayout.VERTICAL
                ll.background = GradientDrawable().apply {
                    cornerRadius = dp(12.0).toFloat()
                    setColor(parseColor(node.optString("color", "#ffffff"), Color.WHITE))
                }
                ll.elevation = dp(2.0).toFloat()
                val pad = node.optDouble("padding", 12.0)
                ll.setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
                fillLinear(ll, node, ctx, sink)
                ll
            }

            "scroll", "listView" -> {
                val sv = ScrollView(ctx)
                sv.isVerticalScrollBarEnabled = true
                val ll = LinearLayout(ctx)
                ll.orientation = LinearLayout.VERTICAL
                fillLinear(ll, node, ctx, sink)
                if (childNode != null) ll.addView(inflate(childNode, ctx, sink))
                sv.addView(ll, ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT)
                sv
            }

            "divider" -> View(ctx).also { v ->
                v.setBackgroundColor(Color.parseColor("#33888888"))
                v.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1.0))
            }

            "spacer" -> View(ctx).also { v ->
                v.layoutParams = LinearLayout.LayoutParams(0,
                    if (node.has("height")) dp(node.getDouble("height"))
                    else ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            }

            else -> {
                Log.w("NativeRenderer", "неизвестная нода '$type' — пустая заглушка")
                View(ctx).also { v ->
                    v.layoutParams = ViewGroup.LayoutParams(0, 0)
                }
            }
        }

        // Общие пост-настройки корня ноды: margin/padding/размеры
        if (node.has("margin") && type != "sizedBox") {
            val m = dp(node.getDouble("margin"))
            val lp = view.layoutParams ?: ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
            if (lp is ViewGroup.MarginLayoutParams) {
                lp.setMargins(m, m, m, m)
                view.layoutParams = lp
            }
        }
        if (node.has("padding") && type !in CONTAINER_TYPES) {
            val p = dp(node.getDouble("padding"))
            view.setPadding(p, p, p, p)
        }
        if (type == "sizedBox") {
            val w = if (node.has("width")) dp(node.getDouble("width"))
            else ViewGroup.LayoutParams.WRAP_CONTENT
            val h = if (node.has("height")) dp(node.getDouble("height"))
            else ViewGroup.LayoutParams.WRAP_CONTENT
            view.layoutParams = ViewGroup.LayoutParams(w, h)
        } else if (type != "divider" && type != "spacer") {
            applyExplicitSize(view, node)
        }
        return view
    }

    private fun fillLinear(
        ll: LinearLayout,
        node: JSONObject,
        ctx: Context,
        sink: EventSink,
    ) {
        val children = node.optJSONArray("children") ?: return
        val spacing = if (node.has("spacing")) dp(node.getDouble("spacing")) else 0
        for (i in 0 until children.length()) {
            val child = children.optJSONObject(i) ?: continue
            val view = inflate(child, ctx, sink)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            if (child.has("margin")) {
                val m = dp(child.getDouble("margin"))
                lp.setMargins(m, m, m, m)
            }
            if (spacing > 0 && i > 0) {
                if (ll.orientation == LinearLayout.VERTICAL) lp.topMargin += spacing
                else lp.leftMargin += spacing
            }
            view.layoutParams = lp
            ll.addView(view)
        }
    }

    private fun containerCommon(v: View, node: JSONObject): View {
        val bg = node.optString("color", node.optString("backgroundColor"))
        if (!bg.isNullOrEmpty() && bg != "transparent") {
            val gd = GradientDrawable()
            gd.setColor(parseColor(bg, Color.TRANSPARENT))
            val r = node.optDouble("borderRadius", -1.0)
            if (r >= 0) gd.cornerRadius = dp(r).toFloat()
            v.background = gd
        }
        if (node.has("padding") && v !is TextView && v !is Button) {
            val p = dp(node.getDouble("padding"))
            v.setPadding(p, p, p, p)
        }
        return v
    }
}
