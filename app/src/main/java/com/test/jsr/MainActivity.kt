package com.test.jsr

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
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
import java.io.File
import java.util.UUID

class MainActivity : Activity(), HostSinks, NativeRenderer.EventSink {
    private lateinit var engine: JsWidgetRuntime
    private lateinit var runtime: WidgetRuntime
    private lateinit var timers: Timers
    private lateinit var handlers: ChannelHandlers
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
        timers = Timers(engine)
        handlers = ChannelHandlers(
            engine, sinks = this, services = Services(
                storage = AndroidHostServices.storage(this),
                secrets = AndroidHostServices.secrets(this),
                http = AndroidHostServices.http(),
                assets = AndroidHostServices.assets(this),
                processRunner = AndroidHostServices.processRunner(),
                openUrl = ActivityOpenUrl(this),
                capture = ViewCapture({ root }, cacheDir),
            ),
            timers = timers,
            capabilities = AndroidHostServices.declaredCapabilities(this),
        )
        runtime = WidgetRuntime(engine, handlers::dispatch)
        engine.messageSink = runtime::onJsMessage
        engine.start()
        loadWidget("counter.js")
    }

    override fun onDestroy() {
        timers.dispose()
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

    // ---------- HostSinks: fire-and-forget каналы ----------

    override fun onRender(tree: JSONObject) {
        runOnUiThread {
            try {
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
    }

    override fun onTitle(title: String) {
        this.title = title
    }

    override fun onLog(line: String) {
        Log.i("jsr", line)
    }

    override fun onExportState(state: JSONObject) {
        Log.i(TAG, "exportState: $state")
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

/** Снимок корневого view в PNG в кеше приложения (канал __jsr_capture). */
class ViewCapture(private val view: () -> View, private val dir: File) : CaptureService {
    override fun capture(): CaptureResult? = captureView(view(), dir)
}

internal fun captureView(view: View?, dir: File): CaptureResult? {
    view ?: return null
    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    view.draw(Canvas(bitmap))
    dir.mkdirs()
    val file = File(dir, "jsr-capture-${System.currentTimeMillis()}.png")
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return CaptureResult(file.absolutePath, bitmap.width, bitmap.height)
}
