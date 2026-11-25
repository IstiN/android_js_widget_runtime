package com.test.jsr

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.util.UUID

class MainActivity : Activity(), WidgetRuntime.Listener, NativeRenderer.EventSink {
    private lateinit var engine: JsWidgetRuntime
    private lateinit var runtime: WidgetRuntime
    private lateinit var root: FrameLayout

    /** Метка экземпляра движка; __IID помечает исходящие payload (план 1.1). */
    private val iid = "android-" + UUID.randomUUID().toString().substring(0, 8)

    private val widgets = listOf(
        "hello.js" to "Hello",
        "counter.js" to "Counter",
        "scroll_list.js" to "List",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#0f172a"))
        setContentView(buildChrome())

        engine = JsWidgetRuntime()
        runtime = WidgetRuntime(engine, this)
        engine.messageSink = runtime::onJsMessage
        engine.start()
        loadWidget("counter.js")
    }

    override fun onDestroy() {
        runtime.dispose()
        super.onDestroy()
    }

    // ---------- chrome: верхняя панель с кнопками выбора виджета ----------

    private fun buildChrome(): View {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#1e293b"))
            setPadding(12, 12, 12, 12)
        }
        for ((file, label) in widgets) {
            val b = Button(this).apply {
                text = label
                isAllCaps = false
                textSize = 14f
                setOnClickListener { loadWidget(file) }
            }
            bar.addView(b, LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        outer.addView(bar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))
        root.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        outer.addView(root)
        return outer
    }

    // ---------- загрузка виджета ----------

    private fun loadWidget(file: String) {
        root.removeAllViews()
        // Порядок контракта: runtime-половина, widget-половина, файл виджета
        // (__IID ставит WidgetRuntime.boot первым).
        val runtimeJs = assets.open("jsr/bootstrap-runtime.js").bufferedReader().use { it.readText() }
        val widgetJs = assets.open("jsr/bootstrap-widget.js").bufferedReader().use { it.readText() }
        val widget = assets.open(file).bufferedReader().use { it.readText() }
        val err = runtime.boot(iid, runtimeJs, widgetJs, widget)
        if (err != null) {
            Log.e(TAG, "eval error: $err")
            showError(err)
        }
    }

    private fun showError(message: String) {
        root.removeAllViews()
        val tv = TextView(this)
        tv.text = "JS error:\n$message"
        tv.setTextColor(Color.RED)
        tv.textSize = 14f
        tv.setPadding(32, 32, 32, 32)
        root.addView(tv, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    // ---------- JsWidgetRuntime.Listener: каналы от JS ----------

    override fun onJsMessage(channel: String, payload: String) {
        when (channel) {
            "__jsr_set_interval" -> engine.scheduleTimer(payload) {
                // Тик таймера: зовём глобал __jsrInterval(id) из bootstrap-runtime.
                val id = JSONObject(payload).optString("id")
                val err = engine.eval("__jsrInterval('$id');", "timer")
                if (err != null) Log.e(TAG, "timer $id: $err")
            }
            "__jsr_clear_interval" -> engine.cancelTimer(
                JSONObject(payload).optString("id"))
            "__jsr_render" -> runOnUiThread {
                Log.i(TAG, "render: rebuilding root")
                try {
                    val tree = JSONObject(payload)
                    val view = NativeRenderer.build(tree, this, this)
                    root.removeAllViews()
                    root.addView(view, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT))
                } catch (e: Exception) {
                    Log.e(TAG, "render error: $e")
                    showError("render: $e")
                }
            }
            "__jsr_set_title" -> title = payload
            "__jsr_log" -> Log.i("jsr", payload)
            else -> Log.d(TAG, "channel $channel: $payload")
        }
    }

    // ---------- NativeRenderer.EventSink: тапы из нативных View ----------

    override fun onAction(actionId: String, payloadJson: String) {
        Log.i(TAG, "onAction: $actionId")
        val err = engine.callEvent(actionId, payloadJson)
        if (err != null) Log.e(TAG, "event $actionId error: $err")
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
