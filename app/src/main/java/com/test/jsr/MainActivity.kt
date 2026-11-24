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

class MainActivity : Activity(), JsWidgetRuntime.Listener, NativeRenderer.EventSink {
    private lateinit var runtime: JsWidgetRuntime
    private lateinit var root: FrameLayout

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

        runtime = JsWidgetRuntime(this)
        runtime.timerHandler = { channel, payload ->
            when (channel) {
                "__jsr_set_interval" -> runtime.scheduleTimer(payload) {
                    // Тик таймера: напрямую зовём зарегистрированную JS-функцию
                    val id = JSONObject(payload).optString("id")
                    val err = runtime.eval(
                        "var __f=jsr._timers['$id']; if(__f) __f();")
                    if (err != null) Log.e(TAG, "timer $id: $err")
                }
                "__jsr_clear_interval" -> runtime.cancelTimer(
                    JSONObject(payload).optString("id"))
            }
        }
        runtime.start()
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
        val js = assets.open(file).bufferedReader().use { it.readText() }
        val full = BOOTSTRAP + "\n" + js
        val err = runtime.eval(full)
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
        val err = runtime.callEvent(actionId, payloadJson)
        if (err != null) Log.e(TAG, "event $actionId error: $err")
    }

    companion object {
        private const val TAG = "MainActivity"

        /**
         * Упрощённый bootstrap jsr (контракт flutter_js_widget_runtime):
         * render/setTitle/onEvent/log + таймеры. Fetch/exec/secrets — в POC
         * заглушены (POI Promise-разрешение требует моста ответов —
         * добавим при переносе в android_js_widget_runtime).
         */
        private val BOOTSTRAP = """
var __idCounter = 0;
var __nid = function(){ return 'i' + (++__idCounter); };

var console = {
  log: function(){ sendMessage('__jsr_log', Array.prototype.slice.call(arguments).join(' ')); },
  warn: function(){ sendMessage('__jsr_log', '[W] ' + Array.prototype.slice.call(arguments).join(' ')); },
  error: function(){ sendMessage('__jsr_log', '[E] ' + Array.prototype.slice.call(arguments).join(' ')); }
};

var setTimeout = function(fn, ms) {
  var id = __nid();
  sendMessage('__jsr_set_interval', '{"id":"' + id + '","ms":' + (ms || 0) + ',"once":true}');
  jsr._timers[id] = fn;
  return id;
};
var clearTimeout = function(id) {
  sendMessage('__jsr_clear_interval', String(id));
  delete jsr._timers[String(id)];
};
var setInterval = function(fn, ms) {
  var id = __nid();
  sendMessage('__jsr_set_interval', '{"id":"' + id + '","ms":' + (ms || 1000) + '}');
  jsr._timers[id] = fn;
  return id;
};
var clearInterval = clearTimeout;

var jsr = {
  _handler: null,
  _timers: {},
  render: function(tree){ sendMessage('__jsr_render', JSON.stringify(tree)); },
  setTitle: function(t){ sendMessage('__jsr_set_title', String(t)); },
  onEvent: function(fn){ jsr._handler = fn; }
};
"""
    }
}
