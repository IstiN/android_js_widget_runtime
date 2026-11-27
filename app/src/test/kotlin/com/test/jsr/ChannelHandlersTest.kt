package com.test.jsr

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Записывающий TimerDriver для тестов каналов. */
class FakeTimerDriver : TimerDriver {
    val scheduled = mutableListOf<String>()
    val cancelled = mutableListOf<String>()
    val frames = mutableListOf<String>()
    val cancelledFrames = mutableListOf<String>()
    override fun schedule(payload: String) { scheduled.add(payload) }
    override fun cancel(id: String) { cancelled.add(id) }
    override fun requestFrame(id: String) { frames.add(id) }
    override fun cancelFrame(id: String) { cancelledFrames.add(id) }
    override fun dispose() = Unit
}

/** Записывающие приёмники хоста. */
class RecordingSinks : HostSinks {
    val renders = mutableListOf<JSONObject>()
    val titles = mutableListOf<String>()
    val logs = mutableListOf<String>()
    val states = mutableListOf<JSONObject>()
    override fun onRender(tree: JSONObject) { renders.add(tree) }
    override fun onTitle(title: String) { titles.add(title) }
    override fun onLog(line: String) { logs.add(line) }
    override fun onExportState(state: JSONObject) { states.add(state) }
}

/**
 * Обвязка тестов каналов: FakeJsEngine записывает eval'ы резолвов,
 * in-memory сервисы по умолчанию (правила волны C).
 */
abstract class ChannelHandlersTestBase(
    private val capabilities: Set<String> = emptySet(),
) {
    protected lateinit var engine: FakeJsEngine
    protected lateinit var handlers: ChannelHandlers
    protected lateinit var storage: InMemoryStorage
    protected lateinit var secrets: InMemorySecrets
    protected lateinit var http: FakeHttpService
    protected lateinit var assets: FakeAssetLoader
    protected lateinit var runner: FakeProcessRunner
    protected lateinit var sinks: RecordingSinks
    protected lateinit var timers: FakeTimerDriver

    @Before
    fun setUpBase() {
        engine = FakeJsEngine()
        storage = InMemoryStorage()
        secrets = InMemorySecrets()
        http = FakeHttpService()
        assets = FakeAssetLoader()
        runner = FakeProcessRunner()
        sinks = RecordingSinks()
        timers = FakeTimerDriver()
        handlers = ChannelHandlers(
            engine,
            sinks = sinks,
            services = Services(storage, secrets, http, assets, runner),
            timers = timers,
            capabilities = capabilities,
        )
    }

    /** Подменяет сервисы и строит маршрутизатор заново. */
    protected fun rebuild(services: Services) {
        handlers = ChannelHandlers(engine, sinks, services, timers, capabilities)
    }

    protected fun dispatch(channel: String, payload: String) = handlers.dispatch(channel, payload)

    /** Последний eval — __jsrResolve(id, value); возвращает пару. */
    protected fun lastResolve(): Pair<String, String> {
        val code = engine.evals.last().code
        val match = Regex("__jsrResolve\\([\"'](.+?)[\"'], (.*)\\);").find(code)
            ?: error("no resolve in: $code")
        return match.groupValues[1] to match.groupValues[2]
    }
}

class StorageHandlerTest : ChannelHandlersTestBase() {
    @Test
    fun `set stores value json then get resolves it`() {
        dispatch("__jsr_storage_set", """{"key":"theme","value":"dark"}""")
        assertEquals("\"dark\"", storage.get("theme"))

        dispatch("__jsr_storage_get", """{"id":"i9","key":"theme"}""")

        assertEquals("i9" to "\"dark\"", lastResolve())
    }

    @Test
    fun `get of unknown key resolves null`() {
        dispatch("__jsr_storage_get", """{"id":"i9","key":"missing"}""")

        assertEquals("i9" to "null", lastResolve())
    }

    @Test
    fun `object values are stored and resolved verbatim`() {
        dispatch("__jsr_storage_set", """{"key":"cfg","value":{"a":1}}""")

        dispatch("__jsr_storage_get", """{"id":"i2","key":"cfg"}""")

        assertEquals("i2" to """{"a":1}""", lastResolve())
    }

    @Test
    fun `array number and bool values keep their json shape`() {
        dispatch("__jsr_storage_set", """{"key":"list","value":[1,2]}""")
        dispatch("__jsr_storage_set", """{"key":"n","value":5}""")
        dispatch("__jsr_storage_set", """{"key":"b","value":true}""")

        dispatch("__jsr_storage_get", """{"id":"i3","key":"list"}""")
        assertEquals("i3" to "[1,2]", lastResolve())
        dispatch("__jsr_storage_get", """{"id":"i4","key":"n"}""")
        assertEquals("i4" to "5", lastResolve())
        dispatch("__jsr_storage_get", """{"id":"i5","key":"b"}""")
        assertEquals("i5" to "true", lastResolve())
    }
}

class SecretsHandlerTest : ChannelHandlersTestBase() {
    @Test
    fun `set resolves ok boolean then get resolves the value`() {
        dispatch("__jsr_secrets_set", """{"id":"i11","key":"token","value":"s3cret"}""")

        assertEquals("i11" to "true", lastResolve())

        dispatch("__jsr_secrets_get", """{"id":"i10","key":"token"}""")

        assertEquals("i10" to "\"s3cret\"", lastResolve())
    }

    @Test
    fun `delete sends value null and get resolves null`() {
        secrets.set("token", "\"x\"")

        dispatch("__jsr_secrets_set", """{"id":"i12","key":"token","value":null}""")
        assertEquals("i12" to "true", lastResolve())

        dispatch("__jsr_secrets_get", """{"id":"i13","key":"token"}""")
        assertEquals("i13" to "null", lastResolve())
    }

    @Test
    fun `get of missing secret resolves null`() {
        dispatch("__jsr_secrets_get", """{"id":"i1","key":"nope"}""")

        assertEquals("i1" to "null", lastResolve())
    }
}

class FetchHandlerTest : ChannelHandlersTestBase(capabilities = setOf("NETWORK")) {
    @Test
    fun `fetch resolves the parsed json body`() {
        http.next = HttpResult(bodyText = """{"temp":12}""")

        dispatch("__jsr_fetch", """{"id":"i4","url":"http://x/v1","method":"GET","headers":{}}""")

        assertEquals("http://x/v1" to "GET", http.requests.last().let { it.url to it.method })
        assertEquals("i4" to """{"temp":12}""", lastResolve())
    }

    @Test
    fun `network error resolves __error`() {
        http.next = HttpResult(error = "fetch http://x: timeout")

        dispatch("__jsr_fetch", """{"id":"i4","url":"http://x","method":"GET","headers":{}}""")

        val (id, value) = lastResolve()
        assertEquals("i4", id)
        assertTrue(value.contains("__error"))
    }

    @Test
    fun `non-json body resolves invalid json error`() {
        http.next = HttpResult(bodyText = "<html>")

        dispatch("__jsr_fetch", """{"id":"i5","url":"http://x","method":"GET","headers":{}}""")

        val (id, value) = lastResolve()
        assertEquals("i5", id)
        assertTrue(value.contains("invalid json"))
    }
}

class ExecHandlerTest : ChannelHandlersTestBase(capabilities = setOf("EXEC")) {
    @Test
    fun `exec resolves exit code and streams`() {
        runner.next = ExecResult(0, "hi\n", "")

        dispatch("__jsr_exec", """{"id":"i5","cmd":"echo hi"}""")

        assertEquals("echo hi", runner.commands.last())
        assertEquals(
            "i5" to """{"exitCode":0,"stdout":"hi\n","stderr":""}""",
            lastResolve(),
        )
    }
}

class CapabilityGateTest : ChannelHandlersTestBase(capabilities = setOf("NETWORK")) {
    @Test
    fun `exec without EXEC capability resolves not permitted`() {
        dispatch("__jsr_exec", """{"id":"i5","cmd":"ls"}""")

        val (id, value) = lastResolve()
        assertEquals("i5", id)
        assertTrue(value.contains("__jsr_exec is not permitted by this host"))
    }

    @Test
    fun `fetch with NETWORK capability passes the gate`() {
        dispatch("__jsr_fetch", """{"id":"i4","url":"http://x","method":"GET","headers":{}}""")
        assertEquals(1, http.requests.size)
    }

    @Test
    fun `ungated channels need no capability`() {
        dispatch("__jsr_storage_get", """{"id":"i9","key":"k"}""")

        assertEquals("i9" to "null", lastResolve())
    }
}

class HostCallHandlerTest : ChannelHandlersTestBase() {
    @Test
    fun `host_call without service resolves not supported`() {
        dispatch("__jsr_host_call", """{"id":"i7","name":"asr.record","args":{}}""")

        val (id, value) = lastResolve()
        assertEquals("i7", id)
        assertTrue(value.contains("hostCall is not supported by this host"))
    }

    @Test
    fun `host_call resolves service result or empty object`() {
        var lastName: String? = null
        val service = HostCallService { name, _ ->
            lastName = name
            if (name == "null-able") null else """{"ok":true}"""
        }
        rebuild(Services(storage, secrets, http, assets, runner, hostCall = service))

        dispatch("__jsr_host_call", """{"id":"i7","name":"asr.record","args":{"lang":"en"}}""")
        assertEquals("i7" to """{"ok":true}""", lastResolve())
        assertEquals("asr.record", lastName)

        dispatch("__jsr_host_call", """{"id":"i8","name":"null-able","args":{}}""")
        assertEquals("i8" to "{}", lastResolve())
    }
}

class OpenUrlHandlerTest : ChannelHandlersTestBase() {
    @Test
    fun `open_url without service resolves not supported`() {
        dispatch("__jsr_open_url", """{"id":"i6","url":"https://example.com"}""")

        val (id, value) = lastResolve()
        assertEquals("i6", id)
        assertTrue(value.contains("openUrl is not supported by this host"))
    }

    @Test
    fun `open_url with empty url resolves the documented error`() {
        rebuild(Services(storage, secrets, http, assets, runner, openUrl = OpenUrlService { true }))

        dispatch("__jsr_open_url", """{"id":"i6","url":""}""")

        val (id, value) = lastResolve()
        assertEquals("i6", id)
        assertTrue(value.contains("openUrl: empty url"))
    }

    @Test
    fun `accepted launch resolves true`() {
        val opened = mutableListOf<String>()
        rebuild(Services(storage, secrets, http, assets, runner,
            openUrl = OpenUrlService { url -> opened.add(url); true }))

        dispatch("__jsr_open_url", """{"id":"i6","url":"https://example.com"}""")

        assertEquals(listOf("https://example.com"), opened)
        assertEquals("i6" to "true", lastResolve())
    }

    @Test
    fun `failed launch resolves an error`() {
        rebuild(Services(storage, secrets, http, assets, runner, openUrl = OpenUrlService { false }))

        dispatch("__jsr_open_url", """{"id":"i6","url":"https://example.com"}""")

        val (id, value) = lastResolve()
        assertEquals("i6", id)
        assertTrue(value.contains("openUrl: launch failed"))
    }
}

class LoadAssetHandlerTest : ChannelHandlersTestBase() {
    @Test
    fun `load_asset resolves the content string`() {
        rebuild(Services(
            storage, secrets, http,
            FakeAssetLoader(mutableMapOf("fixtures/data.json" to """{"n":1}""")),
            runner,
        ))

        dispatch("__jsr_load_asset", """{"id":"i12","path":"fixtures/data.json"}""")

        // Референс разрешает ТЕКСТ ассета строкой, не разобранный JSON.
        assertEquals("i12" to "\"{\\\"n\\\":1}\"", lastResolve())
    }

    @Test
    fun `missing asset resolves null`() {
        dispatch("__jsr_load_asset", """{"id":"i14","path":"missing.json"}""")

        assertEquals("i14" to "null", lastResolve())
    }
}

class FireAndForgetHandlerTest : ChannelHandlersTestBase() {
    @Test
    fun `log carries the raw unwrapped string`() {
        dispatch("__jsr_log", """{"iid":"w1","id":"hello world"}""")

        assertEquals(listOf("hello world"), sinks.logs)
    }

    @Test
    fun `render passes the tree through`() {
        dispatch("__jsr_render", """{"type":"column","children":[]}""")

        assertEquals(1, sinks.renders.size)
        assertEquals("column", sinks.renders[0].optString("type"))
    }

    @Test
    fun `set_title and export_state reach the sinks`() {
        dispatch("__jsr_set_title", """{"iid":"w1","id":"Counter"}""")
        dispatch("__jsr_export_state", """{"count":1}""")

        assertEquals(listOf("Counter"), sinks.titles)
        assertEquals(1, sinks.states[0].optInt("count"))
    }

    @Test
    fun `capture without service resolves not supported`() {
        dispatch("__jsr_capture", """{"id":"i8","opts":{"format":"png"}}""")

        val (id, value) = lastResolve()
        assertEquals("i8", id)
        assertTrue(value.contains("capture is not supported by this host"))
    }

    @Test
    fun `capture resolves path and dimensions`() {
        val service = CaptureService { CaptureResult("/tmp/shot.png", 1080, 1920) }
        rebuild(Services(storage, secrets, http, assets, runner, capture = service))

        dispatch("__jsr_capture", """{"id":"i8","opts":{}}""")

        assertEquals("i8" to """{"path":"/tmp/shot.png","width":1080,"height":1920}""", lastResolve())
    }

    @Test
    fun `unsupported binary and scene3d lanes are dropped`() {
        dispatch("__jsr_host_call_binary", "scene.transforms eyJtIjoxfQ==")
        dispatch("__jsr_scene3d_command", """{"kind":"create","sceneId":"s1","payload":{}}""")
        dispatch("__jsr_event_done", "{}")

        assertEquals(0, engine.evals.size)
    }
}

class TimerChannelTest : ChannelHandlersTestBase() {
    @Test
    fun `set_interval forwards the documented payload`() {
        dispatch("__jsr_set_interval", """{"id":"i1","ms":250,"once":true}""")

        assertEquals(listOf("""{"id":"i1","ms":250,"once":true}"""), timers.scheduled)
    }

    @Test
    fun `clear_interval and caf forward raw ids`() {
        dispatch("__jsr_clear_interval", """{"iid":"w1","id":"i1"}""")
        dispatch("__jsr_caf", """{"iid":"w1","id":"i3"}""")

        assertEquals(listOf("i1"), timers.cancelled)
        assertEquals(listOf("i3"), timers.cancelledFrames)
    }

    @Test
    fun `raf forwards the frame id`() {
        dispatch("__jsr_raf", """{"id":"i3","iid":"w1"}""")

        assertEquals(listOf("i3"), timers.frames)
    }
}

class EnvelopeTest : ChannelHandlersTestBase() {
    @Test
    fun `raw envelope unwraps to the id field`() {
        assertEquals("hello world", handlers.unwrap("""{"iid":"w1","id":"hello world"}"""))
    }

    @Test
    fun `object payloads pass through unchanged`() {
        val tree = """{"type":"column","iid":"w1","children":[]}"""
        assertEquals(tree, handlers.unwrap(tree))
    }

    @Test
    fun `non-json payloads pass through`() {
        assertEquals("i1", handlers.unwrap("i1"))
    }
}
