package com.test.jsr

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Раннер фикстур на устройстве (план 1.4, issue #1): один инструментальный
 * тест на фикстуру; result.json в дополнительный вывод AGP (connectedConformance
 * собирает glob connected_android_test_additional_output по подпапке conformance).
 *
 * Движок QuickJS живёт на main-потоке (JNI и Handler таймеров привязаны к
 * нему); действия драйвера исполняются runOnMainSync, между действиями —
 * waitForIdleSync (posts рендера и микрозадачи resolve).
 */
@RunWith(Parameterized::class)
class ConformanceRunner(private val fixtureId: String) {

    private val rendererBox = arrayOfNulls<NativeRenderer>(1)
    private val onActionBox = arrayOfNulls<(String, String) -> Unit>(1)
    private val onHostEventBox = arrayOfNulls<(String, String) -> Unit>(1)
    private val timersBox = arrayOfNulls<Timers>(1)
    private val engineBox = arrayOfNulls<JsWidgetRuntime>(1)

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun fixtures(): List<String> {
            val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
            val all = assets.list("conformance")!!.sorted()
            // Аргумент filter: список id через запятую (A2 гоняет три seed,
            // A3 убирает фильтр и гоняет все L0).
            val filter = InstrumentationRegistry.getArguments().getString("filter")
            return if (filter.isNullOrEmpty()) all else all.filter { it in filter.split(",") }
        }
    }

    /** Запись каналов хоста (HostSinks); всё вызывается с main-потока. */
    private class Recorder : HostSinks {
        val renders = mutableListOf<String>()
        val trace = mutableListOf<String>()
        val console = mutableListOf<String>()
        var title: String? = null
        var state: JSONObject? = null

        override fun onRender(tree: JSONObject) {
            renders.add(tree.toString())
            trace.add("render")
        }

        override fun onTitle(title: String) {
            this.title = title
            trace.add("set_title")
        }

        override fun onLog(line: String) {
            console.add(line)
        }

        override fun onExportState(state: JSONObject) {
            this.state = state
            trace.add("export_state")
        }
    }

    @Test
    fun fixture() {
        val instr = InstrumentationRegistry.getInstrumentation()
        val target = instr.targetContext
        val recorder = Recorder()

        val widget = asset(target, "conformance/$fixtureId/widget.js")
        val expected = JSONObject(asset(target, "conformance/$fixtureId/expected.json"))
        val runtimeJs = asset(target, "jsr/bootstrap-runtime.js")
        val widgetJs = asset(target, "jsr/bootstrap-widget.js")

        val bootError = arrayOfNulls<String>(1)

        ActivityScenario.launch(ConformanceActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.root.removeAllViews()
                val engine = JsWidgetRuntime()
                val onAction: (String, String) -> Unit = { actionId, payloadJson ->
                    engine.callEvent(actionId, payloadJson)
                }
                val onHostEvent: (String, String) -> Unit = { eventTarget, payloadJson ->
                    engine.eval("__jsrHostEvent(${JsStrings.quote(eventTarget)}, $payloadJson);", "host-event")
                }
                onActionBox[0] = onAction
                onHostEventBox[0] = onHostEvent
                val renderer = NativeRenderer(target, onAction, activity.root)
                val timers = Timers(engine)
                val handlers = ChannelHandlers(
                    engine,
                    sinks = recorder,
                    services = Services(
                        storage = InMemoryStorage(),
                        secrets = InMemorySecrets(),
                        http = AndroidHostServices.http(),
                        assets = FakeAssetLoader(),
                        processRunner = FakeProcessRunner(),
                    ),
                    timers = timers,
                    capabilities = setOf("NETWORK"),
                )
                val runtime = WidgetRuntime(engine, handlers::dispatch)
                engine.messageSink = runtime::onJsMessage
                engine.start()
                engineBox[0] = engine
                rendererBox[0] = renderer
                timersBox[0] = timers

                // Глобал мок-сервера для виджета (аргумент mockOrigin), затем
                // порядок контракта: __IID, runtime, widget, код виджета.
                val origin = InstrumentationRegistry.getArguments().getString("mockOrigin")
                val code = if (origin != null) {
                    "var __JSR_MOCK_ORIGIN__ = " + JsStrings.quote(origin) + ";\n" + widget
                } else {
                    widget
                }
                bootError[0] = runtime.boot("android-conformance", runtimeJs, widgetJs, code)
            }
            instr.waitForIdleSync()

            if (bootError[0] != null) {
                writeResult(target, fixtureId, recorder, null, bootError[0])
                disposeOnMain(instr, timersBox, engineBox)
                return
            }

            applyActions(instr, expected.optJSONArray("actions") ?: JSONArray(), rendererBox[0]!!)

            instr.waitForIdleSync()
            settle(400)
            instr.waitForIdleSync()

            val dumpBox = arrayOfNulls<JSONObject>(1)
            scenario.onActivity { activity ->
                val root = rendererBox[0]!!.rootNode()
                if (root != null) dumpBox[0] = AuiDump.dump(root, rendererBox[0]!!::viewOf)
            }
            writeResult(target, fixtureId, recorder, dumpBox[0], null)
            disposeOnMain(instr, timersBox, engineBox)
        }
    }

    /** Действия expected.json по порядку; между действиями — очередь main. */
    private fun applyActions(
        instr: android.app.Instrumentation,
        actions: JSONArray,
        renderer: NativeRenderer,
    ) {
        for (i in 0 until actions.length()) {
            val action = actions.getJSONObject(i)
            when {
                action.has("tap") -> instr.runOnMainSync { driver(renderer).tap(action.getString("tap")) }
                action.has("input") -> {
                    val spec = action.getJSONObject("input")
                    instr.runOnMainSync {
                        driver(renderer).input(spec.getString("node"), spec.getString("value"))
                    }
                }
                action.has("scroll") -> {
                    val dxdy = action.getJSONArray("scroll")
                    instr.runOnMainSync { driver(renderer).scroll(dxdy.getInt(1)) }
                }
                action.has("swipe") -> {
                    val s = action.getJSONArray("swipe")
                    instr.runOnMainSync {
                        driver(renderer).swipe(
                            s.getInt(0), s.getInt(1), s.getInt(2), s.getInt(3), s.optInt(4, 300),
                        )
                    }
                }
                action.has("back") -> instr.runOnMainSync { driver(renderer).back() }
                action.has("wait") -> {
                    settle(action.getLong("wait"))
                    instr.waitForIdleSync()
                }
            }
            instr.waitForIdleSync()
            settle(250) // микрозадачи resolve и последующий рендер
            instr.waitForIdleSync()
        }
    }

    private fun driver(renderer: NativeRenderer): InteractionDriver = InteractionDriver(
        views = { renderer.viewOf(it) },
        allViews = { renderer.allViews() },
        root = { renderer.rootNode() },
        onAction = { a, p -> onActionBox[0]?.invoke(a, p) },
        onHostEvent = { t, p -> onHostEventBox[0]?.invoke(t, p) },
    )

    private fun settle(ms: Long) {
        SystemClock.sleep(ms)
    }

    private fun disposeOnMain(
        instr: android.app.Instrumentation,
        timersBox: Array<Timers?>,
        engineBox: Array<JsWidgetRuntime?>,
    ) {
        instr.runOnMainSync {
            timersBox[0]?.dispose()
            engineBox[0]?.dispose()
        }
    }

    /** result.json: dump/finalDump — финальное состояние; рендеры в порядке. */
    private fun writeResult(
        target: android.content.Context,
        fixtureId: String,
        recorder: Recorder,
        dump: JSONObject?,
        error: String?,
    ) {
        val result = JSONObject()
        result.put("fixture", fixtureId)
        result.put("platform", "android")
        result.put("status", "DONE")
        if (dump != null) {
            result.put("dump", dump)
            result.put("finalDump", dump)
        }
        result.put("trace", JSONArray(recorder.trace))
        result.put("diffs", JSONArray())
        result.put("renders", JSONArray(recorder.renders))
        if (recorder.title != null) result.put("title", recorder.title!!)
        if (recorder.state != null) result.put("state", recorder.state!!)
        result.put("console", JSONArray(recorder.console))
        if (error != null) result.put("error", error)
        result.put("skippedReason", JSONObject.NULL)
        ConformanceResult.write(target, fixtureId, result)
    }

    private fun asset(target: android.content.Context, path: String): String =
        target.assets.open(path).bufferedReader().use { it.readText() }
}
