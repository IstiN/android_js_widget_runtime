package com.test.jsr

import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
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
    private val onActionBox = arrayOfNulls<(String, String) -> String?>(1)
    private val onHostEventBox = arrayOfNulls<(String, String) -> String?>(1)
    private val timersBox = arrayOfNulls<Timers>(1)
    private val engineBox = arrayOfNulls<JsWidgetRuntime>(1)

    companion object {
        const val TAG = "ConformanceRunner"

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun fixtures(): List<String> {
            // Фикстуры лежат в assets ТЕСТОВОГО apk (instrumentation.context),
            // не target (apk приложения под тестом).
            val all = InstrumentationRegistry.getInstrumentation().context.assets
                .list("conformance")!!.sorted()
            // Аргумент fixtureFilter: список id через запятую (A2 гоняет три
            // seed, A3 убирает аргумент и гоняет все L0). Имя не `filter`:
            // AndroidJUnitRunner обрабатывает его как class-список и падает.
            val filter = InstrumentationRegistry.getArguments().getString("fixtureFilter")
            return if (filter.isNullOrEmpty()) all else all.filter { it in filter.split(",") }
        }
    }

    /**
     * Запись каналов хоста (HostSinks) + рендер в дерево View: без рендера
     * NativeRenderer остаётся пустым (dump = null, тапы драйвера мимо
     * корневой записи — root cause прогона 45700024123).
     * Всё вызывается с main-потока.
     */
    private class Recorder(private val renderInto: (JSONObject) -> Unit) : HostSinks {
        val renders = mutableListOf<String>()
        val trace = mutableListOf<String>()
        val console = mutableListOf<String>()
        var title: String? = null
        var state: JSONObject? = null

        override fun onRender(tree: JSONObject) {
            renders.add(tree.toString())
            trace.add("render")
            Log.i(TAG, "render #${renders.size} -> view tree")
            renderInto(tree)
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
        // Рендер payloads идут в NativeRenderer (тот же путь, что у
        // MainActivity.onRender) — иначе view-дерево и dump не строятся.
        val recorder = Recorder { tree ->
            rendererBox[0]?.render(JsWidgetTree.normalize(tree))
        }

        val testCtx = InstrumentationRegistry.getInstrumentation().context
        val widget = asset(testCtx, "conformance/$fixtureId/widget.js")
        val expected = JSONObject(asset(testCtx, "conformance/$fixtureId/expected.json"))
        val runtimeJs = asset(target, "jsr/bootstrap-runtime.js")
        val widgetJs = asset(target, "jsr/bootstrap-widget.js")

        val bootError = arrayOfNulls<String>(1)

        // Intent from the APPLICATION context: the component gets the target
        // package com.test.jsr and resolves against the app manifest.
        // launch(Class) builds the component from the instrumentation package
        // com.test.jsr.test, where the activity is not declared (run 45673792050).
        ActivityScenario.launch<ConformanceActivity>(
            Intent(ApplicationProvider.getApplicationContext(), ConformanceActivity::class.java)
        ).use { scenario ->
            scenario.onActivity { activity ->
                activity.root.removeAllViews()
                val engine = JsWidgetRuntime()
                // Колбэки возвращают ошибку движка (или null): драйвер
                // превращает её в ошибку действия, никаких тихих сбоев.
                val onAction: (String, String) -> String? = { actionId, payloadJson ->
                    Log.i(TAG, "callEvent $actionId")
                    engine.callEvent(actionId, payloadJson)?.let { "event $actionId: $it" }
                }
                val onHostEvent: (String, String) -> String? = { eventTarget, payloadJson ->
                    engine.eval("__jsrHostEvent(${JsStrings.quote(eventTarget)}, $payloadJson);", "host-event")
                        ?.let { "host event $eventTarget: $it" }
                }
                onActionBox[0] = onAction
                onHostEventBox[0] = onHostEvent
                // EventSink рендерера (реальные тапы по View) — Unit: ошибка
                // движка там не фатальна, путь драйвера возвращает её сам.
                val renderer = NativeRenderer(
                    target, { a, p -> onActionBox[0]?.invoke(a, p) }, activity.root,
                )
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
            idleBounded(instr)

            if (bootError[0] != null) {
                writeResult(target, fixtureId, recorder, null, null, bootError[0])
                disposeOnMain(instr, timersBox, engineBox)
                return
            }

            // dump = состояние ПЕРВОГО рендера (compare сверяет его с
            // normalizeTree(renders[0])), finalDump — после действий
            // (normalizeTree(renders[last])).
            val initialDump = dumpOf(instr)
            val actionError = applyActions(
                instr, expected.optJSONArray("actions") ?: JSONArray(), rendererBox[0]!!,
            )
            if (actionError != null) Log.e(TAG, actionError)

            idleBounded(instr)
            settle(400)
            idleBounded(instr)

            writeResult(target, fixtureId, recorder, initialDump, dumpOf(instr), actionError)
            disposeOnMain(instr, timersBox, engineBox)
        }
    }

    /**
     * Действия expected.json по порядку; между действиями — очередь main.
     * Первое неприменимое действие останавливает набор и возвращается как
     * ошибка фикстуры (никаких тихих no-op: причина попадает в result.json
     * и logcat).
     */
    private fun applyActions(
        instr: android.app.Instrumentation,
        actions: JSONArray,
        renderer: NativeRenderer,
    ): String? {
        for (i in 0 until actions.length()) {
            val action = actions.getJSONObject(i)
            Log.i(TAG, "action ${action}")
            if (action.has("wait")) {
                settle(action.getLong("wait"))
                idleBounded(instr)
                continue
            }
            val error = applyOne(instr, action, renderer)
            if (error != null) return error
            idleBounded(instr)
            settle(250) // микрозадачи resolve и последующий рендер
            idleBounded(instr)
        }
        return null
    }

    /** Одно действие; возвращает причину, если применить не удалось. */
    private fun applyOne(
        instr: android.app.Instrumentation,
        action: JSONObject,
        renderer: NativeRenderer,
    ): String? {
        val errorBox = arrayOfNulls<String>(1)
        instr.runOnMainSync {
            val d = driver(renderer)
            errorBox[0] = when {
                action.has("tap") -> d.tap(action.getString("tap"))
                action.has("input") -> {
                    val spec = action.getJSONObject("input")
                    d.input(spec.getString("node"), spec.getString("value"))
                }
                action.has("submit") -> d.submit(action.getString("submit"))
                action.has("scroll") -> d.scroll(action.getJSONArray("scroll").getInt(1))
                action.has("swipe") -> {
                    val s = action.getJSONArray("swipe")
                    d.swipe(s.getInt(0), s.getInt(1), s.getInt(2), s.getInt(3), s.optInt(4, 300))
                }
                action.has("back") -> d.back()
                else -> "unknown action: $action"
            }
        }
        return errorBox[0]
    }

    /** AUI dump текущего view-дерева (null, пока рендеров не было). */
    private fun dumpOf(instr: android.app.Instrumentation): JSONObject? {
        val box = arrayOfNulls<JSONObject>(1)
        instr.runOnMainSync {
            val renderer = rendererBox[0] ?: return@runOnMainSync
            val root = renderer.rootNode() ?: return@runOnMainSync
            box[0] = AuiDump.dump(root, renderer::viewOf)
        }
        return box[0]
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

    /**
     * waitForIdleSync без таймаута в API: вечно анимирующийся View —
     * неопределённый индикатор прогресса (l0_circular_progress_indicator) —
     * не даёт очереди главного потока опустеть, и синхронный вызов вешает
     * фикстуру до конца окна инструментария (заморозка гостя под TCG:
     * прогоны 45735253590 shard 0 и 45739152252 shard 0, обе попытки).
     * Ждём покой не дольше 2 c; поток-сирота завершится сам, когда
     * ActivityScenario закроет activity и анимация остановится.
     */
    private fun idleBounded(instr: android.app.Instrumentation, ms: Long = 2000) {
        val waiter = Thread { instr.waitForIdleSync() }
        waiter.isDaemon = true
        waiter.start()
        waiter.join(ms)
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

    /** result.json (план 1.4): dump — первый рендер, finalDump — последний;
     * рендеры в порядке; actionError — причина первого неприменённого действия. */
    private fun writeResult(
        target: android.content.Context,
        fixtureId: String,
        recorder: Recorder,
        dump: JSONObject?,
        finalDump: JSONObject?,
        error: String?,
    ) {
        val result = JSONObject()
        result.put("fixture", fixtureId)
        result.put("platform", "android")
        result.put("status", "DONE")
        if (dump != null) result.put("dump", dump)
        result.put("trace", JSONArray(recorder.trace))
        result.put("diffs", JSONArray())
        result.put("screenshot", JSONObject.NULL)
        result.put("renders", JSONArray(recorder.renders))
        if (finalDump != null) result.put("finalDump", finalDump)
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
