package com.test.jsr

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.util.UUID

/**
 * Галерея примеров fa_widgets (локальная сборка для прогона на GT-P7510):
 * список widget.js из assets/jsr/examples, тап — виджет на весь экран,
 * «←» — назад к списку. Движок пересоздаётся на каждое переключение:
 * dispose старого + guard в JsWidgetRuntime.eval гасит гонку
 * fetchJson-after-dispose (асинхронный ответ уходит в остановленный движок).
 */
class GalleryActivity : Activity(), HostSinks, NativeRenderer.EventSink {
    private lateinit var root: FrameLayout
    private lateinit var listScroll: ScrollView
    private lateinit var engine: JsWidgetRuntime
    private lateinit var runtime: WidgetRuntime
    private lateinit var timers: Timers
    private lateinit var handlers: ChannelHandlers
    private lateinit var renderer: NativeRenderer
    private var iid = ""
    private var current: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#0f172a"))
        setContentView(buildChrome())
        listScroll = buildList()
        root.addView(listScroll)

        val deep = intent.getStringExtra("widget")
        if (deep != null) openWidget(deep)
    }

    override fun onDestroy() {
        disposeEngine()
        super.onDestroy()
    }

    // ---------- список примеров ----------

    private fun examples(): List<String> =
        assets.list("jsr/examples").orEmpty().filter { it.endsWith(".js") }
            .map { it.removeSuffix(".js") }.sorted()

    private fun label(id: String): String = id.split('-').joinToString(" ") { p ->
        when (p) {
            "3d" -> "3D"; "m3" -> "M3"; "glb" -> "GLB"; "yolo" -> "YoLo"
            else -> p.replaceFirstChar { it.uppercase() }
        }
    }

    private fun buildList(): ScrollView {
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Color.parseColor("#0f172a"))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val pad = dp(16)
        col.setPadding(pad, dp(24), pad, pad)

        val header = TextView(this).apply {
            text = "fa_widgets — примеры"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(20))
        }
        col.addView(header)

        for (id in examples()) {
            val b = Button(this).apply {
                text = label(id)
                isAllCaps = false
                textSize = 16f
                setOnClickListener { openWidget(id) }
            }
            col.addView(b, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, dp(10))
            })
        }
        scroll.addView(col)
        return scroll
    }

    // ---------- каркас с кнопкой «←» ----------

    private fun buildChrome(): View {
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#1e293b"))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            visibility = View.GONE
        }
        val back = Button(this).apply {
            text = "←"
            textSize = 18f
            setOnClickListener { showList() }
        }
        bar.addView(back, LinearLayout.LayoutParams(dp(70), ViewGroup.LayoutParams.WRAP_CONTENT))
        val title = TextView(this).apply {
            id = View.generateViewId()
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(title, LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        outer.addView(bar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        outer.addView(root)
        return outer
    }

    private fun showList() {
        disposeEngine()
        current = null
        root.removeAllViews()
        if (!::listScroll.isInitialized) listScroll = buildList()
        root.addView(listScroll)
        (findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0) as? LinearLayout)
            ?.getChildAt(0)?.visibility = View.GONE
    }

    // ---------- загрузка виджета (свежий движок на каждый) ----------

    private fun openWidget(id: String) {
        disposeEngine()
        current = id
        root.removeAllViews()
        (findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0) as? LinearLayout)
            ?.getChildAt(0)?.visibility = View.VISIBLE

        engine = JsWidgetRuntime()
        renderer = NativeRenderer(this, this, root)
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
        iid = "android-" + UUID.randomUUID().toString().substring(0, 8)
        engine.start()

        val runtimeJs = assets.open("jsr/bootstrap-runtime.js").bufferedReader().use { it.readText() }
        val widgetJs = assets.open("jsr/bootstrap-widget.js").bufferedReader().use { it.readText() }
        val widget = assets.open("jsr/examples/$id.js").bufferedReader().use { it.readText() }
        val err = runtime.boot(iid, runtimeJs, widgetJs, widget)
        if (err != null) {
            Log.e(TAG, "eval error: $err")
            showError(err)
        } else {
            // Локальная сборка (GT-P7510): сообщить JS-стороне реальный
            // viewport — иначе jsr.breakpoint() видит 0 и выбирает compact,
            // а рендер по ширине выбирает expanded (рассинхрон веток).
            val dm = resources.displayMetrics
            val wDp = (dm.widthPixels / dm.density).toInt()
            val hDp = (dm.heightPixels / dm.density).toInt()
            val vErr = engine.eval(
                "__jsrHostEvent('viewport', {width: $wDp, height: $hDp});", "viewport")
            if (vErr != null) Log.e(TAG, "viewport: $vErr")
        }
    }

    private fun disposeEngine() {
        if (!::engine.isInitialized) return
        try { timers.dispose() } catch (_: Exception) {}
        try { runtime.dispose() } catch (_: Exception) {}
    }

    private fun showError(message: String) {
        root.removeAllViews()
        val tv = TextView(this)
        tv.text = "JS error:\n$message"
        tv.setTextColor(Color.RED)
        tv.textSize = 14f
        tv.setPadding(dp(24), dp(24), dp(24), dp(24))
        root.addView(tv, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------- HostSinks ----------

    override fun onRender(tree: JSONObject) {
        runOnUiThread {
            try {
                // Локальная сборка (GT-P7510): adaptive-нода — выбор ветки по
                // ширине экрана (compact < 600dp), до normalize. Контракт
                // обещает выбор без JS round trip.
                renderer.render(JsWidgetTree.normalize(resolveAdaptive(tree)))
            } catch (e: Exception) {
                Log.e(TAG, "render error: $e", e)
                showError("render: $e")
            }
        }
    }

    private fun resolveAdaptive(o: JSONObject): JSONObject {
        if (o.optString("type") != "adaptive") {
            // Рекурсия по обоим формамpayload: child (одиночный) и children.
            o.optJSONObject("child")?.let { o.put("child", resolveAdaptive(it)) }
            val children = o.optJSONArray("children")
            if (children != null) {
                for (i in 0 until children.length()) {
                    val c = children.optJSONObject(i) ?: continue
                    children.put(i, resolveAdaptive(c))
                }
            }
            return o
        }
        val dm = resources.displayMetrics
        val widthDp = dm.widthPixels / dm.density
        val branch = if (widthDp < 600) o.optJSONObject("compact") else o.optJSONObject("expanded")
        return if (branch != null) resolveAdaptive(branch)
        else org.json.JSONObject("""{"type":"sizedBox"}""")
    }

    override fun onTitle(title: String) {
        this.title = title
    }

    override fun onLog(line: String) {
        Log.i("jsr-gallery", line)
    }

    override fun onExportState(state: JSONObject) {
        Log.i(TAG, "exportState: $state")
    }

    // ---------- NativeRenderer.EventSink ----------

    override fun onAction(actionId: String, payloadJson: String) {
        if (!::engine.isInitialized) return
        val err = engine.callEvent(actionId, payloadJson)
        if (err != null) Log.e(TAG, "event $actionId error: $err")
    }

    companion object {
        private const val TAG = "GalleryActivity"
    }
}
